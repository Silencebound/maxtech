package dev.silencebound.maxtech.lib.network;

import dev.silencebound.maxtech.lib.api.network.UnloadedChunkMode;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.platform.LifecycleEvents;
import dev.silencebound.maxtech.lib.platform.Platform;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * The per-level entry point to network topology. Node owners report their nodes here: add when placed or loaded, remove
 * when broken or unloaded (a block entity's {@code setRemoved} covers both), invalidate when their connections change.
 * Changes are queued and applied at the end of the level tick, together with each type's {@link NetworkLogic}; nothing
 * ticks per block. Server thread only.
 * <p>
 * Unloaded chunks need no chunk events: an unloading node removes itself, and the graph sees that its chunk is gone and
 * keeps the neighbours' edges as cut edges into it. In {@code truncate} mode that just shortens the network; in
 * {@code pause} mode a network with cut edges is not ticked. A loading node re-adds itself and heals the cut edges.
 */
public final class NetworkManager {
    /**
     * Nodes a level may walk per tick to settle splits, shared by all types. A performance bound rather than a balance
     * value, so not in config; larger splits simply take a few more ticks, during which the network is not ticked.
     */
    public static final int SPLIT_BUDGET = 4096;

    private static final Reference2ObjectOpenHashMap<ServerLevel, NetworkManager> MANAGERS = new Reference2ObjectOpenHashMap<>();

    private final ServerLevel level;
    private @Nullable NetworkGraph[] graphs = new NetworkGraph[0];
    private int firstType;

    private NetworkManager(ServerLevel level) {
        this.level = level;
    }

    /** Hooks the managers into the level tick; called once from Lib's init. */
    public static void bootstrap() {
        LifecycleEvents lifecycle = Platform.get().lifecycle();
        lifecycle.onServerLevelTickEnd(NetworkManager::tickLevel);
        lifecycle.onServerStopped(NetworkManager::clear);
    }

    public static NetworkManager get(ServerLevel level) {
        NetworkManager manager = MANAGERS.get(level);
        if (manager == null) {
            manager = new NetworkManager(level);
            MANAGERS.put(level, manager);
        }
        return manager;
    }

    public void addNode(NetworkType type, BlockPos pos) {
        graph(type).add(pos.asLong());
    }

    public void removeNode(NetworkType type, BlockPos pos) {
        graph(type).remove(pos.asLong());
    }

    public void invalidateNode(NetworkType type, BlockPos pos) {
        graph(type).invalidate(pos.asLong());
    }

    /**
     * The anchor hook (PRD 5.2): an anchored node keeps its network ticking in {@code pause} mode while
     * {@code networks.allowAnchors} is on. Chunk loading the network's {@link Network#chunks()} is up to the anchor
     * block. The flag lives on the node, so the anchor sets it again after its chunk reloads.
     */
    public void setAnchored(NetworkType type, BlockPos pos, boolean anchored) {
        graph(type).setAnchored(pos.asLong(), anchored);
    }

    /** The network of the node at {@code pos}, or {@code null} if there is none or it was added this tick. */
    public @Nullable Network network(NetworkType type, BlockPos pos) {
        return graph(type).network(pos.asLong());
    }

    public NetworkGraph graph(NetworkType type) {
        int index = type.index();
        if (index >= graphs.length) {
            @Nullable
            NetworkGraph[] grown = new NetworkGraph[index + 1];
            System.arraycopy(graphs, 0, grown, 0, graphs.length);
            graphs = grown;
        }
        NetworkGraph graph = graphs[index];
        if (graph == null) {
            graph = type.createGraph(level);
            graphs[index] = graph;
        }
        return graph;
    }

    private void tick() {
        UnloadedChunkMode mode = ServerConfig.UNLOADED_CHUNK_MODE.get();
        boolean allowAnchors = ServerConfig.ALLOW_ANCHORS.getAsBoolean();
        int budget = SPLIT_BUDGET;
        int count = graphs.length;
        // Start with a different type each tick so the split budget is shared fairly.
        for (int n = 0; n < count; n++) {
            NetworkGraph graph = graphs[(firstType + n) % count];
            if (graph != null) {
                budget = Math.max(0, graph.tick(mode, allowAnchors, budget));
            }
        }
        if (count > 0) {
            firstType = (firstType + 1) % count;
        }
    }

    private static void tickLevel(ServerLevel level) {
        NetworkManager manager = MANAGERS.get(level);
        if (manager != null) {
            manager.tick();
        }
    }

    private static void clear(MinecraftServer server) {
        MANAGERS.clear();
    }
}
