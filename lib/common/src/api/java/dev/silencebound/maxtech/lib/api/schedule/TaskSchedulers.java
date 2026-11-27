package dev.silencebound.maxtech.lib.api.schedule;

import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

/**
 * Groups in the {@link TaskScheduler} of each server level, which Lib owns and runs from the level tick after the
 * networks; addons never tick a scheduler themselves. An owner that runs budgeted work in a level gets a group with
 * {@code TaskSchedulers.newGroup(level)}, with Lib's configured per-owner budgets ({@code scheduler.ownerBudgetMicros},
 * {@code scheduler.ownerOpsPerTick}). Schedulers and their groups die with the server; an owner makes a new group when
 * its level loads again. Server thread only.
 */
public final class TaskSchedulers {
    private static volatile Function<ServerLevel, TaskGroup> groups = level -> {
        throw new IllegalStateException("MaxTech Lib has not set up task schedulers yet");
    };

    private TaskSchedulers() {
    }

    /** A new group in {@code level} with Lib's configured per-owner budgets, read on each pass. */
    public static TaskGroup newGroup(ServerLevel level) {
        return groups.apply(level);
    }

    /** Sets where {@link #newGroup} makes a level's groups; Lib calls it once during init. */
    @ApiStatus.Internal
    public static void setLevelSource(Function<ServerLevel, TaskGroup> newGroups) {
        groups = newGroups;
    }
}
