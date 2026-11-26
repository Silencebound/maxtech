package dev.silencebound.maxtech.storage.machine;

import dev.silencebound.maxtech.lib.api.recipe.ItemOutput;
import dev.silencebound.maxtech.lib.schedule.ScheduledTask;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
// PLATFORM-LEAK: Storage machines run on FE and expose NeoForge transfer handlers (PRD 7.2).
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * The lithograph, fabricator and assembler: run their machine's recipes in the selected mode, paying the recipe's FE
 * each tick. Inputs are taken and outputs made when a run finishes, so taking an input out mid-run only resets the run
 * and nothing is lost on a reload; a run starts only with room for every output, chance outputs included. The recipe is
 * looked up again only when an input or the mode changed. A module in the module slot can multiply a run's outputs (the
 * lithograph's precision module), shorten it at a higher FE cost (speed) or make it cheaper (efficiency); the void
 * module beside it makes a run do several recipes at once, as many as the inputs and the output room allow up to its
 * parallel count, for more FE per run. A run's FE is spread over its ticks so they add up to the modules' total. It
 * sleeps without a recipe, without energy (until FE arrives) and with full outputs (until one is taken out).
 */
public final class ProcessingMachineBlockEntity extends StorageMachineBlockEntity {
    private final Input input = new Input();
    private int modeIndex;
    private int progress;
    private boolean lookUp = true;
    private @Nullable RecipeHolder<StorageMachineRecipe> current;
    /**
     * The last recipe the machine matched, for the pattern export button. Not saved: it is a convenience that the next
     * match restores, not machine state, so it stays out of the item form.
     */
    private @Nullable RecipeHolder<StorageMachineRecipe> lastMatched;
    // The recipe a saved run belonged to; a reload keeps the progress only if the lookup finds the same one.
    private @Nullable Identifier savedRecipe;

