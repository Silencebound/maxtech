package dev.silencebound.maxtech.lib.conduit;

import dev.silencebound.maxtech.lib.platform.LifecycleEvents;
import dev.silencebound.maxtech.lib.platform.Platform;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.IdentityHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * The conduits that attach to blocks ({@code Conduits.Builder.attachesToBlocks}) and sit on a chunk border, by the
 * chunk across that border. A chunk reaching full status sends no neighbour update, so when one of those chunks does,
 * its conduits look at their neighbours again ({@link ConduitBlockEntity#neighbourChunkArrived()}) at the end of the
 * level tick, once the chunk is readable. Nothing polls: a level with no such conduits returns at the map lookup.
 * Server thread only.
 */
public final class ConduitAttachments {
    /** The ticket level of a chunk at full status; lower is more loaded. */
    private static final int FULL_LEVEL = 33;
    private static final IdentityHashMap<ServerLevel, Waits> LEVELS = new IdentityHashMap<>();

    private ConduitAttachments() {
    }

    /** Hooks the chunk and tick events; Lib's init. */
    public static void bootstrap() {
        LifecycleEvents lifecycle = Platform.get().lifecycle();
        lifecycle.onChunkLoaded(ConduitAttachments::arrived);
        // A chunk that stayed in memory below full status fires only this when it comes back.
        lifecycle.onChunkLevelChanged((level, chunk, oldLevel, newLevel) -> {
            if (newLevel <= FULL_LEVEL && oldLevel > FULL_LEVEL) {
                arrived(level, chunk);
            } else if (newLevel > FULL_LEVEL && oldLevel <= FULL_LEVEL) {
                left(level, chunk);
            }
        });
        lifecycle.onServerLevelTickEnd(ConduitAttachments::tickLevel);
        lifecycle.onServerStopped(server -> LEVELS.clear());
    }

    /** Lists the conduit at {@code pos} under the chunks across the borders it sits on, if any. */
    static void track(ServerLevel level, BlockPos pos) {
        int x = pos.getX();
        int z = pos.getZ();
        if (!onBorder(x) && !onBorder(z)) {
            return;
        }
        Waits waits = LEVELS.computeIfAbsent(level, l -> new Waits());
        long packed = pos.asLong();
        forEachFacedChunk(x, z, chunk -> {
            LongArrayList conduits = waits.byChunk.computeIfAbsent(chunk, c -> new LongArrayList(1));
            if (!conduits.contains(packed)) {
                conduits.add(packed);
            }
        });
    }

    /** Drops the conduit at {@code pos} again; it was broken or its chunk unloads. */
    static void untrack(ServerLevel level, BlockPos pos) {
        int x = pos.getX();
        int z = pos.getZ();
        Waits waits = LEVELS.get(level);
        if (waits == null || (!onBorder(x) && !onBorder(z))) {
            return;
        }
        long packed = pos.asLong();
        forEachFacedChunk(x, z, chunk -> {
            LongArrayList conduits = waits.byChunk.get(chunk);
            if (conduits != null && conduits.rem(packed) && conduits.isEmpty()) {
                waits.byChunk.remove(chunk);
            }
        });
    }

    private static boolean onBorder(int coordinate) {
        int inChunk = coordinate & 15;
        return inChunk == 0 || inChunk == 15;
    }

    private static void forEachFacedChunk(int x, int z, ChunkAction action) {
        int chunkX = SectionPos.blockToSectionCoord(x);
        int chunkZ = SectionPos.blockToSectionCoord(z);
        if ((x & 15) == 0) {
            action.accept(ChunkPos.pack(chunkX - 1, chunkZ));
        } else if ((x & 15) == 15) {
            action.accept(ChunkPos.pack(chunkX + 1, chunkZ));
        }
        if ((z & 15) == 0) {
            action.accept(ChunkPos.pack(chunkX, chunkZ - 1));
        } else if ((z & 15) == 15) {
            action.accept(ChunkPos.pack(chunkX, chunkZ + 1));
        }
    }

    private static void arrived(ServerLevel level, long chunk) {
        Waits waits = LEVELS.get(level);
        if (waits != null && waits.byChunk.containsKey(chunk)) {
            waits.arrived.add(chunk);
        }
    }

    private static void left(ServerLevel level, long chunk) {
        Waits waits = LEVELS.get(level);
        if (waits != null && !waits.arrived.isEmpty()) {
            waits.arrived.remove(chunk);
        }
    }

    // Inside the chunk events the chunk may not be readable yet; a real load finishes later, so wait for it here.
    private static void tickLevel(ServerLevel level) {
        Waits waits = LEVELS.get(level);
        if (waits == null || waits.arrived.isEmpty()) {
            return;
        }
        LongIterator chunks = waits.arrived.iterator();
        while (chunks.hasNext()) {
            long chunk = chunks.nextLong();
            if (level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)) == null) {
                continue;
            }
            chunks.remove();
            LongArrayList conduits = waits.byChunk.get(chunk);
            if (conduits == null) {
                continue;
            }
            for (int i = 0; i < conduits.size(); i++) {
                long pos = conduits.getLong(i);
                int x = BlockPos.getX(pos);
                int z = BlockPos.getZ(pos);
                if (ConduitBlockEntity.isChunkPresent(level, x, z)
                        && level.getBlockEntity(BlockPos.of(pos)) instanceof ConduitBlockEntity conduit
                        && !conduit.isRemoved()) {
                    conduit.neighbourChunkArrived();
                }
            }
        }
    }

    @FunctionalInterface
    private interface ChunkAction {
        void accept(long chunk);
    }

    private static final class Waits {
        final Long2ObjectOpenHashMap<LongArrayList> byChunk = new Long2ObjectOpenHashMap<>();
        // Chunks of byChunk that reached full status and are waiting to be readable.
        final LongOpenHashSet arrived = new LongOpenHashSet();
    }
}
