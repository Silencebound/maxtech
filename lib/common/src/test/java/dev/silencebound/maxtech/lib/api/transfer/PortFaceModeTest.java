package dev.silencebound.maxtech.lib.api.transfer;

import static dev.silencebound.maxtech.lib.api.transfer.PortFaceMode.OFF;
import static dev.silencebound.maxtech.lib.api.transfer.PortFaceMode.PULL;
import static dev.silencebound.maxtech.lib.api.transfer.PortFaceMode.PUSH;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void betweenTwoPortsOnlyThePusherFacingAPullerMoves() {
        PortFaceMode[] modes = {OFF, PUSH, PULL};
        boolean[][] moves = {
                // facing: OFF, PUSH, PULL
                {false, false, false}, // OFF
                {false, false, true}, // PUSH: only into a pulling face
                {false, false, false}, // PULL: never from a pushing face; the pusher moves
        };
        for (int mine = 0; mine < modes.length; mine++) {
            for (int facing = 0; facing < modes.length; facing++) {
                assertEquals(moves[mine][facing], modes[mine].movesAgainst(modes[facing]),
                        modes[mine] + " facing " + modes[facing]);
            }
        }
    }
}
