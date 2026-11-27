package dev.silencebound.maxtech.lib.api.client.gui;

import dev.silencebound.maxtech.lib.api.style.AccentTheme;
import dev.silencebound.maxtech.lib.api.text.TextFit;
import org.jetbrains.annotations.ApiStatus;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A small readout in the Accent look: a label on the left in {@link Accent#LABEL} and a value right-aligned in
 * {@link Accent#CYAN}, in a flat slot-coloured box. The value always shows whole; the label gets an ellipsis when the
 * two do not fit. Size the readout with {@link #minWidth} from the measured texts. Client only for drawing; the size
 * math is plain arithmetic.
 */
@ApiStatus.Experimental
public final class AccentReadout {
    /** Room between the edge and the text, left and right. */
    public static final int PAD = 3;
    /** Room between label and value. */
    public static final int GAP = 4;

    private AccentReadout() {
    }

    /** The readout's height for {@code font}: edge, one pixel above and below the text. */
    public static int height(Font font) {
        return Accent.textHeight(font) + 4;
    }

    /** The narrowest readout that shows a label {@code labelWidth} wide and a value {@code valueWidth} wide whole. */
    public static int minWidth(int labelWidth, int valueWidth) {
        return 2 + 2 * PAD + labelWidth + GAP + valueWidth;
    }

    /** How much room the label has in a readout {@code width} wide next to a value {@code valueWidth} wide. */
    public static int labelRoom(int width, int valueWidth) {
        return Math.max(0, width - 2 - 2 * PAD - GAP - valueWidth);
    }

    /** Draws a readout at ({@code x}, {@code y}), {@code width} wide, {@link #height} tall. */
    public static void draw(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, String label,
            String value) {
        draw(graphics, font, x, y, width, label, value, AccentTheme.LIB);
    }

    /** {@code draw} with the value in {@code theme}'s heading colour. */
    public static void draw(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, String label,
            String value, AccentTheme theme) {
        int height = height(font);
        graphics.fill(x, y, x + width, y + height, Accent.SLOT_EDGE);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, Accent.SLOT);
        int valueWidth = TextScale.width(font, value);
        TextScale.text(graphics, font, value, x + width - 1 - PAD - valueWidth, y + 2, theme.heading(), false);
        TextScale.text(graphics, font, TextFit.fit(label, labelRoom(width, valueWidth), font::width), x + 1 + PAD,
                y + 2, Accent.LABEL, false);
    }
}
