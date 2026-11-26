package dev.silencebound.maxtech.storage.neoforge.client;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.terminal.client.TerminalTooltips;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;

/**
 * Drops the terminal's cached tooltip texts on every client resource reload (F3+T, a resource pack change, and a
 * language change, which reloads the resources), so a {@code $} search reads the new texts.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID, value = Dist.CLIENT)
final class TerminalTooltipReload {
    private TerminalTooltipReload() {
    }

    @SubscribeEvent
    static void addListener(AddClientReloadListenersEvent event) {
        event.addListener(MaxtechStorage.id("terminal_tooltips"),
                (ResourceManagerReloadListener) manager -> TerminalTooltips.clear());
    }
}
