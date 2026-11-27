package dev.silencebound.maxtech.lib.api.client.gui;

import org.jetbrains.annotations.ApiStatus;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A dark well set into an Accent panel for lists, tables and detail text: a {@link Accent#SLOT_EDGE} edge, a one-pixel
 * {@link Accent#SLOT} margin and the {@link Accent#SLOT} inside, so content sits {@link #BORDER} inside the given
 * rectangle. {@link #begin} draws it and clips drawing to its inside until {@link #end}; {@link #draw} draws it without
 * clipping. Clipping follows the current pose, so it works under a screen's own scale. Client only for drawing; the
 * size math is plain arithmetic.
 */
@ApiStatus.Experimental
public final class AccentInset {
    /** How far content stays inside the given rectangle on every side: the edge and a one-pixel margin. */
    public static final int BORDER = 2;

    private AccentInset() {
    }

    /** Draws the well covering the rectangle, edge included. */
    public static void draw(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, Accent.SLOT_EDGE);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, Accent.SLOT);
    }

    /** Draws the well and clips drawing to its inside; pair with {@link #end}. */
    public static void begin(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        draw(graphics, x, y, width, height);
        graphics.enableScissor(x + BORDER, y + BORDER, x + width - BORDER, y + height - BORDER);
    }

    /** Ends the clip {@link #begin} started. */
    public static void end(GuiGraphicsExtractor graphics) {
        graphics.disableScissor();
    }

    /** The inside's width in a well {@code width} wide. */
    public static int innerWidth(int width) {
        return Math.max(0, width - 2 * BORDER);
    }

    /** The inside's height in a well {@code height} tall. */
    public static int innerHeight(int height) {
        return Math.max(0, height - 2 * BORDER);
    }
}
