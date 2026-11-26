package dev.silencebound.maxtech.lib.energy;

import dev.silencebound.maxtech.lib.api.energy.EnergyNetworks;
import dev.silencebound.maxtech.lib.api.energy.EnergyScheduler;
import dev.silencebound.maxtech.lib.platform.LifecycleEvents;
import dev.silencebound.maxtech.lib.platform.Platform;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Owns one {@link EnergyScheduler} per server level and ticks it at the end of the level tick, after the task
 * scheduler, so energy passes see the declarations every block entity and task made this tick (MLE). Schedulers die
 * with the server. Server thread only; a tick with no network to run costs one map lookup and two compares.
 */
public final class LevelEnergy {
    private static final Reference2ObjectOpenHashMap<ServerLevel, EnergyScheduler> LEVELS = new Reference2ObjectOpenHashMap<>();

    private LevelEnergy() {
    }

    /** Hooks the schedulers into the level tick and {@link EnergyNetworks}; called once from Lib's init. */
    public static void bootstrap() {
        EnergyNetworks.setLevelSource(LevelEnergy::of);
        LifecycleEvents lifecycle = Platform.get().lifecycle();
        lifecycle.onServerLevelTickEnd(LevelEnergy::tickLevel);
        lifecycle.onServerStopped(LevelEnergy::clear);
    }

    /** The level's energy scheduler, made on first use. */
    public static EnergyScheduler of(ServerLevel level) {
        EnergyScheduler scheduler = LEVELS.get(level);
        if (scheduler == null) {
            scheduler = new EnergyScheduler(level::getGameTime);
            LEVELS.put(level, scheduler);
        }
        return scheduler;
    }

    private static void tickLevel(ServerLevel level) {
        EnergyScheduler scheduler = LEVELS.get(level);
        if (scheduler != null) {
            scheduler.tick();
        }
    }

    private static void clear(MinecraftServer server) {
        LEVELS.clear();
    }
}
