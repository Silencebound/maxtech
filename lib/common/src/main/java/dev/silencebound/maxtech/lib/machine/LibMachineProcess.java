package dev.silencebound.maxtech.lib.machine;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.inventory.ResourceTank;
import dev.silencebound.maxtech.lib.api.machine.MachineProcess;
import dev.silencebound.maxtech.lib.api.machine.MachineProcessFactory;
import dev.silencebound.maxtech.lib.api.machine.MachineStatus;
import dev.silencebound.maxtech.lib.api.machine.RedstoneControl;
import dev.silencebound.maxtech.lib.api.machine.RedstoneMode;
import dev.silencebound.maxtech.lib.api.recipe.ItemOutput;
import dev.silencebound.maxtech.lib.api.recipe.KindOutput;
import dev.silencebound.maxtech.lib.api.recipe.MachineRecipe;
import dev.silencebound.maxtech.lib.api.recipe.MachineRecipeInput;
import dev.silencebound.maxtech.lib.api.recipe.MachineRecipeLookup;
import dev.silencebound.maxtech.lib.api.recipe.MachineRecipePlan;
import dev.silencebound.maxtech.lib.api.recipe.MachineRecipeShape;
import dev.silencebound.maxtech.lib.api.recipe.MachineRecipeType;
import dev.silencebound.maxtech.lib.api.resource.KindKey;
import dev.silencebound.maxtech.lib.api.schedule.ScheduledTask;
import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import dev.silencebound.maxtech.lib.api.schedule.TaskPriority;
import dev.silencebound.maxtech.lib.inventory.LibItemSlots;
import dev.silencebound.maxtech.lib.inventory.LibResourceTank;
import dev.silencebound.maxtech.lib.inventory.MachineMoves;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
// PLATFORM-LEAK: a finished run takes its inputs and puts out its outputs in one NeoForge transaction (ADR-0019).
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.Transaction;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * Lib's {@link MachineProcess}: one {@link ScheduledTask} that steps a run once per tick while it runs and waits for an
 * event otherwise. The recipe is looked up again only after an input, tank, mode or recipe manager change, and the
 * inputs are matched once more just before a run ends, so the plan the transaction follows is the slots' current one. A
 * finished run is one root transaction: inputs out, every output in, committed only when all of it fit. Events during
 * its own step come from its own moves or redstone pulse and only mark the recipe for a lookup. Server thread only.
 */
public final class LibMachineProcess implements MachineProcess {
    /** Lib's factory, installed from Lib's init. */
    public static final MachineProcessFactory.Factory FACTORY = LibMachineProcess::new;

    private static final String PROGRESS = "progress";
    private static final String ACTIVE = "active";
    private static final String RECIPE = "recipe";
    private static final String MODE = "mode";
    private static final ItemResource[] NO_RESOURCES = new ItemResource[0];

    private final MachineRecipeType type;
    private final @Nullable LibItemSlots slots;
    private final int inputFrom;
    private final int inputSlots;
    private final int catalystSlots;
    private final int outputFrom;
    private final int outputTo;
    private final LibResourceTank<?>[] inputTanks;
    private final LibResourceTank<?>[] outputTanks;
    private final @Nullable EnergyEndpoint energy;
    private final @Nullable RedstoneControl redstone;
    private final @Nullable Function<ServerLevel, TaskGroup> tasks;
    private final Consumer<MachineStatus> onStatus;
    private final Runnable onChange;
    private final List<String> modes;
    private final MachineRecipeLookup lookup;
    private final MachineRecipePlan plan;
    private final Input input;
    private final Task task = new Task();
    private @Nullable ServerLevel level;
    private @Nullable RecipeManager manager;
    private @Nullable RecipeHolder<MachineRecipe> current;
    /** The item outputs of {@link #current} as resources, made once per recipe. */
    private ItemResource[] outputResources = NO_RESOURCES;
    /** The run's recipe id from a save, until the first lookup after the load decides whether the run goes on. */
    private @Nullable Identifier savedRecipe;
    private String mode;
    private long progress;
    /** Whether a run started (room checked, pulse used); it holds until the run ends or resets. */
    private boolean active;
    private boolean lookUp = true;
    private boolean stepping;
    /**
     * Game time the energy demand was last raised; its flow arrives with the network's pass at the end of that tick.
     */
    private long demandSince = -1;
    private MachineStatus status = MachineStatus.IDLE;

