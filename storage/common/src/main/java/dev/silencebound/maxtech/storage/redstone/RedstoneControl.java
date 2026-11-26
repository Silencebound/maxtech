package dev.silencebound.maxtech.storage.redstone;

import dev.silencebound.maxtech.storage.network.DeviceLink;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * One device's redstone mode (B09 item 9) and the signal it last read. Outside its mode's signal the device is
 * inactive: its {@link DeviceLink} stops ({@link DeviceLink#setRedstoneOff}), so its tasks sleep and its work paths
 * refuse, and a storage bus withdraws what it shows. In {@link RedstoneMode#PULSE} the device stays active but works
 * only on the rising edges counted here ({@link #takePulses}). The signal is read only on neighbour updates and once
 * when the device starts, never polled; a neighbour in a chunk that is not loaded keeps the last reading. The mode is
 * saved by its fixed id, and the last reading too, so an edge across a reload is neither lost nor doubled. Server
 * thread.
 */
public final class RedstoneControl {
    /** Rising edges kept while the device has not used them yet; more are dropped, as a dispenser drops them. */
    static final int MAX_PENDING = 8;
    private static final String MODE = "redstone";
    private static final String POWERED = "redstone_powered";

    private final DeviceLink link;
    private final boolean pulses;
    private final BooleanSupplier pulseCounts;
    private final Runnable saved;
    private final Runnable wake;
    private RedstoneMode mode = RedstoneMode.IGNORE;
    private boolean powered;
    private int pending;
    /** Where the device reads its signal, once it started; null before. */
    private @Nullable Level level;
    private @Nullable BlockPos pos;
    /** In ignore mode updates are not read: the reading may be old until the mode changes. */
    private boolean stale;

    /**
     * @param pulses
     *            whether the device offers {@link RedstoneMode#PULSE} at all
     * @param pulseCounts
     *            whether pulse mode counts now; while false it acts as {@link RedstoneMode#IGNORE} (a port in storage
     *            mode)
     * @param saved
     *            marks the device for saving
     * @param wake
     *            wakes the device's work, for a counted edge or a changed mode
     */
    public RedstoneControl(DeviceLink link, boolean pulses, BooleanSupplier pulseCounts, Runnable saved,
            Runnable wake) {
        this.link = link;
        this.pulses = pulses;
        this.pulseCounts = pulseCounts;
        this.saved = saved;
        this.wake = wake;
    }

    /** The mode as set. */
    public RedstoneMode mode() {
        return mode;
    }

    /** Whether the device offers {@link RedstoneMode#PULSE}. */
    public boolean pulses() {
        return pulses;
    }

    /** The signal at the last reading. */
    public boolean powered() {
        return powered;
    }

    /**
     * The mode that applies now: {@link RedstoneMode#PULSE} acts as {@link RedstoneMode#IGNORE} while it does not
     * count.
     */
    public RedstoneMode effective() {
        return mode == RedstoneMode.PULSE && !pulseCounts.getAsBoolean() ? RedstoneMode.IGNORE : mode;
    }

    /** Whether the device works only on counted edges now. */
    public boolean pulsing() {
        return effective() == RedstoneMode.PULSE;
    }

    /** Whether the mode keeps the device from working now. */
    public boolean inactive() {
        return !effective().active(powered);
    }

    /**
     * Sets the mode ({@link RedstoneMode#PULSE} becomes {@link RedstoneMode#IGNORE} for a device without it), saves and
     * wakes the device. Returns whether it changed.
     */
    public boolean setMode(RedstoneMode next) {
        RedstoneMode checked = next == RedstoneMode.PULSE && !pulses ? RedstoneMode.IGNORE : next;
        if (checked == mode) {
            return false;
        }
        mode = checked;
        pending = 0;
        Level current = level;
        BlockPos at = pos;
        if (stale && current != null && at != null) {
            // Ignore mode skipped the updates: read now, without counting an edge.
            stale = false;
            int read = RedstoneSignal.read(current, at);
            if (read != RedstoneSignal.UNKNOWN) {
                powered = read == RedstoneSignal.POWERED;
            }
        }
        saved.run();
        apply();
        wake.run();
        return true;
    }

    /** Takes the mode from a placed item or a load, without saving or waking. */
    public void restore(RedstoneMode next) {
        mode = next == RedstoneMode.PULSE && !pulses ? RedstoneMode.IGNORE : next;
        pending = 0;
        apply();
    }

    /** Reads the signal once as the device starts at {@code pos}; an edge here is not counted. */
    public void start(Level level, BlockPos pos) {
        this.level = level;
        this.pos = pos.immutable();
        stale = false;
        int read = RedstoneSignal.read(level, pos);
        if (read != RedstoneSignal.UNKNOWN && (read == RedstoneSignal.POWERED) != powered) {
            powered = read == RedstoneSignal.POWERED;
            saved.run();
        }
        apply();
    }

    /**
     * A neighbour changed: reads the signal at {@code pos}, counts a rising edge in pulse mode and applies the mode. In
     * ignore mode, the default, it only notes that the reading is old and returns, so most devices pay nothing per
     * update; a mode change reads again.
     */
    public void neighbourChanged(Level level, BlockPos pos) {
        if (mode == RedstoneMode.IGNORE) {
            stale = true;
            return;
        }
        int read = RedstoneSignal.read(level, pos);
        signal(read == RedstoneSignal.POWERED || (read == RedstoneSignal.UNKNOWN && powered));
    }

    /** Takes the reading {@code now}; split out of {@link #neighbourChanged} for tests. */
    void signal(boolean now) {
        boolean rising = now && !powered;
        if (now != powered) {
            powered = now;
            saved.run();
        }
        if (rising && pulsing()) {
            pending = Math.min(MAX_PENDING, pending + 1);
            wake.run();
        }
        apply();
    }

    /** Whether rising edges are waiting to be used. */
    public boolean hasPulses() {
        return pending > 0;
    }

    /** The rising edges counted since the last call, at most {@link #MAX_PENDING}; clears them. */
    public int takePulses() {
        int taken = pending;
        pending = 0;
        return taken;
    }

    /** The device's own mode changed (a port into or out of storage mode): applies the mode again. */
    public void refresh() {
        if (!pulsing()) {
            pending = 0;
        }
        apply();
    }

    private void apply() {
        link.setRedstoneOff(inactive());
    }

    /** Puts the mode into an item's components, unless it is the default. */
    public void write(DataComponentMap.Builder components) {
        if (mode != RedstoneMode.IGNORE) {
            components.set(ModDataComponents.REDSTONE_MODE.get(), mode);
        }
    }

    /** The mode an item's components name; {@link RedstoneMode#IGNORE} when they name none. */
    public static RedstoneMode mode(DataComponentGetter components) {
        return components.getOrDefault(ModDataComponents.REDSTONE_MODE.get(), RedstoneMode.IGNORE);
    }

    /** Writes the mode and the reading, leaving out the defaults. */
    public void save(ValueOutput output) {
        if (mode != RedstoneMode.IGNORE) {
            output.putInt(MODE, mode.id());
        }
        if (powered) {
            output.putBoolean(POWERED, true);
        }
    }

    /** Reads what {@link #save} wrote, without saving or waking. */
    public void load(ValueInput input) {
        powered = input.getBooleanOr(POWERED, false);
        restore(RedstoneMode.byId(input.getIntOr(MODE, RedstoneMode.IGNORE.id())));
    }
}
