package dev.silencebound.maxtech.storage.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.network.RouteTree;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/**
 * Per-zone route patches ({@link Routes}): on a 10 x 10 grid rooted in a corner, removing a node or cutting a link
 * reroutes exactly the nodes whose route ran through it, over the rest of the grid, and leaves every other route as it
 * was.
 */
class RoutesTest {
    private static final int SIDE = 10;
    private static final long ROOT = pos(0, 0);

    private final LongOpenHashSet nodes = new LongOpenHashSet();
    /** Links cut on purpose, as pairs {@code a, b} in both orders. */
    private final LongOpenHashSet cutLinks = new LongOpenHashSet();

    private static long pos(int x, int z) {
        return BlockPos.asLong(x, 0, z);
    }

    RoutesTest() {
        for (int x = 0; x < SIDE; x++) {
            for (int z = 0; z < SIDE; z++) {
                nodes.add(pos(x, z));
            }
        }
    }

    private void links(long pos, LongList out) {
        if (!nodes.contains(pos)) {
            return;
        }
        for (Direction side : Direction.values()) {
            long next = BlockPos.offset(pos, side);
            if (nodes.contains(next) && !cutLinks.contains(pos * 31 + next)) {
                out.add(next);
            }
        }
    }

    private static long width(long from, long to) {
        return 32;
    }

    private Routes build() {
        return new Routes(RouteTree.build(ROOT, this::links, RoutesTest::width, nodes.size()));
    }

    /** Every routed node's parent is linked to it and its route reaches the root in {@code depth} steps. */
    private void assertValid(Routes routes) {
        for (long pos : nodes) {
            assertTrue(routes.contains(pos), "not routed: " + BlockPos.of(pos));
            int steps = 0;
            long node = pos;
            while (node != ROOT) {
                long parent = routes.parent(node);
                LongArrayList linked = new LongArrayList();
                links(node, linked);
                assertTrue(linked.contains(parent), "parent not linked at " + BlockPos.of(node));
                assertEquals(routes.depth(node), routes.depth(parent) + 1, "depth at " + BlockPos.of(node));
                node = parent;
                steps++;
            }
            assertEquals(routes.depth(pos), steps, "depth of " + BlockPos.of(pos));
        }
    }

    @Test
    void removingANodeReroutesOnlyItsSubtree() {
        Routes routes = build();
        long removed = pos(1, 0);
        LongOpenHashSet before = new LongOpenHashSet();
        LongArrayList kept = new LongArrayList();
        for (long pos : nodes) {
            long node = pos;
            boolean through = false;
            while (node != ROOT) {
                if (node == removed) {
                    through = true;
                }
                node = routes.parent(node);
            }
            if (through) {
                before.add(pos);
            } else {
                kept.add(pos);
            }
        }
        long[] keptParents = new long[kept.size()];
        for (int i = 0; i < kept.size(); i++) {
            keptParents[i] = routes.parent(kept.getLong(i));
        }
        nodes.remove(removed);
        LongOpenHashSet gone = LongOpenHashSet.of(removed);
        LongArrayList detached = new LongArrayList();
        assertTrue(routes.detached(nodes, gone, new LongOpenHashSet(), nodes.size(), detached));
        before.remove(removed);
        assertEquals(before, new LongOpenHashSet(detached), "detached nodes");
        LongArrayList settled = new LongArrayList();
        routes.reroute(gone, detached, this::links, RoutesTest::width, settled);
        assertEquals(detached.size(), settled.size(), "every detached node rerouted");
        assertFalse(routes.contains(removed), "removed node still routed");
        assertValid(routes);
        for (int i = 0; i < kept.size(); i++) {
            assertEquals(keptParents[i], routes.parent(kept.getLong(i)), "kept route changed");
        }
        // Parents first: each settled node's parent is kept or settled before it.
        LongOpenHashSet seen = new LongOpenHashSet();
        for (int i = 0; i < settled.size(); i++) {
            long node = settled.getLong(i);
            long parent = routes.parent(node);
            assertTrue(!before.contains(parent) || seen.contains(parent), "child before parent");
            seen.add(node);
        }
    }

    @Test
    void cuttingALinkDetachesTheNodeBelowIt() {
        Routes routes = build();
        long child = pos(5, 5);
        long parent = routes.parent(child);
        cutLinks.add(child * 31 + parent);
        cutLinks.add(parent * 31 + child);
        LongArrayList detached = new LongArrayList();
        assertTrue(routes.detached(nodes, new LongOpenHashSet(), LongOpenHashSet.of(child), nodes.size(), detached));
        assertTrue(detached.contains(child), "the cut node is detached");
        assertFalse(detached.contains(parent), "its parent keeps its route");
        routes.reroute(new LongOpenHashSet(), detached, this::links, RoutesTest::width, new LongArrayList());
        assertValid(routes);
        assertTrue(routes.parent(child) != parent, "still routed over the cut link");
    }

    @Test
    void tooManyDetachedNodesGiveUp() {
        Routes routes = build();
        long removed = routes.parent(pos(SIDE - 1, SIDE - 1));
        while (routes.parent(removed) != ROOT) {
            removed = routes.parent(removed);
        }
        nodes.remove(removed);
        LongArrayList detached = new LongArrayList();
        assertFalse(routes.detached(nodes, LongOpenHashSet.of(removed), new LongOpenHashSet(), 3, detached),
                "a limit of 3 must be exceeded by the root's child's subtree");
    }

    @Test
    void attachUsesPatchedRoutes() {
        Routes routes = build();
        long removed = pos(1, 0);
        nodes.remove(removed);
        LongOpenHashSet gone = LongOpenHashSet.of(removed);
        LongArrayList detached = new LongArrayList();
        routes.detached(nodes, gone, new LongOpenHashSet(), nodes.size(), detached);
        routes.reroute(gone, detached, this::links, RoutesTest::width, new LongArrayList());
        long added = pos(SIDE, 0);
        nodes.add(added);
        assertTrue(routes.attach(added, this::links, RoutesTest::width));
        assertEquals(pos(SIDE - 1, 0), routes.parent(added));
        assertEquals(routes.depth(pos(SIDE - 1, 0)) + 1, routes.depth(added));
        assertValid(routes);
    }

    @Test
    void attachTakesTheLowestOfEqualNeighbours() {
        // (1, 1) joins next to (1, 0) and (0, 1), both one step from the root; its links list (1, 0) first.
        long joining = pos(1, 1);
        nodes.remove(joining);
        Routes routes = build();
        nodes.add(joining);
        assertTrue(routes.attach(joining, this::links, RoutesTest::width));
        assertEquals(pos(0, 1), routes.parent(joining));
    }

    @Test
    void rerouteTakesTheLowestOfEqualNeighbours() {
        Routes routes = build();
        long child = pos(1, 1);
        assertEquals(pos(0, 1), routes.parent(child));
        cutLinks.add(child * 31 + pos(0, 1));
        cutLinks.add(pos(0, 1) * 31 + child);
        LongArrayList detached = new LongArrayList();
        assertTrue(routes.detached(nodes, new LongOpenHashSet(), LongOpenHashSet.of(child), nodes.size(), detached));
        assertTrue(detached.contains(pos(2, 1)), "(2, 1) routed through the cut node");
        routes.reroute(new LongOpenHashSet(), detached, this::links, RoutesTest::width, new LongArrayList());
        assertEquals(pos(1, 0), routes.parent(child));
        // (2, 0), offered first, and the rerouted (1, 1) give (2, 1) the same route; the lower position wins.
        assertEquals(child, routes.parent(pos(2, 1)));
        assertValid(routes);
    }
}