    private LibMachineProcess(MachineProcessFactory.Spec spec) {
        this.type = spec.type();
        MachineRecipeShape shape = type.shape();
        if (spec.slots() != null && !(spec.slots() instanceof LibItemSlots)) {
            throw new IllegalArgumentException("Not Lib's slots: " + spec.slots().getClass().getName());
        }
        this.slots = (LibItemSlots) spec.slots();
        this.inputFrom = spec.firstInput();
        this.inputSlots = shape.itemInputs();
        this.catalystSlots = shape.catalysts();
        this.outputFrom = spec.firstOutput();
        this.outputTo = spec.firstOutput() + shape.itemOutputs();
        this.inputTanks = libTanks(spec.inputTanks());
        this.outputTanks = libTanks(spec.outputTanks());
        this.input = new Input();
        this.energy = spec.energy();
        this.redstone = spec.redstone();
        this.tasks = spec.tasks();
        this.onStatus = spec.onStatus();
        this.onChange = spec.onChange();
        this.modes = shape.modes();
        this.mode = modes.isEmpty() ? "" : modes.get(0);
        this.lookup = new MachineRecipeLookup(type);
        this.plan = new MachineRecipePlan(inputSlots + catalystSlots, inputTanks.length);
        EnergyEndpoint endpoint = energy;
        if (endpoint != null) {
            endpoint.setListener(changed -> {
                if (status == MachineStatus.NO_ENERGY) {
                    wake();
                }
            });
        }
    }

    private static LibResourceTank<?>[] libTanks(List<ResourceTank<?>> tanks) {
        LibResourceTank<?>[] lib = new LibResourceTank<?>[tanks.size()];
        for (int i = 0; i < lib.length; i++) {
            if (!(tanks.get(i) instanceof LibResourceTank<?> tank)) {
                throw new IllegalArgumentException("Not Lib's tank: " + tanks.get(i).getClass().getName());
            }
            lib[i] = tank;
        }
        return lib;
    }

    @Override
    public MachineRecipeType type() {
        return type;
    }

    @Override
    public void start(ServerLevel newLevel, BlockPos pos) {
        if (level != null) {
            return;
        }
        level = newLevel;
        manager = null;
        lookUp = true;
        demandSince = -1;
        join(newLevel);
    }

    @Override
    public void stop() {
        if (level == null) {
            return;
        }
        level = null;
        TaskGroup group = task.group();
        if (group != null) {
            group.remove(task);
        }
        demand(0, 0);
    }

    @Override
    public boolean started() {
        return level != null;
    }

    @Override
    public void wake() {
        ServerLevel current = level;
        if (current == null || stepping) {
            return;
        }
        if (task.group() == null) {
            join(current);
        } else if (!task.isQueued()) {
            task.wake();
        }
    }

    @Override
    public void slotChanged(int slot) {
        if (slot >= inputFrom && slot < inputFrom + inputSlots + catalystSlots) {
            lookUp = true;
            wake();
        } else if (slot >= outputFrom && slot < outputTo && status == MachineStatus.OUTPUT_FULL) {
            wake();
        }
    }

    @Override
    public void tankChanged() {
        lookUp = true;
        wake();
    }

    @Override
    public MachineStatus status() {
        return status;
    }

    @Override
    public Optional<RecipeHolder<MachineRecipe>> recipe() {
        return Optional.ofNullable(current);
    }

    @Override
    public long progress() {
        return progress;
    }

    @Override
    public long progressTotal() {
        RecipeHolder<MachineRecipe> holder = current;
        return holder == null ? 0 : total(holder.value());
    }

    @Override
    public String mode() {
        return mode;
    }

    @Override
    public void setMode(String id) {
        if (!modes.contains(id)) {
            throw new IllegalArgumentException(type.id() + " has no mode " + id + ", only " + modes);
        }
        if (id.equals(mode)) {
            return;
        }
        mode = id;
        lookUp = true;
        onChange.run();
        wake();
    }

