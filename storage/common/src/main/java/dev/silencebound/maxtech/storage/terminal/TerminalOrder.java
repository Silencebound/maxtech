package dev.silencebound.maxtech.storage.terminal;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.Arrays;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/**
 * The order of the terminal grid: pinned entries first (in sort order), then recently used ones (latest first), then
 * the rest in sort order. It works on the search's matches only, so a pinned entry the search hides stays hidden.
 * Client thread; runs only when the view, the pins or the recent list changed.
 */
public final class TerminalOrder {
    private TerminalOrder() {
    }

    /**
     * Arranges {@code view} into {@code out}, which is cleared first.
     *
     * @param pinned
     *            whether an id is pinned
     * @param recentRank
     *            an id's place among the recent entries (0 is the latest), or -1 for one that is not recent
     * @param recentSlots
     *            how many recent ranks exist; ranks at or above it count as not recent
     */
    public static void arrange(IntList view, IntPredicate pinned, IntUnaryOperator recentRank, int recentSlots,
            IntArrayList out) {
        out.clear();
        int[] recent = new int[recentSlots];
        Arrays.fill(recent, -1);
        for (int i = 0; i < view.size(); i++) {
            int id = view.getInt(i);
            if (pinned.test(id)) {
                out.add(id);
            } else {
                int rank = recentRank.applyAsInt(id);
                if (rank >= 0 && rank < recentSlots) {
                    recent[rank] = id;
                }
            }
        }
        for (int id : recent) {
            if (id >= 0) {
                out.add(id);
            }
        }
        for (int i = 0; i < view.size(); i++) {
            int id = view.getInt(i);
            int rank = recentRank.applyAsInt(id);
            if (!pinned.test(id) && (rank < 0 || rank >= recentSlots)) {
                out.add(id);
            }
        }
    }
}
