package dev.silencebound.maxtech.storage.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerminalViewTest {
    @Test
    void showsItemsFluidsOrBoth() {
        assertTrue(TerminalView.ALL.shows(false));
        assertTrue(TerminalView.ALL.shows(true));
        assertTrue(TerminalView.ITEMS.shows(false));
        assertFalse(TerminalView.ITEMS.shows(true));
        assertFalse(TerminalView.FLUIDS.shows(false));
        assertTrue(TerminalView.FLUIDS.shows(true));
    }

    @Test
    void cyclesThroughEveryViewBackToAll() {
        TerminalView view = TerminalView.ALL;
        for (int i = 0; i < TerminalView.values().length; i++) {
            view = view.next();
        }
        assertEquals(TerminalView.ALL, view);
        assertEquals(TerminalView.ITEMS, TerminalView.ALL.next());
        assertEquals(TerminalView.FLUIDS, TerminalView.ITEMS.next());
    }

    @Test
    void filterIsOneObjectPerViewAndNoneForAll() {
        assertNull(TerminalView.ALL.filter(), "no filter: the index skips the check");
        for (TerminalView view : TerminalView.values()) {
            assertSame(view.filter(), view.filter(), "the index rebuilds only when the filter object changes");
        }
    }

    @Test
    void serialNamesRoundTripAndUnknownIsAll() {
        for (TerminalView view : TerminalView.values()) {
            assertEquals(view, TerminalView.bySerialName(view.serialName()));
        }
        assertEquals(TerminalView.ALL, TerminalView.bySerialName("gases"));
        assertEquals(TerminalView.ALL, TerminalView.bySerialName(null));
    }
}
