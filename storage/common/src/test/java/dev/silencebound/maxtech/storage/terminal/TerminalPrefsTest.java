package dev.silencebound.maxtech.storage.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TerminalPrefsTest {
    @Test
    void pinsToggleAndCap() {
        TerminalPrefs<String> prefs = new TerminalPrefs<>();
        assertTrue(prefs.togglePin("a"));
        assertTrue(prefs.isPinned("a"));
        assertFalse(prefs.togglePin("a"), "a second toggle unpins");
        assertFalse(prefs.isPinned("a"));
        for (int i = 0; i < TerminalPrefs.MAX_PINS; i++) {
            assertTrue(prefs.togglePin("k" + i));
        }
        assertFalse(prefs.togglePin("one more"), "full");
        assertFalse(prefs.isPinned("one more"));
        assertTrue(prefs.isDirty());
    }

    @Test
    void tabsRefuseBlankDuplicateAndOverflow() {
        TerminalPrefs<String> prefs = new TerminalPrefs<>();
        assertFalse(prefs.addTab("  "));
        assertTrue(prefs.addTab(" @maxtech_storage "));
        assertFalse(prefs.addTab("@maxtech_storage"), "the same search, trimmed");
        for (int i = 1; i < TerminalPrefs.MAX_TABS; i++) {
            assertTrue(prefs.addTab("tab" + i));
        }
        assertFalse(prefs.addTab("overflow"));
        assertFalse(prefs.addTab("x".repeat(TerminalPrefs.MAX_TAB_LENGTH + 1)));
        prefs.removeTab(0);
        prefs.removeTab(99);
        assertEquals(TerminalPrefs.MAX_TABS - 1, prefs.tabs().size());
        assertEquals("tab1", prefs.tabs().getFirst());
    }

    @Test
    void recentKeepsTheLatestFirstAndDoesNotDirty() {
        TerminalPrefs<String> prefs = new TerminalPrefs<>();
        for (int i = 0; i < TerminalPrefs.RECENT + 2; i++) {
            prefs.used("k" + i);
        }
        prefs.used("k5");
        assertEquals(0, prefs.recentRank("k5"));
        assertEquals(1, prefs.recentRank("k" + (TerminalPrefs.RECENT + 1)));
        assertEquals(-1, prefs.recentRank("k0"), "the oldest fell off");
        assertFalse(prefs.isDirty(), "the recent list is not saved");
    }

    @Test
    void loadReplacesPinsAndTabsWithinTheCaps() {
        TerminalPrefs<String> prefs = new TerminalPrefs<>();
        prefs.togglePin("old");
        int version = prefs.version();
        prefs.load(List.of("a", "b", "a"), List.of("x", "x", ""), TerminalView.ALL, Map.of());
        assertFalse(prefs.isPinned("old"));
        assertEquals(List.of("a", "b"), List.copyOf(prefs.pins()));
        assertEquals(List.of("x"), prefs.tabs());
        assertFalse(prefs.isDirty());
        assertTrue(prefs.version() != version);
    }

    @Test
    void viewChangeIsSavedAndLoaded() {
        TerminalPrefs<String> prefs = new TerminalPrefs<>();
        assertEquals(TerminalView.ALL, prefs.view());
        int version = prefs.version();
        prefs.setView(TerminalView.FLUIDS);
        assertEquals(TerminalView.FLUIDS, prefs.view());
        assertTrue(prefs.isDirty(), "the view is saved with pins and tabs");
        assertTrue(prefs.version() != version);
        prefs.markSaved();
        prefs.setView(TerminalView.FLUIDS);
        assertFalse(prefs.isDirty(), "the same view again changes nothing");
        prefs.load(List.of(), List.of(), TerminalView.ITEMS, Map.of(TerminalKind.PATTERN, TerminalSize.FILL));
        assertEquals(TerminalView.ITEMS, prefs.view());
        assertEquals(TerminalSize.FILL, prefs.size(TerminalKind.PATTERN));
        assertEquals(TerminalSize.SMALL, prefs.size(TerminalKind.TERMINAL), "a kind not loaded is small");
    }

    /** Each terminal kind keeps its own size (B09 item 21c). */
    @Test
    void sizesAreKeptPerKind() {
        TerminalPrefs<String> prefs = new TerminalPrefs<>();
        for (TerminalKind kind : TerminalKind.values()) {
            assertEquals(TerminalSize.SMALL, prefs.size(kind));
        }
        prefs.setSize(TerminalKind.CRAFTING, TerminalSize.TALL);
        assertTrue(prefs.isDirty(), "a size is saved with pins and tabs");
        assertEquals(TerminalSize.TALL, prefs.size(TerminalKind.CRAFTING));
        assertEquals(TerminalSize.SMALL, prefs.size(TerminalKind.TERMINAL), "other kinds keep theirs");
        assertEquals(TerminalSize.SMALL, prefs.size(TerminalKind.PATTERN));
        prefs.markSaved();
        prefs.setSize(TerminalKind.CRAFTING, TerminalSize.TALL);
        assertFalse(prefs.isDirty(), "the same size again changes nothing");
    }

    @Test
    void sizeButtonCyclesBothWays() {
        assertEquals(TerminalSize.TALL, TerminalSize.SMALL.step(true));
        assertEquals(TerminalSize.FILL, TerminalSize.TALL.step(true));
        assertEquals(TerminalSize.SMALL, TerminalSize.FILL.step(true));
        assertEquals(TerminalSize.FILL, TerminalSize.SMALL.step(false));
        for (TerminalSize size : TerminalSize.values()) {
            assertEquals(size, TerminalSize.bySerialName(size.serialName()));
        }
        assertEquals(TerminalSize.SMALL, TerminalSize.bySerialName("huge"));
    }
}
