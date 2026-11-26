package dev.silencebound.maxtech.lib.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A screen's own scale, independent of the player's GUI scale: one layout unit is a whole number of window pixels,
 * picked so a layout of at least {@code designWidth} by {@code designHeight} units fits the window, and never fewer
 * than {@code minPixels} pixels per unit so text stays readable (the node editor, like Steve's Factory Manager). The
 * screen pushes the scale on {@code pose()} once and then draws in units; mouse positions arrive in GUI coordinates and
 * go through {@link #toUnits}. Because a unit is whole pixels, fills and text land on pixel edges at any GUI scale.
 *
 * <p>
 * Immutable; make a new one when the window resizes (a screen's {@code init}). The math is static and pure so it can be
 * tested without a window. Client only for {@link #push}; the rest is plain arithmetic.
 */
public final class PixelScale {
    private final int pixels;
    private final int guiScale;
    private final int width;
    private final int height;

    private PixelScale(int pixels, int guiScale, int width, int height) {
        this.pixels = pixels;
        this.guiScale = guiScale;
        this.width = width;
        this.height = height;
    }

    /**
     * The largest whole number of window pixels per unit at which {@code designWidth} by {@code designHeight} units fit
     * a window of {@code windowWidth} by {@code windowHeight} pixels, but at least {@code minPixels} (and at least 1).
     */
    public static int pixelsPerUnit(int windowWidth, int windowHeight, int designWidth, int designHeight,
            int minPixels) {
        int fit = Math.min(windowWidth / Math.max(1, designWidth), windowHeight / Math.max(1, designHeight));
        return Math.max(Math.max(1, minPixels), fit);
    }

    /**
     * The scale for a window of {@code windowWidth} by {@code windowHeight} pixels at GUI scale {@code guiScale}
     * (window pixels per GUI pixel, at least 1).
     */
    public static PixelScale of(int windowWidth, int windowHeight, int guiScale, int designWidth, int designHeight,
            int minPixels) {
        int pixels = pixelsPerUnit(windowWidth, windowHeight, designWidth, designHeight, minPixels);
        return new PixelScale(pixels, Math.max(1, guiScale), windowWidth / pixels, windowHeight / pixels);
    }

    /** Window pixels per unit. */
    public int pixels() {
        return pixels;
    }

    /** The layout's width in units: the whole window, at least the design width unless {@code minPixels} won. */
    public int width() {
        return width;
    }

    /** The layout's height in units. */
    public int height() {
        return height;
    }

    /** The factor to push on {@code pose()}: GUI pixels per unit. */
    public float poseScale() {
        return (float) pixels / guiScale;
    }

    /** A GUI coordinate (as mouse events carry) in units. */
    public double toUnits(double gui) {
        return gui * guiScale / pixels;
    }

    /** A length in units as GUI pixels, for tooltips and other things drawn outside the scale; may be fractional. */
    public double toGui(double units) {
        return units * pixels / guiScale;
    }

    /** Pushes the scale; every draw call until {@link #pop} is in units. */
    public void push(GuiGraphicsExtractor graphics) {
        graphics.pose().pushMatrix();
        graphics.pose().scale(poseScale());
    }

    public void pop(GuiGraphicsExtractor graphics) {
        graphics.pose().popMatrix();
    }
}
