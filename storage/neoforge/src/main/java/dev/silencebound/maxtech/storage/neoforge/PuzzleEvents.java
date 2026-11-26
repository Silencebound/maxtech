package dev.silencebound.maxtech.storage.neoforge;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleSessions;
import dev.silencebound.maxtech.storage.tool.cartridge.PuzzleCartridgeItem;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Drops a player's open puzzle when they log out, so it neither lingers nor stays submittable after a relog. */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class PuzzleEvents {
    private PuzzleEvents() {
    }

    @SubscribeEvent
    static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        PuzzleSessions.cancel(event.getEntity());
        PuzzleCartridgeItem.forget(event.getEntity().getUUID());
    }
}