    public ProcessingMachineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, MachineKind kind) {
        // The fallback only matters outside a world, where no server config is loaded (guide export scenes).
        super(type, pos, state, kind,
                StorageServerConfig.loadedOr(() -> StorageServerConfig.MACHINE_MAX_ENERGY_INPUT.getAsInt(),
                        StorageServerConfig.MACHINE_MAX_ENERGY_INPUT_DEFAULT),
                0);
    }

    @Override
    public EnergyHandler energyHandler() {
        return energy.inputOnly();
    }

    @Override
    public int progress() {
        return progress;
    }

    @Override
    public int duration() {
        RecipeHolder<StorageMachineRecipe> recipe = current;
        return recipe == null ? 0 : runDuration(recipe.value());
    }

    @Override
    public int modeIndex() {
        return modeIndex;
    }

    /** The selected mode, or an empty string for the assembler. */
    public String mode() {
        List<String> modes = kind.modes();
        return modes.isEmpty() ? "" : modes.get(modeIndex);
    }

    @Override
    public void cycleMode() {
        if (kind.modes().size() > 1) {
            modeIndex = (modeIndex + 1) % kind.modes().size();
            markDirty();
            inputsChanged();
        }
    }

    /** The recipe the export button writes: the one the slots select now, else the last one matched, else null. */
    public @Nullable RecipeHolder<StorageMachineRecipe> exportRecipe() {
        RecipeHolder<StorageMachineRecipe> selected = current;
        if (selected == null && lastMatched == null && level instanceof ServerLevel serverLevel) {
            // A machine that has not run since it loaded: match its slots now, once.
            lastMatched = find(serverLevel);
        }
        return selected != null ? selected : lastMatched;
    }

    /** Whether the export button has a recipe without looking one up; the menu syncs it to grey the button. */
    public boolean hasExportRecipe() {
        return current != null || lastMatched != null;
    }

    /** The recipe being run, or {@code null}. */
    public @Nullable RecipeHolder<StorageMachineRecipe> currentRecipe() {
        return current;
    }

    @Override
    protected int work(ServerLevel level) {
        if (lookUp) {
            lookUp = false;
            RecipeHolder<StorageMachineRecipe> found = find(level);
            if (found == null || current == null || !found.id().equals(current.id())) {
                Identifier saved = savedRecipe;
                if (found == null || saved == null || !found.id().identifier().equals(saved)) {
                    progress = 0;
                }
                current = found;
                if (found != null) {
                    lastMatched = found;
                }
            }
            savedRecipe = null;
        }
        RecipeHolder<StorageMachineRecipe> holder = current;
        if (holder == null) {
            progress = 0;
            setState(MachineState.IDLE);
            return ScheduledTask.WAIT;
        }
        StorageMachineRecipe recipe = holder.value();
        if (progress == 0 && !researched(level, recipe)) {
            // Re-checked on the next slot, mode or module change; research progress does not wake machines yet.
            setState(MachineState.RESEARCH_NEEDED);
            return ScheduledTask.WAIT;
        }
        if (progress == 0 && !roomFor(recipe, 1)) {
            setState(MachineState.OUTPUT_FULL);
            return ScheduledTask.WAIT;
        }
        int duration = runDuration(recipe);
        long cost = ModuleMath.energyForTick(runEnergy(recipe), duration, progress);
        if (!energy.tryConsume(cost)) {
            setState(MachineState.NO_ENERGY);
            return ScheduledTask.WAIT;
        }
        progress++;
        setState(MachineState.RUNNING);
        if (progress >= duration) {
            if (!roomFor(recipe, 1)) {
                // Held at the end until an output is taken; the energy for this tick is already spent.
                progress = duration;
                setState(MachineState.OUTPUT_FULL);
                return ScheduledTask.WAIT;
            }
            finish(level, recipe);
            progress = 0;
            // The inputs changed; whether the same recipe still matches is decided on the next tick.
            lookUp = true;
        }
        markDirty();
        return ScheduledTask.NEXT_TICK;
    }

    private @Nullable RecipeHolder<StorageMachineRecipe> find(ServerLevel level) {
        if (ResourceHandlerUtil.isEmpty(inputs)) {
            return null;
        }
        Optional<RecipeHolder<StorageMachineRecipe>> found = level.recipeAccess()
                .getRecipeFor(MachineContent.recipeType(kind), input, level);
        return found.orElse(null);
    }

    /** Whether the owner's team has the research the recipe names, if any. */
    private boolean researched(ServerLevel level, StorageMachineRecipe recipe) {
        Optional<Identifier> unlock = recipe.research();
        return unlock.isEmpty() || ResearchUnlocks.isUnlocked(level, ownerRecord(), unlock.get());
    }

    /**
     * Whether every output of {@code batch} runs, chance outputs counted as made and module yield included, fits the
     * outputs together.
     */
    private boolean roomFor(StorageMachineRecipe recipe, int batch) {
        int multiplier = outputMultiplier() * batch;
        // Closed without a commit, so nothing is inserted. Server thread, outside any other transaction.
        try (Transaction transaction = Transaction.openRoot()) {
            for (ItemOutput output : recipe.outputs()) {
                ItemStack stack = output.item().create();
                int count = stack.getCount() * multiplier;
                if (ResourceHandlerUtil.insertStacking(outputs, ItemResource.of(stack), count, transaction) < count) {
                    return false;
                }
            }
        }
        return true;
    }

    /** How many times its outputs a run in the current mode makes, from the fitted module; 1 without one. */
    public int outputMultiplier() {
        int multiplier = 1;
        for (int slot = 0; slot < modules.size(); slot++) {
            MachineModule module = MachineModuleItem.of(modules.stack(slot));
            if (module != null) {
                multiplier *= module.outputMultiplier(kind, mode());
            }
        }
        return multiplier;
    }

    /** Most recipes one run does at once in the current mode, from the fitted void module; 1 without one. */
    public int parallel() {
        int parallel = 1;
        for (int slot = 0; slot < modules.size(); slot++) {
            MachineModule module = MachineModuleItem.of(modules.stack(slot));
            if (module != null) {
                parallel = Math.max(parallel, module.parallel(kind, mode()));
            }
        }
        return parallel;
    }

    /**
     * Ticks a run of {@code recipe} takes with the fitted module (a speed module shortens it). A module taken out or
     * put in mid-run keeps the progress, so a run past its new end finishes on the next tick.
     */
    private int runDuration(StorageMachineRecipe recipe) {
        int bonus = 0;
        for (int slot = 0; slot < modules.size(); slot++) {
            MachineModule module = MachineModuleItem.of(modules.stack(slot));
            if (module != null) {
                bonus += module.speedBonusPercent(kind, mode());
            }
        }
        return ModuleMath.duration(recipe.duration(), bonus);
    }

    /** FE a whole run of {@code recipe} costs with the fitted module, paid tick by tick. */
    public long runEnergy(StorageMachineRecipe recipe) {
        int percent = 100;
        for (int slot = 0; slot < modules.size(); slot++) {
            MachineModule module = MachineModuleItem.of(modules.stack(slot));
            if (module != null) {
                percent = percent * module.energyPercent(kind, mode()) / 100;
            }
        }
        return ModuleMath.runEnergy(recipe.energyPerTick(), recipe.duration(), percent);
    }

    /** Whether a run makes {@code output} this time: always for a guaranteed one, else with its chance. */
    public static boolean rolls(ItemOutput output, RandomSource random) {
        return output.guaranteed() || random.nextInt(ItemOutput.ALWAYS) < output.chance();
    }

    /**
     * Takes the planned inputs and adds the outputs, rolling each chance output once per recipe; with a void module as
     * many recipes at once as {@link #batch} allows.
     */
    private void finish(ServerLevel level, StorageMachineRecipe recipe) {
        if (!recipe.matches(input, level)) {
            // An input went missing without a change event; nothing is taken or made.
            return;
        }
        int[] take = input.scratch();
        int batch = batch(recipe, take);
        for (int slot = 0; slot < inputs.size(); slot++) {
            if (take[slot] > 0) {
                inputs.shrink(slot, take[slot] * batch);
            }
        }
        // One list per finished run, only while someone listens; runs last many ticks, so this is no hot path.
        @Nullable
        List<ItemStack> made = MachineRuns.anyListener() ? new ArrayList<>(recipe.outputs().size()) : null;
        int multiplier = outputMultiplier();
        try (Transaction transaction = Transaction.openRoot()) {
            for (ItemOutput output : recipe.outputs()) {
                int rolled = 0;
                for (int i = 0; i < batch; i++) {
                    if (rolls(output, level.getRandom())) {
                        rolled++;
                    }
                }
                if (rolled > 0) {
                    ItemStack stack = output.item().create();
                    stack.setCount(stack.getCount() * multiplier * rolled);
                    ResourceHandlerUtil.insertStacking(outputs, ItemResource.of(stack), stack.getCount(), transaction);
                    if (made != null) {
                        made.add(stack);
                    }
                }
            }
            transaction.commit();
        }
        if (made != null) {
            MachineRuns.fire(level, this, recipe.mode(), made);
        }
    }

    /**
     * Recipes the finishing run does at once: up to {@link #parallel()}, as many times as every input slot holds the
     * amount {@code take} plans for it and the outputs have room for; at least one, which the run already checked.
     */
    private int batch(StorageMachineRecipe recipe, int[] take) {
        int batch = parallel();
        for (int slot = 0; slot < inputs.size() && batch > 1; slot++) {
            if (take[slot] > 0) {
                batch = Math.min(batch, inputs.stack(slot).getCount() / take[slot]);
            }
        }
        batch = Math.max(1, batch);
        while (batch > 1 && !roomFor(recipe, batch)) {
            batch--;
        }
        return batch;
    }

    @Override
    protected void inputsChanged() {
        markDirty();
        lookUp = true;
        wake();
    }

    @Override
    protected void outputsChanged() {
        markDirty();
        if (state() == MachineState.OUTPUT_FULL) {
            wake();
        }
    }

    /** A module changes what a run makes, and so whether its outputs fit. */
    @Override
    protected void modulesChanged() {
        markDirty();
        wake();
    }

    @Override
    protected void energyChanged() {
        markDirty();
        if (state() == MachineState.NO_ENERGY) {
            wake();
        }
    }

    @Override
    protected boolean isPristine() {
        return super.isPristine() && progress == 0;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("mode", mode());
        output.putInt("progress", progress);
        RecipeHolder<StorageMachineRecipe> recipe = current;
        if (recipe != null) {
            output.putString("recipe", recipe.id().identifier().toString());
        }
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        modeIndex = Math.max(0, kind.modes().indexOf(input.getStringOr("mode", "")));
        progress = input.getIntOr("progress", 0);
        savedRecipe = Identifier.tryParse(input.getStringOr("recipe", ""));
        current = null;
        lookUp = true;
    }

    /** The live view recipes match against: input slots, then catalyst slots. */
    private final class Input implements StorageMachineRecipe.Input {
        private final int[] scratch = new int[kind.inputs() + kind.catalysts()];

        @Override
        public ItemStack getItem(int index) {
            return index < inputs.size() ? inputs.stack(index) : catalysts.stack(index - inputs.size());
        }

        @Override
        public int size() {
            return inputs.size() + catalysts.size();
        }

        @Override
        public String mode() {
            return ProcessingMachineBlockEntity.this.mode();
        }

        @Override
        public int inputSlots() {
            return inputs.size();
        }

        @Override
        public int catalystSlots() {
            return catalysts.size();
        }

        @Override
        public int[] scratch() {
            return scratch;
        }
    }
}
