package dev.silencebound.maxtech.storage.logic.controller.client;

import dev.silencebound.maxtech.storage.logic.GraphChange;
import dev.silencebound.maxtech.storage.logic.controller.EditorTexts;
import dev.silencebound.maxtech.storage.logic.controller.HistoryEntry;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * A rule's change history in words, for the controller screen's HISTORY view and the node editor's history panel (M7L2
 * chunk 9): a heading (in-game day, the server's date and time in this computer's time zone, the player) and the
 * change's parts joined with commas. Client only.
 */
final class HistoryText {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private HistoryText() {
    }

    /** "Day 12, 2026-10-08 14:03 · Steve". */
    static Component heading(HistoryEntry entry) {
        Component who = entry.name().isEmpty()
                ? Component.translatable(EditorTexts.HISTORY_SOMEONE)
                : Component.literal(entry.name());
        if (entry.realTime() <= 0) {
            return Component.translatable(EditorTexts.HISTORY_HEADER_DAY, entry.day(), who);
        }
        String clock = CLOCK.format(Instant.ofEpochMilli(entry.realTime()).atZone(ZoneId.systemDefault()));
        return Component.translatable(EditorTexts.HISTORY_HEADER, entry.day(), clock, who);
    }

    /** "added 2 nodes, changed Compare limit 100 → 200". */
    static Component summary(HistoryEntry entry) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < entry.changes().size(); i++) {
            if (i > 0) {
                out.append(Component.translatable(EditorTexts.HISTORY_JOIN));
            }
            out.append(part(entry.changes().get(i)));
        }
        return out;
    }

    private static Component part(GraphChange change) {
        Object[] args = new Object[change.args().size()];
        for (int i = 0; i < args.length; i++) {
            GraphChange.Arg arg = change.args().get(i);
            args[i] = arg.translate() ? Component.translatable(arg.text()) : Component.literal(arg.text());
        }
        return Component.translatable(change.key(), args);
    }
}
