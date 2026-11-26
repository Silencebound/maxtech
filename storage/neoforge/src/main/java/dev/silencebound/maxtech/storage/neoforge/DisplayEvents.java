package dev.silencebound.maxtech.storage.neoforge;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.display.DisplayTerminal;
import dev.silencebound.maxtech.storage.display.DisplayViewers;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;

/**
 * Hands chunk tracking to the display screens' viewer lists: a chunk counts as seen once it was sent to the player's
 * client (not when queued, so a screen's first update finds the client's block entity), and unseen when the player
 * stops tracking it. A player who logs out leaves the viewer lists and the display terminal's click memory.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class DisplayEvents {
    private DisplayEvents() {
    }

    @SubscribeEvent
    static void sent(ChunkWatchEvent.Sent event) {
        DisplayViewers.watch(event.getLevel(), event.getPlayer(), event.getPos().pack());
    }

    /** Unwatch events after a dimension change may name the new level; drop the player from the old ones. */
    @SubscribeEvent
    static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DisplayViewers.forget(player, player.level());
        }
    }

    @SubscribeEvent
    static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DisplayViewers.forget(player, null);
            DisplayTerminal.forget(player.getUUID());
        }
    }

    @SubscribeEvent
    static void unwatch(ChunkWatchEvent.UnWatch event) {
        DisplayViewers.unwatch(event.getLevel(), event.getPlayer(), event.getPos().pack());
    }
}
