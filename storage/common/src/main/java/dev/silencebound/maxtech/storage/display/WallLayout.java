package dev.silencebound.maxtech.storage.display;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Cuts a group of panels in one plane into wall screens: rectangles of at most {@code maxWidth} x {@code maxHeight}
 * panels. Cells are {@code (u, v)} plane coordinates (u to the viewer's right, v up). Greedy and deterministic: the
 * lowest row first, left to right; each free cell starts a rectangle as wide as the free cells to its right allow, then
 * as tall as whole rows of that width allow. A full rectangle becomes one screen; an L shape becomes two. Pure logic.
 */
public final class WallLayout {
    /** One screen: its bottom-left cell and its size in panels. */
    public record Rect(int u, int v, int width, int height) {
        public boolean contains(int cu, int cv) {
            return cu >= u && cu < u + width && cv >= v && cv < v + height;
        }
    }

    /** {@link #frame} bits: the screen's frame runs along this panel's left, right, bottom or top edge. */
    public static final int LEFT = 1;
    public static final int RIGHT = 2;
    public static final int BOTTOM = 4;
    public static final int TOP = 8;
    /** A lone panel: framed all round. */
    public static final int ALL = LEFT | RIGHT | BOTTOM | TOP;

    private WallLayout() {
    }

    /**
     * Which edges of the panel at {@code (du, dv)} inside a {@code width} x {@code height} screen (counted from its
     * bottom-left panel) carry the frame: only those on the screen's outer edge, so a wall shows one frame around one
     * screen.
     */
    public static int frame(int du, int dv, int width, int height) {
        int edges = 0;
        if (du == 0) {
            edges |= LEFT;
        }
        if (du == width - 1) {
            edges |= RIGHT;
        }
        if (dv == 0) {
            edges |= BOTTOM;
        }
        if (dv == height - 1) {
            edges |= TOP;
        }
        return edges;
    }

    public static long pack(int u, int v) {
        return ((long) u << 32) | (v & 0xFFFFFFFFL);
    }

    public static int u(long cell) {
        return (int) (cell >> 32);
    }

    public static int v(long cell) {
        return (int) cell;
    }

    public static List<Rect> partition(LongCollection cells, int maxWidth, int maxHeight) {
        LongOpenHashSet free = new LongOpenHashSet(cells);
        long[] order = cells.toLongArray();
        // Row by row (v), then left to right (u).
        long[] keys = new long[order.length];
        int minU = Integer.MAX_VALUE;
        for (long cell : order) {
            minU = Math.min(minU, u(cell));
        }
        for (int i = 0; i < order.length; i++) {
            keys[i] = ((long) v(order[i]) << 32) | ((u(order[i]) - minU) & 0xFFFFFFFFL);
        }
        Arrays.sort(keys);
        List<Rect> rects = new ArrayList<>();
        for (long key : keys) {
            int v = (int) (key >> 32);
            int u = (int) key + minU;
            if (!free.contains(pack(u, v))) {
                continue;
            }
            int width = 1;
            while (width < maxWidth && free.contains(pack(u + width, v))) {
                width++;
            }
            int height = 1;
            while (height < maxHeight && rowFree(free, u, v + height, width)) {
                height++;
            }
            for (int dv = 0; dv < height; dv++) {
                for (int du = 0; du < width; du++) {
                    free.remove(pack(u + du, v + dv));
                }
            }
            rects.add(new Rect(u, v, width, height));
        }
        return rects;
    }

    private static boolean rowFree(LongOpenHashSet free, int u, int v, int width) {
        for (int du = 0; du < width; du++) {
            if (!free.contains(pack(u + du, v))) {
                return false;
            }
        }
        return true;
    }

    /** The cells of {@code rects}, for tests. */
    static LongArrayList cells(List<Rect> rects) {
        LongArrayList out = new LongArrayList();
        for (Rect rect : rects) {
            for (int dv = 0; dv < rect.height(); dv++) {
                for (int du = 0; du < rect.width(); du++) {
                    out.add(pack(rect.u() + du, rect.v() + dv));
                }
            }
        }
        return out;
    }
}
