package dev.silencebound.maxtech.lib.neoforge.compat.jade;

import dev.silencebound.maxtech.lib.info.InfoEntry;
import java.util.ArrayList;
import java.util.List;

/**
 * The one place that decides how a block's info entries are arranged in the overlay: what goes on the title line next
 * to the block's name, and which rows follow. Jade-free, so the arrangement is testable without a client. The owner
 * picked variant B on the mockup page (2026-10-17): the status dot next to the name, then one row with the bars and
 * icons; with details every further entry gets its own row.
 */
public final class OverlayLayout {
    /** The compact arrangements on the owner's mockup page; {@link #DEFAULT} is used. */
    public enum Variant {
        /** A: the name alone, then one row with the status dot, the bars and the icons. */
        ICON_ROW,
        /** B: the status dot next to the name, then one row with the bars and the icons. */
        TWO_ROWS,
        /** C: the status dot next to the name, then the first bar only (or the first entry without a bar). */
        MINIMAL_BAR
    }

    /** The compact arrangement in use: B, the owner's pick. */
    public static final Variant DEFAULT = Variant.TWO_ROWS;

    /**
     * An arrangement.
     *
     * @param title
     *            entries appended to the overlay's title line (the block's name); statuses only
     * @param rows
     *            the rows below it, none empty, each drawn left to right
     */
    public record Layout(List<InfoEntry> title, List<List<InfoEntry>> rows) {
    }

    private OverlayLayout() {
    }

    /** {@code entries} arranged as {@link #DEFAULT}, or with {@code details} one row per entry. */
    public static Layout arrange(List<InfoEntry> entries, boolean details) {
        return arrange(entries, details, DEFAULT);
    }

    /**
     * {@code entries} arranged: statuses on the title line (variant A: at the start of the row instead), the rest in
     * one row, or with {@code details} in one row each.
     */
    public static Layout arrange(List<InfoEntry> entries, boolean details, Variant variant) {
        List<InfoEntry> statuses = new ArrayList<>();
        List<InfoEntry> rest = new ArrayList<>();
        for (InfoEntry entry : entries) {
            (entry.kind() == InfoEntry.Kind.STATUS ? statuses : rest).add(entry);
        }
        List<List<InfoEntry>> rows = new ArrayList<>();
        if (details) {
            for (InfoEntry entry : rest) {
                rows.add(List.of(entry));
            }
            return new Layout(statuses, rows);
        }
        switch (variant) {
            case ICON_ROW -> {
                List<InfoEntry> row = new ArrayList<>(statuses);
                row.addAll(rest);
                if (!row.isEmpty()) {
                    rows.add(row);
                }
                return new Layout(List.of(), rows);
            }
            case TWO_ROWS -> {
                if (!rest.isEmpty()) {
                    rows.add(rest);
                }
            }
            case MINIMAL_BAR -> {
                InfoEntry first = null;
                for (InfoEntry entry : rest) {
                    if (entry.kind() == InfoEntry.Kind.PROGRESS) {
                        first = entry;
                        break;
                    }
                }
                if (first == null && !rest.isEmpty()) {
                    first = rest.getFirst();
                }
                if (first != null) {
                    rows.add(List.of(first));
                }
            }
        }
        return new Layout(statuses, rows);
    }
}
