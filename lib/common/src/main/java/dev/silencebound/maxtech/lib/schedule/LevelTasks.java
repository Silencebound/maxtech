package dev.silencebound.maxtech.lib.schedule;

import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.platform.LifecycleEvents;
import dev.silencebound.maxtech.lib.platform.Platform;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Owns one {@link TaskScheduler} per level and runs it from the level tick event, after the network manager. Entry
 * point for owners that run budgeted work in a level: {@code LevelTasks.newGroup(level)} gives a group with the
 * configured budgets ({@code scheduler.ownerBudgetMicros}, {@code scheduler.ownerOpsPerTick}). Schedulers and their
 * groups die with the server; an owner makes a new group when its level loads again. Server thread only; the tick
 * allocates nothing.
 */
public final class LevelTasks {
    private static final Reference2ObjectOpenHashMap<ServerLevel, LevelTasks> LEVELS = new Reference2ObjectOpenHashMap<>();
    private static boolean profiling;

    private final TaskScheduler tasks = new TaskScheduler(System::nanoTime, ServerConfig.SCHEDULER_MAX_BACKOFF_TICKS);
    private long lastTickNanos = -1;

    private LevelTasks() {
    }

    /** Hooks the schedulers into the level tick; called once from Lib's init, after the network manager's. */
    public static void bootstrap() {
        LifecycleEvents lifecycle = Platform.get().lifecycle();
        lifecycle.onServerLevelTickEnd(LevelTasks::tickLevel);
        lifecycle.onServerStopped(LevelTasks::clear);
    }

    /** The level's task scheduler, made on first use. */
    public static TaskScheduler of(ServerLevel level) {
        return forLevel(level).tasks;
    }

    /** A group in {@code level} with the configured per-owner budgets, read on each pass. */
    public static TaskGroup newGroup(ServerLevel level) {
        return of(level).newGroup(() -> ServerConfig.SCHEDULER_OWNER_BUDGET_MICROS.getAsInt() * 1000L,
                ServerConfig.SCHEDULER_OWNER_OPS_PER_TICK);
    }

    /**
     * Turns tick timing on or off for every level's scheduler; off by default, where it costs one static read per level
     * tick. Either way every level's {@link #lastTickNanos} goes back to -1, so a time from an earlier profiling run is
     * never read as a new one. For perf tests and profiling.
     */
    public static void setProfiling(boolean enabled) {
        profiling = enabled;
        for (LevelTasks level : LEVELS.values()) {
            level.lastTickNanos = -1;
        }
    }

    /**
     * Nanoseconds the latest profiled tick of {@code level}'s task scheduler took; -1 while profiling is off and until
     * it has timed a tick.
     */
    public static long lastTickNanos(ServerLevel level) {
        return forLevel(level).lastTickNanos;
    }

    private static LevelTasks forLevel(ServerLevel level) {
        LevelTasks owner = LEVELS.get(level);
        if (owner == null) {
            owner = new LevelTasks();
            LEVELS.put(level, owner);
        }
        return owner;
    }

    private static void tickLevel(ServerLevel level) {
        LevelTasks owner = LEVELS.get(level);
        if (owner == null) {
            return;
        }
        if (!profiling) {
            owner.tasks.tick(level.getGameTime());
            return;
        }
        long start = System.nanoTime();
        owner.tasks.tick(level.getGameTime());
        owner.lastTickNanos = System.nanoTime() - start;
    }

    private static void clear(MinecraftServer server) {
        LEVELS.clear();
    }
}