    @Override
    public void save(ValueOutput output) {
        if (progress > 0) {
            output.putLong(PROGRESS, progress);
        }
        if (active) {
            output.putBoolean(ACTIVE, true);
        }
        RecipeHolder<MachineRecipe> holder = current;
        Identifier id = holder != null ? holder.id().identifier() : savedRecipe;
        if (id != null && (progress > 0 || active)) {
            output.putString(RECIPE, id.toString());
        }
        if (!modes.isEmpty() && !mode.equals(modes.get(0))) {
            output.putString(MODE, mode);
        }
    }

    @Override
    public void load(ValueInput input) {
        progress = Math.max(0, input.getLongOr(PROGRESS, 0));
        active = input.getBooleanOr(ACTIVE, false);
        String saved = input.getStringOr(RECIPE, "");
        savedRecipe = saved.isEmpty() ? null : Identifier.tryParse(saved);
        String savedMode = input.getStringOr(MODE, "");
        mode = modes.contains(savedMode) ? savedMode : modes.isEmpty() ? "" : modes.get(0);
        current = null;
        outputResources = NO_RESOURCES;
        lookUp = true;
    }

    /** Adds the task to the owner's group for {@code current}, or the level's machine group while that is closed. */
    private void join(ServerLevel current) {
        TaskGroup group = tasks == null ? null : tasks.apply(current);
        if (group == null || group.isClosed()) {
            group = MachineTasks.group(current);
        }
        group.add(task);
    }

    /** One step of the machine; see the class comment. */
    @SuppressWarnings("ReferenceEquality") // a reload makes a new recipe manager; identity is the point
    private int step(ServerLevel at, long gameTime) {
        RecipeManager recipes = at.recipeAccess();
        if (recipes != manager) {
            manager = recipes;
            lookUp = true;
        }
        if (lookUp) {
            lookUp = false;
            resolve(at);
        }
        RecipeHolder<MachineRecipe> holder = current;
        if (holder == null) {
            resetRun();
            pause(input.isEmpty() ? MachineStatus.IDLE : MachineStatus.NO_RECIPE);
            return ScheduledTask.WAIT;
        }
        MachineRecipe recipe = holder.value();
        RedstoneControl control = redstone;
        boolean pulseRun = active && control != null && control.mode() == RedstoneMode.PULSE;
        if (control != null && !pulseRun && !control.allowsRun()) {
            pause(MachineStatus.REDSTONE_OFF);
            return ScheduledTask.WAIT;
        }
        if (!active) {
            if (!roomFor(recipe)) {
                pause(MachineStatus.OUTPUT_FULL);
                return ScheduledTask.WAIT;
            }
            if (control != null) {
                control.consumePulse();
            }
            active = true;
            onChange.run();
        }
        long total = total(recipe);
        if (progress < total) {
            int cost = recipe.energyPerTick();
            EnergyEndpoint endpoint = energy;
            if (endpoint != null) {
                demand(cost, gameTime);
            }
            if (endpoint != null && cost > 0) {
                if (gameTime == demandSince) {
                    setStatus(MachineStatus.RUNNING);
                    return ScheduledTask.NEXT_TICK;
                }
                long received = Math.min(endpoint.received(), cost);
                if (received <= 0) {
                    setStatus(MachineStatus.NO_ENERGY);
                    return ScheduledTask.WAIT;
                }
                progress = Math.min(total, progress + received);
            } else {
                progress++;
            }
            onChange.run();
        }
        if (progress >= total) {
            if (!recipe.matches(input, at)) {
                // The listeners missed a change; nothing was taken, so the run starts over on what is there.
                resetRun();
                lookUp = true;
                return ScheduledTask.NEXT_TICK;
            }
            if (!finish(at, recipe)) {
                progress = total;
                pause(MachineStatus.OUTPUT_FULL);
                return ScheduledTask.WAIT;
            }
            progress = 0;
            active = false;
            lookUp = true;
            onChange.run();
        }
        setStatus(MachineStatus.RUNNING);
        return ScheduledTask.NEXT_TICK;
    }

