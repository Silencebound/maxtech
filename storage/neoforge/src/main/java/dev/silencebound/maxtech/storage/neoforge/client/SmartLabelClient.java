package dev.silencebound.maxtech.storage.neoforge.client;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.smartlabel.SmartLabelContent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** The smart labels' client glue: registers their block entity renderer. */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID, value = Dist.CLIENT)
final class SmartLabelClient {
    private SmartLabelClient() {
    }

    @SubscribeEvent
    static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(SmartLabelContent.BLOCK_ENTITY.get(), SmartLabelRenderer::new);
    }
}
