package dev.silencebound.maxtech.storage.crafting.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.junit.jupiter.api.Test;

class PlanLayoutTest {
    /**
     * <pre>
     * 0
     * +- 1
     * |  +- 2
     * |  `- 3
     * |     `- 4
     * `- 5
     *    `- 6
     * </pre>
     */
    private static final int[] TREE = {-1, 0, 1, 1, 3, 0, 5};

    private static int[] rows(PlanLayout layout) {
        int[] rows = new int[layout.rowCount()];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = layout.node(i);
        }
        return rows;
    }

    @Test
    void depthsSizesAndLastChildren() {
        PlanLayout layout = new PlanLayout(TREE);
        assertEquals(0, layout.depth(0));
        assertEquals(3, layout.depth(4));
        assertEquals(7, layout.size(0));
        assertEquals(4, layout.size(1));
        assertEquals(2, layout.size(3));
        assertEquals(1, layout.size(2));
        assertTrue(layout.hasChildren(3));
        assertFalse(layout.hasChildren(2));
        assertFalse(layout.isLast(1), "1 has a later sibling, 5");
        assertFalse(layout.isLast(2));
        assertTrue(layout.isLast(3));
        assertTrue(layout.isLast(5));
        assertTrue(layout.isLast(0));
    }

    @Test
    void everyNodeIsARowWhenNothingIsCollapsed() {
        PlanLayout layout = new PlanLayout(TREE);
        layout.layout(IntSet.of());
        assertEquals(7, layout.rowCount());
        assertEquals(4, layout.node(4));
    }

    @Test
    void collapsingHidesExactlyTheSubTree() {
        PlanLayout layout = new PlanLayout(TREE);
        IntOpenHashSet collapsed = new IntOpenHashSet();
        collapsed.add(1);
        layout.layout(collapsed);
        assertEquals(java.util.Arrays.toString(new int[]{0, 1, 5, 6}), java.util.Arrays.toString(rows(layout)));
        collapsed.add(0);
        layout.layout(collapsed);
        assertEquals(1, layout.rowCount(), "a collapsed root hides everything below it");
        collapsed.clear();
        collapsed.add(3);
        layout.layout(collapsed);
        assertEquals(java.util.Arrays.toString(new int[]{0, 1, 2, 3, 5, 6}), java.util.Arrays.toString(rows(layout)));
    }

    @Test
    void guideLinesRunOnlyPastAncestorsWithLaterSiblings() {
        PlanLayout layout = new PlanLayout(TREE);
        layout.layout(IntSet.of());
        // Row 4 is node 4 (depth 3): its ancestor 1 (depth 1) has the later sibling 5, its ancestor 3 (depth 2) none.
        assertTrue(layout.guide(4, 1));
        assertFalse(layout.guide(4, 2));
        // Row 6 is node 6 under 5, the last child of the root: no guide at depth 1.
        assertFalse(layout.guide(6, 1));
        // Row 2 is node 2 under 1: depth 1 runs on.
        assertTrue(layout.guide(2, 1));
        assertFalse(layout.guide(0, 0), "the root has no guides");
    }

    @Test
    void aSingleNodeAndAChain() {
        PlanLayout single = new PlanLayout(new int[]{-1});
        single.layout(IntSet.of());
        assertEquals(1, single.rowCount());
        assertTrue(single.isLast(0));
        int depth = 100;
        int[] chain = new int[depth];
        for (int i = 0; i < depth; i++) {
            chain[i] = i - 1;
        }
        PlanLayout deep = new PlanLayout(chain);
        deep.layout(IntSet.of());
        assertEquals(depth, deep.rowCount());
        assertEquals(depth - 1, deep.depth(depth - 1));
        assertFalse(deep.guide(depth - 1, 70), "no guide beyond the deepest tracked level");
    }

    @Test
    void durations() {
        assertEquals("1s", PlanLayout.duration(0));
        assertEquals("1s", PlanLayout.duration(20));
        assertEquals("2s", PlanLayout.duration(21));
        assertEquals("59s", PlanLayout.duration(59 * 20));
        assertEquals("1m 0s", PlanLayout.duration(60 * 20));
        assertEquals("3m 20s", PlanLayout.duration(200 * 20));
        assertEquals("2h 5m", PlanLayout.duration((2 * 3600 + 5 * 60 + 7) * 20L));
    }

    @Test
    void refusesAChildBeforeItsParent() {
        assertThrows(IllegalArgumentException.class, () -> new PlanLayout(new int[]{-1, 2, 0}));
    }
}
