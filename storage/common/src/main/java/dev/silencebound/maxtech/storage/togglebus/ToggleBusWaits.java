package dev.silencebound.maxtech.storage.togglebus;

import dev.silencebound.maxtech.lib.platform.LifecycleEvents;
import dev.silencebound.maxtech.lib.platform.Platform;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.IdentityHashMap;
import net.minecraft.server.level.ServerLevel;

/**
 * The toggle buses that could not read their signal because a chunk the read would touch was not loaded, by that chunk:
 * when it loads they look again ({@link ToggleBusPart#chunkArrived()}), since a loading chunk sends no block update.
 * Nothing polls. A bus whose cable is gone stays listed until its chunk loads or the server stops, and is skipped then.
 * Server thread only.
 */
public final class ToggleBusWaits {
    /** The ticket level of a chunk at full status; lower is more loaded. */
    private static final int FULL_LEVEL = 33;
    private static final IdentityHashMap<ServerLevel, Long2ObjectOpenHashMap<ObjectArrayList<ToggleBusPart>>> WAITS = new IdentityHashMap<>();

    private ToggleBusWaits() {
    }

    /** Hooks the chunk events; mod construction. */
    static void init() {
        LifecycleEvents lifecycle = Platform.get().lifecycle();
        lifecycle.onChunkLoaded(ToggleBusWaits::loaded);
        // A chunk that stayed in memory below full status fires only this when it comes back.
        lifecycle.onChunkLevelChanged((level, chunk, oldLevel, newLevel) -> {
            if (newLevel <= FULL_LEVEL && oldLevel > FULL_LEVEL) {
                loaded(level, chunk);
            }
        });
        lifecycle.onServerStopped(server -> WAITS.clear());
    }

    /** Has {@code bus} look again when the chunk at {@code chunkPos} ({@code ChunkPos.pack}) loads. */
    static void await(ServerLevel level, long chunkPos, ToggleBusPart bus) {
        ObjectArrayList<ToggleBusPart> buses = WAITS.computeIfAbsent(level, l -> new Long2ObjectOpenHashMap<>())
                .computeIfAbsent(chunkPos, c -> new ObjectArrayList<>(1));
        if (!buses.contains(bus)) {
            buses.add(bus);
        }
    }

    /**
     * The chunk at {@code chunkPos} ({@code ChunkPos.pack}) of {@code level} reached full status: the buses waiting for
     * it look again. Called by the chunk events; tests call it as those would.
     */
    public static void loaded(ServerLevel level, long chunkPos) {
        Long2ObjectOpenHashMap<ObjectArrayList<ToggleBusPart>> byChunk = WAITS.get(level);
        if (byChunk == null || byChunk.isEmpty()) {
            return;
        }
        ObjectArrayList<ToggleBusPart> buses = byChunk.remove(chunkPos);
        if (buses == null) {
            return;
        }
        for (int i = 0; i < buses.size(); i++) {
            ToggleBusPart bus = buses.get(i);
            if (!bus.isRemoved()) {
                bus.chunkArrived();
            }
        }
    }
}
