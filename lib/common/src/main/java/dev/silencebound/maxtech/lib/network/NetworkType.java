package dev.silencebound.maxtech.lib.network;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * A kind of network (Storage's cables, ...). Each level gets one {@link NetworkGraph} per type, built from the
 * factories given at registration. Nodes of different types never connect, so conduits of different kinds side by side
 * stay in separate networks.
 */
public final class NetworkType {
    private static final ObjectArrayList<NetworkType> TYPES = new ObjectArrayList<>();

    private final Identifier id;
    private final int index;
    private final Function<ServerLevel, NodeConnectivity> connectivity;
    private final Function<ServerLevel, NetworkLogic> logic;

    private NetworkType(Identifier id, int index, Function<ServerLevel, NodeConnectivity> connectivity,
            Function<ServerLevel, NetworkLogic> logic) {
        this.id = id;
        this.index = index;
        this.connectivity = connectivity;
        this.logic = logic;
    }

    /**
     * Registers a type. Call during mod construction; mods construct in parallel, hence the lock. The factories run the
     * first time a level sees a node of this type.
     */
    public static synchronized NetworkType register(Identifier id, Function<ServerLevel, NodeConnectivity> connectivity,
            Function<ServerLevel, NetworkLogic> logic) {
        for (int i = 0; i < TYPES.size(); i++) {
            if (TYPES.get(i).id.equals(id)) {
                throw new IllegalArgumentException("Network type " + id + " is already registered");
            }
        }
        NetworkType type = new NetworkType(id, TYPES.size(), connectivity, logic);
        TYPES.add(type);
        return type;
    }

    /**
     * The type registered under {@code id}, or {@code null} if there is none (e.g. a conduit type whose network logic
     * is not registered yet).
     */
    public static synchronized @Nullable NetworkType byId(Identifier id) {
        for (int i = 0; i < TYPES.size(); i++) {
            NetworkType type = TYPES.get(i);
            if (type.id.equals(id)) {
                return type;
            }
        }
        return null;
    }

    public Identifier id() {
        return id;
    }

    /** Dense index in registration order, used to address per-level graphs without hashing. */
    int index() {
        return index;
    }

    NetworkGraph createGraph(ServerLevel level) {
        return new NetworkGraph(connectivity.apply(level), logic.apply(level));
    }

    @Override
    public String toString() {
        return "NetworkType[" + id + "]";
    }
}
