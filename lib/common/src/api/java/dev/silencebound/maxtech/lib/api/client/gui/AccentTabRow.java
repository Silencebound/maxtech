package dev.silencebound.maxtech.lib.api.client.gui;

import dev.silencebound.maxtech.lib.api.style.AccentTheme;
import org.jetbrains.annotations.ApiStatus;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A row of text tabs in the Accent look, for switching a screen's pages or views: each tab is a compact button face
 * ({@link AccentButton#COMPACT_HEIGHT} high) as wide as its text, the active one lit, the one under the mouse edged,
 * and optionally a count badge (errors on a log tab). The screen asks {@link #tabAt} on a click and calls
 * {@link #setActive}. Side tabs on a device panel are Lib's internal side tabs, not this.
 *
 * <p>
 * Mutable (the active tab); lay a new one out when the screen resizes or a count changes, so drawing allocates nothing.
 * Client only for drawing; the static layout math is plain arithmetic.
 */
@ApiStatus.Experimental
public final class AccentTabRow {
    /** Room between a tab's edge and its text. */
    public static final int PAD = 4;
    /** Room between tabs. */
    public static final int GAP = 2;
    /** Room between a tab's text and its badge. */
    public static final int BADGE_GAP = 3;
    /** Room between a badge's edge and its number. */
    public static final int BADGE_PAD = 2;
    /** The largest count a badge shows; more reads "99+". */
    public static final int BADGE_MAX = 99;

    /**
     * One tab.
     *
     * @param label
     *            the tab's text, already translated
     * @param count
     *            the number on the badge, or 0 or less for no badge
     * @param badge
     *            the badge's fill (ARGB)
     */
    public record Tab(String label, int count, int badge) {
        /** A tab with no badge. */
        public static Tab text(String label) {
            return new Tab(label, 0, Accent.RED);
        }

        /** This tab with a red badge showing {@code count}. */
        public Tab withCount(int count) {
            return new Tab(label, count, badge);
        }

        /** This tab with a badge showing {@code count} in {@code colour}. */
        public Tab withCount(int count, int colour) {
            return new Tab(label, count, colour);
        }
    }

    private final List<Tab> tabs;
    private final int y;
    private final int[] xs;
    private final int[] widths;
    private final String[] badges;
    private int active;
    private AccentTheme theme = AccentTheme.LIB;

    /** Lays out {@code tabs} from ({@code x}, {@code y}) to the right; the first tab is active. */
    public AccentTabRow(Font font, int x, int y, List<Tab> tabs) {
        this.tabs = List.copyOf(tabs);
        this.y = y;
        int size = this.tabs.size();
        this.xs = new int[size];
        this.widths = new int[size];
        this.badges = new String[size];
        for (int i = 0; i < size; i++) {
            Tab tab = this.tabs.get(i);
            badges[i] = badgeText(tab.count());
            widths[i] = tabWidth(TextScale.width(font, tab.label()),
                    badges[i].isEmpty() ? 0 : TextScale.width(font, badges[i]));
        }
        positions(x, widths, xs);
    }

    /** Draws the row in {@code theme}'s colours instead of Lib's cyan. */
    public AccentTabRow theme(AccentTheme theme) {
        this.theme = theme;
        return this;
    }

    /** The colour of a tab's label: the theme's text when active or hovered, the label grey otherwise. */
    static int labelColour(boolean on, boolean over, AccentTheme theme) {
        return on || over ? theme.text() : Accent.LABEL;
    }

    /** A tab's width for text {@code textWidth} wide and a badge number {@code badgeWidth} wide (0: none). */
    public static int tabWidth(int textWidth, int badgeWidth) {
        int width = 2 * PAD + textWidth;
        return badgeWidth <= 0 ? width : width + BADGE_GAP + badgeWidth + 2 * BADGE_PAD;
    }

    /** The badge's text for {@code count}: empty for none, the number, or "99+" past {@link #BADGE_MAX}. */
    public static String badgeText(int count) {
        if (count <= 0) {
            return "";
        }
        return count > BADGE_MAX ? BADGE_MAX + "+" : Integer.toString(count);
    }

    /** Writes each tab's left edge into {@code out}, starting at {@code x}, {@link #GAP} apart; returns the end. */
    public static int positions(int x, int[] widths, int[] out) {
        int at = x;
        for (int i = 0; i < widths.length; i++) {
            out[i] = at;
            at += widths[i] + GAP;
        }
        return widths.length == 0 ? x : at - GAP;
    }

    /** The tab whose column holds {@code px}, or -1; the gaps belong to no tab. */
    public static int indexAt(int[] xs, int[] widths, double px) {
        for (int i = 0; i < xs.length; i++) {
            if (px >= xs[i] && px < xs[i] + widths[i]) {
                return i;
            }
        }
        return -1;
    }

    /** The tab under the point, or -1. */
    public int tabAt(double px, double py) {
        if (py < y || py >= y + height()) {
            return -1;
        }
        return indexAt(xs, widths, px);
    }

    /** The index of the active tab. */
    public int active() {
        return active;
    }

    /** Makes tab {@code active} the active one, kept within the tabs. */
    public void setActive(int active) {
        this.active = Math.clamp(active, 0, Math.max(0, tabs.size() - 1));
    }

    /** The row's total width. */
    public int width() {
        return xs.length == 0 ? 0 : xs[xs.length - 1] + widths[widths.length - 1] - xs[0];
    }

    /** The row's height: a compact button's. */
    public int height() {
        return AccentButton.COMPACT_HEIGHT;
    }

    /** Draws the row. */
    public void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        int hovered = tabAt(mouseX, mouseY);
        int height = height();
        int textY = y + (height - Accent.textHeight(font) + 1) / 2;
        for (int i = 0; i < xs.length; i++) {
            Tab tab = tabs.get(i);
            boolean on = i == active;
            boolean over = i == hovered;
            if (on) {
                Accent.litFace(graphics, xs[i], y, widths[i], height, theme);
            } else {
                Accent.face(graphics, xs[i], y, widths[i], height, over, theme);
            }
            int cx = xs[i] + PAD;
            TextScale.text(graphics, font, tab.label(), cx, textY, labelColour(on, over, theme), false);
            if (!badges[i].isEmpty()) {
                int bx = cx + TextScale.width(font, tab.label()) + BADGE_GAP;
                int bw = TextScale.width(font, badges[i]) + 2 * BADGE_PAD;
                graphics.fill(bx, textY - 1, bx + bw, textY + Accent.textHeight(font), tab.badge());
                TextScale.text(graphics, font, badges[i], bx + BADGE_PAD, textY, Accent.OUTLINE, false);
            }
        }
    }
}
