package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.concurrent.CowSnapshot;
import dev.silencebound.maxtech.lib.resource.KeyRegistry;
import dev.silencebound.maxtech.storage.api.StorageContents;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongMaps;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A network's storage index (PRD 7.2, Storage data model): every key interned to an int id with its total over all
 * providers, a copy-on-write snapshot of those totals for off-thread readers, and per key the providers that hold it
 * with their amounts, so inserting into an existing stack and extracting are lookups instead of walks over every
 * provider. All of it changes only through the providers' {@link StorageProvider.Listener}s, i.e. by committed amounts.
 * Server thread only.
 */
final class StorageIndex implements StorageContents {
    /** Keys at 0 are released this often (ticks), so ids stay few without churning on items that come and go. */
    static final int RELEASE_INTERVAL = 200;
    /** Most keys {@link #appeared} holds before it starts over. */
    static final int APPEARED_LIMIT = 1024;

    private final KeyRegistry<StackKey> keys = KeyRegistry.stackKeys();
    private final CowSnapshot<Int2LongOpenHashMap> totals = CowSnapshot.int2Long();
    /** Key id -> the providers holding it; each entry knows its own amount. */
    private final Int2ObjectOpenHashMap<ObjectArrayList<Entry>> holders = new Int2ObjectOpenHashMap<>();
    /** Every provider in the index, highest priority first. */
    private final ObjectArrayList<Entry> providers = new ObjectArrayList<>();
    private @Nullable Snapshot snapshot;
    private volatile @Nullable Snapshot published;
    /** Open terminals; usually empty, so a change costs one size check. */
    private final ObjectArrayList<ContentsWatcher> watchers = new ObjectArrayList<>();
    /** Key listeners by key; usually empty, so a change costs one empty check. */
    private final Object2ObjectOpenHashMap<StackKey, ObjectArrayList<KeyListener>> keyListeners = new Object2ObjectOpenHashMap<>();
    /** The network's observer (research), told about every change with its delta; set once by the network. */
    @Nullable
    NetworkObserver observer;
    /** Where overflow a provider destroyed goes (the network's recovery bin); set once by the network. */
    StorageProvider.@Nullable Listener voids;
    /** Views of this storage in other networks, told every committed change of a provider that is no view. */
    private final ObjectArrayList<StorageProvider.Listener> nativeListeners = new ObjectArrayList<>(0);
    /** Items and mB of fluid in the index, kept with every change (telemetry reads them). */
    private long itemTotal;
    private long fluidTotal;
    /**
     * Keys whose total went from 0 to above 0, in that order (B09 item 18): export ports with fuzzy, tag or mod entries
     * read what appeared since their last run instead of walking every key. Holds at most {@link #APPEARED_LIMIT}; when
     * full it is emptied, and a reader whose position fell behind {@link #appearedStart()} walks every key once.
     */
    private final ObjectArrayList<StackKey> appeared = new ObjectArrayList<>();
    /** The log position of {@code appeared}'s first key. */
    private long appearedBase;

    StorageIndex() {
        keys.onRelease(this::released);
    }

    /** One indexed provider, what the index was told it holds, and the listener it reports to. */
    final class Entry implements StorageProvider.Listener {
        final StorageProvider provider;
        final int priority;
        /** Whether the provider shows another network ({@link NetworkView}); left out of the native reads. */
        final boolean view;
        /** Key id -> amount, as reported; what is subtracted when the provider leaves. */
        final Int2LongOpenHashMap held = new Int2LongOpenHashMap();

        Entry(StorageProvider provider) {
            this.provider = provider;
            this.priority = provider.priority();
            this.view = provider instanceof NetworkView;
        }

        @Override
        public void changed(StackKey key, long delta) {
            apply(this, key, delta);
        }

        @Override
        public void voided(StackKey key, long amount) {
            StorageProvider.Listener sink = voids;
            if (sink != null) {
                sink.voided(key, amount);
            }
        }
    }

    /** Indexes {@code provider}'s contents and starts listening to it. */
    Entry add(StorageProvider provider) {
        Entry entry = new Entry(provider);
        int at = 0;
        while (at < providers.size() && providers.get(at).priority >= entry.priority) {
            at++;
        }
        providers.add(at, entry);
        provider.forEachStored((key, amount) -> apply(entry, key, amount));
        provider.setListener(entry);
        return entry;
    }

