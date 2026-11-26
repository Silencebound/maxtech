package dev.silencebound.maxtech.storage.redstone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The redstone mode's pure rules (B09 item 9): the fixed ids saves and items hold, when each mode lets a device work,
 * the button's cycles with and without pulse, and the menu's packed data slot.
 */
class RedstoneModeTest {
    @Test
    void idsAreFixed() {
        assertEquals(0, RedstoneMode.IGNORE.id());
        assertEquals(1, RedstoneMode.WITH_SIGNAL.id());
        assertEquals(2, RedstoneMode.WITHOUT_SIGNAL.id());
        assertEquals(3, RedstoneMode.PULSE.id());
        for (RedstoneMode mode : RedstoneMode.values()) {
            assertEquals(mode, RedstoneMode.byId(mode.id()));
        }
        assertEquals(RedstoneMode.IGNORE, RedstoneMode.byId(99), "an unknown id is the default");
    }

    @Test
    void activeFollowsTheSignal() {
        assertTrue(RedstoneMode.IGNORE.active(false) && RedstoneMode.IGNORE.active(true));
        assertTrue(RedstoneMode.WITH_SIGNAL.active(true));
        assertFalse(RedstoneMode.WITH_SIGNAL.active(false));
        assertTrue(RedstoneMode.WITHOUT_SIGNAL.active(false));
        assertFalse(RedstoneMode.WITHOUT_SIGNAL.active(true));
        // Pulse works only on counted edges, which the device checks; the link stays on.
        assertTrue(RedstoneMode.PULSE.active(false) && RedstoneMode.PULSE.active(true));
    }

    @Test
    void cyclesVisitEveryModeOnceAndBackUndoesForward() {
        for (boolean pulses : new boolean[]{true, false}) {
            List<RedstoneMode> seen = new ArrayList<>();
            RedstoneMode mode = RedstoneMode.IGNORE;
            do {
                seen.add(mode);
                assertEquals(mode, mode.next(pulses).previous(pulses), "back after forward from " + mode);
                mode = mode.next(pulses);
            } while (mode != RedstoneMode.IGNORE);
            assertEquals(pulses ? 4 : 3, seen.size(), "modes with pulses " + pulses);
            assertEquals(pulses, seen.contains(RedstoneMode.PULSE));
        }
    }

    @Test
    void theDataSlotCarriesModeAndState() {
        RedstoneSync.State state = RedstoneSync.unpack(RedstoneMode.PULSE.id() | 1 << 4 | 1 << 6);
        assertEquals(RedstoneMode.PULSE, state.mode());
        assertTrue(state.pulses());
        assertFalse(state.powered());
        assertTrue(state.inactive());
        RedstoneSync.State other = RedstoneSync.unpack(RedstoneMode.WITHOUT_SIGNAL.id() | 1 << 5);
        assertEquals(RedstoneMode.WITHOUT_SIGNAL, other.mode());
        assertTrue(other.powered());
        assertFalse(other.pulses() || other.inactive());
    }
}
