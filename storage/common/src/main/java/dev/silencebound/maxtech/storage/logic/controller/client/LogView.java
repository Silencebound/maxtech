package dev.silencebound.maxtech.storage.logic.controller.client;

import dev.silencebound.maxtech.storage.logic.controller.ControllerLog;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The Logic Controller screen's LOG tab as entries (M7L2 chunk 10), made from the log the server sent
 * ({@link ControllerLog.Update}): a rule paused by errors is an error entry, a rule's last run an action entry when it
 * asked for actions and a skipped entry when it did not, a network notice from a notify node an action entry and one
 * from a failed action a warning. Errors come first, the rest newest first. Plain data, no drawing; client side.
 */
public final class LogView {
    /** What an entry is; the filter chips follow this order. */
    public enum Kind {
        ACTION, SKIPPED, WARNING, ERROR
    }

    /**
     * One entry.
     *
     * @param kind
     *            what it is
     * @param rule
     *            the index of the rule it is about, or -1 for a network notice
     * @param time
     *            the game time it happened, or -1 for an error (it holds now)
     * @param chain
     *            the why chain: the run's actions and trace, or the rule's errors; empty for a notice
     * @param notice
     *            the network notice, or null for a rule's entry
     */
    public record Entry(Kind kind, int rule, long time, List<ControllerLog.Line> chain,
            NetworkNotices.@Nullable Notice notice) {
        public Entry {
            chain = List.copyOf(chain);
        }
    }

    private LogView() {
    }

    /** The entries of {@code update}: errors first, then newest first. */
    public static List<Entry> entries(ControllerLog.Update update) {
        List<Entry> errors = new ArrayList<>();
        List<Entry> timed = new ArrayList<>();
        List<ControllerLog.Rule> rules = update.rules();
        for (int i = 0; i < rules.size(); i++) {
            ControllerLog.Rule rule = rules.get(i);
            if (!rule.errors().isEmpty()) {
                errors.add(new Entry(Kind.ERROR, i, -1, rule.errors(), null));
            }
            if (rule.lastRun() >= 0) {
                timed.add(new Entry(rule.actions() > 0 ? Kind.ACTION : Kind.SKIPPED, i, rule.lastRun(), rule.lines(),
                        null));
            }
        }
        for (NetworkNotices.Notice notice : update.notices()) {
            timed.add(new Entry(notice.key().isEmpty() ? Kind.ACTION : Kind.WARNING, -1, notice.gameTime(), List.of(),
                    notice));
        }
        // Stable: rules before notices of the same tick.
        timed.sort((x, y) -> Long.compare(y.time(), x.time()));
        errors.addAll(timed);
        return errors;
    }

    /** The entries whose kind is in {@code shown}, in order. */
    public static List<Entry> filter(List<Entry> entries, Set<Kind> shown) {
        List<Entry> out = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            if (shown.contains(entry.kind())) {
                out.add(entry);
            }
        }
        return out;
    }

    /** How many of {@code entries} are of {@code kind}. */
    public static int count(List<Entry> entries, Kind kind) {
        int count = 0;
        for (Entry entry : entries) {
            if (entry.kind() == kind) {
                count++;
            }
        }
        return count;
    }
}
