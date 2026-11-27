package dev.silencebound.maxtech.farms.neoforge;

import dev.silencebound.maxtech.farms.MaxtechFarms;
import dev.silencebound.maxtech.farms.registry.FarmsContent;
import dev.silencebound.maxtech.farms.sampler.SamplerItem;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Loader glue for the sampler: FE charging, sampling ahead of a mob's own interaction, the creative tab. */
@EventBusSubscriber(modid = MaxtechFarms.MOD_ID)
final class SamplerSetup {
    private SamplerSetup() {
    }

    @SubscribeEvent
    static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerItem(Capabilities.Energy.ITEM, (stack, access) -> new SamplerEnergyHandler(access),
                FarmsContent.SAMPLER.get());
    }

    @SubscribeEvent
    static void buildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(CreativeModeTabs.TOOLS_AND_UTILITIES)) {
            event.accept(FarmsContent.SAMPLER.get());
        }
    }

    /**
     * Villagers trade and horses mount on use; once sampling starts it wins over that. A refused sample passes, so the
     * entity's own interaction still happens.
     */
    @SubscribeEvent
    static void interactEntity(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getItemStack().getItem() instanceof SamplerItem)) {
            return;
        }
        InteractionResult result = SamplerItem.startSampling(event.getEntity(), event.getHand(), event.getTarget(),
                true);
        if (result.consumesAction()) {
            event.setCancellationResult(result);
            event.setCanceled(true);
        }
    }
}
