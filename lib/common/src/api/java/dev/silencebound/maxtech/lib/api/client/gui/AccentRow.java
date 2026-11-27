package dev.silencebound.maxtech.lib.api.client.gui;

import dev.silencebound.maxtech.lib.api.style.AccentTheme;
import dev.silencebound.maxtech.lib.api.text.TextFit;
import org.jetbrains.annotations.ApiStatus;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * One row of a list in an {@link AccentInset}: a selected row is lit like a pressed button ({@link #select}: a dim
 * accent fill with an accent strip on its left), a hovered row gets a faint fill ({@link #hover}), a dimmed row
 * (paused, disabled) grey text, and an optional status dot sits on the right. Text that does not fit ends with an
 * ellipsis and shows whole as a tooltip while the mouse is on the row. Client only for drawing; the layout math is
 * plain arithmetic.
 *
 * <p>
 * Tooltips are placed at the mouse position as given, so a screen drawing under its own scale passes GUI coordinates or
 * draws rows unscaled.
 */
@ApiStatus.Experimental
public final class AccentRow {
    /** The selected row's strip on the left. */
    public static final int STRIP = 2;
    /** Where the text starts, from the row's left edge: after the strip and a gap. */
    public static final int TEXT_X = STRIP + 4;
    /** Room right of the text, and on either side of the status dot. */
    public static final int MARGIN = 3;

    private AccentRow() {
    }

    /** A row's height for {@code font}. */
    public static int height(Font font) {
        return Accent.textHeight(font) + 4;
    }

    /** How wide the text may be in a row {@code width} wide, with or without a status dot. */
    public static int textRoom(int width, boolean dot) {
        int right = dot ? MARGIN + AccentStatus.SIZE + MARGIN : MARGIN;
        return Math.max(0, width - TEXT_X - right);
    }

    /**
     * Draws a row at ({@code x}, {@code y}), {@code width} wide, and returns whether the mouse is on it.
     *
     * @param dot
     *            the status dot's state, or null for none
     */
    public static boolean draw(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, String text,
            boolean selected, boolean dimmed, @Nullable AccentStatus dot, int mouseX, int mouseY) {
        return draw(graphics, font, x, y, width, text, selected, dimmed, dot, mouseX, mouseY, AccentTheme.LIB);
    }

    /** {@code draw} in {@code theme}'s colours. */
    public static boolean draw(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, String text,
            boolean selected, boolean dimmed, @Nullable AccentStatus dot, int mouseX, int mouseY, AccentTheme theme) {
        int height = height(font);
        boolean hovered = Accent.contains(x, y, width, height, mouseX, mouseY);
        if (selected) {
            select(graphics, x, y, width, height, theme);
        } else if (hovered) {
            hover(graphics, x, y, width, height);
        }
        int room = textRoom(width, dot != null);
        TextScale.text(graphics, font, TextFit.fit(text, room, font::width), x + TEXT_X, y + 2,
                dimmed ? Accent.DIM : theme.text(), false);
        if (dot != null) {
            AccentStatus.dot(graphics, x + width - MARGIN - AccentStatus.SIZE, y + (height - AccentStatus.SIZE) / 2,
                    dot.colour());
        }
        if (hovered && TextFit.cut(text, room, font::width)) {
            graphics.setTooltipForNextFrame(font, Component.literal(text), mouseX, mouseY);
        }
        return hovered;
    }

    /** The selected look over the rectangle: a dim accent fill with the accent strip on its left. */
    public static void select(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        select(graphics, x, y, width, height, AccentTheme.LIB);
    }

    /** {@link #select(GuiGraphicsExtractor, int, int, int, int)} in {@code theme}'s accent colours. */
    public static void select(GuiGraphicsExtractor graphics, int x, int y, int width, int height, AccentTheme theme) {
        graphics.fill(x, y, x + width, y + height, theme.accentDim());
        graphics.fill(x, y, x + STRIP, y + height, theme.accent());
    }

    /** The hovered look over the rectangle: a faint light fill. */
    public static void hover(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, Accent.HOVER_FILL);
    }
}
