package dev.silencebound.maxtech.lib.api.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class RouteTreeTest {
    /** An undirected graph of numbered nodes with a width per edge. */
    private static final class Graph implements RouteTree.Links, RouteTree.EdgeWidth {
        final Long2ObjectOpenHashMap<LongArrayList> adjacency = new Long2ObjectOpenHashMap<>();
        final Long2LongOpenHashMap widths = new Long2LongOpenHashMap();

        Graph edge(long a, long b, long width) {
            adjacency.computeIfAbsent(a, k -> new LongArrayList()).add(b);
            adjacency.computeIfAbsent(b, k -> new LongArrayList()).add(a);
            widths.put(key(a, b), width);
            return this;
        }

        private static long key(long a, long b) {
            return Math.min(a, b) * 1_000_003L + Math.max(a, b);
        }

        @Override
        public void links(long pos, LongList out) {
            LongArrayList list = adjacency.get(pos);
            if (list != null) {
                out.addAll(list);
            }
        }

        @Override
        public long width(long from, long to) {
            return widths.get(key(from, to));
        }
    }

    @Test
    void prefersTheWidestPathOverTheShortest() {
        // 0 - 1 - 4 is short but narrow (width 2); 0 - 2 - 3 - 4 is longer and 8 wide.
        Graph graph = new Graph().edge(0, 1, 2).edge(1, 4, 16).edge(0, 2, 8).edge(2, 3, 8).edge(3, 4, 8);
        RouteTree tree = RouteTree.build(0, graph, graph, 5);
        assertEquals(5, tree.size());
        assertEquals(3, tree.parent(4));
        assertEquals(8, tree.width(4));
        assertEquals(3, tree.depth(4));
        assertEquals(0, tree.parent(0), "the root is its own parent");
        assertEquals(RouteTree.ROOT_WIDTH, tree.width(0));
        LongArrayList route = new LongArrayList();
        assertEquals(4, tree.route(4, route));
        assertEquals(LongArrayList.wrap(new long[]{4, 3, 2, 0}), route);
        // Node 1 is reached more widely through 4 (min(8, 16) = 8) than directly (2).
        assertEquals(4, tree.parent(1));
        assertEquals(8, tree.width(1));
    }

    @Test
    void equalWidthsTakeTheFewestHops() {
        Graph graph = new Graph().edge(0, 1, 4).edge(1, 2, 4).edge(2, 3, 4).edge(0, 3, 4);
        RouteTree tree = RouteTree.build(0, graph, graph, 4);
        assertEquals(0, tree.parent(3));
        assertEquals(1, tree.depth(3));
        assertEquals(2, tree.depth(2));

        // On a uniform grid every route is a shortest one: depth is the Manhattan distance to the corner root.
        Graph grid = new Graph();
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                if (x + 1 < 20) {
                    grid.edge(x * 100L + z, (x + 1) * 100L + z, 4);
                }
                if (z + 1 < 20) {
                    grid.edge(x * 100L + z, x * 100L + z + 1, 4);
                }
            }
        }
        RouteTree gridTree = RouteTree.build(0, grid, grid, 400);
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                assertEquals(x + z, gridTree.depth(x * 100L + z), "depth at " + x + ", " + z);
            }
        }
    }

    @Test
    void attachBreaksTiesByDepth() {
        Graph graph = new Graph().edge(0, 1, 4).edge(1, 2, 4).edge(0, 3, 4);
        RouteTree tree = RouteTree.build(0, graph, graph, 4);
        graph.edge(9, 2, 4).edge(9, 3, 4);
        assertTrue(tree.attach(9, graph, graph));
        assertEquals(3, tree.parent(9), "equal widths: the shallower neighbour");
        assertEquals(2, tree.depth(9));
    }

    @Test
    void zeroWidthEdgesAreNotUsed() {
        Graph graph = new Graph().edge(0, 1, 4).edge(1, 2, 0);
        RouteTree tree = RouteTree.build(0, graph, graph, 3);
        assertTrue(tree.contains(1));
        assertFalse(tree.contains(2));
        assertThrows(IllegalArgumentException.class, () -> tree.parent(2));
    }

    @Test
    void attachPicksTheBestNeighbourWithoutRebuilding() {
        Graph graph = new Graph().edge(0, 1, 2).edge(0, 2, 10).edge(2, 3, 10);
        RouteTree tree = RouteTree.build(0, graph, graph, 4);
        graph.edge(5, 1, 100).edge(5, 3, 6);
        assertTrue(tree.attach(5, graph, graph));
        assertEquals(3, tree.parent(5), "through 3 the route is 6 wide, through 1 only 2");
        assertEquals(6, tree.width(5));
        assertEquals(3, tree.depth(5));
        assertFalse(tree.attach(5, graph, graph), "already attached");
        assertFalse(tree.attach(9, graph, graph), "no neighbour in the tree");
        // The new node does not re-route others: 1 keeps its narrow direct route until a rebuild.
        assertEquals(0, tree.parent(1));
        RouteTree rebuilt = RouteTree.build(0, graph, graph, 5);
        assertEquals(5, rebuilt.parent(1), "a rebuild finds the wider route through the new node");
        assertEquals(6, rebuilt.width(1));
    }

    /** The x = 0 column and the z = 9 row. */
    private static boolean highway(long a, long b) {
        boolean row = BlockPos.getZ(a) == 9 && BlockPos.getZ(b) == 9;
        boolean column = BlockPos.getX(a) == 0 && BlockPos.getX(b) == 0;
        return row || column;
    }

    @Test
    void buildsFromANetworkGraph() {
        LongOpenHashSet blocks = new LongOpenHashSet();
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                blocks.add(BlockPos.asLong(x, 0, z));
            }
        }
        NodeConnectivity grid = new NodeConnectivity() {
            @Override
            public void connections(long pos, LongList out) {
                int x = BlockPos.getX(pos);
                int z = BlockPos.getZ(pos);
                offer(out, BlockPos.asLong(x + 1, 0, z));
                offer(out, BlockPos.asLong(x - 1, 0, z));
                offer(out, BlockPos.asLong(x, 0, z + 1));
                offer(out, BlockPos.asLong(x, 0, z - 1));
            }

            private void offer(LongList out, long pos) {
                if (blocks.contains(pos)) {
                    out.add(pos);
                }
            }

            @Override
            public boolean isLoaded(long pos) {
                return true;
            }
        };
        NetworkGraph graph = new NetworkGraph(grid, network -> {
        });
        for (long pos : blocks) {
            graph.add(pos);
        }
        graph.tick(UnloadedChunkMode.TRUNCATE, false, 1_000_000);
        long root = BlockPos.asLong(0, 0, 0);
        Network network = graph.network(root);
        assertTrue(network != null);
        // Row z = 9 is a wide highway; everything else is 1 wide.
        RouteTree tree = graph.routeTree(network, root, (a, b) -> highway(a, b) ? 10 : 1);
        assertEquals(100, tree.size());
        long corner = BlockPos.asLong(9, 0, 9);
        assertEquals(10, tree.width(corner), "the far corner goes up the x = 0 column and along the highway");
        assertEquals(18, tree.depth(corner));
        assertThrows(IllegalArgumentException.class,
                () -> graph.routeTree(network, BlockPos.asLong(50, 0, 50), (a, b) -> 1));
    }

    @Test
    void tiesGoToTheLowestParent() {
        // 3 can route through 1 or 2 at the same width and depth; the edges to 2 come first.
        Graph graph = new Graph().edge(0, 2, 4).edge(2, 3, 4).edge(0, 1, 4).edge(1, 3, 4);
        RouteTree tree = RouteTree.build(0, graph, graph, 4);
        assertEquals(1, tree.parent(3));
        assertTrue(tree.attach(5, (pos, out) -> {
            if (pos == 5) {
                out.add(2);
                out.add(1);
            } else {
                graph.links(pos, out);
            }
        }, (a, b) -> 4));
        assertEquals(1, tree.parent(5), "an attached node takes the lowest of equal neighbours too");
    }

    @Test
    void theTreeIgnoresLinkOrderAndSizing() {
        int side = 40;
        RouteTree.EdgeWidth width = (a, b) -> 1 + (HashCommon.mix(Math.min(a, b) * 31 + Math.max(a, b)) & 3);
        long root = BlockPos.asLong(0, 0, 0);
        RouteTree plain = RouteTree.build(root, (pos, out) -> grid(pos, out, side, side, -1, 0), width, side * side);
        for (int seed = 1; seed <= 5; seed++) {
            int s = seed;
            RouteTree shuffled = RouteTree.build(root, (pos, out) -> grid(pos, out, side, side, -1, s), width, 16);
            assertEquals(plain.size(), shuffled.size());
            for (int x = 0; x < side; x++) {
                for (int z = 0; z < side; z++) {
                    long pos = BlockPos.asLong(x, 0, z);
                    assertEquals(plain.parent(pos), shuffled.parent(pos), "parent at " + x + ", " + z);
                    assertEquals(plain.width(pos), shuffled.width(pos));
                    assertEquals(plain.depth(pos), shuffled.depth(pos));
                }
            }
        }
    }

    @Test
    void aPartJoinedByOneLinkLeavesTheRestAlone() {
        // Two 30 x 30 grids, the second joined to the first by one cable at x 15: the first grid's routes stay as they
        // were, whatever order the joined graph is searched in.
        int side = 30;
        RouteTree.EdgeWidth width = (a, b) -> 1;
        long root = BlockPos.asLong(0, 0, 0);
        RouteTree alone = RouteTree.build(root, (pos, out) -> grid(pos, out, side, side, -1, 0), width, side * side);
        RouteTree joined = RouteTree.build(root, (pos, out) -> {
            int x = BlockPos.getX(pos);
            int z = BlockPos.getZ(pos);
            if (z == side) {
                out.add(BlockPos.asLong(x, 0, side - 1));
                out.add(BlockPos.asLong(x, 0, side + 1));
                return;
            }
            grid(pos, out, side, 2 * side + 1, side, 3);
            if (x == side / 2 && (z == side - 1 || z == side + 1)) {
                out.add(BlockPos.asLong(x, 0, side));
            }
        }, width, 16);
        assertEquals(2 * side * side + 1, joined.size());
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                long pos = BlockPos.asLong(x, 0, z);
                assertEquals(alone.parent(pos), joined.parent(pos), "parent at " + x + ", " + z);
            }
        }
    }

    /**
     * Links of a grid of {@code sideX} x {@code sideZ} nodes, leaving out row {@code gapZ} (a gap the caller may
     * bridge; -1 for none), in an order shuffled by {@code seed} (0 keeps it).
     */
    private static void grid(long pos, LongList out, int sideX, int sideZ, int gapZ, int seed) {
        int x = BlockPos.getX(pos);
        int z = BlockPos.getZ(pos);
        int start = out.size();
        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] step : steps) {
            int nx = x + step[0];
            int nz = z + step[1];
            if (nx >= 0 && nx < sideX && nz >= 0 && nz < sideZ && nz != gapZ) {
                out.add(BlockPos.asLong(nx, 0, nz));
            }
        }
        if (seed != 0) {
            // A fixed shuffle per node and seed, so different seeds search the links in different orders.
            int n = out.size() - start;
            for (int i = n - 1; i > 0; i--) {
                int j = (int) Math.floorMod(HashCommon.mix(pos * 7 + seed * 131 + i), (long) (i + 1));
                long tmp = out.getLong(start + i);
                out.set(start + i, out.getLong(start + j));
                out.set(start + j, tmp);
            }
        }
    }
}
