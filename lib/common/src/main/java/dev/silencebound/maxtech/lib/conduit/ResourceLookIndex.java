package dev.silencebound.maxtech.lib.conduit;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.conduit.ConduitContents;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.Direction;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The session's look indices: one small number per resource a conduit shows (in Lib, its kind and draw base), which a
 * packed {@link ConduitContents} holds in bits 0-15, plus the paint resolved for each. Indices count up from 0 in the
 * order resources are first seen and stay put until {@link #clear}; a paint is resolved by the painter when its index
 * is added and again on {@link #repaint}, so render code reads it and never asks registries or kind code.
 *
 * <p>
 * Threads: {@link #indexOf}, {@link #repaint} and {@link #clear} change the index and are meant for the client's main
 * thread (they lock, so another thread is safe, only pointless); {@link #paint}, {@link #key} and {@link #generation}
 * read a volatile copy-on-write array and never lock, so chunk meshing threads can call them at any time. A reader sees
 * either the array before a change or the one after, never a half-written one.
 *
 * <p>
 * Past {@link ConduitContents#MAX_LOOK_INDEX} the index is full: a new resource gets no index (-1, drawn as nothing)
 * and one warning is logged.
 *
 * @param <K>
 *            the resource key, compared by {@code equals}
 * @param <P>
 *            the resolved paint
 */
public final class ResourceLookIndex<K, P> {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Function<K, @Nullable P> painter;
    private final int capacity;
    // Only under the lock.
    private final Map<K, Integer> indices = new HashMap<>();
    private boolean warnedFull;
    private volatile Entry<K, P>[] entries = empty();
    private volatile int generation;

    /**
     * @param painter
     *            resolves the paint of a key, or null when it has none (drawn as nothing); called on the thread that
     *            adds or repaints, never on a reader's
     */
    public ResourceLookIndex(Function<K, @Nullable P> painter) {
        this(painter, ConduitContents.MAX_LOOK_INDEX + 1);
    }

    /** An index holding at most {@code capacity} keys; tests use a small one. */
    ResourceLookIndex(Function<K, @Nullable P> painter, int capacity) {
        if (capacity < 0 || capacity > ConduitContents.MAX_LOOK_INDEX + 1) {
            throw new IllegalArgumentException("capacity out of range: " + capacity);
        }
        this.painter = painter;
        this.capacity = capacity;
    }

    /**
     * The index of {@code key}, added (and its paint resolved) the first time it is seen; -1 when the index is full.
     * Allocates only when it adds.
     */
    public synchronized int indexOf(K key) {
        Integer known = indices.get(key);
        if (known != null) {
            return known;
        }
        Entry<K, P>[] current = entries;
        if (current.length >= capacity) {
            if (!warnedFull) {
                warnedFull = true;
                LOGGER.warn("More than {} different resources shown in conduits this session; {} and any further ones"
                        + " are drawn as nothing", capacity, key);
            }
            return -1;
        }
        int index = current.length;
        Entry<K, P>[] grown = Arrays.copyOf(current, index + 1);
        grown[index] = new Entry<>(key, painter.apply(key));
        indices.put(key, index);
        entries = grown;
        return index;
    }

    /**
     * Packs contents showing {@code key} (see {@link ConduitContents#pack}), adding the key if it is new; a full index
     * packs it as no resource, keeping fill, upstream side and lane.
     */
    public int pack(Optional<K> key, int fill, Optional<Direction> upstream, boolean lane) {
        int index = key.isPresent() ? indexOf(key.get()) : -1;
        return ConduitContents.pack(index, fill, upstream.orElse(null), lane);
    }

    /** Whether the full-index warning was given since the last {@link #clear}; tests only. */
    synchronized boolean warnedFull() {
        return warnedFull;
    }

    /** The paint of {@code index}, or null when it has none or the index is not (or no longer) held. Never locks. */
    public @Nullable P paint(int index) {
        Entry<K, P>[] current = entries;
        return index >= 0 && index < current.length ? current[index].paint() : null;
    }

    /** The key at {@code index}, or empty when it is not held. Never locks; allocates. */
    public Optional<K> key(int index) {
        Entry<K, P>[] current = entries;
        return index >= 0 && index < current.length ? Optional.of(current[index].key()) : Optional.empty();
    }

    /** How many keys are held. */
    public int size() {
        return entries.length;
    }

    /**
     * Counts every {@link #repaint} and {@link #clear}; a cache of parts baked from paints drops them when it changes.
     * Never locks.
     */
    public int generation() {
        return generation;
    }

    /**
     * Resolves every held key's paint again (after a resource reload changed sprites), keeping the indices, since
     * packed values in loaded conduits still name them.
     */
    public synchronized void repaint() {
        Entry<K, P>[] current = entries;
        Entry<K, P>[] painted = Arrays.copyOf(current, current.length);
        for (int i = 0; i < painted.length; i++) {
            K key = painted[i].key();
            painted[i] = new Entry<>(key, painter.apply(key));
        }
        entries = painted;
        generation++;
    }

    /** Drops every key and paint; only safe when no packed value from before is still drawn. */
    public synchronized void clear() {
        indices.clear();
        warnedFull = false;
        entries = empty();
        generation++;
    }

    @SuppressWarnings("unchecked") // a generic array; it only ever holds Entry<K, P>
    private static <K, P> Entry<K, P>[] empty() {
        return (Entry<K, P>[]) new Entry<?, ?>[0];
    }

    private record Entry<K, P>(K key, @Nullable P paint) {
    }
}