    /**
     * Stops listening to {@code entry}'s provider and takes what the index was told it holds out of the totals; the
     * provider is not asked again, so a provider that already lost its contents cannot leave a count behind.
     */
    void remove(Entry entry) {
        if (!providers.remove(entry)) {
            return;
        }
        entry.provider.setListener(null);
        int[] ids = entry.held.keySet().toIntArray();
        for (int id : ids) {
            StackKey key = keys.key(id);
            if (key != null) {
                apply(entry, key, -entry.held.get(id));
            }
        }
    }

    /** Every indexed provider, highest priority first; read-only. */
    ObjectArrayList<Entry> providers() {
        return providers;
    }

    /** The providers holding the key with {@code id}; null when none does. Read-only. */
    @Nullable
    ObjectArrayList<Entry> holders(int id) {
        return holders.get(id);
    }

    void apply(Entry entry, StackKey key, long delta) {
        if (delta == 0) {
            return;
        }
        int id = keys.intern(key);
        long total = keys.add(id, delta);
        if (total > 0 && total == delta) {
            if (appeared.size() >= APPEARED_LIMIT) {
                appearedBase += appeared.size();
                appeared.clear();
            }
            appeared.add(key);
        }
        if (key.kind() == StackKey.Kind.FLUID) {
            fluidTotal += delta;
        } else {
            itemTotal += delta;
        }
        Int2LongOpenHashMap live = totals.write();
        if (total == 0) {
            live.remove(id);
        } else {
            live.put(id, total);
        }
        for (int i = 0; i < watchers.size(); i++) {
            watchers.get(i).changed(id, total);
        }
        if (observer != null) {
            observer.keyChanged(key, total, delta);
        }
        if (!keyListeners.isEmpty()) {
            ObjectArrayList<KeyListener> listening = keyListeners.get(key);
            if (listening != null) {
                for (int i = 0; i < listening.size(); i++) {
                    listening.get(i).keyChanged(key, total);
                }
            }
        }
        if (!entry.view) {
            for (int i = 0; i < nativeListeners.size(); i++) {
                nativeListeners.get(i).changed(key, delta);
            }
        }
        long before = entry.held.get(id);
        long after = before + delta;
        if (after <= 0) {
            entry.held.remove(id);
        } else {
            entry.held.put(id, after);
        }
        if (before <= 0 && after > 0) {
            ObjectArrayList<Entry> list = holders.get(id);
            if (list == null) {
                list = new ObjectArrayList<>(2);
                holders.put(id, list);
            }
            list.add(entry);
        } else if (before > 0 && after <= 0) {
            ObjectArrayList<Entry> list = holders.get(id);
            if (list != null) {
                list.remove(entry);
                if (list.isEmpty()) {
                    holders.remove(id);
                }
            }
        }
    }

    /** Starts telling {@code listener} every committed change of a provider that is no view; once per listener. */
    @SuppressWarnings("ReferenceEquality") // a view's listener is one object
    void listenNative(StorageProvider.Listener listener) {
        for (int i = 0; i < nativeListeners.size(); i++) {
            if (nativeListeners.get(i) == listener) {
                return;
            }
        }
        nativeListeners.add(listener);
    }

    @SuppressWarnings("ReferenceEquality") // as in listenNative
    void unlistenNative(StorageProvider.Listener listener) {
        for (int i = nativeListeners.size() - 1; i >= 0; i--) {
            if (nativeListeners.get(i) == listener) {
                nativeListeners.remove(i);
            }
        }
    }

    /** How much of {@code key} the providers that are no view hold. O(holders of the key). */
    long nativeAmount(StackKey key) {
        int id = keys.id(key);
        ObjectArrayList<Entry> list = id < 0 ? null : holders.get(id);
        if (list == null) {
            return 0;
        }
        long amount = 0;
        for (int i = 0; i < list.size(); i++) {
            Entry entry = list.get(i);
            if (!entry.view) {
                amount += entry.held.get(id);
            }
        }
        return amount;
    }

