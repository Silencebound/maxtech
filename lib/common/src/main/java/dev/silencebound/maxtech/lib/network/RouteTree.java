package dev.silencebound.maxtech.lib.network;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.Arrays;

/**
 * A widest-path tree over one connected component: every node gets one fixed route to the root, along the path whose
 * narrowest edge is as wide as possible, and among those the one with the fewest hops (PRD 7.2, zones with derived
 * routes). Remaining ties go to the lowest parent position, so the tree depends only on the graph and the widths, never
 * on link order or hash order. Edge widths come from the caller (cable channels, say); the tree is topology only and
 * moves nothing. The caller counts channels along the routes when the topology changes, and nothing pathfinds at
 * runtime.
 *
 * <p>
 * Build it when the component is rebuilt ({@link NetworkLogic#onRebuilt}, with {@link NetworkGraph} as the
 * {@link Links}). A node added later is {@linkplain #attach attached} to its best neighbour already in the tree without
 * a rebuild; that keeps every route valid, though a new edge that would widen other routes only counts after the next
 * rebuild. Loops therefore give no extra width. Removing a node means a rebuild.
 *
 * <p>
 * Building costs O(E log E) for E edges. Server thread only; lookups allocate nothing.
 */
public final class RouteTree {
    /** The links of a node; {@link NetworkGraph} is one. */
    @FunctionalInterface
    public interface Links {
        /** Appends the positions linked to {@code pos}; appends nothing for an unknown position. */
        void links(long pos, LongList out);
    }

    /** How wide the edge between two linked nodes is; 0 or less means it carries nothing and is not used. */
    @FunctionalInterface
    public interface EdgeWidth {
        long width(long from, long to);
    }

    /** Width of the root's own (empty) route. */
    public static final long ROOT_WIDTH = Long.MAX_VALUE;

    private final long root;
    private final Long2IntOpenHashMap index;
    private long[] positions;
    private int[] parents;
    private long[] widths;
    private int[] depths;
    private int size;
    private final LongArrayList scratch = new LongArrayList();

    private RouteTree(long root, int expected) {
        // Headroom so the first attached nodes do not copy every array.
        int capacity = Math.max(16, expected + (expected >> 3));
        this.root = root;
        this.index = new Long2IntOpenHashMap(capacity);
        index.defaultReturnValue(-1);
        this.positions = new long[capacity];
        this.parents = new int[capacity];
        this.widths = new long[capacity];
        this.depths = new int[capacity];
    }

    /**
     * Builds the tree of everything reachable from {@code root} over edges of positive width. {@code expectedSize} only
     * sizes the arrays (the component's node count).
     */
    public static RouteTree build(long root, Links links, EdgeWidth width, int expectedSize) {
        RouteTree tree = new RouteTree(root, expectedSize);
        tree.add(root, -1, ROOT_WIDTH, 0);
        new Builder(tree, links, width, expectedSize).run();
        return tree;
    }

    /**
     * Attaches {@code pos}, which joined after the build, to the neighbour in the tree that gives it the widest route
     * (then the shortest, then the lowest position). Returns false when it is already in the tree or no neighbour in it
     * links to it over a positive width; then nothing changes.
     */
    public boolean attach(long pos, Links links, EdgeWidth width) {
        if (index.containsKey(pos)) {
            return false;
        }
        scratch.clear();
        links.links(pos, scratch);
        int best = -1;
        long bestWidth = 0;
        for (int i = 0; i < scratch.size(); i++) {
            long neighbour = scratch.getLong(i);
            int n = index.get(neighbour);
            if (n < 0) {
                continue;
            }
            long edge = width.width(neighbour, pos);
            if (edge <= 0) {
                continue;
            }
            long w = Math.min(widths[n], edge);
            if (best < 0 || w > bestWidth || (w == bestWidth
                    && (depths[n] < depths[best] || (depths[n] == depths[best] && neighbour < positions[best])))) {
                best = n;
                bestWidth = w;
            }
        }
        if (best < 0) {
            return false;
        }
        add(pos, best, bestWidth, depths[best] + 1);
        return true;
    }

