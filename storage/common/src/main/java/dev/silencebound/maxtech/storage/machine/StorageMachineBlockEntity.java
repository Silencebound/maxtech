package dev.silencebound.maxtech.storage.machine;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.energy.FeBuffer;
import dev.silencebound.maxtech.lib.resource.MachineItemStorage;
import dev.silencebound.maxtech.lib.schedule.ScheduledTask;
import dev.silencebound.maxtech.lib.schedule.TaskGroup;
import dev.silencebound.maxtech.lib.schedule.TaskPriority;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.energy.FeView;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.Block;
import dev.silencebound.maxtech.lib.save.SaveFormat;
import dev.silencebound.maxtech.lib.save.VersionedBlockEntity;
import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
// PLATFORM-LEAK: Storage machines hold MU in NeoForge transfer handlers; other mods see FE through views (MLE).
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * What every Storage machine shares (PRD 7.2): input, catalyst and output slots behind one handler on every side,
 * upgrade module slots only the player fills (hoppers and pipes never see them), an MU buffer (FE for other mods
 * through {@link #feHandler()}), a task in its level's Lib task group, the {@link StorageMachineBlock#ACTIVE} block
 * state and its item form. There is no ticker: the task runs while the machine works and waits for an event (a slot
 * change, arriving energy, a neighbour update) otherwise. Subclasses do the work in {@link #work}. Broken, the machine
 * drops as an item holding its slots, energy and settings, which placing it restores. Server thread only, except the
 * getters the menu reads.
 */
public abstract class StorageMachineBlockEntity extends VersionedBlockEntity {
    protected final MachineKind kind;
    protected final MachineItemStorage inputs;
    protected final MachineItemStorage catalysts;
    protected final MachineItemStorage outputs;
    protected final MachineItemStorage modules;
    protected final FeBuffer energy;
    private @Nullable FeView feView;
    private final MachineSides sides;
    private final Task task = new Task();
    private MachineState state = MachineState.IDLE;
    private long runs;
    private OwnerRecord owner = OwnerRecord.UNOWNED;

    protected StorageMachineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState blockState, MachineKind kind,
            long maxInsert, long maxExtract) {
        super(type, pos, blockState);
        this.kind = kind;
        this.inputs = new MachineItemStorage(kind.inputs(), this::inputsChanged, this::acceptsInput, Integer.MAX_VALUE);
        // A catalyst is kept between runs, so one per slot is all a recipe can use.
        this.catalysts = new MachineItemStorage(kind.catalysts(), this::inputsChanged,
                StorageMachineBlockEntity::isMask, 1);
        this.outputs = new MachineItemStorage(kind.outputs(), this::outputsChanged);
        this.modules = new MachineItemStorage(kind.modules(), this::modulesChanged, this::acceptsModule, 1);
        // The fallback only matters outside a world, where no server config is loaded (guide export scenes).
        this.energy = new FeBuffer(
                (int) StorageServerConfig.loadedOr(() -> StorageServerConfig.MACHINE_ENERGY_CAPACITY.getAsInt(),
                        StorageServerConfig.MACHINE_ENERGY_CAPACITY_DEFAULT),
                maxInsert, maxExtract, this::energyChanged);
        this.sides = new MachineSides(catalysts, inputs, outputs);
    }

    /** Masks go only into the catalyst slot, so a hopper feeding a lithograph cannot fill its inputs with them. */
    static boolean isMask(ItemStack stack) {
        return stack.is(MachineContent.MASKS);
    }

    /**
     * Whether an input slot takes {@code stack} (a one-item copy), asked on insertion; anything but a mask or a module
     * by default, so a hopper cannot block the inputs with them.
     */
    protected boolean acceptsInput(ItemStack stack) {
        return !isMask(stack) && MachineModuleItem.of(stack) == null;
    }

    /**
     * Whether a module slot takes {@code stack}: a module that fits this machine, stacks with every module already
     * fitted (the void module beside one other) and, on the server, whose research the owner's team has. The client
     * skips the research check, so slot prediction never refuses what the server takes.
     */
    private boolean acceptsModule(ItemStack stack) {
        MachineModule module = MachineModuleItem.of(stack);
        if (module == null || !module.fits(kind)) {
            return false;
        }
        // The storage is built after this method is referenced; it exists by the time anything is inserted.
        for (int slot = 0; slot < modules.size(); slot++) {
            MachineModule fitted = MachineModuleItem.of(modules.stack(slot));
            if (fitted != null && !module.stacksWith(fitted)) {
                return false;
            }
        }
        return !(level instanceof ServerLevel serverLevel)
                || ResearchUnlocks.isUnlocked(serverLevel, ownerRecord(), module.research());
    }

    public MachineKind kind() {
        return kind;
    }

    /** The player who placed the machine; research progress and gates go to that player's team. */
    public OwnerRecord ownerRecord() {
        return owner;
    }

    /** Sets the owner, normally from the player who placed the machine. Server side. */
    public void setOwner(OwnerRecord owner) {
        this.owner = owner;
        setChanged();
    }

    /** The handler every side exposes. */
    public ResourceHandler<ItemResource> items() {
        return sides;
    }

    /**
     * The machine's own buffer as an MU handler: input only on consumers, output only on the generator. Other mods see
     * it in FE through {@link #feHandler()}.
     */
    public abstract EnergyHandler energyHandler();

    /**
     * What every side shows other mods through NeoForge's energy capability: {@link #energyHandler()} in FE at Lib's
     * configured rate ({@link FeView}). The same instance for the machine's life.
     */
    public final EnergyHandler feHandler() {
        return feView();
    }

    /** {@link #feHandler()} as its view, made on first use. */
    protected final FeView feView() {
        FeView view = feView;
        if (view == null) {
            view = createFeView(energyHandler());
            feView = view;
        }
        return view;
    }

    /**
     * The MaxTech energy endpoint diagnostics read (Lib's endpoint capability): the generator's producer while it feeds
     * a storage network; null for the other machines, which hold their own buffer outside any energy network.
     */
    public @Nullable EnergyEndpoint energyEndpoint() {
        return null;
    }

    /** Makes the FE view over {@code handler}: input only; the generator's gives FE out under the export settings. */
    protected FeView createFeView(EnergyHandler handler) {
        return FeView.input(handler, this::markDirty);
    }

    public MachineItemStorage inputs() {
        return inputs;
    }

    public MachineItemStorage catalysts() {
        return catalysts;
    }

    public MachineItemStorage outputs() {
        return outputs;
    }

    /** The upgrade module slots; empty for the generator. */
    public MachineItemStorage modules() {
        return modules;
    }

    public FeBuffer energy() {
        return energy;
    }

    public MachineState state() {
        return state;
    }

    /** Ticks done of the current run or burn, for the GUI. */
    public abstract int progress();

    /** Ticks the current run or burn takes; 0 when idle. */
    public abstract int duration();

    /** The selected mode's index; 0 for machines without modes. */
    public int modeIndex() {
        return 0;
    }

    /** Selects the next mode; does nothing for machines without modes. */
    public void cycleMode() {
    }

    /** Whether the machine's task waits to run on the next pass; for tests. */
    public boolean isAwake() {
        return task.isQueued();
    }

    /** Times the machine's task has run since it was created; a sleeping machine does not count up. For tests. */
    public long runs() {
        return runs;
    }

    /** One run of the machine's task; returns a {@link ScheduledTask} result. */
    protected abstract int work(ServerLevel level);

    /** A slot in the inputs or catalysts changed. */
    protected abstract void inputsChanged();

    /** A slot in the outputs changed, through the machine or a neighbour. */
    protected abstract void outputsChanged();

    /** A module went in or out; the menu is the only way in. */
    protected void modulesChanged() {
        markDirty();
    }

    /** The buffer changed by a committed transfer or the machine's own use. */
    protected abstract void energyChanged();

    /** A neighbouring block changed; the generator looks for new consumers. */
    void neighbourChanged() {
    }

    /** Queues the machine's task for the next pass; the event hook every change calls. */
    protected final void wake() {
        task.wake();
    }

    /**
     * Records what the machine is doing and keeps the block state's {@code active} in step, changing the block state
     * only when it flips, never per tick. Flag 2 sends it to clients without neighbour updates.
     */
    protected final void setState(MachineState next) {
        if ((next == MachineState.RESEARCH_NEEDED) != (state == MachineState.RESEARCH_NEEDED)) {
            ResearchWaits.waiting(this, next == MachineState.RESEARCH_NEEDED);
        }
        state = next;
        boolean active = next == MachineState.RUNNING;
        BlockState current = getBlockState();
        if (level != null && !level.isClientSide() && current.getValue(StorageMachineBlock.ACTIVE) != active
                && level.getBlockState(worldPosition).is(current.getBlock())) {
            level.setBlock(worldPosition, current.setValue(StorageMachineBlock.ACTIVE, active), Block.UPDATE_CLIENTS);
        }
    }

    /** Marks the chunk unsaved without {@code setChanged}'s neighbour updates, which a working machine would repeat. */
    protected final void markDirty() {
        if (level != null) {
            level.blockEntityChanged(worldPosition);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            MachineTasks.group(serverLevel).add(task);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        ResearchWaits.waiting(this, false);
        TaskGroup group = task.group();
        if (group != null) {
            group.remove(task);
        }
    }

    /** The energy to save: the buffer's; the generator adds what its energy network link holds. */
    protected long savedEnergy() {
        return energy.stored();
    }

    /** Whether the machine holds nothing and has no setting a new one lacks; such a machine drops a plain item. */
    protected boolean isPristine() {
        return energy.stored() == 0 && ResourceHandlerUtil.isEmpty(inputs) && ResourceHandlerUtil.isEmpty(catalysts)
                && ResourceHandlerUtil.isEmpty(outputs) && ResourceHandlerUtil.isEmpty(modules) && modeIndex() == 0;
    }

    /** The machine as an item: its saved data in vanilla's block entity component, unless it is pristine. */
    public ItemStack toItemStack(HolderLookup.Provider registries) {
        ItemStack stack = new ItemStack(getBlockState().getBlock());
        if (!isPristine()) {
            stack.set(DataComponents.BLOCK_ENTITY_DATA,
                    TypedEntityData.<BlockEntityType<?>>of(getType(), saveCustomOnly(registries)));
        }
        return stack;
    }

    /** Drops every slot's items and empties them, for a removal that drops no machine item. */
    public void spillContents() {
        if (level == null) {
            return;
        }
        for (MachineItemStorage storage : new MachineItemStorage[]{inputs, catalysts, outputs, modules}) {
            for (int slot = 0; slot < storage.size(); slot++) {
                ItemStack stack = storage.stack(slot);
                if (!stack.isEmpty()) {
                    Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
                            stack.copy());
                    storage.shrink(slot, stack.getCount());
                }
            }
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putLong("energy", savedEnergy());
        FeView view = feView;
        if (view != null) {
            view.save(output);
        }
        inputs.serialize(output.child("inputs"));
        catalysts.serialize(output.child("catalysts"));
        outputs.serialize(output.child("outputs"));
        modules.serialize(output.child("modules"));
        if (owner.isOwned()) {
            output.store("owner", OwnerRecord.CODEC, owner);
        }
        // Derived and never loaded; the item tooltip reads it without decoding the stacks.
        output.putBoolean("holds_items", !ResourceHandlerUtil.isEmpty(inputs) || !ResourceHandlerUtil.isEmpty(catalysts)
                || !ResourceHandlerUtil.isEmpty(outputs) || !ResourceHandlerUtil.isEmpty(modules));
    }

    @Override
    protected final SaveFormat saveFormat() {
        return StorageSaveFormats.BLOCK_ENTITIES;
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        energy.load(input.getLongOr("energy", 0));
        // The fraction of one MU an FE insert or the move from FE (MLE item 4) left.
        feView().load(input);
        inputs.deserialize(input.childOrEmpty("inputs"));
        catalysts.deserialize(input.childOrEmpty("catalysts"));
        outputs.deserialize(input.childOrEmpty("outputs"));
        modules.deserialize(input.childOrEmpty("modules"));
        owner = input.read("owner", OwnerRecord.CODEC).orElse(OwnerRecord.UNOWNED);
    }

    private final class Task extends ScheduledTask {
        Task() {
            super(TaskPriority.BACKGROUND);
        }

        @Override
        protected int run(TaskGroup group, long gameTime) {
            if (!(level instanceof ServerLevel serverLevel) || isRemoved()) {
                return DONE;
            }
            runs++;
            return work(serverLevel);
        }
    }
}
