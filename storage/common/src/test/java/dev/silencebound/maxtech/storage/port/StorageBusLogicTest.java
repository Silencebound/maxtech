package dev.silencebound.maxtech.storage.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The storage bus's pure parts (B09 item 8): the look cadence, the mode and access ids and names that saves and menus
 * hold, and the fingerprint mix.
 */
class StorageBusLogicTest {
    private static final int MIN = 5;
    private static final int MAX = 100;

    @Test
    void firstLookRunsAtOnceThenKeepsTheMinimumSpacing() {
        BusCadence cadence = new BusCadence();
        assertEquals(0, cadence.delay(1000, MIN));
        cadence.scanned(1000);
        assertEquals(MIN, cadence.delay(1000, MIN));
        assertEquals(2, cadence.delay(1003, MIN));
        assertEquals(0, cadence.delay(1005, MIN));
        // An event does not lift the spacing: a hopper feeding the chest every tick costs one look per MIN ticks.
        cadence.poke();
        assertEquals(1, cadence.delay(1004, MIN));
    }

    @Test
    void unchangedLooksBackOffToTheCapAndActivityResets() {
        BusCadence cadence = new BusCadence();
        int[] expected = {5, 10, 20, 40, 80, 100, 100};
        for (int wait : expected) {
            assertEquals(wait, cadence.unchanged(MIN, MAX));
        }
        assertEquals(MIN, cadence.active(MIN));
        assertEquals(0, cadence.interval());
        assertEquals(MIN, cadence.unchanged(MIN, MAX));
        assertEquals(2 * MIN, cadence.unchanged(MIN, MAX));
        cadence.poke();
        assertEquals(MIN, cadence.unchanged(MIN, MAX));
    }

    @Test
    void resetLooksAtOnce() {
        BusCadence cadence = new BusCadence();
        cadence.scanned(50);
        cadence.unchanged(MIN, MAX);
        cadence.reset();
        assertEquals(0, cadence.delay(51, MIN));
        assertEquals(0, cadence.interval());
    }

    @Test
    void capBelowMinimumStillWaitsTheMinimum() {
        BusCadence cadence = new BusCadence();
        assertEquals(MIN, cadence.unchanged(MIN, 1));
        assertEquals(MIN, cadence.unchanged(MIN, 1));
    }

    /** Saves, items and block states hold the names; menus and the part's look the ids. Neither may change. */
    @Test
    void modeNamesAndIdsAreStable() {
        assertEquals("import", PortMode.IMPORT.getSerializedName());
        assertEquals("export", PortMode.EXPORT.getSerializedName());
        assertEquals("storage", PortMode.STORAGE.getSerializedName());
        Set<Integer> ids = new HashSet<>();
        for (PortMode mode : PortMode.values()) {
            assertEquals(mode, PortMode.byName(mode.getSerializedName()));
            assertEquals(mode, PortMode.byId(mode.id()));
            assertTrue(ids.add(mode.id()), "duplicate id " + mode.id());
        }
        assertEquals(0, PortMode.IMPORT.id());
        assertEquals(1, PortMode.EXPORT.id());
        assertEquals(PortMode.IMPORT, PortMode.byName(""));
        assertEquals(PortMode.IMPORT, PortMode.byId(99));
    }

    @Test
    void modeCycleVisitsEveryModeOnce() {
        Set<PortMode> seen = new HashSet<>();
        PortMode mode = PortMode.IMPORT;
        for (int i = 0; i < PortMode.values().length; i++) {
            assertTrue(seen.add(mode), "visited twice: " + mode);
            mode = mode.next();
        }
        assertEquals(PortMode.IMPORT, mode);
    }

    @Test
    void accessReadsAndWrites() {
        assertTrue(BusAccess.READ.reads());
        assertFalse(BusAccess.READ.writes());
        assertFalse(BusAccess.WRITE.reads());
        assertTrue(BusAccess.WRITE.writes());
        assertTrue(BusAccess.BOTH.reads() && BusAccess.BOTH.writes());
        Set<BusAccess> seen = new HashSet<>();
        BusAccess access = BusAccess.BOTH;
        for (int i = 0; i < BusAccess.values().length; i++) {
            assertTrue(seen.add(access));
            assertEquals(access, BusAccess.byName(access.getSerializedName()));
            assertEquals(access, BusAccess.byId(access.id()));
            access = access.next();
        }
        assertEquals(BusAccess.BOTH, access);
        assertEquals(BusAccess.BOTH, BusAccess.byName("nonsense"));
    }

    @Test
    void mixSeparatesOrderAndValues() {
        long a = PortStorage.mix(PortStorage.mix(1, 10), 20);
        long b = PortStorage.mix(PortStorage.mix(1, 20), 10);
        assertNotEquals(a, b, "order must count: two slots swapping contents is a change");
        assertNotEquals(PortStorage.mix(1, 64), PortStorage.mix(1, 63));
        assertEquals(PortStorage.mix(7, 3), PortStorage.mix(7, 3));
    }
}
