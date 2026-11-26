package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The storage cables painted as zone borders in one level (M6e, the zone painter): a painted cable always starts a zone
 * of its own, and the nodes routed through it join that zone (see {@code NetworkState.assignZone}). Kept as positions
 * in the level's saved data, since the cable block entity belongs to Lib's shared conduit type; a mark is dropped when
 * its cable is broken, not when its chunk unloads ({@link StorageNetworkLogic#onNodeRemoved}). Server thread only.
 */
public final class ZoneBorders extends SavedData {
    static final Identifier ID = MaxtechStorage.id("zone_borders");
    private static final Codec<ZoneBorders> CODEC = Codec.LONG_STREAM.xmap(
            stream -> new ZoneBorders(new LongOpenHashSet(stream.toArray())),
            borders -> Arrays.stream(borders.painted.toLongArray()));
    /** The saved data type; its codec writes the painted positions as one long array. */
    public static final SavedDataType<ZoneBorders> TYPE = StorageSaveFormats.savedData(ID, ZoneBorders::new, CODEC);

    private final LongOpenHashSet painted;

    private ZoneBorders() {
        this(new LongOpenHashSet());
    }

    private ZoneBorders(LongOpenHashSet painted) {
        this.painted = painted;
    }

    /** The level's borders, created on first use. */
    public static ZoneBorders of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    /** Whether the cable at {@code pos} is painted as a zone border. */
    public boolean isBorder(long pos) {
        return painted.contains(pos);
    }

    /** The painted positions; a read-only view for diagnostics (the goggles, the painter's highlight). */
    public LongSet painted() {
        return LongSets.unmodifiable(painted);
    }

    /**
     * Paints ({@code border} true) or clears the mark on the cable at {@code pos} and has the storage network of type
     * {@code networkId} there rebuild its routes and zones. Returns whether the mark changed.
     */
    public static boolean set(ServerLevel level, Identifier networkId, BlockPos pos, boolean border) {
        ZoneBorders borders = of(level);
        long key = pos.asLong();
        boolean changed = border ? borders.painted.add(key) : borders.painted.remove(key);
        if (!changed) {
            return false;
        }
        borders.setDirty();
        NetworkState network = StorageNetworks.network(level, networkId, pos);
        if (network != null) {
            // Zones are only cut at rebuilds; painting is rare enough that a full, rate-limited one is fine.
            network.markChanged();
        }
        return true;
    }

    /** Drops the mark at {@code pos} without touching a network: its cable is gone. */
    void forget(long pos) {
        if (painted.remove(pos)) {
            setDirty();
        }
    }
}
