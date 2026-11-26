package dev.silencebound.maxtech.lib.api.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConduitFaceModeTest {
    @Test
    void idsRoundTrip() {
        for (ConduitFaceMode mode : ConduitFaceMode.values()) {
            assertEquals(Optional.of(mode), ConduitFaceMode.byId(mode.id()));
        }
        assertEquals(Optional.empty(), ConduitFaceMode.byId(-1));
        assertEquals(Optional.empty(), ConduitFaceMode.byId(4));
    }

    @Test
    void cyclesThroughAllModesInOrder() {
        Set<ConduitFaceMode> all = EnumSet.allOf(ConduitFaceMode.class);
        assertEquals(ConduitFaceMode.INSERT, ConduitFaceMode.BOTH.next(all));
        assertEquals(ConduitFaceMode.EXTRACT, ConduitFaceMode.INSERT.next(all));
        assertEquals(ConduitFaceMode.DISABLED, ConduitFaceMode.EXTRACT.next(all));
        assertEquals(ConduitFaceMode.BOTH, ConduitFaceMode.DISABLED.next(all));
    }

    @Test
    void skipsUnsupportedModes() {
        Set<ConduitFaceMode> link = EnumSet.of(ConduitFaceMode.BOTH, ConduitFaceMode.DISABLED);
        assertEquals(ConduitFaceMode.DISABLED, ConduitFaceMode.BOTH.next(link));
        assertEquals(ConduitFaceMode.BOTH, ConduitFaceMode.DISABLED.next(link));
        Set<ConduitFaceMode> noBoth = EnumSet.of(ConduitFaceMode.INSERT, ConduitFaceMode.DISABLED);
        assertEquals(ConduitFaceMode.INSERT, ConduitFaceMode.DISABLED.next(noBoth));
        assertEquals(ConduitFaceMode.DISABLED, ConduitFaceMode.EXTRACT.next(noBoth));
    }

    @Test
    void staysPutWithoutAnAlternative() {
        assertEquals(ConduitFaceMode.BOTH, ConduitFaceMode.BOTH.next(EnumSet.of(ConduitFaceMode.BOTH)));
        assertEquals(ConduitFaceMode.INSERT, ConduitFaceMode.INSERT.next(EnumSet.noneOf(ConduitFaceMode.class)));
    }

    @Test
    void directionsMatchTheMode() {
        assertTrue(ConduitFaceMode.BOTH.inserts() && ConduitFaceMode.BOTH.extracts());
        assertTrue(ConduitFaceMode.INSERT.inserts() && !ConduitFaceMode.INSERT.extracts());
        assertTrue(!ConduitFaceMode.EXTRACT.inserts() && ConduitFaceMode.EXTRACT.extracts());
        assertTrue(!ConduitFaceMode.DISABLED.inserts() && !ConduitFaceMode.DISABLED.extracts());
    }
}
