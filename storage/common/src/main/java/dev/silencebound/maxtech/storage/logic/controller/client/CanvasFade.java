package dev.silencebound.maxtech.storage.logic.controller.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A soft fade at the edges of a node canvas (owner decision 2026-10-09): {@link #STEPS} rings of {@link #BAND} pixels
 * in the canvas colour, most opaque at the edge, so a node cut off by the edge reads as going on beyond it. Stepped in
 * whole pixels to fit the pixel look, never a smooth gradient. Drawn over the nodes inside the canvas's scissor, by the
 * logic editor and the controller's NODES diagram. Client thread; draws only.
 */
public final class CanvasFade {
    /** Rings from the edge inward. */
    public static final int STEPS = 4;
    /** Width of one ring in pixels. */
    public static final int BAND = 2;

    private CanvasFade() {
    }

    /** The alpha of ring {@code step} (0 at the edge): 192, 144, 96, 48. */
    public static int alpha(int step) {
        return 0xC0 * (STEPS - step) / STEPS;
    }

    /**
     * Draws the fade inside the box from {@code left}, {@code top} to {@code right}, {@code bottom} in the opaque
     * colour {@code canvas}. Rings do not overlap, so the corners are no darker than the sides. A box too small for the
     * rings gets as many as fit.
     */
    public static void draw(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, int canvas) {
        int rgb = canvas & 0xFFFFFF;
        for (int step = 0; step < STEPS; step++) {
            int in = step * BAND;
            int x0 = left + in;
            int y0 = top + in;
            int x1 = right - in;
            int y1 = bottom - in;
            if (x1 - x0 < 2 * BAND || y1 - y0 < 2 * BAND) {
                return;
            }
            int colour = (alpha(step) << 24) | rgb;
            graphics.fill(x0, y0, x1, y0 + BAND, colour);
            graphics.fill(x0, y1 - BAND, x1, y1, colour);
            graphics.fill(x0, y0 + BAND, x0 + BAND, y1 - BAND, colour);
            graphics.fill(x1 - BAND, y0 + BAND, x1, y1 - BAND, colour);
        }
    }
}
