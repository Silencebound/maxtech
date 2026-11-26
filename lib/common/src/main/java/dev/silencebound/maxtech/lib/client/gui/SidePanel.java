package dev.silencebound.maxtech.lib.client.gui;

import dev.silencebound.maxtech.lib.client.gui.accent.ScrollBar;
import dev.silencebound.maxtech.lib.text.TextFit;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * A side panel with a title and a vertically scrolling body, for palettes and settings beside a canvas. The screen lays
 * the body out in content coordinates (0 at the top of the body) and asks {@link #screenY} where a row lands; between
 * {@link #begin} and {@link #end} drawing is clipped to the body. A thin Accent scrollbar shows when the content is
 * taller than the body; the mouse wheel scrolls by {@code step} units.
 *
 * <p>
 * Colours come from a {@link Style}, so the panel fits any screen's look. Mutable, one per panel; client thread only.
 * The layout math ({@link #setBounds}, {@link #setContentHeight}, {@link #screenY}, {@link #scrollBy}) is plain
 * arithmetic.
 */
public final class SidePanel {
    /** Height of the title row; the body starts below it. */
    public static final int TITLE_HEIGHT = 12;
    /** Width of the scrollbar inside the right border. */
    public static final int SCROLLBAR_WIDTH = 2;

    /**
     * The panel's colours (ARGB).
     *
     * @param background
     *            the panel's fill
     * @param border
     *            its one-unit outline
     * @param title
     *            the title text
     */
    public record Style(int background, int border, int title) {
    }

    private int x;
    private int y;
    private int width;
    private int height;
    private int contentHeight;
    private int scroll;

    /** Places the panel, border included; keeps the scroll position, clamped. */
    public void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = Math.max(0, width);
        this.height = Math.max(0, height);
        scroll = Math.clamp(scroll, 0, maxScroll());
    }

    /** How tall the body's content is; clamps the scroll position. */
    public void setContentHeight(int contentHeight) {
        this.contentHeight = Math.max(0, contentHeight);
        scroll = Math.clamp(scroll, 0, maxScroll());
    }

    /** The body's height: below the title and inside the border. */
    public int bodyHeight() {
        return Math.max(0, height - TITLE_HEIGHT - 1);
    }

    /** The body's top edge on screen. */
    public int bodyTop() {
        return y + TITLE_HEIGHT;
    }

    /** The body's left edge on screen, inside the border. */
    public int bodyLeft() {
        return x + 2;
    }

    /** The body's usable width, leaving room for the scrollbar. */
    public int bodyWidth() {
        return Math.max(0, width - 4 - SCROLLBAR_WIDTH);
    }

    public int maxScroll() {
        return Math.max(0, contentHeight - bodyHeight());
    }

    public int scroll() {
        return scroll;
    }

    /** Scrolls by {@code units}, positive down; clamped. */
    public void scrollBy(int units) {
        scroll = Math.clamp((long) scroll + units, 0, maxScroll());
    }

    /** Where content y {@code contentY} is on screen. */
    public int screenY(int contentY) {
        return bodyTop() + contentY - scroll;
    }

    /** The content y under screen y {@code screenY}. */
    public double contentY(double screenY) {
        return screenY - bodyTop() + scroll;
    }

    /** Whether a row at content y {@code contentY}, {@code rowHeight} tall, is at least partly in the body. */
    public boolean shows(int contentY, int rowHeight) {
        int top = contentY - scroll;
        return top + rowHeight > 0 && top < bodyHeight();
    }

    /** Whether the screen point is on the panel. */
    public boolean contains(double px, double py) {
        return px >= x && py >= y && px < x + width && py < y + height;
    }

    /** Whether the screen point is in the body (below the title). */
    public boolean bodyContains(double px, double py) {
        return px >= x && px < x + width && py >= bodyTop() && py < bodyTop() + bodyHeight();
    }

    /** Wheel input: scrolls when the mouse is on the panel and returns whether it did. */
    public boolean mouseScrolled(double px, double py, double scrollY, int step) {
        if (!contains(px, py) || scrollY == 0 || maxScroll() == 0) {
            return false;
        }
        scrollBy(scrollY > 0 ? -step : step);
        return true;
    }

    /** Draws the frame and title and clips to the body; pair with {@link #end}. */
    public void begin(GuiGraphicsExtractor graphics, Font font, Component title, Style style) {
        graphics.fill(x, y, x + width, y + height, style.border());
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, style.background());
        TextScale.text(graphics, font, TextFit.fit(title.getString(), Math.max(0, width - 6), font::width), x + 3,
                y + 2, style.title(), false);
        graphics.enableScissor(x + 1, bodyTop(), x + width - 1, bodyTop() + bodyHeight());
    }

    /** Ends the clip and draws the scrollbar (the Accent {@link ScrollBar}'s look) when the content does not fit. */
    public void end(GuiGraphicsExtractor graphics) {
        graphics.disableScissor();
        if (maxScroll() == 0) {
            return;
        }
        int right = x + width - 1;
        ScrollBar.draw(graphics, right - SCROLLBAR_WIDTH, bodyTop(), SCROLLBAR_WIDTH, bodyHeight(), contentHeight,
                bodyHeight(), scroll, false);
    }
}
