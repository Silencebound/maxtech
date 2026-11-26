package dev.silencebound.maxtech.storage.logic.controller.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.silencebound.maxtech.storage.logic.controller.ControllerLog;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogViewTest {
    private static final ControllerLog.Line WHY = new ControllerLog.Line("node", "why", "1", "2", "", "", "");
    private static final ControllerLog.Line ERROR = new ControllerLog.Line("", "error", "", "", "", "", "");

    private static ControllerLog.Rule rule(long lastRun, int actions, List<ControllerLog.Line> errors) {
        return new ControllerLog.Rule("r", lastRun, lastRun < 0 ? 0 : 1, actions, List.of(WHY), errors);
    }

    private static ControllerLog.Update update() {
        return new ControllerLog.Update(1,
                List.of(rule(100, 2, List.of()), rule(-1, 0, List.of(ERROR)), rule(300, 0, List.of())),
                List.of(new NetworkNotices.Notice(200, "", "Sticks ready", ""),
                        new NetworkNotices.Notice(250, "log.denied", "lamp", "")));
    }

    private static List<String> described(List<LogView.Entry> entries) {
        List<String> out = new ArrayList<>();
        for (LogView.Entry entry : entries) {
            out.add(entry.kind() + " " + entry.rule() + " " + entry.time());
        }
        return out;
    }

    @Test
    void errorsFirstThenNewestFirst() {
        assertEquals(List.of("ERROR 1 -1", "SKIPPED 2 300", "WARNING -1 250", "ACTION -1 200", "ACTION 0 100"),
                described(LogView.entries(update())));
    }

    @Test
    void aRuleEntryCarriesItsChain() {
        List<LogView.Entry> entries = LogView.entries(update());
        assertEquals(List.of(ERROR), entries.get(0).chain(), "an error entry: the errors");
        assertEquals(List.of(WHY), entries.get(1).chain(), "a run: the actions and the trace");
        assertEquals(List.of(), entries.get(2).chain(), "a notice has none");
    }

    @Test
    void filterAndCount() {
        List<LogView.Entry> entries = LogView.entries(update());
        assertEquals(2, LogView.count(entries, LogView.Kind.ACTION));
        assertEquals(1, LogView.count(entries, LogView.Kind.ERROR));
        assertEquals(List.of("WARNING -1 250"), described(LogView.filter(entries, EnumSet.of(LogView.Kind.WARNING))));
        assertEquals(5, LogView.filter(entries, EnumSet.allOf(LogView.Kind.class)).size());
        assertEquals(0, LogView.filter(entries, EnumSet.noneOf(LogView.Kind.class)).size());
    }

    @Test
    void anEmptyLogHasNoEntries() {
        assertEquals(List.of(), LogView.entries(new ControllerLog.Update(1, List.of(), List.of())));
    }
}
