package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitBodyKey;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitLayer;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitParts;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitTexture;
import dev.silencebound.maxtech.lib.neoforge.api.client.conduit.ConduitModelHook;
import dev.silencebound.maxtech.lib.neoforge.api.client.conduit.RegisterConduitModelsEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * The model hook of {@link ConduitApiGameTests#GLASS_TUBE}, on the NeoForge api alone as Farms' lab tube will be: Lib's
 * body boxes drawn as vanilla glass, with a slightly smaller translucent core inside in the render value's colour (an
 * RGB; 0 leaves the tube empty). Rings and stubs stay Lib's. Set a tube's value in the dev client with
 * {@code /maxtech_lib_demo tube <pos> <rrggbb>}. Part of the GameTest source set, so it is in dev runs only.
 */
@EventBusSubscriber(modid = ConduitApiGameTests.MOD_ID, value = Dist.CLIENT)
public final class GlassTubeDemoClient {
    private static final Identifier GLASS = Identifier.withDefaultNamespace("block/glass");
    // How far the contents sit inside the glass, in pixels.
    private static final double INSET = 0.5;
    private static final int CONTENTS_ALPHA = 0xB0;

    private GlassTubeDemoClient() {
    }

    @SubscribeEvent
    static void register(RegisterConduitModelsEvent event) {
        event.register(ConduitApiGameTests.GLASS_TUBE, new ConduitModelHook() {
            @Override
            public Set<Identifier> sprites() {
                return Set.of(GLASS);
            }

            @Override
            public List<ConduitQuad> body(ConduitLook look, ConduitBodyKey key, boolean labels) {
                List<ConduitQuad> lib = ConduitParts.body(look, key, false);
                List<ConduitQuad> quads = new ArrayList<>(lib.size() * 2);
                for (ConduitQuad quad : lib) {
                    quads.add(ConduitQuad.ofSprite(quad.box(), quad.facing(), GLASS, 0xFFFFFFFF, quad.culled(),
                            ConduitLayer.TRANSLUCENT));
                    if (key.renderValue() != 0 && !quad.culled()) {
                        int argb = CONTENTS_ALPHA << 24 | (key.renderValue() & 0xFFFFFF);
                        quads.add(new ConduitQuad(inset(quad.box()), quad.facing(), ConduitTexture.FLAT, argb, false,
                                ConduitLayer.TRANSLUCENT));
                    }
                }
                return quads;
            }
        });
    }

    /** {@code box} shrunk by {@link #INSET} on every side that is not on the block's face. */
    private static AABB inset(AABB box) {
        return new AABB(in(box.minX, 1), in(box.minY, 1), in(box.minZ, 1), in(box.maxX, -1), in(box.maxY, -1),
                in(box.maxZ, -1));
    }

    private static double in(double bound, int direction) {
        return bound <= 0 || bound >= 16 ? bound : bound + direction * INSET;
    }
}
