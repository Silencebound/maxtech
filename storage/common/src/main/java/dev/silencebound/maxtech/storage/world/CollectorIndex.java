package dev.silencebound.maxtech.storage.world;

import dev.silencebound.maxtech.lib.platform.Platform;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;

/**
 * Where collectors are, so a new item entity finds the collectors in range without anyone scanning for entities (PRD
 * 7.2, Performance: the collector registers new drops through the entity-join event against a spatial index of
 * collector ranges). Each collector is listed under every chunk its largest possible range ({@link #MAX_RADIUS}) and
 * drift margin ({@link CollectorWorker#DRIFT_MARGIN}) touch; an item entity entering the level costs one map lookup for
 * its chunk, and the collectors listed there check their real range and margin. Removed collectors drop out when their
 * chunk is next looked at or they register again. Cleared when the server stops. Server thread.
 */
public final class CollectorIndex {
    /** The largest radius a collector may be configured to; the index covers it whatever the config says. */
    public static final int MAX_RADIUS = 8;
    /** How far from a collector the index lists it: the largest range plus the drift margin. */
    private static final int REACH = MAX_RADIUS + CollectorWorker.DRIFT_MARGIN;

    private static final Reference2ObjectOpenHashMap<ServerLevel, Long2ObjectOpenHashMap<ObjectArrayList<CollectorWorker>>> LEVELS = new Reference2ObjectOpenHashMap<>();

    private CollectorIndex() {
    }

    /** Clears the index when the server stops; called once during mod construction. */
    static void init() {
        Platform.get().lifecycle().onServerStopped(server -> LEVELS.clear());
    }

    /** Lists {@code collector} under the chunks around {@code center}. */
    static void add(ServerLevel level, BlockPos center, CollectorWorker collector) {
        Long2ObjectOpenHashMap<ObjectArrayList<CollectorWorker>> chunks = LEVELS.computeIfAbsent(level,
                key -> new Long2ObjectOpenHashMap<>());
        int minX = (center.getX() - REACH) >> 4;
        int maxX = (center.getX() + REACH) >> 4;
        int minZ = (center.getZ() - REACH) >> 4;
        int maxZ = (center.getZ() + REACH) >> 4;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                ObjectArrayList<CollectorWorker> list = chunks.computeIfAbsent(ChunkPos.pack(x, z),
                        key -> new ObjectArrayList<>(1));
                purge(list);
                if (!list.contains(collector)) {
                    list.add(collector);
                }
            }
        }
    }

    /**
     * An item entity is entering {@code level}: the collectors listed for its chunk that have it in range wake. One map
     * lookup when no collector is near.
     */
    public static void itemJoined(ServerLevel level, ItemEntity item) {
        Long2ObjectOpenHashMap<ObjectArrayList<CollectorWorker>> chunks = LEVELS.get(level);
        if (chunks == null) {
            return;
        }
        long chunk = ChunkPos.pack(item.getBlockX() >> 4, item.getBlockZ() >> 4);
        ObjectArrayList<CollectorWorker> list = chunks.get(chunk);
        if (list == null) {
            return;
        }
        purge(list);
        if (list.isEmpty()) {
            chunks.remove(chunk);
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            list.get(i).itemNearby(item);
        }
    }

    private static void purge(ObjectArrayList<CollectorWorker> list) {
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).isGone()) {
                list.remove(i);
            }
        }
    }

    /** Collectors listed in {@code level}, counted once per chunk; for tests. */
    public static int entries(ServerLevel level) {
        Long2ObjectOpenHashMap<ObjectArrayList<CollectorWorker>> chunks = LEVELS.get(level);
        int count = 0;
        if (chunks != null) {
            for (ObjectArrayList<CollectorWorker> list : chunks.values()) {
                count += list.size();
            }
        }
        return count;
    }
}
