package dev.silencebound.maxtech.lib.api.client.gui;

import dev.silencebound.maxtech.lib.api.style.AccentTheme;
import org.jetbrains.annotations.ApiStatus;
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
@ApiStatus.Experimental
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
    private AccentTheme theme = AccentTheme.LIB;

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

    /** Draws the panel in {@code theme}'s colours instead of Lib's cyan. */
    public AccentPanel theme(AccentTheme theme) {
        this.theme = theme;
        return this;
    }

    /** The left edge. */
    public int x() {
        return x;
    }

    /** The top edge (the title tab sticks out above it). */
    public int y() {
        return y;
    }

    /** The width, border included. */
    public int width() {
        return width;
    }

    /** The height, border included. */
    public int height() {
        return height;
    }

    /** The right edge, exclusive. */
    public int right() {
        return x + width;
    }

    /** The bottom edge, exclusive. */
    public int bottom() {
        return y + height;
    }

    /** Where content starts on the left, {@link #INSET} inside the edge. */
    public int contentLeft() {
        return x + INSET;
    }

    /** Where content ends on the right, exclusive. */
    public int contentRight() {
        return x + width - INSET;
    }

    /** Where content starts below the top edge, {@link #CONTENT_TOP} down. */
    public int contentTop() {
        return y + CONTENT_TOP;
    }

    /** Where content ends at the bottom, exclusive. */
    public int contentBottom() {
        return y + height - INSET;
    }

    /** The width content may take; never negative. */
    public int contentWidth() {
        return Math.max(0, contentRight() - contentLeft());
    }

    /** The height content may take; never negative. */
    public int contentHeight() {
        return Math.max(0, contentBottom() - contentTop());
    }

    /** Draws the panel and its title tab; a shortened title shows whole while the mouse is on it. */
    public void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        Accent.panel(graphics, font, x, y, width, height, title, mouseX, mouseY, theme);
    }
}
