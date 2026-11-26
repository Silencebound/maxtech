package dev.silencebound.maxtech.storage.logic.admin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Sorting, filtering and paging of the admin overview's rows, shared by the command and the screen. Pure; runs only
 * when an operator asks.
 */
public final class AdminList {
    /** Rows per chat page. */
    public static final int PAGE_SIZE = 8;

    /** How the overview sorts its rows; every order ends on the costliest first, then the place. */
    public enum Sort {
        /** Checks per second, then the last run's cost: the costliest first. */
        COST,
        /** Action points per second, then the last run's points, then by checks: the costliest actions first. */
        ACTIONS,
        /** Rules with the most errors first, then by cost. */
        ERRORS,
        /** By owner name, then by cost. */
        OWNER;

        /** The command's word for it. */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final Comparator<AdminRow> COST = Comparator.comparingDouble(AdminRow::checksPerSecond).reversed()
            .thenComparing(Comparator.comparingInt(AdminRow::lastCost).reversed());
    private static final Comparator<AdminRow> ACTIONS = Comparator.comparingDouble(AdminRow::actionPointsPerSecond)
            .reversed().thenComparing(Comparator.comparingInt(AdminRow::lastPoints).reversed());
    private static final Comparator<AdminRow> PLACE = Comparator.comparing(AdminRow::dimension)
            .thenComparingInt(row -> row.pos().getX()).thenComparingInt(row -> row.pos().getY())
            .thenComparingInt(row -> row.pos().getZ()).thenComparingInt(AdminRow::graph);
    private static final Comparator<AdminRow> OWNER = Comparator
            .comparing((AdminRow row) -> row.owner().toLowerCase(Locale.ROOT));

    private AdminList() {
    }

    /** The comparator for {@code sort}. */
    public static Comparator<AdminRow> order(Sort sort) {
        return switch (sort) {
            case COST -> COST.thenComparing(PLACE);
            case ACTIONS -> ACTIONS.thenComparing(COST).thenComparing(PLACE);
            case ERRORS ->
                Comparator.comparingInt(AdminRow::errors).reversed().thenComparing(COST).thenComparing(PLACE);
            case OWNER -> OWNER.thenComparing(COST).thenComparing(PLACE);
        };
    }

    /** A new list of {@code rows} in {@code sort}'s order. */
    public static List<AdminRow> sorted(Collection<AdminRow> rows, Sort sort) {
        List<AdminRow> out = new ArrayList<>(rows);
        out.sort(order(sort));
        return out;
    }

    /**
     * The rows in dimension {@code dimension} whose owner name contains {@code owner} (ignoring case); an empty filter
     * keeps every row. Keeps the order.
     */
    public static List<AdminRow> filter(List<AdminRow> rows, String dimension, String owner) {
        String wanted = owner.toLowerCase(Locale.ROOT);
        List<AdminRow> out = new ArrayList<>(rows.size());
        for (AdminRow row : rows) {
            if ((dimension.isEmpty() || row.dimension().equals(dimension))
                    && (wanted.isEmpty() || row.owner().toLowerCase(Locale.ROOT).contains(wanted))) {
                out.add(row);
            }
        }
        return out;
    }

    /** How many pages of {@code size} rows {@code count} rows fill; at least 1. */
    public static int pages(int count, int size) {
        return Math.max(1, (count + size - 1) / size);
    }

    /** Page {@code page} (from 1, clamped to the pages there are) of {@code rows}, {@code size} rows each. */
    public static List<AdminRow> page(List<AdminRow> rows, int page, int size) {
        int shown = Math.clamp(page, 1, pages(rows.size(), size));
        int from = (shown - 1) * size;
        return rows.subList(Math.min(from, rows.size()), Math.min(from + size, rows.size()));
    }
}
