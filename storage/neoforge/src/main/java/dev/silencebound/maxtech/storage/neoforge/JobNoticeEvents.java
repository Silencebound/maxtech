package dev.silencebound.maxtech.storage.neoforge;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.job.JobNotices;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Crafting job notices across logins (B09 item 21d): a player who joins gets the current notice of each of their jobs,
 * so the jobs tab badge is right; one who leaves is forgotten ({@link JobNotices}). Server side; no client classes.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class JobNoticeEvents {
    private JobNoticeEvents() {
    }

    @SubscribeEvent
    static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            JobNotices.loggedIn(player);
        }
    }

    @SubscribeEvent
    static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            JobNotices.loggedOut(player);
        }
    }
}
