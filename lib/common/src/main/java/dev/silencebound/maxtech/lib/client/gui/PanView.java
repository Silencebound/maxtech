package dev.silencebound.maxtech.lib.client.gui;

/**
 * A pannable view onto a larger canvas: where the canvas's origin sits in a viewport, moved by dragging empty space.
 * Canvas and viewport coordinates are both in whole units (a {@link PixelScale}'s units, or GUI pixels); drag deltas
 * may be fractional and are kept until they add up to a whole unit, so slow drags still move. The offset is clamped so
 * content between {@link #setContent} bounds never leaves the viewport entirely: at least {@code margin} units of it
 * stay visible.
 *
 * <p>
 * Mutable, one per screen; not thread-safe (client thread). Pure arithmetic, no drawing: the screen scissors the
 * viewport and draws content at {@link #screenX}/{@link #screenY}.
 */
public final class PanView {
    private int viewX;
    private int viewY;
    private int viewWidth;
    private int viewHeight;
    private int contentMinX;
    private int contentMinY;
    private int contentMaxX;
    private int contentMaxY;
    private int margin;
    /** Canvas coordinate shown at the viewport's top left corner. */
    private int scrollX;
    private int scrollY;
    private double restX;
    private double restY;

    /** Sets the viewport's rectangle in screen units; keeps the scroll position, clamped. */
    public void setViewport(int x, int y, int width, int height) {
        viewX = x;
        viewY = y;
        viewWidth = Math.max(0, width);
        viewHeight = Math.max(0, height);
        clamp();
    }

    /**
     * Sets the content's bounds in canvas units and how much of it must stay in view ({@code margin}); the scroll
     * position is clamped to them.
     */
    public void setContent(int minX, int minY, int maxX, int maxY, int margin) {
        contentMinX = Math.min(minX, maxX);
        contentMinY = Math.min(minY, maxY);
        contentMaxX = Math.max(minX, maxX);
        contentMaxY = Math.max(minY, maxY);
        this.margin = Math.max(0, margin);
        clamp();
    }

    /** Moves the view so canvas point ({@code x}, {@code y}) is at the viewport's centre. */
    public void centreOn(int x, int y) {
        scrollX = x - viewWidth / 2;
        scrollY = y - viewHeight / 2;
        restX = 0;
        restY = 0;
        clamp();
    }

    /** Drags the canvas by ({@code dx}, {@code dy}) screen units: content follows the mouse. */
    public void drag(double dx, double dy) {
        restX -= dx;
        restY -= dy;
        int wholeX = (int) restX;
        int wholeY = (int) restY;
        restX -= wholeX;
        restY -= wholeY;
        scrollX += wholeX;
        scrollY += wholeY;
        clamp();
    }

    private void clamp() {
        int keepX = Math.min(margin, contentMaxX - contentMinX);
        int keepY = Math.min(margin, contentMaxY - contentMinY);
        // Content's right edge at least keepX into the view, its left edge at most keepX before the view's right.
        int lowX = contentMinX + keepX - viewWidth;
        int highX = contentMaxX - keepX;
        int lowY = contentMinY + keepY - viewHeight;
        int highY = contentMaxY - keepY;
        scrollX = Math.clamp(scrollX, Math.min(lowX, highX), Math.max(lowX, highX));
        scrollY = Math.clamp(scrollY, Math.min(lowY, highY), Math.max(lowY, highY));
    }

    /** The canvas x shown at the viewport's left edge. */
    public int scrollX() {
        return scrollX;
    }

    /** The canvas y shown at the viewport's top edge. */
    public int scrollY() {
        return scrollY;
    }

    /** Where canvas x {@code x} is on screen. */
    public int screenX(int x) {
        return viewX + x - scrollX;
    }

    /** Where canvas y {@code y} is on screen. */
    public int screenY(int y) {
        return viewY + y - scrollY;
    }

    /** The canvas x under screen x {@code x}. */
    public double canvasX(double x) {
        return x - viewX + scrollX;
    }

    /** The canvas y under screen y {@code y}. */
    public double canvasY(double y) {
        return y - viewY + scrollY;
    }

    /** Whether screen point ({@code x}, {@code y}) is inside the viewport. */
    public boolean contains(double x, double y) {
        return x >= viewX && y >= viewY && x < viewX + viewWidth && y < viewY + viewHeight;
    }

    public int viewX() {
        return viewX;
    }

    public int viewY() {
        return viewY;
    }

    public int viewWidth() {
        return viewWidth;
    }

    public int viewHeight() {
        return viewHeight;
    }
}
