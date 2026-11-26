package dev.silencebound.maxtech.storage.redstone;

import net.minecraft.world.inventory.DataSlot;
import org.jspecify.annotations.Nullable;

/**
 * How a device menu carries the redstone mode to its screen: one data slot with the mode's id in the low four bits, and
 * whether the device offers pulse, gets a signal and is inactive in the bits above (data slots sync shorts, which is
 * plenty). The screen's mode tab reads it; its button steps the mode through the menu's button ids ({@link #step}).
 */
public final class RedstoneSync {
    private static final int MODE_MASK = 0xF;
    private static final int PULSES = 1 << 4;
    private static final int POWERED = 1 << 5;
    private static final int INACTIVE = 1 << 6;

    private RedstoneSync() {
    }

    /** What the screen knows: the mode, whether pulse is offered, the signal and whether the device is inactive. */
    public record State(RedstoneMode mode, boolean pulses, boolean powered, boolean inactive) {
    }

    /** The data slot: live from {@code device} on the server, plain on the client (null device). */
    public static DataSlot slot(@Nullable RedstoneControlled device) {
        if (device == null) {
            return DataSlot.standalone();
        }
        return new DataSlot() {
            @Override
            public int get() {
                return pack(device.redstone());
            }

            @Override
            public void set(int value) {
                // Server side the device is the truth; the menu buttons change it.
            }
        };
    }

    /** {@code control} packed into one data slot value. */
    static int pack(RedstoneControl control) {
        return control.mode().id() | (control.pulses() ? PULSES : 0) | (control.powered() ? POWERED : 0)
                | (control.inactive() ? INACTIVE : 0);
    }

    /** The state in {@code slot}. */
    public static State read(DataSlot slot) {
        return unpack(slot.get());
    }

    /** The state packed in {@code value}. */
    static State unpack(int value) {
        return new State(RedstoneMode.byId(value & MODE_MASK), (value & PULSES) != 0, (value & POWERED) != 0,
                (value & INACTIVE) != 0);
    }

    /** Steps {@code control}'s mode forward or back in its cycle; server side, after the menu checked access. */
    public static void step(RedstoneControl control, boolean forward) {
        RedstoneMode mode = control.mode();
        control.setMode(forward ? mode.next(control.pulses()) : mode.previous(control.pulses()));
    }
}
