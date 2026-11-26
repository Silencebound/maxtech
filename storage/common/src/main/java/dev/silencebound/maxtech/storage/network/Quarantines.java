package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.stream.LongStream;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The node positions of quarantined storage networks in one level, saved with the level
 * ({@code data/maxtech_storage/storage_quarantine.dat}). Graphs are not persisted, so a network that is built again
 * (after a restart, a chunk load or a split) finds its quarantine through any of its nodes. A position stays in the set
 * while its chunk unloads, so a network that unloads in parts keeps the quarantine; a block broken in a loaded chunk
 * loses its position at once ({@link StorageNetworkLogic#onNodeRemoved}), so a later network built through that spot
 * starts unfrozen. Lifting the quarantine removes the network's positions. Server thread only.
 */
public final class Quarantines extends SavedData {
    private static final Codec<Quarantines> CODEC = Codec.LONG_STREAM.xmap(Quarantines::new,
            data -> LongStream.of(data.nodes.toLongArray()));
    private static final SavedDataType<Quarantines> TYPE = StorageSaveFormats
            .savedData(MaxtechStorage.id("storage_quarantine"), Quarantines::new, CODEC);

    private final LongOpenHashSet nodes = new LongOpenHashSet();

    private Quarantines() {
    }

    private Quarantines(LongStream loaded) {
        loaded.forEach(nodes::add);
    }

    /** The level's quarantine set. */
    public static Quarantines get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    boolean isEmpty() {
        return nodes.isEmpty();
    }

    /** Whether the node at {@code pos} belongs to a quarantined network, loaded or not. O(1). */
    public boolean contains(long pos) {
        return nodes.contains(pos);
    }

    /** Whether any of {@code network} lies on a quarantined position. O(nodes) when anything is quarantined. */
    boolean containsAny(LongSet network) {
        if (nodes.isEmpty()) {
            return false;
        }
        LongIterator it = network.iterator();
        while (it.hasNext()) {
            if (nodes.contains(it.nextLong())) {
                return true;
            }
        }
        return false;
    }

    void add(long pos) {
        if (nodes.add(pos)) {
            setDirty();
        }
    }

    /** Forgets one spot: a block of the network was broken (not unloaded). */
    void remove(long pos) {
        if (nodes.remove(pos)) {
            setDirty();
        }
    }

    void addAll(LongSet network) {
        if (nodes.addAll(network)) {
            setDirty();
        }
    }

    void removeAll(LongSet network) {
        if (nodes.removeAll(network)) {
            setDirty();
        }
    }
}
