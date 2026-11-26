package dev.silencebound.maxtech.storage.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TerminalActionTest {
    @Test
    void everyKindRoundTripsWithAndWithoutAnEntry() {
        for (TerminalAction.Kind kind : TerminalAction.Kind.values()) {
            for (int id : new int[]{-1, 0, 1, 4095, TerminalAction.MAX_KEY_ID}) {
                TerminalAction action = new TerminalAction(kind, id);
                assertEquals(action, TerminalAction.decode(action.buttonId()), kind + " on " + id);
            }
        }
    }

    @Test
    void refusesWhatNoClientSends() {
        assertNull(TerminalAction.decode(-1), "negative button ids");
        assertNull(TerminalAction.decode(15), "an unknown kind code");
        assertThrows(IllegalArgumentException.class,
                () -> new TerminalAction(TerminalAction.Kind.TAKE_ONE, TerminalAction.MAX_KEY_ID + 1).buttonId());
        assertThrows(IllegalArgumentException.class,
                () -> new TerminalAction(TerminalAction.Kind.TAKE_ONE, -2).buttonId());
    }
}
