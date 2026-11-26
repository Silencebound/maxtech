package dev.silencebound.maxtech.lib.client.gui;

/** Row scrolling math for {@link VirtualGrid}, kept apart so it can be unit-tested without a screen. */
public final class GridScroll {
    private GridScroll() {
    }

    /** Rows needed for {@code entries} entries in {@code columns} columns. */
    public static int totalRows(int entries, int columns) {
        return (entries + columns - 1) / columns;
    }

    /** The largest first visible row: 0 while everything fits. */
    public static int maxScroll(int entries, int columns, int visibleRows) {
        return Math.max(0, totalRows(entries, columns) - visibleRows);
    }

    /** The scrollbar thumb's height in a track of {@code track} pixels, at least {@code minThumb}. */
    public static int thumbHeight(int track, int entries, int columns, int visibleRows, int minThumb) {
        int total = totalRows(entries, columns);
        if (total <= visibleRows) {
            return track;
        }
        return Math.max(minThumb, track * visibleRows / total);
    }

    /** The thumb's offset from the top of the track for first visible row {@code scroll}. */
    public static int thumbOffset(int scroll, int maxScroll, int track, int thumb) {
        return maxScroll == 0 ? 0 : (track - thumb) * scroll / maxScroll;
    }

    /** The first visible row for a thumb dragged to {@code offset} pixels below the top of the track. */
    public static int scrollForThumb(double offset, int maxScroll, int track, int thumb) {
        if (track <= thumb || maxScroll == 0) {
            return 0;
        }
        int scroll = (int) Math.round(offset * maxScroll / (track - thumb));
        return Math.clamp(scroll, 0, maxScroll);
    }
}
