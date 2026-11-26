package dev.silencebound.maxtech.storage.neoforge;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.terminal.export.ExportOutputs;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Forgets a player's export cooldowns ({@link ExportOutputs}) when they log out, so the maps hold only players on the
 * server. Server side; no client classes.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class ExportLogout {
    private ExportLogout() {
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ExportOutputs.forget(event.getEntity().getUUID());
    }
}
