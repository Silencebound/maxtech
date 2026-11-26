package dev.silencebound.maxtech.lib.network;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

/**
 * One connected set of nodes of one type in one level. Owned and mutated by its {@link NetworkGraph}; everything public
 * here is a read-only view for {@link NetworkLogic}. Server thread only.
 */
public final class Network {
    private final int id;
    final LongOpenHashSet nodes = new LongOpenHashSet();
    private final LongSet nodesView = LongSets.unmodifiable(nodes);
    /** Chunk -> number of this network's nodes in it; kept current instead of rebuilt so anchors can read it. */
    final Long2IntOpenHashMap nodeChunks = new Long2IntOpenHashMap();
    private final LongSet nodeChunksView = LongSets.unmodifiable(nodeChunks.keySet());
    /** Chunk -> number of edges from this network into it that were cut because it is unloaded. */
    final Long2IntOpenHashMap unloadedChunks = new Long2IntOpenHashMap();
    private final LongSet unloadedChunksView = LongSets.unmodifiable(unloadedChunks.keySet());
    int anchors;
    boolean alive = true;
    boolean paused;
    boolean dirty;
    @Nullable
    SplitJob split;
    /** Index in the graph's network list, for O(1) removal. */
    int index = -1;
    long lastTickNanos = -1;
    int lastTickEpoch;

    Network(int id) {
        this.id = id;
    }

    /** Unique among the live and past networks of its graph; never reused while the level is loaded. */
    public int id() {
        return id;
    }

    public int size() {
        return nodes.size();
    }

    public boolean contains(long pos) {
        return nodes.contains(pos);
    }

    /** Packed positions of every node; a live view. */
    public LongSet nodes() {
        return nodesView;
    }

    /** Packed {@link ChunkPos} of every chunk holding one of this network's nodes; a live view. */
    public LongSet chunks() {
        return nodeChunksView;
    }

    /**
     * Packed {@link ChunkPos} of unloaded chunks this network has edges into; a live view. Non-empty means the network
     * is truncated there, or paused in {@code pause} mode.
     */
    public LongSet unloadedChunks() {
        return unloadedChunksView;
    }

    /** Whether at least one node carries an anchor (see {@link NetworkManager#setAnchored}). */
    public boolean isAnchored() {
        return anchors > 0;
    }

    /** Whether the last tick skipped this network because it touches an unloaded chunk in {@code pause} mode. */
    public boolean isPaused() {
        return paused;
    }

    /** Whether a removal may have split this network and the check has not finished; such networks are not ticked. */
    public boolean isSettling() {
        return split != null;
    }

    /**
     * Nanoseconds its {@link NetworkLogic#tick} took the last time it ran while {@link NetworkGraph#setProfiling
     * profiling} was on; -1 while profiling is off and until it has been timed since profiling was switched.
     */
    public long lastTickNanos() {
        return NetworkGraph.profiledNanos(lastTickNanos, lastTickEpoch);
    }

    /** False once the network was merged into another, split to nothing or lost its last node. */
    public boolean isAlive() {
        return alive;
    }

    void addNode(Node node) {
        nodes.add(node.pos);
        nodeChunks.addTo(chunkOf(node.pos), 1);
        if (node.anchor) {
            anchors++;
        }
        LongArrayList unloaded = node.unloaded;
        if (unloaded != null) {
            for (int i = 0; i < unloaded.size(); i++) {
                unloadedChunks.addTo(chunkOf(unloaded.getLong(i)), 1);
            }
        }
    }

    void removeNode(Node node) {
        nodes.remove(node.pos);
        decrement(nodeChunks, chunkOf(node.pos));
        if (node.anchor) {
            anchors--;
        }
        LongArrayList unloaded = node.unloaded;
        if (unloaded != null) {
            for (int i = 0; i < unloaded.size(); i++) {
                decrement(unloadedChunks, chunkOf(unloaded.getLong(i)));
            }
        }
    }

    static void decrement(Long2IntOpenHashMap counts, long key) {
        if (counts.addTo(key, -1) == 1) {
            counts.remove(key);
        }
    }

    /**
     * The packed {@link ChunkPos} holding {@code pos}. Same packing as {@code ChunkPos.pack(int, int)}, spelled out
     * because {@code ChunkPos}'s static initialiser needs a bootstrapped game, which unit tests don't have.
     */
    static long chunkOf(long pos) {
        long x = SectionPos.blockToSectionCoord(BlockPos.getX(pos));
        long z = SectionPos.blockToSectionCoord(BlockPos.getZ(pos));
        return (x & 0xFFFFFFFFL) | ((z & 0xFFFFFFFFL) << 32);
    }

    @Override
    public String toString() {
        return "Network[" + id + ", " + nodes.size() + " nodes]";
    }
}
