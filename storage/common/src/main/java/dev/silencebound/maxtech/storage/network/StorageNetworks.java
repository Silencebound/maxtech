package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.network.Network;
import dev.silencebound.maxtech.lib.network.NetworkManager;
import dev.silencebound.maxtech.lib.network.NetworkType;
import dev.silencebound.maxtech.lib.network.NodeConnectivity;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * The storage network type on Lib's graph: storage cables and storage devices are its nodes, linked by
 * {@link StorageConnectivity}, and {@link StorageNetworkLogic} keeps each network's index, energy, routes and
 * scheduler.
 */
public final class StorageNetworks {
    public static final Identifier ID = MaxtechStorage.id("storage");
    public static final NetworkType TYPE = register(ID, UnaryOperator.identity());

    private StorageNetworks() {
    }

    /**
     * Registers a storage network type under {@code id}, its connectivity passed through {@code connectivity}; the game
     * has one ({@link #TYPE}), GameTests add their own to simulate unloaded chunks. During mod construction.
     */
    public static NetworkType register(Identifier id, UnaryOperator<NodeConnectivity> connectivity) {
        return NetworkType.register(id, level -> connectivity.apply(new StorageConnectivity(level, id)),
                level -> new StorageNetworkLogic(level, id));
    }

    /** The logic of storage network type {@code id} in {@code level}; null if no such type is registered. */
    public static @Nullable StorageNetworkLogic logic(ServerLevel level, Identifier id) {
        NetworkType type = NetworkType.byId(id);
        if (type == null) {
            return null;
        }
        return NetworkManager.get(level).graph(type).logic() instanceof StorageNetworkLogic logic ? logic : null;
    }

    /**
     * The storage network of the node at {@code pos} in the network type {@code id}, or null when there is no node
     * there yet or it is not of that type. Server thread.
     */
    public static @Nullable NetworkState network(ServerLevel level, Identifier id, BlockPos pos) {
        StorageNetworkLogic logic = logic(level, id);
        NetworkType type = NetworkType.byId(id);
        if (logic == null || type == null) {
            return null;
        }
        Network network = NetworkManager.get(level).network(type, pos);
        return network == null ? null : logic.state(network);
    }

    /** Loads the class during mod construction, so the type is registered before any node can load. */
    public static void init() {
    }
}
