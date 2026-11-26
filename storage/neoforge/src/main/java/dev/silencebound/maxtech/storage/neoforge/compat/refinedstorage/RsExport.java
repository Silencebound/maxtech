package dev.silencebound.maxtech.storage.neoforge.compat.refinedstorage;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.composite.CompositeAwareChild;
import com.refinedmods.refinedstorage.api.storage.composite.ParentComposite;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.bridge.BridgeGuard;
import dev.silencebound.maxtech.storage.bridge.ExportedStorage;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A bridge's Storage network as a storage of a Refined Storage network: a source of the RS network's root storage (its
 * {@code StorageNetworkComponent}), so RS grids list it and RS inserts into and extracts from it.
 *
 * <p>
 * RS keeps a cached list of everything its sources hold and learns of changes only through the sources: a
 * {@link CompositeAwareChild} pushes its changes into its parents' caches, as RS's own external storage does. This one
 * keeps {@link #told}, exactly what it has put into RS's cache: every committed change of the Storage network's own
 * storage (the {@link ExportedStorage}'s delta listener) is applied to it and pushed, {@link #getAll()} reports it (so
 * adding and removing the source adds and removes exactly that). The contents are read once, at mount; after that the
 * deltas are the whole truth, since the export lives in one Storage network object only: a bridge that leaves its
 * network unmounts first ({@code NetworkState.join} makes a device leave its old network before it joins another), and
 * devices joining or leaving the bridge's network add or remove their contents through the same deltas. Moves report
 * {@code amountForList} 0, since the delta listener already pushes what they changed.
 *
 * <p>
 * Loop guard: moves go through {@link ExportedStorage}, which accepts nothing while a bridge talks to a foreign
 * network. RS's cached list still holds {@link #told} then, so {@link RsNetwork#forEachStored} takes the {@link #told}
 * of every export mounted in the same RS network off what RS lists ({@link #mountedAmount}). Server thread only.
 */
final class RsExport implements CompositeAwareChild {
    /** Exports mounted in some RS network now, for {@link #mountedAmount}; a handful at most. */
    private static final ObjectArrayList<RsExport> MOUNTED = new ObjectArrayList<>();
    /** Set while an export pushes into RS's caches, so the bridges' change signals ignore those changes. */
    private static boolean pushing;

    private final ExportedStorage storage;
    private final RootStorage root;
    private final Network network;
    private final Object2LongOpenHashMap<ResourceKey> told = new Object2LongOpenHashMap<>();
    /**
     * RS's resources for the keys in {@link #told}, so a delta does not make a new one. A key whose amount drops to 0
     * is dropped with it, so this never holds more kinds than the network does now. RS's lists key resources by
     * {@code equals}, so a resource made again later for the same key is the same entry to RS.
     */
    private final Object2ObjectOpenHashMap<StackKey, ResourceKey> resources = new Object2ObjectOpenHashMap<>();
    private final ObjectArrayList<ParentComposite> parents = new ObjectArrayList<>(1);
    /** The read at mount came while the guard was held (the export shows nothing then); read once it is free. */
    private boolean stale;

    RsExport(ExportedStorage storage, RootStorage root, Network network) {
        this.storage = storage;
        this.root = root;
        this.network = network;
    }

    /** What exports mounted in {@code root} put into its cache for {@code resource}. */
    @SuppressWarnings("ReferenceEquality") // one RS network's root storage object, not a value
    static long mountedAmount(RootStorage root, ResourceKey resource) {
        long sum = 0;
        for (int i = 0; i < MOUNTED.size(); i++) {
            RsExport export = MOUNTED.get(i);
            if (export.root == root) {
                sum += export.told.getLong(resource);
            }
        }
        return sum;
    }

    /**
     * For tests: how many exports are mounted in {@code root} (an RS root storage), and how many resources they keep
     * for their keys, summed.
     */
    @SuppressWarnings("ReferenceEquality") // one RS network's root storage object, not a value
    static int[] mountedIn(Object root) {
        int[] counts = new int[2];
        for (int i = 0; i < MOUNTED.size(); i++) {
            RsExport export = MOUNTED.get(i);
            if (export.root == root) {
                counts[0]++;
                counts[1] += export.resources.size();
            }
        }
        return counts;
    }

    /** Forgets every mounted export; a server stop does not unmount them, and a second world must start clean. */
    static void clearMounted() {
        MOUNTED.clear();
        pushing = false;
    }

    /** Whether an export is pushing into RS's caches right now. */
    static boolean pushing() {
        return pushing;
    }

    /** Starts listening, reads the contents and becomes a source of the RS network. */
    void mount() {
        MOUNTED.add(this);
        storage.setListener(this::changed);
        read();
        root.addSource(this);
    }

    /** Leaves the RS network (which takes {@link #told} out of its cache) and stops listening. */
    void unmount() {
        storage.setListener(null);
        root.removeSource(this);
        MOUNTED.remove(this);
        told.clear();
        resources.clear();
        stale = false;
    }

    // --- Keeping RS's cache --------------------------------------------------------------------------------------

    /** Reads the Storage network's contents and pushes the difference to {@link #told}. */
    private void read() {
        if (BridgeGuard.held()) {
            stale = true;
            return;
        }
        stale = false;
        Object2LongOpenHashMap<ResourceKey> now = new Object2LongOpenHashMap<>();
        storage.forEachStored((key, amount) -> now.addTo(resource(key), amount));
        List<ResourceKey> gone = new ArrayList<>();
        for (Object2LongMap.Entry<ResourceKey> entry : Object2LongMaps.fastIterable(told)) {
            if (!now.containsKey(entry.getKey())) {
                gone.add(entry.getKey());
            }
        }
        for (ResourceKey resource : gone) {
            push(resource, -told.getLong(resource));
        }
        for (Object2LongMap.Entry<ResourceKey> entry : Object2LongMaps.fastIterable(now)) {
            push(entry.getKey(), entry.getLongValue() - told.getLong(entry.getKey()));
        }
        ObjectIterator<Object2ObjectMap.Entry<StackKey, ResourceKey>> it = Object2ObjectMaps.fastIterator(resources);
        while (it.hasNext()) {
            if (!told.containsKey(it.next().getValue())) {
                it.remove();
            }
        }
    }

    /** A read that came under the guard is done now if the guard is free. */
    private void catchUp() {
        if (stale && !BridgeGuard.held()) {
            read();
        }
    }

    /** One committed change of the Storage network's own storage. */
    private void changed(StackKey key, long delta) {
        if (stale && !BridgeGuard.held()) {
            // The read includes this change: the index applies a change before it tells its listeners.
            read();
            return;
        }
        ResourceKey resource = resource(key);
        push(resource, delta);
        if (!told.containsKey(resource)) {
            resources.remove(key);
        }
    }

    /** RS's resource for {@code key}, kept while RS lists it: deltas come on every insert into the Storage network. */
    private ResourceKey resource(StackKey key) {
        ResourceKey resource = resources.get(key);
        if (resource == null) {
            resource = RsKeys.resource(key);
            resources.put(key, resource);
        }
        return resource;
    }

    /** Applies {@code delta} to {@link #told} (never below 0) and to the parents' caches. */
    private void push(ResourceKey resource, long delta) {
        long before = told.getLong(resource);
        long after = Math.max(0, before + delta);
        long change = after - before;
        if (change == 0) {
            return;
        }
        if (after == 0) {
            told.removeLong(resource);
        } else {
            told.put(resource, after);
        }
        pushing = true;
        try {
            for (int i = 0; i < parents.size(); i++) {
                if (change > 0) {
                    parents.get(i).addToCache(resource, change);
                } else {
                    parents.get(i).removeFromCache(resource, -change);
                }
            }
        } finally {
            pushing = false;
        }
    }

    // --- Storage -------------------------------------------------------------------------------------------------

    @Override
    public long insert(ResourceKey resource, long amount, Action action, Actor actor) {
        catchUp();
        StackKey key = RsKeys.key(resource);
        return key == null || amount <= 0 || !RsNetwork.powered(network)
                ? 0
                : RsKeys.clamp(storage.insert(key, amount, action == Action.SIMULATE), amount);
    }

    @Override
    public long extract(ResourceKey resource, long amount, Action action, Actor actor) {
        catchUp();
        StackKey key = RsKeys.key(resource);
        return key == null || amount <= 0 || !RsNetwork.powered(network)
                ? 0
                : RsKeys.clamp(storage.extract(key, amount, action == Action.SIMULATE), amount);
    }

    @Override
    public Amount compositeInsert(ResourceKey resource, long amount, Action action, Actor actor) {
        return new Amount(insert(resource, amount, action, actor), 0);
    }

    @Override
    public Amount compositeExtract(ResourceKey resource, long amount, Action action, Actor actor) {
        return new Amount(extract(resource, amount, action, actor), 0);
    }

    /** Exactly what is in RS's cache from this storage (see the class comment). */
    @Override
    public Collection<ResourceAmount> getAll() {
        List<ResourceAmount> all = new ArrayList<>(told.size());
        for (Object2LongMap.Entry<ResourceKey> entry : Object2LongMaps.fastIterable(told)) {
            all.add(new ResourceAmount(entry.getKey(), entry.getLongValue()));
        }
        return all;
    }

    @Override
    public long getStored() {
        long sum = 0;
        for (Object2LongMap.Entry<ResourceKey> entry : Object2LongMaps.fastIterable(told)) {
            sum += entry.getLongValue();
        }
        return sum;
    }

    @Override
    public void onAddedIntoComposite(ParentComposite parentComposite) {
        if (!parents.contains(parentComposite)) {
            parents.add(parentComposite);
        }
    }

    @Override
    public void onRemovedFromComposite(ParentComposite parentComposite) {
        parents.remove(parentComposite);
    }
}