    /** Visits every key the providers that are no view hold, each once with its amount. Only when a view mounts. */
    void forEachNative(StorageProvider.Visitor visitor) {
        Int2LongOpenHashMap sums = new Int2LongOpenHashMap();
        for (int i = 0; i < providers.size(); i++) {
            Entry entry = providers.get(i);
            if (!entry.view) {
                for (Int2LongMap.Entry held : entry.held.int2LongEntrySet()) {
                    sums.addTo(held.getIntKey(), held.getLongValue());
                }
            }
        }
        for (Int2LongMap.Entry sum : sums.int2LongEntrySet()) {
            StackKey key = keys.key(sum.getIntKey());
            if (key != null && sum.getLongValue() > 0) {
                visitor.visit(key, sum.getLongValue());
            }
        }
    }

    /** Items in every provider together. */
    long itemTotal() {
        return itemTotal;
    }

    /** mB of fluid in every provider together. */
    long fluidTotal() {
        return fluidTotal;
    }

    /** Starts telling {@code watcher} about every change of a total; adding one twice adds it once. */
    // Watchers are compared by identity: an open terminal is one object.
    @SuppressWarnings("ReferenceEquality")
    void watch(ContentsWatcher watcher) {
        for (int i = 0; i < watchers.size(); i++) {
            if (watchers.get(i) == watcher) {
                return;
            }
        }
        watchers.add(watcher);
    }

    @SuppressWarnings("ReferenceEquality") // as in watch
    void unwatch(ContentsWatcher watcher) {
        for (int i = watchers.size() - 1; i >= 0; i--) {
            if (watchers.get(i) == watcher) {
                watchers.remove(i);
            }
        }
    }

    /** Calls {@code listener} on every change of {@code key}'s total; see {@link KeyListener}. */
    void listen(StackKey key, KeyListener listener) {
        ObjectArrayList<KeyListener> listening = keyListeners.get(key);
        if (listening == null) {
            listening = new ObjectArrayList<>(1);
            keyListeners.put(key, listening);
        }
        listening.add(listener);
    }

    /** Removes one registration of {@code listener} for {@code key}. */
    @SuppressWarnings("ReferenceEquality") // listeners are compared by identity, as watchers are
    void unlisten(StackKey key, KeyListener listener) {
        ObjectArrayList<KeyListener> listening = keyListeners.get(key);
        if (listening == null) {
            return;
        }
        for (int i = listening.size() - 1; i >= 0; i--) {
            if (listening.get(i) == listener) {
                listening.remove(i);
                break;
            }
        }
        if (listening.isEmpty()) {
            keyListeners.remove(key);
        }
    }

    /** Keys with at least one listener; for tests. */
    int listenedKeys() {
        return keyListeners.size();
    }

    private void released(int id) {
        for (int i = 0; i < watchers.size(); i++) {
            watchers.get(i).released(id);
        }
    }

    /** The log position of the oldest key {@link #appeared(long)} still holds. */
    long appearedStart() {
        return appearedBase;
    }

    /** The log position the next key that appears will get. */
    long appearedEnd() {
        return appearedBase + appeared.size();
    }

    /** The key at log position {@code position}, between {@link #appearedStart()} and {@link #appearedEnd()}. */
    StackKey appeared(long position) {
        return appeared.get((int) (position - appearedBase));
    }

    /** Releases keys whose total is 0; on a cadence, see {@link #RELEASE_INTERVAL}. */
    void releaseUnused() {
        keys.releaseUnused();
    }

    /** The id {@code key} is interned to, even at a total of 0 until it is released; -1 when it is not interned. */
    int rawId(StackKey key) {
        return keys.id(key);
    }

    @Override
    public long amount(StackKey key) {
        return keys.total(keys.id(key));
    }

    @Override
    public int keyId(StackKey key) {
        int id = keys.id(key);
        return id >= 0 && keys.total(id) > 0 ? id : -1;
    }

    @Override
    public @Nullable StackKey key(int id) {
        return keys.key(id);
    }

    @Override
    public long amount(int id) {
        return keys.total(id);
    }

    @Override
    public int size() {
        return totals.read().size();
    }

    @Override
    public long generation() {
        return totals.generation();
    }

    @Override
    public Snapshot snapshot() {
        CowSnapshot.Snapshot<Int2LongOpenHashMap> taken = totals.snapshot();
        Snapshot current = snapshot;
        if (current == null || current.generation() != taken.generation()) {
            current = new Snapshot(taken.generation(), Int2LongMaps.unmodifiable(taken.value()));
            snapshot = current;
            published = current;
        }
        return current;
    }

    @Override
    public Optional<Snapshot> latestSnapshot() {
        return Optional.ofNullable(published);
    }
}