    public long root() {
        return root;
    }

    /** Nodes in the tree, the root included. */
    public int size() {
        return size;
    }

    public boolean contains(long pos) {
        return index.containsKey(pos);
    }

    /**
     * The next node from {@code pos} towards the root; the root's parent is itself. Throws
     * {@link IllegalArgumentException} for a position not in the tree.
     */
    public long parent(long pos) {
        int i = indexOf(pos);
        int parent = parents[i];
        return parent < 0 ? root : positions[parent];
    }

    /** The narrowest edge on the route from {@code pos} to the root; {@link #ROOT_WIDTH} for the root. */
    public long width(long pos) {
        return widths[indexOf(pos)];
    }

    /** Edges from {@code pos} to the root; 0 for the root. */
    public int depth(long pos) {
        return depths[indexOf(pos)];
    }

    /** Appends the route from {@code pos} to the root, both included, and returns its length. */
    public int route(long pos, LongList out) {
        int i = indexOf(pos);
        int count = 0;
        while (i >= 0) {
            out.add(positions[i]);
            count++;
            i = parents[i];
        }
        return count;
    }

    private int indexOf(long pos) {
        int i = index.get(pos);
        if (i < 0) {
            throw new IllegalArgumentException("Position not in the route tree: " + pos);
        }
        return i;
    }

    private int add(long pos, int parent, long width, int depth) {
        if (size == positions.length) {
            int length = size * 2;
            positions = Arrays.copyOf(positions, length);
            parents = Arrays.copyOf(parents, length);
            widths = Arrays.copyOf(widths, length);
            depths = Arrays.copyOf(depths, length);
        }
        int i = size++;
        positions[i] = pos;
        parents[i] = parent;
        widths[i] = width;
        depths[i] = depth;
        index.put(pos, i);
        return i;
    }

    /**
     * Dijkstra on (width descending, depth ascending, position ascending) with a lazy binary heap: a node can be pushed
     * again with a better label and stale heap entries are skipped when popped. Among equal offers a candidate keeps
     * the lowest parent position. Candidates are labelled in a side map until settled, then moved into the tree in
     * settle order, so parents always precede children.
     */
    private static final class Builder {
        private final RouteTree tree;
        private final Links links;
        private final EdgeWidth width;
        private final LongArrayList scratch = new LongArrayList(6);
        /** Best label so far per candidate position (the root and settled nodes too): its index in the label arrays. */
        private final Long2IntOpenHashMap candidates;
        private long[] candPos;
        private long[] candWidth;
        private int[] candDepth;
        private int[] candParent;
        private boolean[] settled;
        private int candidateCount;
        private long[] heapWidth;
        private int[] heapDepth;
        private int[] heapCand;
        private int heapSize;

        Builder(RouteTree tree, Links links, EdgeWidth width, int expected) {
            this.tree = tree;
            this.links = links;
            this.width = width;
            int capacity = Math.max(16, expected);
            this.candidates = new Long2IntOpenHashMap(capacity);
            candidates.defaultReturnValue(-1);
            candPos = new long[capacity];
            candWidth = new long[capacity];
            candDepth = new int[capacity];
            candParent = new int[capacity];
            settled = new boolean[capacity];
            heapWidth = new long[capacity];
            heapDepth = new int[capacity];
            heapCand = new int[capacity];
        }

        void run() {
            // The root is settled from the start, so one candidate lookup per edge also skips tree nodes.
            settled[newCandidate(tree.root)] = true;
            relax(0);
            while (heapSize > 0) {
                int cand = heapCand[0];
                long w = heapWidth[0];
                int d = heapDepth[0];
                pop();
                if (settled[cand] || w != candWidth[cand] || d != candDepth[cand]) {
                    continue;
                }
                settled[cand] = true;
                int node = tree.add(candPos[cand], candParent[cand], w, d);
                relax(node);
            }
        }

