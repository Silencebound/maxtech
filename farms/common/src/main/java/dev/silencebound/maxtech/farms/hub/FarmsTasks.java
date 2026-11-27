package dev.silencebound.maxtech.farms.hub;

import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import dev.silencebound.maxtech.lib.api.schedule.TaskSchedulers;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;

/**
 * Farms' task group in each server level, made on first use with Lib's per-owner budgets. A group closes with its
 * server; a closed one is replaced, and levels that are gone drop out of the map. Server thread only.
 */
public final class FarmsTasks {
    private static final Map<ServerLevel, TaskGroup> GROUPS = new WeakHashMap<>();

    private FarmsTasks() {
    }

    /** {@code level}'s Farms group. */
    public static TaskGroup group(ServerLevel level) {
        TaskGroup group = GROUPS.get(level);
        if (group == null || group.isClosed()) {
            group = TaskSchedulers.newGroup(level);
            GROUPS.put(level, group);
        }
        return group;
    }
}
