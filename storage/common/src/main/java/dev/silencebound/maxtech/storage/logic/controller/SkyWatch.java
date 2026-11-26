package dev.silencebound.maxtech.storage.logic.controller;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.logic.WatchKind;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.WorldClock;

/**
 * One day/night and weather check per level, shared by every Logic Controller whose graphs watch them (the daytime and
 * weather nodes). Sleeping and {@code /time set} move the clock and {@code /weather} the weather without an event
 * common code can see, so the level is looked at once every {@link #INTERVAL} ticks, and only while some controller
 * listens: a level without listeners costs one map lookup per tick. Listeners hear only a change. Server thread only.
 */
public final class SkyWatch {
    /** Ticks between two looks at a level with listeners. */
    public static final int INTERVAL = 20;
    /** Ticks in a Minecraft day. */
    public static final long DAY_TICKS = 24_000;

    /** Told after a look found the day or the weather changed; it only marks work and wakes its task. */
    public interface Listener {
        void skyChanged(boolean dayChanged, boolean weatherChanged);
    }

    private static final Reference2ObjectOpenHashMap<ServerLevel, SkyWatch> LEVELS = new Reference2ObjectOpenHashMap<>();

    private final ObjectArrayList<Listener> listeners = new ObjectArrayList<>();
    private boolean day;
    private int weather;

    private SkyWatch(ServerLevel level) {
        day = level.isBrightOutside();
        weather = weather(level);
    }

    /** Hooks the check into the level tick; called once from {@link LogicContent#init()}. */
    static void init() {
        Platform.get().lifecycle().onServerLevelTickEnd(SkyWatch::tick);
        Platform.get().lifecycle().onServerStopped(server -> LEVELS.clear());
    }

    /** Starts telling {@code listener} about {@code level}; a listener already added is not added twice. */
    // Listeners are compared by identity.
    @SuppressWarnings("ReferenceEquality")
    public static void listen(ServerLevel level, Listener listener) {
        SkyWatch watch = LEVELS.get(level);
        if (watch == null) {
            watch = new SkyWatch(level);
            LEVELS.put(level, watch);
        }
        for (int i = 0; i < watch.listeners.size(); i++) {
            if (watch.listeners.get(i) == listener) {
                return;
            }
        }
        watch.listeners.add(listener);
    }

    /** Stops telling {@code listener} about {@code level}; the level's check stops with its last listener. */
    // Listeners are compared by identity.
    @SuppressWarnings("ReferenceEquality")
    public static void unlisten(ServerLevel level, Listener listener) {
        SkyWatch watch = LEVELS.get(level);
        if (watch == null) {
            return;
        }
        for (int i = 0; i < watch.listeners.size(); i++) {
            if (watch.listeners.get(i) == listener) {
                watch.listeners.remove(i);
                break;
            }
        }
        if (watch.listeners.isEmpty()) {
            LEVELS.remove(level);
        }
    }

    /** Whether some listener follows {@code level}; for tests. */
    public static boolean watched(ServerLevel level) {
        return LEVELS.containsKey(level);
    }

    private static void tick(ServerLevel level) {
        SkyWatch watch = LEVELS.get(level);
        if (watch == null || level.getGameTime() % INTERVAL != 0) {
            return;
        }
        boolean day = level.isBrightOutside();
        int weather = weather(level);
        boolean dayChanged = day != watch.day;
        boolean weatherChanged = weather != watch.weather;
        if (!dayChanged && !weatherChanged) {
            return;
        }
        watch.day = day;
        watch.weather = weather;
        // Listeners only mark and wake, so none leaves the list while it is walked.
        for (int i = 0; i < watch.listeners.size(); i++) {
            watch.listeners.get(i).skyChanged(dayChanged, weatherChanged);
        }
    }

    /**
     * The {@link WatchKind#DAYTIME} value of {@code level}: the time of day (0 to 23999, from the dimension's default
     * clock; 0 where it has none) and whether it is bright outside. Allocates nothing.
     */
    public static long daytime(ServerLevel level) {
        Optional<Holder<WorldClock>> clock = level.dimensionType().defaultClock();
        long time = clock.isPresent() ? Math.floorMod(level.clockManager().getTotalTicks(clock.get()), DAY_TICKS) : 0;
        return WatchKind.packDaytime(time, level.isBrightOutside());
    }

    /** The {@link WatchKind#WEATHER} value of {@code level}: 2 thunder, 1 rain, 0 clear. */
    public static int weather(ServerLevel level) {
        return level.isThundering() ? 2 : level.isRaining() ? 1 : 0;
    }
}
