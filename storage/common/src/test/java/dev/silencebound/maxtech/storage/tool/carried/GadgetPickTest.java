package dev.silencebound.maxtech.storage.tool.carried;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The carried-gadget picker's rules: what a press does, and which row the mouse or a number key picks. */
class GadgetPickTest {
    @Test
    void onePressSaysOpensOrOffers() {
        assertEquals(GadgetPick.Outcome.NONE, GadgetPick.outcome(0), "nothing carried");
        assertEquals(GadgetPick.Outcome.OPEN, GadgetPick.outcome(1), "one carried opens at once");
        assertEquals(GadgetPick.Outcome.CHOOSE, GadgetPick.outcome(2), "two offer the picker");
        assertEquals(GadgetPick.Outcome.CHOOSE, GadgetPick.outcome(4));
    }

    @Test
    void mouseRowsAreRowHeightApart() {
        assertEquals(-1, GadgetPick.rowAt(-1, 3), "above the first row");
        assertEquals(0, GadgetPick.rowAt(0, 3));
        assertEquals(0, GadgetPick.rowAt(GadgetPick.ROW_HEIGHT - 0.5, 3));
        assertEquals(1, GadgetPick.rowAt(GadgetPick.ROW_HEIGHT, 3));
        assertEquals(2, GadgetPick.rowAt(3 * GadgetPick.ROW_HEIGHT - 1, 3));
        assertEquals(-1, GadgetPick.rowAt(3 * GadgetPick.ROW_HEIGHT, 3), "below the last row");
        assertEquals(3 * GadgetPick.ROW_HEIGHT, GadgetPick.height(3));
    }

    @Test
    void numberKeysPickTheirRowOnly() {
        assertEquals(0, GadgetPick.rowForKey(GadgetPick.KEY_1, 2), "1 is the first row");
        assertEquals(1, GadgetPick.rowForKey(GadgetPick.KEY_1 + 1, 2), "2 the second");
        assertEquals(-1, GadgetPick.rowForKey(GadgetPick.KEY_1 + 2, 2), "3 with two rows");
        assertEquals(-1, GadgetPick.rowForKey(GadgetPick.KEY_1 - 1, 2), "0 picks nothing");
        assertEquals(-1, GadgetPick.rowForKey(65, 4), "a letter picks nothing");
    }
}
