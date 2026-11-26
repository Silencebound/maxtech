package dev.silencebound.maxtech.storage.logic.editor;

/**
 * Fits a node graph into a small read-only view (the controller's NODES diagram): the scale that shows the whole graph,
 * snapped to whole window pixels so text stays crisp and never below a readable minimum; where the graph's origin goes
 * (centred when it fits, panned when it does not); and how far it can pan. Sizes are in editor units (the content) and
 * GUI pixels (the view); a scale is GUI pixels per unit. Pure arithmetic, tested in JUnit.
 */
public final class DiagramFit {
    private static final double EPSILON = 1e-9;

    private DiagramFit() {
    }

    /**
     * The scale that fits {@code contentWidth} by {@code contentHeight} units into {@code viewWidth} by
     * {@code viewHeight} GUI pixels, at most {@code max} and at least {@code min}, rounded down to whole window pixels
     * per unit at GUI scale {@code guiScale} (the minimum rounds up, so it stays readable). Empty content shows at
     * {@code max}.
     */
    public static double scale(int contentWidth, int contentHeight, int viewWidth, int viewHeight, double min,
            double max, int guiScale) {
        int gui = Math.max(1, guiScale);
        double floor = Math.ceil(min * gui - EPSILON) / gui;
        double ceiling = Math.max(floor, Math.floor(max * gui + EPSILON) / gui);
        if (contentWidth <= 0 || contentHeight <= 0) {
            return ceiling;
        }
        double fit = Math.min((double) Math.max(0, viewWidth) / contentWidth,
                (double) Math.max(0, viewHeight) / contentHeight);
        double snapped = Math.floor(fit * gui + EPSILON) / gui;
        return Math.clamp(snapped, floor, ceiling);
    }

    /** Whether content {@code contentSize} units long is longer than a view {@code viewSize} long at {@code scale}. */
    public static boolean pans(int contentSize, int viewSize, double scale) {
        return contentSize * scale > viewSize + EPSILON;
    }

    /** How far the view can pan along one axis, in GUI pixels; 0 when the content fits. */
    public static double maxPan(int contentSize, int viewSize, double scale) {
        return Math.max(0, contentSize * scale - viewSize);
    }

    /** {@code pan} kept within 0 and {@link #maxPan}. */
    public static double clampPan(double pan, int contentSize, int viewSize, double scale) {
        return Math.clamp(pan, 0, maxPan(contentSize, viewSize, scale));
    }

    /**
     * Where canvas coordinate 0 lands on screen along one axis: content from {@code contentMin}, {@code contentSize}
     * units long, in a view starting at {@code viewStart} and {@code viewSize} long. Content that fits is centred;
     * longer content starts at the view's start, moved back by {@code pan} (clamped).
     */
    public static double origin(int viewStart, int viewSize, int contentMin, int contentSize, double scale,
            double pan) {
        double size = contentSize * scale;
        if (size <= viewSize + EPSILON) {
            return viewStart + (viewSize - size) / 2 - contentMin * scale;
        }
        return viewStart - contentMin * scale - clampPan(pan, contentSize, viewSize, scale);
    }

    /** A screen coordinate in canvas units, for an axis whose canvas 0 is at {@code origin}. */
    public static double toCanvas(double screen, double origin, double scale) {
        return (screen - origin) / scale;
    }
}
