package dev.silencebound.maxtech.lib.client.gui.accent;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * A screen's Accent Frame panel as a layout: where it is, its title (and an optional second part after it, such as a
 * device's label, drawn in {@link Accent#LABEL}) and where content goes inside it. It draws with {@link Accent#panel},
 * so the title tab sticks out {@link Accent#TAB_HEIGHT} minus one above {@link #y}; place the panel with
 * {@link Accent#top} to keep the tab on the screen. A title too wide for the tab ends with an ellipsis and shows whole
 * as a tooltip.
 *
 * <p>
 * Immutable; make a new one when the screen is laid out ({@code init}), so drawing allocates nothing. Client only for
 * drawing; the content edges are plain arithmetic.
 */
public final class AccentPanel {
    /** How far content stays inside the panel's left, right and bottom edges. */
    public static final int INSET = 6;
    /** Where content starts below the panel's top edge: under the accent line and a gap. */
    public static final int CONTENT_TOP = 7;
    /** What separates the title from its second part. */
    private static final String SEPARATOR = "  ";

    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final Component title;

    private AccentPanel(int x, int y, int width, int height, Component title) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.title = title;
    }

    /** A panel at ({@code x}, {@code y}), {@code width} by {@code height}, titled {@code title}. */
    public static AccentPanel of(int x, int y, int width, int height, Component title) {
        return new AccentPanel(x, y, width, height, title);
    }

    /** {@link #of(int, int, int, int, Component)} with {@code subtitle} after the title; empty leaves it out. */
    public static AccentPanel of(int x, int y, int width, int height, Component title, String subtitle) {
        Component shown = subtitle.isEmpty()
                ? title
                : title.copy().append(Component.literal(SEPARATOR + subtitle).withColor(Accent.LABEL));
        return new AccentPanel(x, y, width, height, shown);
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int right() {
        return x + width;
    }

    public int bottom() {
        return y + height;
    }

    public int contentLeft() {
        return x + INSET;
    }

    public int contentRight() {
        return x + width - INSET;
    }

    public int contentTop() {
        return y + CONTENT_TOP;
    }

    public int contentBottom() {
        return y + height - INSET;
    }

    public int contentWidth() {
        return Math.max(0, contentRight() - contentLeft());
    }

    public int contentHeight() {
        return Math.max(0, contentBottom() - contentTop());
    }

    /** Draws the panel and its title tab; a shortened title shows whole while the mouse is on it. */
    public void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        Accent.panel(graphics, font, x, y, width, height, title, mouseX, mouseY);
    }
}
