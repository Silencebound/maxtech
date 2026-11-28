package dev.silencebound.maxtech.lib.machine;

import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import dev.silencebound.maxtech.lib.platform.LifecycleEvents;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.schedule.LevelTasks;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The machine group of each level: every {@code MachineProcess} without a group of its owner's runs here, with Lib's
 * per-owner budgets. A level's group closes when the level unloads; every entry goes when the server stops. Server
 * thread only.
 */
public final class MachineTasks {
    private static final Reference2ObjectOpenHashMap<ServerLevel, TaskGroup> GROUPS = new Reference2ObjectOpenHashMap<>();

    private MachineTasks() {
    }

    /** Hooks the groups into the server's lifecycle; called once from Lib's init. */
    public static void bootstrap() {
        LifecycleEvents lifecycle = Platform.get().lifecycle();
        lifecycle.onServerStopped(MachineTasks::clear);
        lifecycle.onServerLevelUnloaded(MachineTasks::unload);
    }

    /** The machine group of {@code level}, made on first use and again if it was closed. */
    public static TaskGroup group(ServerLevel level) {
        TaskGroup group = GROUPS.get(level);
        if (group == null || group.isClosed()) {
            group = LevelTasks.newGroup(level);
            GROUPS.put(level, group);
        }
        return group;
    }

    /** Closes {@code level}'s group; its machines stopped with their chunks. */
    static void unload(ServerLevel level) {
        TaskGroup group = GROUPS.remove(level);
        if (group != null) {
            group.close();
        }
    }

    private static void clear(MinecraftServer server) {
        GROUPS.clear();
    }
}
