package dev.silencebound.maxtech.lib.resource;

import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongMaps;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.List;

/**
 * Counts per key id for one container (a storage cell), kept in step with its owner's {@link KeyRegistry} totals, with
 * a dirty flag so an unchanged container is not written again on save (PRD 7.2, Persistence). Every change goes through
 * {@link #add}, which updates the registry total in the same call, so the registry's totals are always the sum over the
 * owner's containers.
 *
 * <p>
 * Server thread only. {@link #add} allocates nothing; {@link #toPalette()} and {@link #load} allocate and are for save
 * and load.
 */
public final class KeyCounts<K> {
    private final KeyRegistry<K> registry;
    private final Int2LongOpenHashMap counts = new Int2LongOpenHashMap();
    private long total;
    private boolean dirty;

    public KeyCounts(KeyRegistry<K> registry) {
        this.registry = registry;
    }

    /** The count of {@code id}; 0 when absent. */
    public long count(int id) {
        return counts.get(id);
    }

    /** The sum of all counts. */
    public long total() {
        return total;
    }

    /** Distinct keys held. */
    public int kinds() {
        return counts.size();
    }

    /** A read-only live view of id to count (a small wrapper per call); do not keep it across changes. */
    public Int2LongMap view() {
        return Int2LongMaps.unmodifiable(counts);
    }

    /**
     * Adds {@code delta} (negative to remove) to the count of {@code id}, an id interned in the registry, and to its
     * registry total; returns the new count. Throws {@link IllegalArgumentException}, changing nothing, when the count
     * would drop below 0.
     */
    public long add(int id, long delta) {
        if (delta == 0) {
            return counts.get(id);
        }
        long updated = counts.get(id) + delta;
        if (updated < 0) {
            throw new IllegalArgumentException("Count of key id " + id + " would drop to " + updated);
        }
        registry.add(id, delta);
        if (updated == 0) {
            counts.remove(id);
        } else {
            counts.put(id, updated);
        }
        total += delta;
        dirty = true;
        return updated;
    }

    /** Removes everything, taking it off the registry totals too (the container left its owner). */
    public void clear() {
        ObjectIterator<Int2LongMap.Entry> it = Int2LongMaps.fastIterator(counts);
        while (it.hasNext()) {
            Int2LongMap.Entry entry = it.next();
            registry.add(entry.getIntKey(), -entry.getLongValue());
        }
        if (!counts.isEmpty()) {
            dirty = true;
        }
        counts.clear();
        total = 0;
    }

    /** Whether anything changed since the last {@link #markSaved()} (or {@link #load}). */
    public boolean isDirty() {
        return dirty;
    }

    /** Called once the contents were written. */
    public void markSaved() {
        dirty = false;
    }

    /** The contents as a palette: one key per held id, in no particular order. */
    public KeyPalette<K> toPalette() {
        List<K> keys = new ArrayList<>(counts.size());
        int[] indices = new int[counts.size()];
        long[] values = new long[counts.size()];
        int i = 0;
        ObjectIterator<Int2LongMap.Entry> it = Int2LongMaps.fastIterator(counts);
        while (it.hasNext()) {
            Int2LongMap.Entry entry = it.next();
            K key = registry.key(entry.getIntKey());
            if (key == null) {
                throw new IllegalStateException("Key id " + entry.getIntKey() + " held but not interned");
            }
            keys.add(key);
            indices[i] = i;
            values[i] = entry.getLongValue();
            i++;
        }
        return new KeyPalette<>(keys, indices, values);
    }

    /**
     * Replaces the contents with {@code palette}, interning its keys and adding their counts to the registry; entries
     * repeating a key add up. Leaves the container clean.
     */
    public void load(KeyPalette<K> palette) {
        clear();
        List<K> keys = palette.keys();
        int[] idOfIndex = new int[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            idOfIndex[i] = registry.intern(keys.get(i));
        }
        int[] indices = palette.indices();
        long[] values = palette.counts();
        for (int i = 0; i < indices.length; i++) {
            if (values[i] > 0) {
                add(idOfIndex[indices[i]], values[i]);
            }
        }
        dirty = false;
    }

    /** The registry the ids belong to. */
    public KeyRegistry<K> registry() {
        return registry;
    }
}
