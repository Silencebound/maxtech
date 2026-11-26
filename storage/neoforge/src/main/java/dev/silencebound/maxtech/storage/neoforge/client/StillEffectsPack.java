package dev.silencebound.maxtech.storage.neoforge.client;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageClientConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddPackFindersEvent;

/**
 * With the client config {@code visuals.effects} off, adds Storage's built-in resource pack
 * {@code resourcepacks/still_effects} as a required pack on top: it replaces each animated strip (the controller face's
 * glow; every other block is static since MTX) with its first frame and no animation metadata, so the blocks draw
 * still. A texture's {@code .mcmeta} is read only from the pack that supplies the texture or a pack above it, so the
 * strips' animation in the mod's own resources no longer applies. Client config is loaded before the pack repository is
 * built, so the switch takes effect at the next start.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID, value = Dist.CLIENT)
final class StillEffectsPack {
    private StillEffectsPack() {
    }

    @SubscribeEvent
    static void addPack(AddPackFindersEvent event) {
        if (StorageClientConfig.effects()) {
            return;
        }
        event.addPackFinders(MaxtechStorage.id("resourcepacks/still_effects"), PackType.CLIENT_RESOURCES,
                Component.literal("MaxTech Storage: still effects"), PackSource.BUILT_IN, true, Pack.Position.TOP);
    }
}
