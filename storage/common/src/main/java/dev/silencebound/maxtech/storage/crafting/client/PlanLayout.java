package dev.silencebound.maxtech.storage.crafting.client;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.LongArrayList;

/**
 * Lays a plan tree out as rows of an indented list, the way a file tree is drawn: one row per visible node with its
 * depth, whether it has children, and which ancestors' guide lines run past it. The nodes come in the planner's
 * pre-order with parent links ({@code PlanTree}), so a node's sub-tree is the range {@code [i, i + size)} and a
 * collapsed node hides exactly that range. Built once per change of the tree or of the collapsed set, never per frame.
 * Pure logic, no game classes, so JUnit covers it.
 */
public final class PlanLayout {
    /** Deepest level that still gets its own guide line; deeper rows draw none (a bit per level in a long). */
    public static final int MAX_GUIDE_DEPTH = 63;

    private final int[] sizes;
    private final int[] depths;
    private final IntArrayList rows = new IntArrayList();
    private final LongArrayList guides = new LongArrayList();
    private final boolean[] last;

    /**
     * @param parents
     *            each node's parent index, -1 for the root; parents come before their children
     */
    public PlanLayout(int[] parents) {
        int n = parents.length;
        sizes = new int[n];
        depths = new int[n];
        last = new boolean[n];
        for (int i = 0; i < n; i++) {
            int parent = parents[i];
            if (parent >= i) {
                throw new IllegalArgumentException("node " + i + " comes before its parent " + parent);
            }
            depths[i] = parent < 0 ? 0 : depths[parent] + 1;
        }
        for (int i = n - 1; i >= 0; i--) {
            sizes[i]++;
            if (parents[i] >= 0) {
                sizes[parents[i]] += sizes[i];
            }
        }
        for (int i = 0; i < n; i++) {
            int next = i + sizes[i];
            last[i] = next >= n || parents[next] != parents[i];
        }
    }

    /** Lays out the rows with the nodes in {@code collapsed} folded. */
    public void layout(IntSet collapsed) {
        rows.clear();
        guides.clear();
        int n = sizes.length;
        // Bit d set: the ancestor at depth d has a later sibling, so its guide line runs past this row.
        long[] open = new long[MAX_GUIDE_DEPTH + 2];
        int i = 0;
        while (i < n) {
            int depth = depths[i];
            long mask = depth == 0 ? 0 : open[Math.min(depth, MAX_GUIDE_DEPTH + 1) - 1];
            rows.add(i);
            guides.add(mask);
            if (depth <= MAX_GUIDE_DEPTH) {
                open[depth] = last[i] || depth == 0 ? mask : mask | 1L << depth;
            }
            i += collapsed.contains(i) ? sizes[i] : 1;
        }
    }

    /** Visible rows. */
    public int rowCount() {
        return rows.size();
    }

    /** The node shown in {@code row}. */
    public int node(int row) {
        return rows.getInt(row);
    }

    /** The depth of node {@code node}, 0 for the root. */
    public int depth(int node) {
        return depths[node];
    }

    /** Nodes in the sub-tree of {@code node}, itself included. */
    public int size(int node) {
        return sizes[node];
    }

    public boolean hasChildren(int node) {
        return sizes[node] > 1;
    }

    /** Whether {@code node} is the last child of its parent; it then draws an elbow instead of a tee. */
    public boolean isLast(int node) {
        return last[node];
    }

    /** {@code ticks} as a short duration: "45s", "3m 20s", "2h 5m"; under a second shows as "1s". */
    public static String duration(long ticks) {
        long seconds = Math.max(1, (ticks + 19) / 20);
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m " + seconds % 60 + "s";
        }
        return minutes / 60 + "h " + minutes % 60 + "m";
    }

    /** Whether the guide line of the ancestor at {@code depth} runs past {@code row} (1 to {@code depth(node) - 1}). */
    public boolean guide(int row, int depth) {
        return depth >= 1 && depth <= MAX_GUIDE_DEPTH && (guides.getLong(row) & 1L << depth) != 0;
    }
}
