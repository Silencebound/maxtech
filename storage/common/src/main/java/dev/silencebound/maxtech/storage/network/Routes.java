package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.network.RouteTree;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * A network's routes: Lib's widest-path {@link RouteTree} from the last full rebuild, plus the nodes routed since then
 * without one (M6d, per-zone rebuilds). A cable that is removed, or a link a route used that is cut, detaches only the
 * nodes whose route ran through it ({@link #reroute}); they get new routes over the rest of the tree, which keeps its
 * own, and nothing else is touched. Nodes attached after the build ({@link #attach}) are kept here too, so the tree
 * itself never changes after it was built.
 *
 * <p>
 * Lookups cost one or two hash lookups and allocate nothing. Server thread only.
 */
final class Routes {
    private static final byte UNKNOWN = 0;
    private static final byte KEPT = 1;
    private static final byte DETACHED = 2;

    private final RouteTree base;
    /** Nodes of {@link #base} that no longer route through it: removed, or rerouted (then also in the maps below). */
    private final LongOpenHashSet dropped = new LongOpenHashSet();
    private final Long2LongOpenHashMap parents = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap widths = new Long2LongOpenHashMap();
    private final Long2IntOpenHashMap depths = new Long2IntOpenHashMap();
    private final LongArrayList scratch = new LongArrayList(6);

    Routes(RouteTree base) {
        this.base = base;
    }

    long root() {
        return base.root();
    }

    boolean contains(long pos) {
        return parents.containsKey(pos) || (base.contains(pos) && !dropped.contains(pos));
    }

    /** The next node towards the root; the root's parent is itself. Only for nodes this {@link #contains}. */
    long parent(long pos) {
        return parents.containsKey(pos) ? parents.get(pos) : base.parent(pos);
    }

    int depth(long pos) {
        return depths.containsKey(pos) ? depths.get(pos) : base.depth(pos);
    }

    long width(long pos) {
        return widths.containsKey(pos) ? widths.get(pos) : base.width(pos);
    }

    /** Nodes routed outside the built tree; a full rebuild is due when they grow too many. */
    int patched() {
        return parents.size() + dropped.size();
    }

    /**
     * Hangs {@code pos}, which joined after the build, off its neighbour in the routes that gives it the widest route
     * (then the shortest). Returns false when it is routed already or no routed neighbour links to it.
     */
    boolean attach(long pos, RouteTree.Links links, RouteTree.EdgeWidth width) {
        if (contains(pos)) {
            return false;
        }
        scratch.clear();
        links.links(pos, scratch);
        long best = 0;
        long bestWidth = 0;
        int bestDepth = Integer.MAX_VALUE;
        boolean found = false;
        for (int i = 0; i < scratch.size(); i++) {
            long neighbour = scratch.getLong(i);
            if (!contains(neighbour)) {
                continue;
            }
            long edge = width.width(neighbour, pos);
            if (edge <= 0) {
                continue;
            }
            long w = Math.min(width(neighbour), edge);
            int d = depth(neighbour);
            if (!found || w > bestWidth
                    || (w == bestWidth && (d < bestDepth || (d == bestDepth && neighbour < best)))) {
                found = true;
                best = neighbour;
                bestWidth = w;
                bestDepth = d;
            }
        }
        if (!found) {
            return false;
        }
        put(pos, best, bestWidth, bestDepth + 1);
        return true;
    }

    private void put(long pos, long parent, long width, int depth) {
        if (base.contains(pos)) {
            dropped.add(pos);
        }
        parents.put(pos, parent);
        widths.put(pos, width);
        depths.put(pos, depth);
    }

    private void drop(long pos) {
        parents.remove(pos);
        widths.remove(pos);
        depths.remove(pos);
        if (base.contains(pos)) {
            dropped.add(pos);
        }
    }

    /**
     * Finds the nodes of {@code nodes} whose route runs through a {@code removed} node or starts with a {@code cut}
     * node's own link to its parent, and adds them to {@code out}, at most {@code limit} of them; returns false when
     * there are more. One walk per node with memo, so linear in the network's size; only on topology changes.
     */
    boolean detached(LongSet nodes, LongSet removed, LongSet cut, int limit, LongArrayList out) {
        Long2ByteOpenHashMap state = new Long2ByteOpenHashMap(nodes.size());
        LongArrayList path = new LongArrayList();
        long root = root();
        LongIterator it = nodes.iterator();
        while (it.hasNext()) {
            long start = it.nextLong();
            if (!contains(start) || state.get(start) != UNKNOWN) {
                continue;
            }
            path.clear();
            long node = start;
            byte found = KEPT;
            while (true) {
                byte known = state.get(node);
                if (known != UNKNOWN) {
                    found = known;
                    break;
                }
                path.add(node);
                if (cut.contains(node)) {
                    found = DETACHED;
                    break;
                }
                if (node == root) {
                    break;
                }
                long parent = parent(node);
                if (removed.contains(parent)) {
                    found = DETACHED;
                    break;
                }
                node = parent;
            }
            for (int i = 0; i < path.size(); i++) {
                long pos = path.getLong(i);
                state.put(pos, found);
                if (found == DETACHED) {
                    out.add(pos);
                    if (out.size() > limit) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Gives {@code detached} (from {@link #detached}) new routes: a widest-path search over them alone, starting from
     * their links to nodes that keep their route. {@code removed} nodes leave the routes. Nodes that reach no kept node
     * stay unrouted. Appends the rerouted nodes to {@code settled}, parents before children. Cost O(d log d) for d
     * detached nodes; the rest of the routes is not walked.
     */
    void reroute(LongSet removed, LongArrayList detached, RouteTree.Links links, RouteTree.EdgeWidth width,
            LongArrayList settled) {
        LongIterator gone = removed.iterator();
        while (gone.hasNext()) {
            drop(gone.nextLong());
        }
        LongOpenHashSet open = new LongOpenHashSet(detached);
        for (int i = 0; i < detached.size(); i++) {
            drop(detached.getLong(i));
        }
        // Lazy heap over (width desc, depth asc, position asc), as in Lib's RouteTree builder.
        Long2LongOpenHashMap labelWidth = new Long2LongOpenHashMap();
        Long2IntOpenHashMap labelDepth = new Long2IntOpenHashMap();
        Long2LongOpenHashMap labelParent = new Long2LongOpenHashMap();
        Heap heap = new Heap();
        for (int i = 0; i < detached.size(); i++) {
            long pos = detached.getLong(i);
            scratch.clear();
            links.links(pos, scratch);
            for (int j = 0; j < scratch.size(); j++) {
                long neighbour = scratch.getLong(j);
                if (!open.contains(neighbour) && contains(neighbour)) {
                    offer(pos, neighbour, width, labelWidth, labelDepth, labelParent, heap);
                }
            }
        }
        while (heap.size > 0) {
            long pos = heap.pos[0];
            long w = heap.width[0];
            int d = heap.depth[0];
            heap.pop();
            if (!open.contains(pos) || labelWidth.get(pos) != w || labelDepth.get(pos) != d) {
                continue;
            }
            open.remove(pos);
            put(pos, labelParent.get(pos), w, d);
            settled.add(pos);
            scratch.clear();
            links.links(pos, scratch);
            for (int j = 0; j < scratch.size(); j++) {
                long next = scratch.getLong(j);
                if (open.contains(next)) {
                    offer(next, pos, width, labelWidth, labelDepth, labelParent, heap);
                }
            }
        }
    }

    /** Offers {@code pos} a route through its routed neighbour {@code via}. */
    private void offer(long pos, long via, RouteTree.EdgeWidth width, Long2LongOpenHashMap labelWidth,
            Long2IntOpenHashMap labelDepth, Long2LongOpenHashMap labelParent, Heap heap) {
        long edge = width.width(via, pos);
        if (edge <= 0) {
            return;
        }
        long w = Math.min(width(via), edge);
        int d = depth(via) + 1;
        if (labelWidth.containsKey(pos)) {
            long known = labelWidth.get(pos);
            int knownDepth = labelDepth.get(pos);
            if (w < known || (w == known && d > knownDepth)) {
                return;
            }
            if (w == known && d == knownDepth) {
                // As in Lib's RouteTree: the lower parent position wins a tie, and the heap entry stays valid.
                if (via < labelParent.get(pos)) {
                    labelParent.put(pos, via);
                }
                return;
            }
        }
        labelWidth.put(pos, w);
        labelDepth.put(pos, d);
        labelParent.put(pos, via);
        heap.push(pos, w, d);
    }

    /** A binary heap of (position, width, depth), widest, then shallowest, then lowest position first. */
    private static final class Heap {
        long[] pos = new long[16];
        long[] width = new long[16];
        int[] depth = new int[16];
        int size;

        void push(long p, long w, int d) {
            if (size == pos.length) {
                pos = java.util.Arrays.copyOf(pos, size * 2);
                width = java.util.Arrays.copyOf(width, size * 2);
                depth = java.util.Arrays.copyOf(depth, size * 2);
            }
            int i = size++;
            set(i, p, w, d);
            while (i > 0) {
                int parent = (i - 1) >>> 1;
                if (!before(i, parent)) {
                    break;
                }
                swap(i, parent);
                i = parent;
            }
        }

        void pop() {
            int last = --size;
            if (last == 0) {
                return;
            }
            set(0, pos[last], width[last], depth[last]);
            int i = 0;
            while (true) {
                int left = 2 * i + 1;
                if (left >= size) {
                    return;
                }
                int right = left + 1;
                int child = right < size && before(right, left) ? right : left;
                if (!before(child, i)) {
                    return;
                }
                swap(i, child);
                i = child;
            }
        }

        private boolean before(int a, int b) {
            if (width[a] != width[b]) {
                return width[a] > width[b];
            }
            return depth[a] != depth[b] ? depth[a] < depth[b] : pos[a] < pos[b];
        }

        private void set(int i, long p, long w, int d) {
            pos[i] = p;
            width[i] = w;
            depth[i] = d;
        }

        private void swap(int a, int b) {
            long p = pos[a];
            long w = width[a];
            int d = depth[a];
            set(a, pos[b], width[b], depth[b]);
            set(b, p, w, d);
        }
    }
}