    /**
     * Looks the recipe up for the current inputs and mode. The run keeps its progress only for the same recipe id as
     * before (or as saved, after a load); any other result resets it.
     */
    @SuppressWarnings("ReferenceEquality") // a new holder (a reload) needs its output resources again
    private void resolve(ServerLevel at) {
        RecipeHolder<MachineRecipe> next = input.isEmpty() ? null : lookup.find(at, input).orElse(null);
        RecipeHolder<MachineRecipe> previous = current;
        Identifier keep = previous != null ? previous.id().identifier() : savedRecipe;
        savedRecipe = null;
        if (next == null || keep == null || !next.id().identifier().equals(keep)) {
            resetRun();
        }
        if (next != previous) {
            current = next;
            outputResources = next == null ? NO_RESOURCES : resources(next.value());
        }
    }

    private static ItemResource[] resources(MachineRecipe recipe) {
        List<ItemOutput> outputs = recipe.outputs();
        ItemResource[] resources = new ItemResource[outputs.size()];
        for (int i = 0; i < resources.length; i++) {
            resources[i] = ItemResource.of(outputs.get(i).item().create());
        }
        return resources;
    }

    /** Work a run of {@code recipe} needs: its energy when the machine pays energy, else its ticks. */
    private long total(MachineRecipe recipe) {
        return energy != null && recipe.energyPerTick() > 0
                ? (long) recipe.energyPerTick() * recipe.duration()
                : recipe.duration();
    }

    /**
     * Whether every output of {@code recipe} at its full count, chance outputs included, fits the outputs together.
     * Rolled back, so nothing moves and no listener runs.
     */
    private boolean roomFor(MachineRecipe recipe) {
        try (Transaction transaction = Transaction.openRoot()) {
            return putOutputs(recipe, null, transaction);
        }
    }

    /** Takes the planned inputs and puts out the rolled outputs in one root transaction; false with nothing moved. */
    private boolean finish(ServerLevel at, MachineRecipe recipe) {
        try (Transaction transaction = Transaction.openRoot()) {
            LibItemSlots items = slots;
            if (items != null) {
                for (int i = 0; i < inputSlots; i++) {
                    int count = plan.items(i);
                    if (count > 0) {
                        int slot = inputFrom + i;
                        ItemResource held = items.getResource(slot);
                        if (held.isEmpty() || items.extract(slot, held, count, transaction) != count) {
                            return false;
                        }
                    }
                }
            }
            for (int tank = 0; tank < inputTanks.length; tank++) {
                long amount = plan.drained(tank);
                if (amount > 0 && !drain(inputTanks[tank], amount, transaction)) {
                    return false;
                }
            }
            if (!putOutputs(recipe, at, transaction)) {
                return false;
            }
            transaction.commit();
            return true;
        }
    }

    /**
     * Puts {@code recipe}'s outputs in as part of {@code transaction}: rolled with {@code at}'s random source, or at
     * full count for a room check without a level. False as soon as one does not fit whole.
     */
    private boolean putOutputs(MachineRecipe recipe, @Nullable ServerLevel at, TransactionContext transaction) {
        List<ItemOutput> outputs = recipe.outputs();
        LibItemSlots items = slots;
        for (int i = 0; i < outputs.size(); i++) {
            ItemOutput output = outputs.get(i);
            int count = at == null ? output.item().count() : output.roll(at.getRandom());
            if (count > 0 && (items == null || MachineMoves.insert(items, outputFrom, outputTo, outputResources[i],
                    count, transaction) != count)) {
                return false;
            }
        }
        List<KindOutput> tankOutputs = recipe.tankOutputs();
        for (int i = 0; i < tankOutputs.size(); i++) {
            KindOutput output = tankOutputs.get(i);
            if (fill(output.resource(), output.amount(), transaction) != output.amount()) {
                return false;
            }
        }
        return true;
    }

