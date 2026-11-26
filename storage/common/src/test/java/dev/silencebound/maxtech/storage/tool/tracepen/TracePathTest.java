package dev.silencebound.maxtech.storage.tool.tracepen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.junit.jupiter.api.Test;

/** The trace pen's path between two nodes of a route tree ({@link TracePath}). */
class TracePathTest {
    /**
     * Two root links (route roots 1 and 2); under 1: 10 -> 11 -> {12, 13}, 13 -> 14; under 2: 20 -> 21.
     */
    private static Long2LongOpenHashMap tree() {
        Long2LongOpenHashMap parents = new Long2LongOpenHashMap();
        parents.defaultReturnValue(TracePath.NO_NODE);
        parents.put(10, 1);
        parents.put(11, 10);
        parents.put(12, 11);
        parents.put(13, 11);
        parents.put(14, 13);
        parents.put(20, 2);
        parents.put(21, 20);
        return parents;
    }

    @Test
    void siblingsMeetAtTheirParent() {
        TracePath path = TracePath.between(12, 14, tree()::get);
        assertEquals(11, path.meet());
        assertFalse(path.throughRoot());
        assertEquals(LongArrayList.of(12), path.up());
        assertEquals(LongArrayList.of(14, 13), path.down());
        assertEquals(3, path.segments());
    }

    @Test
    void aNodeAndItsAncestorMeetAtTheAncestor() {
        TracePath path = TracePath.between(14, 10, tree()::get);
        assertEquals(10, path.meet());
        assertEquals(LongArrayList.of(14, 13, 11), path.up());
        assertTrue(path.down().isEmpty());
        assertEquals(3, path.segments());
    }

    @Test
    void theSameNodeIsAnEmptyPath() {
        TracePath path = TracePath.between(13, 13, tree()::get);
        assertEquals(13, path.meet());
        assertEquals(0, path.segments());
    }

    @Test
    void differentRootLinksMeetInsideTheController() {
        TracePath path = TracePath.between(12, 21, tree()::get);
        assertTrue(path.throughRoot());
        assertEquals(LongArrayList.of(12, 11, 10, 1), path.up());
        assertEquals(LongArrayList.of(21, 20, 2), path.down());
        // The route roots 1 and 2 lead nowhere; the cluster joins them.
        assertEquals(5, path.segments());
    }

    @Test
    void aBrokenTreeStopsAtTheBound() {
        Long2LongOpenHashMap loop = new Long2LongOpenHashMap();
        loop.put(5, 6);
        loop.put(6, 5);
        TracePath path = TracePath.between(5, 7, loop::get);
        assertEquals(TracePath.MAX_NODES, path.up().size());
    }
}
