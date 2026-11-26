package dev.silencebound.maxtech.storage.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Research screen clicks survive the container button id, and ids stay non-negative. */
class ResearchActionTest {
    @Test
    void everyKindRoundTrips() {
        for (ResearchAction.Kind kind : ResearchAction.Kind.values()) {
            ResearchAction action = new ResearchAction(kind, 1234, 15);
            assertTrue(action.encode() >= 0);
            assertEquals(action, ResearchAction.decode(action.encode()));
        }
    }

    @Test
    void nodeAndOptionKeepTheirBits() {
        assertEquals(new ResearchAction(ResearchAction.Kind.PUZZLE, 0, 2),
                ResearchAction.decode(new ResearchAction(ResearchAction.Kind.PUZZLE, 0, 2).encode()));
        assertEquals(new ResearchAction(ResearchAction.Kind.USE_TOKEN, 7, 0),
                ResearchAction.decode(new ResearchAction(ResearchAction.Kind.USE_TOKEN, 7, 0).encode()));
    }

    @Test
    void outOfRangeOptionsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ResearchAction(ResearchAction.Kind.PUZZLE, 0, 16));
        assertThrows(IllegalArgumentException.class, () -> new ResearchAction(ResearchAction.Kind.PUZZLE, -1, 0));
    }
}