    /** Puts {@code amount} of {@code resource} into the output tanks, those holding it first; returns what went in. */
    private long fill(KindKey<?> resource, long amount, TransactionContext transaction) {
        long moved = 0;
        for (int pass = 0; pass < 2 && moved < amount; pass++) {
            for (int i = 0; i < outputTanks.length && moved < amount; i++) {
                LibResourceTank<?> tank = outputTanks[i];
                if (resource.kind() == tank.kind() && (pass == 1 || resource.key().equals(tank.key()))) {
                    moved += insert(tank, resource.key(), amount - moved, transaction);
                }
            }
        }
        return moved;
    }

    @SuppressWarnings("unchecked") // the caller checked that the key is of the tank's kind
    private static <K> long insert(LibResourceTank<K> tank, Object key, long amount, TransactionContext transaction) {
        return tank.insert((K) key, amount, transaction);
    }

    private static <K> boolean drain(LibResourceTank<K> tank, long amount, TransactionContext transaction) {
        K key = tank.key();
        return key != null && tank.extract(key, amount, transaction) == amount;
    }

    /** Declares {@code perTick} as the endpoint's demand, noting when it went up. */
    private void demand(long perTick, long gameTime) {
        EnergyEndpoint endpoint = energy;
        if (endpoint != null && endpoint.demand() != perTick) {
            if (perTick > endpoint.demand()) {
                demandSince = gameTime;
            }
            endpoint.setDemand(perTick);
        }
    }

    /** Sleeps in {@code next}: draws nothing while waiting. */
    private void pause(MachineStatus next) {
        demand(0, 0);
        setStatus(next);
    }

    /** Drops the run's progress; the inputs were never taken, so nothing is lost. */
    private void resetRun() {
        if (progress != 0 || active) {
            progress = 0;
            active = false;
            onChange.run();
        }
    }

    private void setStatus(MachineStatus next) {
        if (status != next) {
            status = next;
            onStatus.accept(next);
        }
    }

    /** The live view recipes match against: input slots, catalyst slots, input tanks and the mode. */
    private final class Input implements MachineRecipeInput {
        private final @Nullable Object[] keys = new Object[inputTanks.length];
        @SuppressWarnings("unchecked")
        private final Optional<KindKey<?>>[] resources = new Optional[inputTanks.length];

        @Override
        public String mode() {
            return mode;
        }

        @Override
        public int inputSlots() {
            return inputSlots;
        }

        @Override
        public int catalystSlots() {
            return catalystSlots;
        }

        @Override
        public ItemStack getItem(int index) {
            LibItemSlots items = slots;
            return items == null ? ItemStack.EMPTY : items.stack(inputFrom + index);
        }

        @Override
        public int inputTanks() {
            return inputTanks.length;
        }

        /** The tank's resource, made once per key the tank holds. */
        @Override
        @SuppressWarnings("ReferenceEquality") // a tank keeps its key instance while it holds that resource
        public Optional<KindKey<?>> tankResource(int tank) {
            LibResourceTank<?> held = inputTanks[tank];
            Object key = held.key();
            if (key == null || held.amount() <= 0) {
                return Optional.empty();
            }
            Optional<KindKey<?>> resource = resources[tank];
            if (key != keys[tank] || resource == null) {
                resource = Optional.of(kindKey(held));
                keys[tank] = key;
                resources[tank] = resource;
            }
            return resource;
        }

        @Override
        public long tankAmount(int tank) {
            return inputTanks[tank].amount();
        }

        @Override
        public MachineRecipePlan plan() {
            return plan;
        }
    }

    private static <K> KindKey<?> kindKey(LibResourceTank<K> tank) {
        K key = tank.key();
        if (key == null) {
            throw new IllegalStateException("Empty tank");
        }
        return new KindKey<>(tank.kind(), key);
    }

    /** Runs the process's steps in its group. */
    private final class Task extends ScheduledTask {
        Task() {
            super(TaskPriority.BACKGROUND);
        }

        @Override
        protected int run(TaskGroup group, long gameTime) {
            ServerLevel at = level;
            if (at == null) {
                return DONE;
            }
            stepping = true;
            try {
                return step(at, gameTime);
            } finally {
                stepping = false;
            }
        }
    }
}
