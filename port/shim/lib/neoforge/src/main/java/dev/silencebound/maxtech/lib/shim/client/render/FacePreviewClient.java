package dev.silencebound.maxtech.lib.shim.client.render;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.client.menu.FacePreviewRenderer;
import dev.silencebound.maxtech.lib.client.menu.FacePreviewState;
import dev.silencebound.maxtech.lib.shim.client.gui.PictureInPictureRenderers;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Registers the faces node's block preview with the gui shim's picture-in-picture drawers, where 26.1's
 * {@code MenuClient} registers {@link FacePreviewRenderer} in {@code RegisterPictureInPictureRenderersEvent} (which
 * 1.21.1 lacks, so the port removes {@code MenuClient}). Client only.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID, value = Dist.CLIENT)
public final class FacePreviewClient {
    private FacePreviewClient() {
    }

    @SubscribeEvent
    static void setup(FMLClientSetupEvent event) {
        FacePreviewRenderer renderer = new FacePreviewRenderer(Minecraft.getInstance().renderBuffers().bufferSource());
        PictureInPictureRenderers.register(FacePreviewState.class, renderer::draw);
    }
}
