package dev.silencebound.maxtech.lib.resource;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.Arrays;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * One owner's (a storage network's) keys interned to small int ids, with each key's total count (PRD 7.2, Storage data
 * model). Hot structures elsewhere hold only the ids; totals live in a dense {@code long[]} by id, and released ids are
 * reused, so the id range stays as small as the number of keys present.
 *
 * <p>
 * A key whose total drops to 0 is not released at once but at the next {@link #releaseUnused()}, which the owner calls
 * on a cadence or before saving; an item that leaves and comes back within that time keeps its id. Released ids are
 * reported to the release listener so caches (client views, listeners) can drop them.
 *
 * <p>
 * The {@linkplain #variants variant index} maps each key's base (the {@code Item} or {@code Fluid} of a
 * {@link StackKey}) to the ids of all its interned variants, so fuzzy filters are lookups.
 *
 * <p>
 * Generic so it can be tested without a game; in game {@code K} is {@link StackKey} and the base is
 * {@link StackKey#base()}. Server thread only; lookups and count changes allocate nothing.
 */
public final class KeyRegistry<K> {
    /** Called with each id when its key is released. */
    @FunctionalInterface
    public interface ReleaseListener {
        void released(int id);
    }

    private final Function<K, Object> baseOf;
    private final IdMap<K> ids = new IdMap<>();
    private final ObjectArrayList<@Nullable K> keys = new ObjectArrayList<>();
    private long[] totals = new long[16];
    private boolean[] pending = new boolean[16];
    private final IntArrayList pendingIds = new IntArrayList();
    private final IntArrayList free = new IntArrayList();
    private final Reference2ObjectOpenHashMap<Object, IntOpenHashSet> variants = new Reference2ObjectOpenHashMap<>();
    private @Nullable ReleaseListener releaseListener;

    /** A registry that groups variants by {@code baseOf(key)} (compared by identity). */
    public KeyRegistry(Function<K, Object> baseOf) {
        this.baseOf = baseOf;
    }

    /** A registry of {@link StackKey}s, with variants grouped by item or fluid. */
    public static KeyRegistry<StackKey> stackKeys() {
        return new KeyRegistry<>(StackKey::base);
    }

    /** Sets who hears about released ids; one listener, replaced by the next call. */
    public void onRelease(@Nullable ReleaseListener listener) {
        this.releaseListener = listener;
    }

    /** The id of {@code key}, or -1 if it is not interned. */
    public int id(K key) {
        return ids.getInt(key);
    }

    /**
     * The id of {@code key}, interning it with a total of 0 if needed. A key interned and never counted is released by
     * the next {@link #releaseUnused()}.
     */
    public int intern(K key) {
        // One probe for both the lookup and the insert: the id the key would get goes in only if it is absent.
        int id = free.isEmpty() ? keys.size() : free.topInt();
        int existing = ids.putIfAbsent(key, id);
        if (existing >= 0) {
            return existing;
        }
        if (id == keys.size()) {
            ensureCapacity(id + 1);
            keys.add(key);
        } else {
            free.popInt();
            keys.set(id, key);
        }
        totals[id] = 0;
        Object base = baseOf.apply(key);
        IntOpenHashSet set = variants.get(base);
        if (set == null) {
            set = new IntOpenHashSet(4);
            variants.put(base, set);
        }
        set.add(id);
        markPending(id);
        return id;
    }

    /** The key with {@code id}, or {@code null} for an id not in use. */
    public @Nullable K key(int id) {
        return id >= 0 && id < keys.size() ? keys.get(id) : null;
    }

    /** The total of {@code id}; 0 for an id not in use. */
    public long total(int id) {
        return id >= 0 && id < keys.size() ? totals[id] : 0;
    }

    /**
     * Adds {@code delta} to the total of {@code id} and returns the new total. Throws {@link IllegalArgumentException}
     * for an id not in use or a change below 0, which would mean a lost or duplicated count somewhere.
     */
    public long add(int id, long delta) {
        if (key(id) == null) {
            throw new IllegalArgumentException("Key id " + id + " is not in use");
        }
        long total = totals[id] + delta;
        if (total < 0) {
            throw new IllegalArgumentException("Total of key id " + id + " would drop to " + total);
        }
        totals[id] = total;
        if (total == 0) {
            markPending(id);
        }
        return total;
    }

    /**
     * Releases every key whose total is 0 now and returns how many; their ids may be handed out again. Cost is in the
     * keys that reached 0 since the last call, not in the registry's size.
     */
    public int releaseUnused() {
        ids.holdShrink = true;
        try {
            return releasePending();
        } finally {
            ids.shrinkHeld();
        }
    }

    private int releasePending() {
        int released = 0;
        for (int i = 0; i < pendingIds.size(); i++) {
            int id = pendingIds.getInt(i);
            pending[id] = false;
            K key = keys.get(id);
            if (key == null || totals[id] != 0) {
                continue;
            }
            ids.removeInt(key);
            keys.set(id, null);
            Object base = baseOf.apply(key);
            IntOpenHashSet set = variants.get(base);
            if (set != null) {
                set.remove(id);
                if (set.isEmpty()) {
                    variants.remove(base);
                }
            }
            free.add(id);
            released++;
            ReleaseListener listener = releaseListener;
            if (listener != null) {
                listener.released(id);
            }
        }
        pendingIds.clear();
        return released;
    }

    /**
     * The ids of every interned key with this base (an {@code Item} or {@code Fluid}), components or not; empty when
     * there are none. A live read-only view: do not change it, and copy it before interning or releasing keys.
     */
    public IntSet variants(Object base) {
        IntOpenHashSet set = variants.get(base);
        return set == null ? IntSets.EMPTY_SET : set;
    }

    /** Keys interned now. */
    public int size() {
        return ids.size();
    }

    /** Slots in the key-to-id hash table, for tests of its shrinking. */
    int tableSlots() {
        return ids.slots();
    }

    /** One past the highest id in use or free; ids are below this. */
    public int idLimit() {
        return keys.size();
    }

    private void markPending(int id) {
        if (!pending[id]) {
            pending[id] = true;
            pendingIds.add(id);
        }
    }

    private void ensureCapacity(int size) {
        if (size > totals.length) {
            int length = Math.max(size, totals.length * 2);
            totals = Arrays.copyOf(totals, length);
            pending = Arrays.copyOf(pending, length);
            // Grow the key list in the same doubling steps, rather than its own smaller ones.
            keys.ensureCapacity(length);
        }
    }

    /**
     * The key to id map, which can hold back the shrinking fastutil does on removal. Releasing many keys at once
     * otherwise halves and rehashes the table again and again on the way down; held back, it shrinks once at the end,
     * to the size it would have reached anyway.
     */
    private static final class IdMap<K> extends Object2IntOpenHashMap<K> {
        boolean holdShrink;

        IdMap() {
            defaultReturnValue(-1);
        }

        @Override
        protected void rehash(int newN) {
            if (holdShrink && newN < n) {
                return;
            }
            super.rehash(newN);
        }

        int slots() {
            return n;
        }

        /** Ends {@link #holdShrink} and shrinks the table as the held-back removals would have. */
        void shrinkHeld() {
            holdShrink = false;
            int target = n;
            int fill = maxFill;
            // The condition fastutil checks after each removal, applied until it no longer holds.
            while (target > minN && size < fill / 4 && target > Hash.DEFAULT_INITIAL_SIZE) {
                target /= 2;
                fill = HashCommon.maxFill(target, f);
            }
            if (target < n) {
                rehash(target);
            }
        }
    }
}
