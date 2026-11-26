package dev.silencebound.maxtech.storage.network.snapshot;

import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The server's network snapshots (M7C item 27), saved data on the overworld for every dimension, keyed by name in the
 * order they were taken. Caps: {@code snapshots.maxPerNetwork} per dimension and anchor and {@code snapshots.maxTotal}
 * in all; a save past either is refused rather than dropping an older snapshot. Server thread.
 */
public final class NetworkSnapshots extends SavedData {
    /** Longest snapshot name. */
    public static final int MAX_NAME = 32;
    public static final Codec<NetworkSnapshots> CODEC = NetworkSnapshot.CODEC.listOf().xmap(NetworkSnapshots::new,
            NetworkSnapshots::saved);
    public static final SavedDataType<NetworkSnapshots> TYPE = StorageSaveFormats
            .savedData(MaxtechStorage.id("network_snapshots"), NetworkSnapshots::new, CODEC);

    private final Map<String, NetworkSnapshot> snapshots = new LinkedHashMap<>();

    public NetworkSnapshots() {
    }

    private NetworkSnapshots(List<NetworkSnapshot> saved) {
        for (NetworkSnapshot snapshot : saved) {
            snapshots.putIfAbsent(snapshot.name(), snapshot);
        }
    }

    private List<NetworkSnapshot> saved() {
        return List.copyOf(snapshots.values());
    }

    public static NetworkSnapshots get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /**
     * Whether {@code name} may name a snapshot: 1 to {@link #MAX_NAME} of {@code a-z}, {@code 0-9}, {@code _},
     * {@code -}.
     */
    public static boolean validName(String name) {
        if (name.isEmpty() || name.length() > MAX_NAME) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-')) {
                return false;
            }
        }
        return true;
    }

    /** The snapshot called {@code name}. */
    public Optional<NetworkSnapshot> get(String name) {
        return Optional.ofNullable(snapshots.get(name));
    }

    /** Every snapshot, oldest first. */
    public List<NetworkSnapshot> all() {
        return List.copyOf(snapshots.values());
    }

    /** The snapshots of the network at {@code anchor} in {@code dimension}, oldest first. */
    public List<NetworkSnapshot> of(ResourceKey<Level> dimension, BlockPos anchor) {
        List<NetworkSnapshot> out = new ArrayList<>();
        for (NetworkSnapshot snapshot : snapshots.values()) {
            if (snapshot.dimension().equals(dimension) && snapshot.anchor().equals(anchor)) {
                out.add(snapshot);
            }
        }
        return out;
    }

    /** The first free name {@code snapshot-1}, {@code snapshot-2}, ... */
    public String freeName() {
        for (int n = 1;; n++) {
            String name = "snapshot-" + n;
            if (!snapshots.containsKey(name)) {
                return name;
            }
        }
    }

    /** Why {@link #add} refused, or that it worked. */
    public enum Added {
        ADDED, NAME_TAKEN, NETWORK_FULL, SERVER_FULL
    }

    /** Keeps {@code snapshot} unless its name is taken or a cap is reached. */
    public Added add(NetworkSnapshot snapshot, int maxPerNetwork, int maxTotal) {
        if (snapshots.containsKey(snapshot.name())) {
            return Added.NAME_TAKEN;
        }
        if (snapshots.size() >= maxTotal) {
            return Added.SERVER_FULL;
        }
        if (of(snapshot.dimension(), snapshot.anchor()).size() >= maxPerNetwork) {
            return Added.NETWORK_FULL;
        }
        snapshots.put(snapshot.name(), snapshot);
        setDirty();
        return Added.ADDED;
    }

    /** Removes the snapshot called {@code name}; false when there is none. */
    public boolean remove(String name) {
        if (snapshots.remove(name) == null) {
            return false;
        }
        setDirty();
        return true;
    }
}
