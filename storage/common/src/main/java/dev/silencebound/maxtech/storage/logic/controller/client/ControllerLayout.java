package dev.silencebound.maxtech.storage.logic.controller.client;

import dev.silencebound.maxtech.lib.client.gui.accent.SideTabs;

/**
 * The Logic Controller screen's layout arithmetic (M7L2 chunk 10), kept apart from drawing so it can be tested: the
 * frame's size for a window, the rule list's share of the width, buttons flowing into rows, and which list rows show.
 * Plain arithmetic.
 */
public final class ControllerLayout {
    /** The frame's preferred size, as the mockup; it shrinks to fit a small window. */
    public static final int PREFERRED_WIDTH = 460;
    public static final int PREFERRED_HEIGHT = 280;
    /** The smallest frame; the smallest window at an automatic GUI scale is 320 by 240. */
    public static final int MIN_WIDTH = 304;
    public static final int MIN_HEIGHT = 224;
    /** Room kept between the frame and the window's edges when the window is small. */
    public static final int MARGIN = 8;
    /** The rule list's narrowest and widest width. */
    public static final int LIST_MIN = 100;
    public static final int LIST_MAX = 160;
    /** Room the device side tabs take right of the frame: their gap and one tab ({@code SideTabs}). */
    public static final int SIDE_TABS = SideTabs.GAP + SideTabs.TAB;

    private ControllerLayout() {
    }

    /**
     * The frame's width or height in a window {@code window} wide or tall: the preferred size, shrunk to leave a
     * margin, never below the minimum unless the window itself is smaller, and never above {@code cap} (the size the
     * screen was made with).
     */
    public static int fit(int window, int preferred, int min, int cap) {
        int size = Math.min(preferred, window - 2 * MARGIN);
        if (size < min) {
            size = Math.min(min, window);
        }
        return Math.max(0, Math.min(size, cap));
    }

    /**
     * The frame's left edge in a window {@code window} wide for a frame {@code frame} wide with {@code side} pixels of
     * side tabs right of it: the two centred together, never left of the window.
     */
    public static int left(int window, int frame, int side) {
        return Math.max(0, (window - frame - side) / 2);
    }

    /** The rule list's width in content {@code contentWidth} wide: two fifths, within its bounds. */
    public static int listWidth(int contentWidth) {
        return Math.clamp(contentWidth * 2 / 5, LIST_MIN, Math.max(LIST_MIN, Math.min(LIST_MAX, contentWidth / 2)));
    }

    /**
     * Flows buttons {@code widths} wide into rows at most {@code width} wide, {@code gap} apart: writes each button's x
     * offset into {@code xs} and its row into {@code rows}, and returns the number of rows (0 for no buttons). A button
     * wider than the row gets a row of its own.
     */
    public static int flow(int[] widths, int width, int gap, int[] xs, int[] rows) {
        int row = 0;
        int x = 0;
        for (int i = 0; i < widths.length; i++) {
            if (x > 0 && x + widths[i] > width) {
                row++;
                x = 0;
            }
            xs[i] = x;
            rows[i] = row;
            x += widths[i] + gap;
        }
        return widths.length == 0 ? 0 : row + 1;
    }

    /** How many rows {@code rowHeight} tall fit in {@code height}. */
    public static int visibleRows(int height, int rowHeight) {
        return rowHeight <= 0 ? 0 : Math.max(0, height / rowHeight);
    }

    /**
     * The y of the {@code i}th shown row of a list whose rows start at {@code top}, {@code rowHeight} apart. The screen
     * draws rows here and {@link #rowAt} hit-tests the same places.
     */
    public static int rowY(int top, int i, int rowHeight) {
        return top + i * rowHeight;
    }

    /**
     * The list row under ({@code x}, {@code y}), as an index into the whole list, or -1 for none: rows are drawn from
     * {@code left} for {@code width}, starting at {@link #rowY}, {@code visible} of them from row {@code scroll}, and
     * only the first {@code count} rows exist.
     */
    public static int rowAt(double x, double y, int left, int top, int width, int rowHeight, int visible, int scroll,
            int count) {
        if (rowHeight <= 0 || x < left || x >= left + width || y < top) {
            return -1;
        }
        int i = (int) ((y - top) / rowHeight);
        int row = scroll + i;
        return i < visible && row >= 0 && row < count ? row : -1;
    }

    /**
     * The first row to show so {@code selected} is visible, starting from {@code scroll}, with {@code visible} rows.
     */
    public static int scrollTo(int scroll, int selected, int visible, int count) {
        int maxScroll = Math.max(0, count - Math.max(1, visible));
        int first = scroll;
        if (selected >= 0 && selected < first) {
            first = selected;
        } else if (selected >= first + visible && visible > 0) {
            first = selected - visible + 1;
        }
        return Math.clamp(first, 0, maxScroll);
    }
}
