package dev.silencebound.maxtech.lib.transfer;

import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import dev.silencebound.maxtech.lib.platform.LifecycleEvents;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.schedule.LevelTasks;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * The started transfer ports of each level by packed position (ADR-0018): a pushing port finds a neighbouring Lib port
 * here, without reading the level, so it can insert into that port's pulling buffers and keep the rules between two
 * ports. A port is in it from {@code start} to {@code stop}. Each level also has Lib's transfer group, where ports run
 * unless their owner gives them a group of its own ({@code Builder.tasks}). A level's entry goes when the level unloads
 * and every entry when the server stops; after a tag reload the ports forget their filters' cached answers. Server
 * thread only.
 */
public final class PortRegistry {
    private static final Reference2ObjectOpenHashMap<ServerLevel, Long2ObjectOpenHashMap<LibTransferPort>> LEVELS = new Reference2ObjectOpenHashMap<>();
    private static final Reference2ObjectOpenHashMap<ServerLevel, TaskGroup> GROUPS = new Reference2ObjectOpenHashMap<>();

    private PortRegistry() {
    }

    /** Hooks the registry into the server's lifecycle; called once from Lib's init. */
    public static void bootstrap() {
        LifecycleEvents lifecycle = Platform.get().lifecycle();
        lifecycle.onServerStopped(PortRegistry::clear);
        lifecycle.onServerLevelUnloaded(PortRegistry::unload);
        lifecycle.onServerTagsReloaded(PortRegistry::tagsReloaded);
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

    /**
     * Lib's transfer group of {@code level}, with the configured per-owner budgets: every port without a group of its
     * owner's shares it. Made on first use, and again if it was closed.
     */
    public static TaskGroup group(ServerLevel level) {
        TaskGroup group = GROUPS.get(level);
        if (group == null || group.isClosed()) {
            group = LevelTasks.newGroup(level);
            GROUPS.put(level, group);
        }
        return group;
    }

    /** Whether a started port is registered at {@code pos} in {@code level}; for tests. */
    public static boolean has(ServerLevel level, long pos) {
        Long2ObjectOpenHashMap<LibTransferPort> ports = LEVELS.get(level);
        return ports != null && ports.containsKey(pos);
    }

    /** The started port at {@code pos} in {@code level}, or null; for tests. */
    public static @Nullable LibTransferPort get(ServerLevel level, long pos) {
        Long2ObjectOpenHashMap<LibTransferPort> ports = LEVELS.get(level);
        return ports == null ? null : ports.get(pos);
    }

    /** Whether {@code level} has an entry (ports or Lib's group); for tests. */
    public static boolean tracks(ServerLevel level) {
        return LEVELS.containsKey(level) || GROUPS.containsKey(level);
    }

    /** Drops {@code level}'s entry; its ports stopped with their chunks. For the unload event and tests. */
    public static void unload(ServerLevel level) {
        LEVELS.remove(level);
        TaskGroup group = GROUPS.remove(level);
        if (group != null) {
            group.close();
        }
    }

    /** Tags may have changed: every started port forgets what its filters answered (a stopped one does on start). */
    static void tagsReloaded() {
        for (Long2ObjectOpenHashMap<LibTransferPort> ports : LEVELS.values()) {
            for (LibTransferPort port : ports.values()) {
                port.forgetFilterAnswers();
            }
        }
    }

    private static void clear(MinecraftServer server) {
        LEVELS.clear();
        GROUPS.clear();
    }
}
