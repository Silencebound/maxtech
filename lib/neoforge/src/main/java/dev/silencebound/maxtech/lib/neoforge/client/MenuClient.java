package dev.silencebound.maxtech.lib.neoforge.client;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.client.menu.FacePreviewRenderer;
import dev.silencebound.maxtech.lib.client.menu.FacePreviewState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent;

/** Client registrations of the toolkit menus' screens: the block preview of faces nodes. */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID, value = Dist.CLIENT)
final class MenuClient {
    private MenuClient() {
    }

    @SubscribeEvent
    static void registerPictures(RegisterPictureInPictureRenderersEvent event) {
        event.register(FacePreviewState.class, FacePreviewRenderer::new);
    }
}
