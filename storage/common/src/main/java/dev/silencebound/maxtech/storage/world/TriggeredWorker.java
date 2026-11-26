package dev.silencebound.maxtech.storage.world;

import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A placer or breaker: it acts on the block it faces, either once per redstone pulse (mode 0, a rising edge of the
 * signal at the device) or, in continuous mode, whenever it can, at most once per configured interval. Pulses that
 * arrive while it cannot act are dropped, as a dispenser's are. It acts through a fake player of its owner
 * ({@link WorldActors}), so protection mods judge it as its owner. Asleep while the faced block gives it nothing to do;
 * neighbour changes, pulses and its filter keys' stock wake it.
 */
abstract class TriggeredWorker extends WorldWorker {
    private static final String POWERED = "powered";
    /** {@link #act}: done; continuous mode waits its interval. */
    static final int ACTED = 0;
    /** {@link #act}: the faced block gives nothing to do; sleep until a neighbour changes. */
    static final int NOTHING_HERE = -1;
    /** {@link #act}: nothing to act with (no item in the network); back off, the key listeners wake it sooner. */
    static final int NOTHING_TO_USE = -2;
    /**
     * {@link #act}: the faced space is busy for now in a way no event reports (item entities lying there); continuous
     * mode looks again after its interval, a pulse is dropped.
     */
    static final int BUSY = -3;

    private boolean powered;
    private boolean pulse;

    TriggeredWorker(Host host) {
        super(host);
    }

    /** Ticks between actions in continuous mode, from config. */
    abstract int interval();

    /**
     * One action on {@code target}: {@link #ACTED}, {@link #NOTHING_HERE}, {@link #NOTHING_TO_USE}, {@link #BUSY}, or a
     * positive delay when the network refused (energy).
     */
    abstract int act(ServerLevel level, BlockPos target, Direction facing, StorageTransfer transfer);

    /** Continuous mode: act whenever possible rather than on pulses. */
    boolean continuous() {
        return mode() == 1;
    }

    /** A rising edge of the redstone signal at the device arms one action; any change wakes the task. */
    @Override
    public void neighbourChanged() {
        Level level = host.level().get();
        if (level != null && !level.isClientSide()) {
            boolean now = level.hasNeighborSignal(host.pos().get());
            if (now && !powered) {
                pulse = true;
            }
            if (now != powered) {
                powered = now;
                host.saved().run();
            }
        }
        wake();
    }

    @Override
    protected int work(ServerLevel level, StorageTransfer transfer, long gameTime) {
        boolean continuous = continuous();
        if (!continuous && !pulse) {
            return StorageTask.WAIT;
        }
        if (!transfer.canOperate()) {
            return Math.max(2, transfer.retryTicks());
        }
        Direction facing = host.facing().get();
        int result = act(level, host.pos().get().relative(facing), facing, transfer);
        if (result > 0) {
            return result;
        }
        pulse = false;
        if (!continuous) {
            return StorageTask.WAIT;
        }
        return switch (result) {
            case ACTED, BUSY -> Math.max(1, interval());
            case NOTHING_TO_USE -> StorageTask.IDLE;
            default -> StorageTask.WAIT;
        };
    }

    /**
     * The owner's fake player, standing in the device and looking at the faced block, with an empty hand; for placing
     * and breaking as that player would.
     */
    ServerPlayer actor(ServerLevel level, Direction facing) {
        ServerPlayer actor = WorldActors.get().player(level, ownerRecord());
        BlockPos pos = host.pos().get();
        actor.setPos(pos.getX() + 0.5, pos.getY() + 0.5 - actor.getEyeHeight(), pos.getZ() + 0.5);
        actor.setYRot(facing.getAxis().isHorizontal() ? facing.toYRot() : 0);
        actor.setXRot(facing == Direction.UP ? -90 : facing == Direction.DOWN ? 90 : 0);
        actor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        return actor;
    }

    @Override
    public void save(ValueOutput output) {
        super.save(output);
        output.putBoolean(POWERED, powered);
    }

    @Override
    public void load(ValueInput input) {
        super.load(input);
        powered = input.getBooleanOr(POWERED, false);
    }
}
