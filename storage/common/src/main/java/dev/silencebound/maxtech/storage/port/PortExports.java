package dev.silencebound.maxtech.storage.port;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.resource.StackKeys;
import dev.silencebound.maxtech.storage.api.StorageContents;
import dev.silencebound.maxtech.storage.network.NetworkState;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
// PLATFORM-LEAK: ports move NeoForge transfer resources; the list holds each key's resource for the move.
import net.neoforged.neoforge.transfer.resource.Resource;
import org.jspecify.annotations.Nullable;

/**
 * What an export port of one kind (items or fluids) tries to move, in order (B09 item 18): each filter entry in slot
 * order, an exact entry as its own key, a fuzzy, tag or mod entry as the network's keys it matches. Those keys are
 * found once when the filter or the network changes (one walk over the network's keys) and afterwards only from the
 * network's log of keys that appeared ({@link NetworkState#appearedKey}), so a run with nothing new costs one compare;
 * keys whose total dropped to 0 stay in the list and are skipped by the move, until the next walk. The list itself is
 * built again only when it changed. Server thread; one per port and kind, reused.
 */
final class PortExports {
    private final boolean fluid;
    /** The ordered list: key, its resource, and the index of its entry in the filter's entries of this kind. */
    private StackKey[] keys = new StackKey[0];
    private Resource[] resources = new Resource[0];
    private int[] entries = new int[0];
    private int count;
    private int filterVersion = Integer.MIN_VALUE;
    private PortFilter.Entry[] filterEntries = new PortFilter.Entry[0];
    /** Network keys a fuzzy, tag or mod entry matches, with their resources and entries, in the order found. */
    private final ObjectArrayList<StackKey> found = new ObjectArrayList<>();
    private final ObjectArrayList<Resource> foundResources = new ObjectArrayList<>();
    private final IntArrayList foundEntries = new IntArrayList();
    private final ObjectOpenHashSet<StackKey> known = new ObjectOpenHashSet<>();
    private @Nullable NetworkState network;
    private long position;
    private boolean dirty = true;

    PortExports(boolean fluid) {
        this.fluid = fluid;
    }

    /**
     * Brings the list up to date with {@code filter} and {@code network}; allocates only when the list changed. Call at
     * the start of an export run.
     */
    // The network is compared by identity: a port that joined another network walks the new one.
    @SuppressWarnings("ReferenceEquality")
    void refresh(PortFilter filter, NetworkState current) {
        if (filter.version() != filterVersion) {
            filterVersion = filter.version();
            filterEntries = fluid ? filter.fluidEntries() : filter.itemEntries();
            network = null;
            dirty = true;
        }
        if (filter.inexact(fluid)) {
            if (current != network || position < current.appearedStart()) {
                walk(current);
            } else {
                long end = current.appearedEnd();
                for (long p = position; p < end; p++) {
                    consider(current.appearedKey(p));
                }
                position = end;
            }
        } else if (!found.isEmpty()) {
            clearFound();
        }
        if (dirty) {
            build();
            dirty = false;
        }
    }

    /** Forgets the network's keys; the next refresh walks the network again. */
    void forget() {
        network = null;
    }

    private void walk(NetworkState current) {
        clearFound();
        network = current;
        position = current.appearedEnd();
        StorageContents contents = current.contents();
        // The ids alone: the entry set of the read-only totals makes an entry object per key.
        IntIterator ids = contents.snapshot().totals().keySet().iterator();
        while (ids.hasNext()) {
            StackKey key = contents.key(ids.nextInt());
            if (key != null) {
                consider(key);
            }
        }
    }

    private void clearFound() {
        found.clear();
        foundResources.clear();
        foundEntries.clear();
        known.clear();
        dirty = true;
    }

    /** Adds {@code key} if a fuzzy, tag or mod entry matches it and it is not listed yet. */
    private void consider(StackKey key) {
        if ((key.kind() == StackKey.Kind.FLUID) != fluid || known.contains(key)) {
            return;
        }
        for (int e = 0; e < filterEntries.length; e++) {
            PortFilter.Entry entry = filterEntries[e];
            if (entry.mode != MatchMode.EXACT && entry.matches(key)) {
                known.add(key);
                found.add(key);
                foundResources.add(StackKeys.toResource(key));
                foundEntries.add(e);
                dirty = true;
                return;
            }
        }
    }

    /** Lays the list out in entry order, each key once (the first entry naming it counts). */
    private void build() {
        ObjectOpenHashSet<StackKey> listed = new ObjectOpenHashSet<>();
        ObjectArrayList<StackKey> outKeys = new ObjectArrayList<>();
        ObjectArrayList<Resource> outResources = new ObjectArrayList<>();
        IntArrayList outEntries = new IntArrayList();
        for (int e = 0; e < filterEntries.length; e++) {
            PortFilter.Entry entry = filterEntries[e];
            if (entry.mode == MatchMode.EXACT) {
                if (listed.add(entry.key)) {
                    outKeys.add(entry.key);
                    outResources.add(entry.resource);
                    outEntries.add(e);
                }
                continue;
            }
            for (int i = 0; i < found.size(); i++) {
                if (foundEntries.getInt(i) == e && listed.add(found.get(i))) {
                    outKeys.add(found.get(i));
                    outResources.add(foundResources.get(i));
                    outEntries.add(e);
                }
            }
        }
        keys = outKeys.toArray(new StackKey[0]);
        resources = outResources.toArray(new Resource[0]);
        entries = outEntries.toIntArray();
        count = keys.length;
    }

    /** How many keys the list holds. */
    int size() {
        return count;
    }

    StackKey key(int i) {
        return keys[i];
    }

    Resource resource(int i) {
        return resources[i];
    }

    /** The index of key {@code i}'s entry in the filter's entries of this kind. */
    int entry(int i) {
        return entries[i];
    }

    /** The filter's entries of this kind, as of the last refresh. */
    PortFilter.Entry[] filterEntries() {
        return filterEntries;
    }
}
