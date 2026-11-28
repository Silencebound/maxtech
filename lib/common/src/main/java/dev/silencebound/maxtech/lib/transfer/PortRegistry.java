package dev.silencebound.maxtech.lib.transfer;

import dev.silencebound.maxtech.lib.platform.Platform;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The started transfer ports of each level by packed position (ADR-0018): a pushing port finds a neighbouring Lib port
 * here, without reading the level, so it can insert into that port's pulling buffers and keep the rules between two
 * ports. A port is in it from {@code start} to {@code stop}. Cleared when the server stops. Server thread only.
 */
public final class PortRegistry {
    private static final Reference2ObjectOpenHashMap<ServerLevel, Long2ObjectOpenHashMap<LibTransferPort>> LEVELS = new Reference2ObjectOpenHashMap<>();

    private PortRegistry() {
    }

    /** Hooks the registry into the server's lifecycle; called once from Lib's init. */
    public static void bootstrap() {
        Platform.get().lifecycle().onServerStopped(PortRegistry::clear);
    }

    /** The ports of {@code level}, made on first use; a port keeps it while started, so lookups skip the level map. */
    static Long2ObjectOpenHashMap<LibTransferPort> of(ServerLevel level) {
        Long2ObjectOpenHashMap<LibTransferPort> ports = LEVELS.get(level);
        if (ports == null) {
            ports = new Long2ObjectOpenHashMap<>();
            LEVELS.put(level, ports);
        }
        return ports;
    }

    /** Whether a started port is registered at {@code pos} in {@code level}; for tests. */
    public static boolean has(ServerLevel level, long pos) {
        Long2ObjectOpenHashMap<LibTransferPort> ports = LEVELS.get(level);
        return ports != null && ports.containsKey(pos);
    }

    private static void clear(MinecraftServer server) {
        LEVELS.clear();
    }
}
