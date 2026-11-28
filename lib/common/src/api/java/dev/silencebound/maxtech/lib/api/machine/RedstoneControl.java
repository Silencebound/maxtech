package dev.silencebound.maxtech.lib.api.machine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A machine's {@link RedstoneMode} and the signal it last read, composed into the machine's block entity. Nothing
 * ticks: the block calls {@link #neighbourChanged} from its {@code neighborChanged}, the block entity calls
 * {@link #start} once in {@code onLoad}, and the machine's process asks {@link #allowsRun()} before it runs and calls
 * {@link #consumePulse()} when it starts a run. The signal is read the way vanilla's {@code hasNeighborSignal} reads it
 * (a neighbour's weak signal, or a strong signal into a conducting neighbour), except that a block in a chunk that is
 * not loaded is never loaded: then the last reading stays. Positions outside the build height give no signal.
 * <p>
 * In {@link RedstoneMode#PULSE} every rising edge of the signal counts one run, up to {@link #MAX_PENDING} waiting runs
 * (more are dropped); a signal that stays on counts nothing more, and an edge seen while the machine starts or loads is
 * not counted. Pulses and the reading are saved with the mode, so an edge across a reload is neither lost nor counted
 * twice. Changing the mode drops the waiting pulses.
 * <p>
 * {@code changed} runs whenever something to save changed (pass the block entity's {@code setChanged}); {@code wake}
 * runs when {@link #allowsRun()} may have changed or a pulse was counted, so a sleeping process can look again. Server
 * thread.
 */
public final class RedstoneControl {
    /** The most rising edges kept waiting for a run. */
    public static final int MAX_PENDING = 8;

    private static final String MODE = "mode";
    private static final String POWERED = "powered";
    private static final String PULSES = "pulses";
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int UNPOWERED = 0;
    private static final int POWERED_READ = 1;
    private static final int UNKNOWN = -1;

    private final Runnable changed;
    private final Runnable wake;
    private RedstoneMode mode = RedstoneMode.ALWAYS;
    private boolean powered;
    private int pending;

    /**
     * @param changed
     *            marks the machine for saving
     * @param wake
     *            wakes the machine's process
     */
    public RedstoneControl(Runnable changed, Runnable wake) {
        this.changed = changed;
        this.wake = wake;
    }

    /** The mode; {@link RedstoneMode#ALWAYS} until set. */
    public RedstoneMode mode() {
        return mode;
    }

    /** Whether the machine got a signal at the last reading. */
    public boolean powered() {
        return powered;
    }

    /** The rising edges counted in {@link RedstoneMode#PULSE} and not used by a run yet. */
    public int pendingPulses() {
        return pending;
    }

    /**
     * Whether the mode lets the machine run now: always, with or without a signal by the mode, while a pulse waits in
     * {@link RedstoneMode#PULSE}, never in {@link RedstoneMode#NEVER}. In pulse mode a run that started keeps going;
     * the process asks this only before it starts one.
     */
    public boolean allowsRun() {
        return switch (mode) {
            case ALWAYS -> true;
            case HIGH -> powered;
            case LOW -> !powered;
            case PULSE -> pending > 0;
            case NEVER -> false;
        };
    }

    /**
     * Uses one waiting pulse for a run that starts now; returns whether there was one. Does nothing and returns false
     * outside {@link RedstoneMode#PULSE}, so a process calls it for every run it starts.
     */
    public boolean consumePulse() {
        if (mode != RedstoneMode.PULSE || pending == 0) {
            return false;
        }
        pending--;
        changed.run();
        if (pending == 0) {
            wake.run();
        }
        return true;
    }

    /** Sets the mode, drops waiting pulses, saves and wakes; returns whether the mode changed. */
    public boolean setMode(RedstoneMode next) {
        if (next == mode) {
            return false;
        }
        mode = next;
        pending = 0;
        changed.run();
        wake.run();
        return true;
    }

    /**
     * Reads the signal at {@code pos} once as the machine starts (its {@code onLoad}), without counting an edge or
     * waking; the machine starts its process after.
     */
    public void start(Level level, BlockPos pos) {
        int read = read(level, pos);
        if (read != UNKNOWN && (read == POWERED_READ) != powered) {
            powered = read == POWERED_READ;
            changed.run();
        }
    }

    /**
     * A neighbour of the machine at {@code pos} changed: reads the signal, counts a rising edge in
     * {@link RedstoneMode#PULSE}, and wakes the machine when that or the signal changes whether it may run.
     */
    public void neighbourChanged(Level level, BlockPos pos) {
        int read = read(level, pos);
        boolean now = read == POWERED_READ || (read == UNKNOWN && powered);
        if (now == powered) {
            return;
        }
        boolean allowed = allowsRun();
        powered = now;
        boolean counted = false;
        if (now && mode == RedstoneMode.PULSE && pending < MAX_PENDING) {
            pending++;
            counted = true;
        }
        changed.run();
        if (counted || allowed != allowsRun()) {
            wake.run();
        }
    }

    /** Writes the mode, the reading and the waiting pulses, leaving out defaults. */
    public void save(ValueOutput output) {
        if (mode != RedstoneMode.ALWAYS) {
            output.putString(MODE, mode.serializedName());
        }
        if (powered) {
            output.putBoolean(POWERED, true);
        }
        if (pending > 0) {
            output.putInt(PULSES, pending);
        }
    }

    /**
     * Reads what {@link #save} wrote, without saving or waking; an unknown mode loads as {@link RedstoneMode#ALWAYS}.
     */
    public void load(ValueInput input) {
        mode = RedstoneMode.byName(input.getStringOr(MODE, RedstoneMode.ALWAYS.serializedName()));
        powered = input.getBooleanOr(POWERED, false);
        pending = mode == RedstoneMode.PULSE ? Math.clamp(input.getIntOr(PULSES, 0), 0, MAX_PENDING) : 0;
    }

    /**
     * {@link #POWERED_READ}, {@link #UNPOWERED} or, when no loaded neighbour gives a signal but one is not loaded,
     * {@link #UNKNOWN}.
     */
    private static int read(Level level, BlockPos pos) {
        boolean unknown = false;
        for (Direction direction : DIRECTIONS) {
            BlockPos neighbour = pos.relative(direction);
            if (level.isOutsideBuildHeight(neighbour)) {
                continue;
            }
            if (!level.isLoaded(neighbour)) {
                unknown = true;
                continue;
            }
            BlockState state = level.getBlockState(neighbour);
            if (state.getSignal(level, neighbour, direction) > 0) {
                return POWERED_READ;
            }
            if (state.isRedstoneConductor(level, neighbour)) {
                for (Direction into : DIRECTIONS) {
                    BlockPos source = neighbour.relative(into);
                    if (level.isOutsideBuildHeight(source)) {
                        continue;
                    }
                    if (!level.isLoaded(source)) {
                        unknown = true;
                    } else if (level.getDirectSignal(source, into) > 0) {
                        return POWERED_READ;
                    }
                }
            }
        }
        return unknown ? UNKNOWN : UNPOWERED;
    }
}
