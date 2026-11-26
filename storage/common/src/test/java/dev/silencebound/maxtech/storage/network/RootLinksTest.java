package dev.silencebound.maxtech.storage.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.network.RouteTree;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/**
 * A controller network's tree from the virtual root, built as {@link NetworkState} builds it: a core at x = 0 and a
 * channel array at x = 1 touching it, a cable line west of the core (x = -1 to -5) and one east of the array (x = 2 to
 * 6), and a device bridging their ends one row over, so each line could also be reached through the other.
 */
class RootLinksTest {
    private static final long SUPER = NetworkState.SUPER_ROOT;
    private static final long CORE = pos(0);
    private static final long ARRAY = pos(1);

    private final LongOpenHashSet nodes = new LongOpenHashSet();
    private final LongOpenHashSet cluster = new LongOpenHashSet();
    /** Base width per node pair is the slower node's; 32 unless set. */
    private final Long2LongOpenHashMap nodeWidth = new Long2LongOpenHashMap();

    private static long pos(int x) {
        return BlockPos.asLong(x, 0, 0);
    }

    private static long pos(int x, int z) {
        return BlockPos.asLong(x, 0, z);
    }

    private void build() {
        cluster.add(CORE);
        cluster.add(ARRAY);
        nodes.addAll(cluster);
        for (int x = -5; x <= 6; x++) {
            nodes.add(pos(x));
        }
        // A loop: a line one row over joins both ends.
        for (int x = -5; x <= 6; x++) {
            nodes.add(pos(x, 1));
        }
        nodeWidth.defaultReturnValue(32);
    }

    private void links(long pos, it.unimi.dsi.fastutil.longs.LongList out) {
        for (Direction side : Direction.values()) {
            long next = BlockPos.offset(pos, side);
            if (nodes.contains(next)) {
                out.add(next);
            }
        }
    }

    private long base(long a, long b) {
        return Math.min(nodeWidth.get(a), nodeWidth.get(b));
    }

    private RouteTree tree(RootLinks rootLinks, int allowed) {
        // Candidates as NetworkState offers them: every link from a cluster block that carries root links outward.
        LongArrayList exits = LongArrayList.of(CORE, ARRAY);
        LongArrayList scratch = new LongArrayList();
        for (int i = 0; i < exits.size(); i++) {
            long node = exits.getLong(i);
            scratch.clear();
            links(node, scratch);
            for (int j = 0; j < scratch.size(); j++) {
                long next = scratch.getLong(j);
                if (!cluster.contains(next)) {
                    rootLinks.offer(node, next, base(node, next));
                }
            }
        }
        rootLinks.enable(allowed);
        return RouteTree.build(SUPER, (pos, out) -> {
            if (pos == SUPER) {
                out.addAll(exits);
            } else {
                links(pos, out);
            }
        }, (from, to) -> rootLinks.width(from, to, SUPER, cluster.contains(from), cluster.contains(to), base(from, to)),
                nodes.size() + 1);
    }

    /** The cluster block a node's route leaves through, or the node itself for a cluster block. */
    private static long exitOf(RouteTree tree, long pos) {
        long node = pos;
        while (tree.parent(node) != SUPER) {
            node = tree.parent(node);
        }
        return node;
    }

    @Test
    void routesUseEveryRootLinkAndNeverCrossTheCluster() {
        build();
        RootLinks rootLinks = new RootLinks();
        RouteTree tree = tree(rootLinks, 4);
        // Core: west, north (z = -1 has nothing) ... only west and south (z = 1) carry nodes; array: east and south.
        assertEquals(4, rootLinks.used());
        assertEquals(nodes.size() + 1, tree.size(), "every node reached");
        assertEquals(CORE, exitOf(tree, pos(-5)), "west line through the core");
        assertEquals(ARRAY, exitOf(tree, pos(6)), "east line through the array");
        assertEquals(SUPER, tree.parent(CORE));
        assertEquals(SUPER, tree.parent(ARRAY));
        assertFalse(tree.parent(ARRAY) == CORE || tree.parent(CORE) == ARRAY, "no route between cluster blocks");
    }

    @Test
    void linksOverTheCountCarryNoRoutes() {
        build();
        RootLinks rootLinks = new RootLinks();
        RouteTree tree = tree(rootLinks, 1);
        assertEquals(1, rootLinks.used());
        // The lowest cluster block and side win among equal widths: the core (x = 0 < 1), its lowest side first.
        assertTrue(rootLinks.allows(pos(0, 1), CORE) || rootLinks.allows(pos(-1), CORE));
        assertEquals(1, countAllowed(rootLinks));
        // Everything is still reached through the loop, all over the one link.
        assertEquals(nodes.size() + 1, tree.size());
        assertEquals(CORE, exitOf(tree, pos(6)));
        assertEquals(CORE, exitOf(tree, pos(6, 1)));
    }

    @Test
    void widerLinksWin() {
        build();
        // The east line is tier 2.
        for (int x = 2; x <= 6; x++) {
            nodeWidth.put(pos(x), 128);
        }
        nodeWidth.put(ARRAY, 128);
        RootLinks rootLinks = new RootLinks();
        tree(rootLinks, 1);
        assertTrue(rootLinks.allows(pos(2), ARRAY), "the tier-2 link is enabled first");
        assertEquals(1, countAllowed(rootLinks));
    }

    @Test
    void noLinksMeansOnlyTheClusterIsReached() {
        build();
        RootLinks rootLinks = new RootLinks();
        RouteTree tree = tree(rootLinks, 0);
        assertEquals(0, rootLinks.used());
        assertEquals(3, tree.size(), "the virtual root and the two cluster blocks");
    }

    private int countAllowed(RootLinks rootLinks) {
        int allowed = 0;
        var it = rootLinks.outsideNodes();
        while (it.hasNext()) {
            long outside = it.nextLong();
            if (rootLinks.allows(outside, CORE)) {
                allowed++;
            }
            if (rootLinks.allows(outside, ARRAY)) {
                allowed++;
            }
        }
        return allowed;
    }
}
