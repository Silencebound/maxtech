package dev.silencebound.maxtech.lib.machine;

import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import dev.silencebound.maxtech.lib.platform.LifecycleEvents;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.schedule.LevelTasks;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The machine group of each level: every {@code MachineProcess} without a group of its owner's runs here, with Lib's
 * per-owner budgets. A level's group closes when the level unloads; every entry goes when the server stops. Started
 * processes are kept here too, so a datapack reload wakes each of them to look its recipe up again. Server thread only.
 */
public final class MachineTasks {
    private static final Reference2ObjectOpenHashMap<ServerLevel, TaskGroup> GROUPS = new Reference2ObjectOpenHashMap<>();
    private static final ReferenceOpenHashSet<LibMachineProcess> STARTED = new ReferenceOpenHashSet<>();

    private MachineTasks() {
    }

    /** Hooks the groups into the server's lifecycle; called once from Lib's init. */
    public static void bootstrap() {
        LifecycleEvents lifecycle = Platform.get().lifecycle();
        lifecycle.onServerStopped(MachineTasks::clear);
        lifecycle.onServerLevelUnloaded(MachineTasks::unload);
        lifecycle.onServerTagsReloaded(MachineTasks::reloaded);
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

    static void started(LibMachineProcess process) {
        STARTED.add(process);
    }

    static void stopped(LibMachineProcess process) {
        STARTED.remove(process);
    }

    /** A datapack reload (Lib's tags-reloaded hook) may add or remove recipes: every started process looks again. */
    public static void reloaded() {
        for (LibMachineProcess process : STARTED) {
            process.reloaded();
        }
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
        STARTED.clear();
    }
}
