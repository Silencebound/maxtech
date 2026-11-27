package dev.silencebound.maxtech.farms.neoforge.client;

import dev.silencebound.maxtech.farms.MaxtechFarms;
import dev.silencebound.maxtech.farms.sampler.SamplerItem;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/** A small bar under the crosshair that fills while the player holds the sampler on a mob. Client only. */
@EventBusSubscriber(modid = MaxtechFarms.MOD_ID, value = Dist.CLIENT)
final class SamplerProgressLayer {
    private static final int WIDTH = 24;
    private static final int HEIGHT = 3;
    private static final int OFFSET_Y = 10;
    private static final int BACKGROUND = 0x80000000;
    private static final int FILL = 0xFF000000 | SamplerItem.BAR_COLOR;

    private SamplerProgressLayer() {
    }

    @SubscribeEvent
    static void registerLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, MaxtechFarms.id("sampler_progress"),
                SamplerProgressLayer::render);
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.options.hideGui || !player.isUsingItem()
                || !(player.getUseItem().getItem() instanceof SamplerItem)) {
            return;
        }
        float progress = Mth.clamp(
                player.getTicksUsingItem(delta.getGameTimeDeltaPartialTick(false)) / SamplerItem.USE_TICKS, 0, 1);
        int left = (graphics.guiWidth() - WIDTH) / 2;
        int top = graphics.guiHeight() / 2 + OFFSET_Y;
        graphics.fill(left, top, left + WIDTH, top + HEIGHT, BACKGROUND);
        graphics.fill(left, top, left + Math.round(WIDTH * progress), top + HEIGHT, FILL);
    }
}
