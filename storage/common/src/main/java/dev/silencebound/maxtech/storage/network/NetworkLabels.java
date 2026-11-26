package dev.silencebound.maxtech.storage.network;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One network's label index (M7L area C): label key ({@link DeviceLabel#key()}) to the member devices carrying it. The
 * network keeps it up to date as devices join, leave and are relabelled, so lookups are a hash lookup and nothing runs
 * per tick. Each {@link NetworkState} has its own, so a subnet's labels stay apart from its parent's. Internal (not
 * api): logic graphs resolve their labels here. Server thread only.
 */
public final class NetworkLabels {
    private final Object2ObjectOpenHashMap<String, Group> groups = new Object2ObjectOpenHashMap<>();
    /** Counts every change to the index, so caches built from it know when to build again. */
    private long revision;

    /** The devices sharing one key, in joining order, with a read-only view handed out to callers. */
    private static final class Group {
        final ObjectArrayList<NetworkDevice> devices = new ObjectArrayList<>(1);
        final List<NetworkDevice> view = Collections.unmodifiableList(devices);
    }

    /** A label in a listing: the first member's text and colour, and how many devices carry it. */
    public record Entry(DeviceLabel label, int devices) {
    }

    void add(NetworkDevice device, DeviceLabel label) {
        if (label.isEmpty()) {
            return;
        }
        revision++;
        Group group = groups.get(label.key());
        if (group == null) {
            group = new Group();
            groups.put(label.key(), group);
        }
        group.devices.add(device);
    }

    // Devices are compared by identity, like the network's members.
    @SuppressWarnings("ReferenceEquality")
    void remove(NetworkDevice device, DeviceLabel label) {
        if (label.isEmpty()) {
            return;
        }
        revision++;
        String key = label.key();
        Group group = groups.get(key);
        if (group == null) {
            return;
        }
        for (int i = group.devices.size() - 1; i >= 0; i--) {
            if (group.devices.get(i) == device) {
                group.devices.remove(i);
                break;
            }
        }
        if (group.devices.isEmpty()) {
            groups.remove(key);
        }
    }

    /**
     * A number that changes whenever a device joins or leaves a label (joining, leaving or relabelling); caches built
     * from the index (logic's machine pools) build again when it moved.
     */
    public long revision() {
        return revision;
    }

    /**
     * The devices labelled with {@code key}, a {@link DeviceLabel#key()} (already lower-cased), as a read-only live
     * view; empty when no device has the label. Allocates nothing; callers resolving typed text use {@link #devices}.
     */
    public List<NetworkDevice> devicesByKey(String key) {
        Group group = groups.get(key);
        return group == null ? List.of() : group.view;
    }

    /** The devices labelled {@code label} as typed (any case, surrounding spaces ignored); empty for none. */
    public List<NetworkDevice> devices(String label) {
        return devicesByKey(DeviceLabel.key(label));
    }

    /** Whether any device on the network carries {@code label} as typed; false means the editor's "no device" error. */
    public boolean has(String label) {
        return groups.containsKey(DeviceLabel.key(label));
    }

    /** How many different labels the network's devices carry. */
    public int size() {
        return groups.size();
    }

    /** The label the first device with {@code key} carries, for display; null when none does. */
    public @Nullable DeviceLabel shown(String key) {
        Group group = groups.get(key);
        return group == null || group.devices.isEmpty() ? null : group.devices.get(0).deviceLink().label();
    }

    /**
     * Every label with its device count, sorted by text ignoring case; at most {@code limit} entries. Allocates; for an
     * open screen's cadence, not per tick.
     */
    public List<Entry> entries(int limit) {
        List<Entry> out = new ArrayList<>(Math.min(groups.size(), limit));
        for (Group group : groups.values()) {
            if (!group.devices.isEmpty()) {
                out.add(new Entry(group.devices.get(0).deviceLink().label(), group.devices.size()));
            }
        }
        out.sort((a, b) -> a.label().key().compareTo(b.label().key()));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }
}
