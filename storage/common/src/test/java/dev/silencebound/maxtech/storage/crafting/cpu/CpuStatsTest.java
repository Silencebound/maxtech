package dev.silencebound.maxtech.storage.crafting.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CpuStatsTest {
    private static final CpuStats.Caps CAPS = new CpuStats.Caps(64, 4, 8, 5);
    private static final CpuStats.Parts PARTS = new CpuStats.Parts(8, 16, 4);

    @Test
    void coreAloneHasBaseNumbers() {
        assertEquals(new CpuStats(8, 1, 1, 5), CpuStats.of(CAPS, PARTS, 0, 0, 0));
    }

    @Test
    void partsFillTheCaps() {
        assertEquals(new CpuStats(40, 3, 5, 5), CpuStats.of(CAPS, PARTS, 2, 2, 1));
    }

    @Test
    void tierCapsBound() {
        assertEquals(new CpuStats(64, 4, 8, 5), CpuStats.of(CAPS, PARTS, 100, 100, 100));
    }

    @Test
    void hugeCountsSaturateInsteadOfOverflowing() {
        CpuStats stats = CpuStats.of(new CpuStats.Caps(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 0),
                new CpuStats.Parts(1, Integer.MAX_VALUE, Integer.MAX_VALUE), Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, stats.steps());
        assertEquals(Integer.MAX_VALUE, stats.lanes());
        assertEquals(Integer.MAX_VALUE, stats.batch());
    }

    @Test
    void negativeCountsCountAsNone() {
        assertEquals(CpuStats.of(CAPS, PARTS, 0, 0, 0), CpuStats.of(CAPS, PARTS, -3, -1, -7));
    }
}
