package dev.silencebound.maxtech.lib.api.client.gui;

import org.jetbrains.annotations.ApiStatus;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A tag: a small word in a box filled with a state colour and dark text ("RUNNING" in green, "SIMULATE" in cyan). It is
 * as wide as its text ({@link #width}) unless the caller gives a width. A tag only shows a state; a word the player
 * switches on and off is an {@link AccentButton} lit while on. Client only for drawing; the size math is plain
 * arithmetic.
 */
@ApiStatus.Experimental
public final class AccentTag {
    /** Room between the edge and the text, left and right. */
    public static final int PAD = 3;

    private AccentTag() {
    }

    /** A tag's height for {@code font}: edge, one pixel above the text, the text. */
    public static int height(Font font) {
        return Accent.textHeight(font) + 3;
    }

    /** The width of a tag for text {@code textWidth} wide. */
    public static int width(int textWidth) {
        return textWidth + 2 * PAD + 2;
    }

    /** Draws a tag as wide as its text and returns its width. */
    public static int draw(GuiGraphicsExtractor graphics, Font font, int x, int y, String text, int colour) {
        int width = width(TextScale.width(font, text));
        draw(graphics, font, x, y, width, text, colour);
        return width;
    }

    /** Draws a tag {@code width} wide, its text centred. */
    public static void draw(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, String text,
            int colour) {
        int height = height(font);
        graphics.fill(x, y, x + width, y + height, Accent.OUTLINE);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, colour);
        TextScale.text(graphics, font, text, x + (width - TextScale.width(font, text) + 1) / 2, y + 2, Accent.OUTLINE,
                false);
    }
}
