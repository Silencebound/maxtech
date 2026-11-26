package dev.silencebound.maxtech.storage.machine;

import dev.silencebound.maxtech.lib.schedule.LevelTasks;
import dev.silencebound.maxtech.lib.schedule.TaskGroup;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;

/**
 * One Lib task group per level for every Storage machine in it, with the configured per-owner budget: no machine ticks
 * on its own, a working one runs each tick and an idle one waits for an event (PRD 7.2, budget scheduler). A level that
 * comes back after a server restart is a new object and gets a new group. Server thread only.
 */
public final class MachineTasks {
    private static final Map<ServerLevel, TaskGroup> GROUPS = new WeakHashMap<>();

    private MachineTasks() {
    }

    /** The group for {@code level}, created on first use. */
    public static TaskGroup group(ServerLevel level) {
        TaskGroup group = GROUPS.get(level);
        if (group == null || group.isClosed()) {
            group = LevelTasks.newGroup(level);
            GROUPS.put(level, group);
        }
        return group;
    }
}
