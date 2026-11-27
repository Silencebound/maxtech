package dev.silencebound.maxtech.storage.creative;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.energy.FeLookups;
import dev.silencebound.maxtech.lib.energy.FeTransfer;
import dev.silencebound.maxtech.lib.platform.CachedBlockLookup;
import dev.silencebound.maxtech.lib.api.schedule.ScheduledTask;
import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import dev.silencebound.maxtech.lib.api.schedule.TaskPriority;
import dev.silencebound.maxtech.storage.energy.FeView;
import dev.silencebound.maxtech.storage.machine.MachineTasks;
import dev.silencebound.maxtech.storage.network.NetworkPowerSource;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
// PLATFORM-LEAK: FE is NeoForge's energy transfer type; Storage runs on it (PRD 7.2).
import net.neoforged.neoforge.transfer.TransferPreconditions;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * Powers its storage network without limit while it is a member, and gives energy to any neighbour that pulls it: MU to
 * Storage's blocks, FE through Lib's rate and export settings to other mods' (MLE item 4). Nothing is stored, and
 * nothing ticks: the network reads its power when it pays for an operation, and neighbours pull.
 *
 * <p>
 * In push mode (toggled by using the block with an empty hand; saved, and kept in the dropped item) it also pushes FE
 * into every neighbour that takes it, so machines that only accept pushed FE run next to it. Pushing is a task in
 * Storage's machine task group: it runs each tick while a neighbour takes FE, backs off while neighbours are full, and
 * sleeps when none has an FE handler until a neighbour changes. Like the fuel generator it cannot see a neighbour use
 * FE up, so a full neighbour is checked again after the idle backoff.
 */
public final class CreativeEnergyBlockEntity extends StorageDeviceBlockEntity implements NetworkPowerSource {
    private static final long LIMITLESS = Long.MAX_VALUE / 4;
    private static final EnergyHandler SOURCE = new Source();
    private static final Direction[] SIDES = Direction.values();
    private static final String PUSH = "push";

    @SuppressWarnings("unchecked") // a generic array, filled per side on first use
    private final @Nullable CachedBlockLookup<EnergyHandler>[] neighbours = new CachedBlockLookup[SIDES.length];
    private final PushTask task = new PushTask();
    private boolean push;
    private int pushRuns;

    public CreativeEnergyBlockEntity(BlockPos pos, BlockState state) {
        super(CreativeEnergyContent.BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Role role() {
        return Role.OTHER;
    }

    /** Whether the source pushes FE into its neighbours. */
    public boolean pushing() {
        return push;
    }

    /** Whether the push task waits to run on the next pass; for tests. */
    public boolean isAwake() {
        return task.isQueued();
    }

    /** Times the push task has run; a sleeping source does not count up. For tests. */
    public int pushRuns() {
        return pushRuns;
    }

    /** Turns push mode on or off; saved with the block and its dropped item. */
    public void setPushing(boolean on) {
        if (on == push) {
            return;
        }
        push = on;
        setChanged();
        if (level instanceof ServerLevel server) {
            if (on) {
                MachineTasks.group(server).add(task);
            } else {
                leaveGroup();
            }
        }
    }

    /** A neighbouring block changed: it may take FE now. */
    public void neighbourChanged() {
        if (push) {
            task.wake();
        }
    }

    private void leaveGroup() {
        TaskGroup group = task.group();
        if (group != null) {
            group.remove(task);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (push && level instanceof ServerLevel server) {
            MachineTasks.group(server).add(task);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        leaveGroup();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (push) {
            output.putBoolean(PUSH, true);
        }
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        push = input.getBooleanOr(PUSH, false);
    }

    /** Push mode travels in the dropped item as block entity data, like other settings. */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (push) {
            CompoundTag tag = new CompoundTag();
            tag.putBoolean(PUSH, true);
            components.set(DataComponents.BLOCK_ENTITY_DATA, TypedEntityData.<BlockEntityType<?>>of(getType(), tag));
        }
    }

    /** Pushes into each neighbour that takes FE; returns the result for the task. */
    private int pushOnce(ServerLevel server) {
        pushRuns++;
        boolean moved = false;
        boolean seen = false;
        for (Direction side : SIDES) {
            CachedBlockLookup<EnergyHandler> lookup = neighbours[side.get3DDataValue()];
            if (lookup == null) {
                lookup = FeLookups.BLOCK.cache(server, worldPosition.relative(side), side.getOpposite());
                neighbours[side.get3DDataValue()] = lookup;
            }
            EnergyHandler target = lookup.find();
            if (target != null) {
                seen = true;
                // Storage's own blocks take MU as they are; other mods get FE under Lib's export settings.
                long sent = target instanceof FeView view
                        ? FeTransfer.move(SOURCE, view.store(), Integer.MAX_VALUE)
                        : FeTransfer.move(feHandler(), target, Integer.MAX_VALUE);
                if (sent > 0) {
                    moved = true;
                }
            }
        }
        if (moved) {
            return ScheduledTask.NEXT_TICK;
        }
        return seen ? ScheduledTask.IDLE : ScheduledTask.WAIT;
    }

    private final class PushTask extends ScheduledTask {
        PushTask() {
            super(TaskPriority.BACKGROUND);
        }

        @Override
        protected int run(TaskGroup group, long gameTime) {
            if (!push || isRemoved() || !(level instanceof ServerLevel server)) {
                return DONE;
            }
            return pushOnce(server);
        }
    }

    /** Gives {@link Integer#MAX_VALUE} MU per call on every side and takes nothing. */
    @Override
    public EnergyHandler energyInput() {
        return SOURCE;
    }

    /** Other mods draw FE from it under Lib's export settings, like from any MaxTech block. */
    @Override
    protected FeView createFeView(EnergyHandler input) {
        return FeView.of(input, false, true, true, CreativeEnergyBlockEntity::noEndpoint, this::gameTime,
                this::setChanged);
    }

    private static @Nullable EnergyEndpoint noEndpoint() {
        return null;
    }

    /** Stateless, so one instance serves every block. */
    private static final class Source implements EnergyHandler {
        @Override
        public long getAmountAsLong() {
            return LIMITLESS;
        }

        @Override
        public long getCapacityAsLong() {
            return LIMITLESS;
        }

        @Override
        public int insert(int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonNegative(amount);
            return 0;
        }

        @Override
        public int extract(int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonNegative(amount);
            return amount;
        }
    }
}
