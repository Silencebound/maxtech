package dev.silencebound.maxtech.lib.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Right-angled wires between two points, drawn with plain fills (the GUI has no line primitive). A wire leaves its
 * start to the right and enters its end from the left, as from an output port to an input port. Going forward it runs
 * across, down (or up) at the middle and across again: four points. When the end is left of the start (a wire looping
 * back) it steps out by {@code stub} units, runs across at the height between both points and steps in again: six
 * points.
 *
 * <p>
 * Routes are written into a caller's {@code int[]} of at least {@link #MAX_POINTS} * 2 entries ({@code x, y} pairs), so
 * drawing a graph per frame allocates nothing. Pure apart from {@link #draw}; client only for drawing.
 */
public final class RightAngleWire {
    /** Most points a route has. */
    public static final int MAX_POINTS = 6;

    private RightAngleWire() {
    }

    /**
     * Writes the route from ({@code x1}, {@code y1}) to ({@code x2}, {@code y2}) into {@code out} as {@code x, y} pairs
     * and returns the number of points (4 or 6).
     */
    public static int route(int x1, int y1, int x2, int y2, int stub, int[] out) {
        if (x2 - x1 >= 2 * stub) {
            int mid = (x1 + x2) / 2;
            set(out, 0, x1, y1);
            set(out, 1, mid, y1);
            set(out, 2, mid, y2);
            set(out, 3, x2, y2);
            return 4;
        }
        int midY = (y1 + y2) / 2;
        set(out, 0, x1, y1);
        set(out, 1, x1 + stub, y1);
        set(out, 2, x1 + stub, midY);
        set(out, 3, x2 - stub, midY);
        set(out, 4, x2 - stub, y2);
        set(out, 5, x2, y2);
        return 6;
    }

    private static void set(int[] out, int point, int x, int y) {
        out[point * 2] = x;
        out[point * 2 + 1] = y;
    }

    /**
     * Whether ({@code x}, {@code y}) lies within {@code tolerance} units of the route's {@code count} points in
     * {@code points}, measured along the axis across each segment.
     */
    public static boolean near(int[] points, int count, double x, double y, double tolerance) {
        for (int i = 0; i + 1 < count; i++) {
            int ax = points[i * 2];
            int ay = points[i * 2 + 1];
            int bx = points[i * 2 + 2];
            int by = points[i * 2 + 3];
            double minX = Math.min(ax, bx) - tolerance;
            double maxX = Math.max(ax, bx) + tolerance;
            double minY = Math.min(ay, by) - tolerance;
            double maxY = Math.max(ay, by) + tolerance;
            if (x >= minX && x <= maxX && y >= minY && y <= maxY) {
                return true;
            }
        }
        return false;
    }

    /**
     * The point halfway along the route's longest segment, where a value label sits; written to {@code out[0]} and
     * {@code out[1]}.
     */
    public static void labelPoint(int[] points, int count, int[] out) {
        int best = 0;
        int bestLength = -1;
        for (int i = 0; i + 1 < count; i++) {
            int length = Math.abs(points[i * 2 + 2] - points[i * 2]) + Math.abs(points[i * 2 + 3] - points[i * 2 + 1]);
            if (length > bestLength) {
                bestLength = length;
                best = i;
            }
        }
        out[0] = (points[best * 2] + points[best * 2 + 2]) / 2;
        out[1] = (points[best * 2 + 1] + points[best * 2 + 3]) / 2;
    }

    /**
     * Draws the route as {@code thickness}-unit fills in {@code argb}; each segment reaches half a thickness past its
     * ends so corners close.
     */
    public static void draw(GuiGraphicsExtractor graphics, int[] points, int count, int thickness, int argb) {
        int before = thickness / 2;
        int after = thickness - before;
        for (int i = 0; i + 1 < count; i++) {
            int ax = points[i * 2];
            int ay = points[i * 2 + 1];
            int bx = points[i * 2 + 2];
            int by = points[i * 2 + 3];
            if (ax == bx && ay == by) {
                continue;
            }
            graphics.fill(Math.min(ax, bx) - before, Math.min(ay, by) - before, Math.max(ax, bx) + after,
                    Math.max(ay, by) + after, argb);
        }
    }
}
