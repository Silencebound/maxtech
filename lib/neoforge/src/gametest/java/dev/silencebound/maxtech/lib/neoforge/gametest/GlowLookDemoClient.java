package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.client.resource.ResourceLook;
import dev.silencebound.maxtech.lib.api.client.resource.ResourceLooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * The client look of the test kind {@link ConduitApiGameTests#TEST_KIND}, so the demo tube can show a kind that is not
 * a fluid ({@code /maxtech_lib_demo tube <pos> glow <value> <fill> ...}): glowstone's sprite with no flowing sprite of
 * its own, a tint by tens of the value, and light rising with it. Part of the GameTest source set, so dev runs only.
 */
@EventBusSubscriber(modid = ConduitApiGameTests.MOD_ID, value = Dist.CLIENT)
public final class GlowLookDemoClient {
    private static final Identifier SPRITE = Identifier.withDefaultNamespace("block/glowstone");
    private static final int[] TINTS = {0xFFFFFFFF, 0xFFFF8040, 0xFF60C0FF, 0xFF80FF80};

    private GlowLookDemoClient() {
    }

    @SubscribeEvent
    static void register(FMLClientSetupEvent event) {
        ResourceLooks.register(ConduitApiGameTests.TEST_KIND, new ResourceLook<>() {
            @Override
            public TextureAtlasSprite sprite(Integer glow) {
                return Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS).getSprite(SPRITE);
            }

            @Override
            public int tint(Integer glow) {
                return TINTS[Math.floorMod(glow / 10, TINTS.length)];
            }

            @Override
            public Component name(Integer glow) {
                return Component.literal("Glow " + glow);
            }

            @Override
            public int lightEmission(Integer glow) {
                return glow / 10;
            }
        });
    }
}
