package dev.silencebound.maxtech.storage.terminal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A player's terminal preferences: pinned entries, saved search tabs, the grid's {@link TerminalView}, the screen's
 * {@link TerminalSize} per {@link TerminalKind} and the recently used entries. Pins, tabs, the view and the sizes are
 * kept by the client between sessions; the recent list lives for the session. Generic over the entry kind so the
 * bookkeeping can be tested without registries. Client thread.
 *
 * @param <K>
 *            the entry kind (a stack key in the game)
 */
public final class TerminalPrefs<K> {
    /** Most pinned entries; four rows of the base grid. */
    public static final int MAX_PINS = 36;
    /** Most saved tabs; as many as fit beside the panel. */
    public static final int MAX_TABS = 8;
    /** Recent entries kept: one row of the grid. */
    public static final int RECENT = 9;
    /** Longest saved search, as long as the search box takes. */
    public static final int MAX_TAB_LENGTH = 100;

    private final LinkedHashSet<K> pins = new LinkedHashSet<>();
    private final List<String> tabs = new ArrayList<>();
    private final List<K> recent = new ArrayList<>(RECENT + 1);
    private TerminalView view = TerminalView.ALL;
    private final EnumMap<TerminalKind, TerminalSize> sizes = new EnumMap<>(TerminalKind.class);
    private int version;
    private boolean dirty;

    /** Pins {@code key}, or unpins it if pinned. Returns whether it is pinned now; false when the pins are full. */
    public boolean togglePin(K key) {
        if (pins.remove(key)) {
            changed(true);
            return false;
        }
        if (pins.size() >= MAX_PINS) {
            return false;
        }
        pins.add(key);
        changed(true);
        return true;
    }

    public boolean isPinned(K key) {
        return pins.contains(key);
    }

    /** The pins in the order they were made; read-only. */
    public Set<K> pins() {
        return Collections.unmodifiableSet(pins);
    }

    /**
     * Saves {@code search} as a tab. Returns false for a blank search, one already saved, one that is too long, or when
     * the tabs are full.
     */
    public boolean addTab(String search) {
        String text = search.strip();
        if (text.isEmpty() || text.length() > MAX_TAB_LENGTH || tabs.contains(text) || tabs.size() >= MAX_TABS) {
            return false;
        }
        tabs.add(text);
        changed(true);
        return true;
    }

    /** Removes tab {@code index}; nothing for an index out of range. */
    public void removeTab(int index) {
        if (index >= 0 && index < tabs.size()) {
            tabs.remove(index);
            changed(true);
        }
    }

    /** The saved searches; read-only. */
    public List<String> tabs() {
        return Collections.unmodifiableList(tabs);
    }

    /** Which kinds the grid lists. */
    public TerminalView view() {
        return view;
    }

    /** Sets which kinds the grid lists; saved with the pins and tabs. */
    public void setView(TerminalView next) {
        if (next != view) {
            view = next;
            changed(true);
        }
    }

    /** The screen size of terminal kind {@code kind}; {@link TerminalSize#SMALL} until the player picks one. */
    public TerminalSize size(TerminalKind kind) {
        return sizes.getOrDefault(kind, TerminalSize.SMALL);
    }

    /** Sets the screen size of terminal kind {@code kind}; saved with the pins and tabs. */
    public void setSize(TerminalKind kind, TerminalSize size) {
        if (size(kind) != size) {
            sizes.put(kind, size);
            changed(true);
        }
    }

    /** The sizes the player picked, by kind; read-only. */
    public Map<TerminalKind, TerminalSize> sizes() {
        return Collections.unmodifiableMap(sizes);
    }

    /** {@code key} was just used: it moves to the front of the recent list. */
    public void used(K key) {
        if (!recent.isEmpty() && recent.getFirst().equals(key)) {
            return;
        }
        recent.remove(key);
        recent.addFirst(key);
        if (recent.size() > RECENT) {
            recent.removeLast();
        }
        changed(false);
    }

    /** {@code key}'s place in the recent list (0 is the latest), or -1. */
    public int recentRank(K key) {
        return recent.indexOf(key);
    }

    /** Raised on every change, so a view knows to rearrange. */
    public int version() {
        return version;
    }

    /** Whether pins, tabs or the view changed since {@link #markSaved}. */
    public boolean isDirty() {
        return dirty;
    }

    public void markSaved() {
        dirty = false;
    }

    /**
     * Replaces pins, tabs, the view and the sizes with loaded ones (capped and de-duplicated like edits); keeps the
     * recent list.
     */
    public void load(List<K> loadedPins, List<String> loadedTabs, TerminalView loadedView,
            Map<TerminalKind, TerminalSize> loadedSizes) {
        view = loadedView;
        sizes.clear();
        sizes.putAll(loadedSizes);
        pins.clear();
        tabs.clear();
        for (K key : loadedPins) {
            if (pins.size() < MAX_PINS) {
                pins.add(key);
            }
        }
        for (String tab : loadedTabs) {
            addTab(tab);
        }
        version++;
        dirty = false;
    }

    private void changed(boolean persistent) {
        version++;
        dirty |= persistent;
    }
}
