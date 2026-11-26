package dev.silencebound.maxtech.storage.neoforge;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.controller.module.AnchorChunks;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.common.world.chunk.TicketHelper;
import net.neoforged.neoforge.common.world.chunk.TicketSet;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Loader glue for network anchors: NeoForge's ticket controller {@code maxtech_storage:network_anchor}, whose tickets
 * are owned by the anchoring controller core's position, and the hook {@link AnchorChunks} forces chunks through.
 * NeoForge saves the tickets with the level; when it loads them again, {@link #validate} hands them to
 * {@link AnchorChunks#seed} (so the core can drop what it no longer wants), or drops them all when anchors are off. It
 * also drives {@link AnchorChunks#serverTick} (the sweep of unclaimed tickets after a restart, and the re-check after a
 * reload of Storage's or Lib's config, which hold the chunk cap and {@code networks.allowAnchors}).
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class AnchorTickets {
    static final TicketController CONTROLLER = new TicketController(MaxtechStorage.id("network_anchor"),
            AnchorTickets::validate);

    private AnchorTickets() {
    }

    @SubscribeEvent
    static void register(RegisterTicketControllersEvent event) {
        event.register(CONTROLLER);
        // Chunks keep no mobs spawning: an anchored network is machinery, not a farm.
        AnchorChunks.install((level, owner, x, z, add) -> CONTROLLER.forceChunk(level, owner, x, z, add, false));
        ModList.get().getModContainerById(MaxtechLib.MOD_ID).map(ModContainer::getEventBus)
                .ifPresent(bus -> bus.addListener(ModConfigEvent.Reloading.class, e -> AnchorChunks.configChanged()));
    }

    @SubscribeEvent
    static void reloading(ModConfigEvent.Reloading event) {
        AnchorChunks.configChanged();
    }

    @SubscribeEvent
    static void serverTick(ServerTickEvent.Post event) {
        AnchorChunks.serverTick();
    }

    private static void validate(ServerLevel level, TicketHelper helper) {
        boolean enabled;
        try {
            enabled = AnchorChunks.cap() > 0 && ServerConfig.ALLOW_ANCHORS.getAsBoolean();
        } catch (IllegalStateException e) {
            // Server config not loaded yet: keep the tickets; the cores trim them once they run.
            enabled = true;
        }
        for (Map.Entry<BlockPos, TicketSet> entry : helper.getBlockTickets().entrySet()) {
            if (enabled) {
                AnchorChunks.seed(level, entry.getKey(), entry.getValue().normal());
            } else {
                helper.removeAllTickets(entry.getKey());
            }
        }
    }
}