        /** Offers the neighbours of tree node {@code node} a route through it. */
        private void relax(int node) {
            long pos = tree.positions[node];
            long nodeWidth = tree.widths[node];
            int nodeDepth = tree.depths[node];
            scratch.clear();
            links.links(pos, scratch);
            for (int i = 0; i < scratch.size(); i++) {
                long next = scratch.getLong(i);
                int cand = candidates.get(next);
                if (cand >= 0 && settled[cand]) {
                    continue;
                }
                long edge = width.width(pos, next);
                if (edge <= 0) {
                    continue;
                }
                long w = Math.min(nodeWidth, edge);
                int d = nodeDepth + 1;
                if (cand < 0) {
                    cand = newCandidate(next);
                } else if (w < candWidth[cand] || (w == candWidth[cand] && d > candDepth[cand])) {
                    continue;
                } else if (w == candWidth[cand] && d == candDepth[cand]) {
                    // The same label through a lower position wins the tie; the heap entry stays valid.
                    if (pos < tree.positions[candParent[cand]]) {
                        candParent[cand] = node;
                    }
                    continue;
                }
                candWidth[cand] = w;
                candDepth[cand] = d;
                candParent[cand] = node;
                push(w, d, cand);
            }
        }

        private int newCandidate(long pos) {
            if (candidateCount == candPos.length) {
                int length = candidateCount * 2;
                candPos = Arrays.copyOf(candPos, length);
                candWidth = Arrays.copyOf(candWidth, length);
                candDepth = Arrays.copyOf(candDepth, length);
                candParent = Arrays.copyOf(candParent, length);
                settled = Arrays.copyOf(settled, length);
            }
            int cand = candidateCount++;
            candPos[cand] = pos;
            candidates.put(pos, cand);
            return cand;
        }

        /** Whether entry {@code a} comes out before {@code b}: wider first, then shallower, then the lower position. */
        private boolean before(int a, int b) {
            if (heapWidth[a] != heapWidth[b]) {
                return heapWidth[a] > heapWidth[b];
            }
            if (heapDepth[a] != heapDepth[b]) {
                return heapDepth[a] < heapDepth[b];
            }
            return candPos[heapCand[a]] < candPos[heapCand[b]];
        }

        private void push(long w, int d, int cand) {
            if (heapSize == heapWidth.length) {
                int length = heapSize * 2;
                heapWidth = Arrays.copyOf(heapWidth, length);
                heapDepth = Arrays.copyOf(heapDepth, length);
                heapCand = Arrays.copyOf(heapCand, length);
            }
            int i = heapSize++;
            heapWidth[i] = w;
            heapDepth[i] = d;
            heapCand[i] = cand;
            while (i > 0) {
                int parent = (i - 1) >>> 1;
                if (!before(i, parent)) {
                    break;
                }
                swap(i, parent);
                i = parent;
            }
        }

        private void pop() {
            int last = --heapSize;
            if (last == 0) {
                return;
            }
            heapWidth[0] = heapWidth[last];
            heapDepth[0] = heapDepth[last];
            heapCand[0] = heapCand[last];
            int i = 0;
            while (true) {
                int left = 2 * i + 1;
                if (left >= heapSize) {
                    break;
                }
                int right = left + 1;
                int child = right < heapSize && before(right, left) ? right : left;
                if (!before(child, i)) {
                    break;
                }
                swap(i, child);
                i = child;
            }
        }

        private void swap(int a, int b) {
            long w = heapWidth[a];
            heapWidth[a] = heapWidth[b];
            heapWidth[b] = w;
            int d = heapDepth[a];
            heapDepth[a] = heapDepth[b];
            heapDepth[b] = d;
            int c = heapCand[a];
            heapCand[a] = heapCand[b];
            heapCand[b] = c;
        }
    }
}
