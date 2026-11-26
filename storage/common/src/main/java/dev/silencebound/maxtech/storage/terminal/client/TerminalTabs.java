package dev.silencebound.maxtech.storage.terminal.client;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The tabs of the terminal screen (see {@link TerminalTab}). Other areas add theirs here instead of editing the
 * terminal screen. Register during client setup; client only.
 */
public final class TerminalTabs {
    private static final List<TerminalTab> TABS = new CopyOnWriteArrayList<>();

    private TerminalTabs() {
    }

    /** Adds {@code tab} after the ones registered before it; a second tab with the same id replaces the first. */
    public static void register(TerminalTab tab) {
        TABS.removeIf(existing -> existing.id().equals(tab.id()));
        TABS.add(tab);
    }

    /** Every registered tab, in order; read-only. */
    public static List<TerminalTab> all() {
        return List.copyOf(TABS);
    }
}
