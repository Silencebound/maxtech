package dev.silencebound.maxtech.storage.neoforge;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.tool.setup.SetupBuild;
import dev.silencebound.maxtech.storage.tool.setup.SetupGhosts;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Loader glue for setup blueprint ghosts (M7C item 21): a block a player placed (last, so a cancelled placement never
 * counts) may fill one of their ghosts; the ghosts are sent on login and dimension change; placed devices waiting for a
 * network and running auto-builds are handled each server tick, which returns at once while there are none. A player's
 * auto-build waits while they are logged out and goes on when they log in.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class SetupEvents {
    private SetupEvents() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void placed(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof Player player && event.getLevel() instanceof ServerLevel level) {
            SetupGhosts.get(level.getServer()).blockPlaced(player, level, event.getPos());
        }
    }

    @SubscribeEvent
    static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity().level() instanceof ServerLevel level) {
            SetupGhosts.get(level.getServer()).prune(level.getServer().overworld().getGameTime());
        }
        // A client that just joined has no ghosts to clear, so only players with saved ghosts need a view.
        if (event.getEntity().level() instanceof ServerLevel level
                && !SetupGhosts.get(level.getServer()).ghosts(event.getEntity().getUUID()).isEmpty()) {
            sync(event.getEntity());
        }
        if (event.getEntity() instanceof ServerPlayer player) {
            SetupBuild.resume(player);
        }
    }

    @SubscribeEvent
    static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SetupBuild.pause(player);
        }
    }

    @SubscribeEvent
    static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        sync(event.getEntity());
    }

    @SubscribeEvent
    static void tick(ServerTickEvent.Post event) {
        SetupGhosts.tick(event.getServer());
        SetupBuild.tick(event.getServer());
    }

    @SubscribeEvent
    static void stopped(ServerStoppedEvent event) {
        SetupGhosts.clearPending();
        SetupBuild.clear();
    }

    private static void sync(Player player) {
        if (player.level() instanceof ServerLevel level) {
            SetupGhosts.get(level.getServer()).sync(player);
        }
    }
}
