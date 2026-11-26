package dev.silencebound.maxtech.lib.sync;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.jspecify.annotations.Nullable;

/**
 * The client's copy of a synced list: each id's definition and count. Built only from {@link SyncedListUpdate}s, so the
 * client's view is whatever the server last sent. {@link #version()} grows with every applied update, which is how an
 * index notices it must rebuild. Holds no client-only types, so a menu can own one on either side. Client thread.
 */
public final class ClientSyncedList<E> {
    private final Int2ObjectOpenHashMap<E> entries = new Int2ObjectOpenHashMap<>();
    private final Int2LongOpenHashMap counts = new Int2LongOpenHashMap();
    private int version;

    /**
     * Applies one batch. A count without a known definition is ignored (it cannot be shown); a count of 0 removes the
     * entry and its definition.
     */
    public void apply(SyncedListUpdate<E> update) {
        if (update.reset()) {
            entries.clear();
            counts.clear();
        }
        for (int i = 0; i < update.definitionCount(); i++) {
            entries.put(update.definitionId(i), update.definition(i));
        }
        for (int i = 0; i < update.countCount(); i++) {
            int id = update.id(i);
            long count = update.count(i);
            if (count == 0) {
                entries.remove(id);
                counts.remove(id);
            } else if (entries.containsKey(id)) {
                counts.put(id, count);
            }
        }
        version++;
    }

    /** The definition of {@code id}, or null if the list does not hold it. */
    public @Nullable E entry(int id) {
        return entries.get(id);
    }

    /** The count of {@code id}, 0 if the list does not hold it. */
    public long count(int id) {
        return counts.get(id);
    }

    /** The ids the list holds; a live view, do not change it. */
    public IntSet ids() {
        return counts.keySet();
    }

    public int size() {
        return counts.size();
    }

    /** Grows by one with every applied update. */
    public int version() {
        return version;
    }
}
