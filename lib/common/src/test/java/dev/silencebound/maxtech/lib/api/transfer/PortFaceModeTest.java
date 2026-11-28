package dev.silencebound.maxtech.lib.api.transfer;

import static dev.silencebound.maxtech.lib.api.transfer.PortFaceMode.OFF;
import static dev.silencebound.maxtech.lib.api.transfer.PortFaceMode.PULL;
import static dev.silencebound.maxtech.lib.api.transfer.PortFaceMode.PUSH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PortFaceModeTest {
    @Test
    void idsAreStable() {
        assertEquals(0, OFF.id());
        assertEquals(1, PUSH.id());
        assertEquals(2, PULL.id());
        for (PortFaceMode mode : PortFaceMode.values()) {
            assertEquals(Optional.of(mode), PortFaceMode.byId(mode.id()));
        }
        assertEquals(Optional.empty(), PortFaceMode.byId(3));
        assertEquals(Optional.empty(), PortFaceMode.byId(-1));
    }

    @Test
    void nextCyclesThroughAllowedModes() {
        Set<PortFaceMode> all = EnumSet.allOf(PortFaceMode.class);
        assertEquals(PUSH, OFF.next(all));
        assertEquals(PULL, PUSH.next(all));
        assertEquals(OFF, PULL.next(all));
        Set<PortFaceMode> pushOnly = EnumSet.of(OFF, PUSH);
        assertEquals(PUSH, OFF.next(pushOnly));
        assertEquals(OFF, PUSH.next(pushOnly));
        assertEquals(OFF, PULL.next(pushOnly));
        assertEquals(OFF, OFF.next(EnumSet.of(OFF)));
    }

    @Test
    void onlyOneSideOfAFacingPairMoves() {
        assertFalse(PULL.movesAgainst(PUSH), "a pulling face never pulls from a pushing port face");
        assertTrue(PUSH.movesAgainst(PULL), "the pushing face does the move");
        assertTrue(PUSH.movesAgainst(PUSH), "two pushing faces push into each other");
        assertTrue(PULL.movesAgainst(PULL), "two pulling faces pull from each other");
        assertTrue(PUSH.movesAgainst(OFF));
        assertTrue(PULL.movesAgainst(OFF));
        for (PortFaceMode facing : PortFaceMode.values()) {
            assertFalse(OFF.movesAgainst(facing), "an off face never moves");
        }
    }
}
