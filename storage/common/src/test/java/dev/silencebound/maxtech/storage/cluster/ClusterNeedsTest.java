package dev.silencebound.maxtech.storage.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.storage.crafting.cpu.CpuPart;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuStats;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClusterNeedsTest {
    /** 64 steps, 4 lanes, batch 8; a core alone has 8 steps, each storage block adds 8 and each cache block 2. */
    private static final CpuStats.Caps CAPS = new CpuStats.Caps(64, 4, 8, 10);
    private static final CpuStats.Parts PARTS = new CpuStats.Parts(8, 8, 2);

    private static List<ClusterNeeds.Need> controller(int slotted, int cap, int maxCap, boolean over, boolean idle) {
        List<ClusterNeeds.Need> out = new ArrayList<>();
        ClusterNeeds.controller(0, slotted, cap, maxCap, over, idle, out);
        return out;
    }

    private static List<ClusterNeeds.Need> cpu(int storage, int lanes, int caches, boolean conflict, int maxParts) {
        List<ClusterNeeds.Need> out = new ArrayList<>();
        ClusterNeeds.cpu(0, 1, storage, lanes, caches, conflict, maxParts, CAPS, PARTS, out);
        return out;
    }

    @Test
    void partsToCapMatchesTheStats() {
        // (64 - 8) / 8 = 7 storage blocks; 3 lanes; (8 - 1) / 2 rounded up = 4 cache blocks.
        assertEquals(7, CpuStats.partsToCap(CAPS, PARTS, CpuPart.STORAGE));
        assertEquals(3, CpuStats.partsToCap(CAPS, PARTS, CpuPart.LANE));
        assertEquals(4, CpuStats.partsToCap(CAPS, PARTS, CpuPart.CACHE));
        // The counts that reach the caps do reach them, one fewer does not.
        assertEquals(64, CpuStats.of(CAPS, PARTS, 7, 3, 4).steps());
        assertEquals(56, CpuStats.of(CAPS, PARTS, 6, 3, 4).steps());
        assertEquals(8, CpuStats.of(CAPS, PARTS, 7, 3, 4).batch());
        assertEquals(7, CpuStats.of(CAPS, PARTS, 7, 3, 3).batch());
        assertEquals(0, CpuStats.partsToCap(CAPS, new CpuStats.Parts(8, 0, 2), CpuPart.STORAGE));
    }

    @Test
    void controllerLines() {
        assertEquals(new ClusterNeeds.Need(ClusterNeeds.Kind.CONTROLLER_FREE, 0, 3, 8, 5),
                controller(3, 8, 16, false, false).get(0));
        assertEquals(ClusterNeeds.Kind.CONTROLLER_FULL, controller(8, 8, 16, false, false).get(0).kind());
        assertEquals(ClusterNeeds.Kind.CONTROLLER_FULL_MAX, controller(16, 16, 16, false, false).get(0).kind());
        assertEquals(ClusterNeeds.Kind.CONTROLLER_OVER, controller(8, 8, 16, true, false).get(0).kind());
        assertEquals(ClusterNeeds.Kind.CONTROLLER_IDLE, controller(0, 8, 16, false, true).get(0).kind());
        assertEquals(1, controller(3, 8, 16, false, false).size());
    }

    @Test
    void cpuMissingAndExtraParts() {
        List<ClusterNeeds.Need> needs = cpu(2, 5, 4, false, 64);
        assertEquals(ClusterNeeds.Kind.CPU, needs.get(0).kind());
        assertEquals(new ClusterNeeds.Need(ClusterNeeds.Kind.CPU_STORAGE_MORE, 0, 5, 64, 0), needs.get(1));
        assertEquals(new ClusterNeeds.Need(ClusterNeeds.Kind.CPU_LANE_EXTRA, 0, 2, 0, 0), needs.get(2));
        // Cache blocks are exactly at the cap: no line.
        assertEquals(3, needs.size());
    }

    @Test
    void cpuCompleteConflictAndLimit() {
        List<ClusterNeeds.Need> complete = cpu(7, 3, 4, false, 64);
        assertEquals(ClusterNeeds.Kind.CPU_COMPLETE, complete.get(1).kind());

        List<ClusterNeeds.Need> conflict = cpu(1, 1, 1, true, 64);
        assertEquals(2, conflict.size());
        assertEquals(ClusterNeeds.Kind.CPU_CONFLICT, conflict.get(1).kind());

        // At the part limit "more" lines would mislead; extra parts still show.
        List<ClusterNeeds.Need> limit = cpu(1, 4, 0, false, 5);
        assertEquals(ClusterNeeds.Kind.CPU_PART_LIMIT, limit.get(1).kind());
        assertEquals(ClusterNeeds.Kind.CPU_LANE_EXTRA, limit.get(2).kind());
        assertEquals(3, limit.size());
    }

    @Test
    void helpsOnlyBelowCapAndLimit() {
        assertTrue(ClusterNeeds.helps(2, 2, 64, CAPS, PARTS, CpuPart.STORAGE));
        assertFalse(ClusterNeeds.helps(7, 7, 64, CAPS, PARTS, CpuPart.STORAGE));
        assertFalse(ClusterNeeds.helps(2, 64, 64, CAPS, PARTS, CpuPart.STORAGE));
    }
}
