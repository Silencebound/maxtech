package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.neoforge.api.client.conduit.RegisterConduitModelsEvent;
import dev.silencebound.maxtech.lib.neoforge.client.ConduitModel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterFluidModelsEvent;
import net.neoforged.neoforge.client.fluid.FluidTintSource;
import net.neoforged.neoforge.client.fluid.FluidTintSources;
import org.jspecify.annotations.Nullable;

/**
 * PROTOTYPE (proto/tube-fx only): the client side of {@link TubeFx}. Registers the tubes' model hooks
 * ({@link TubeFxModel}), the stand-in fluids' fluid models (water-like textures, their own tints) and Lib's prototype
 * sprite hook, which hands the conduit model the fluid sprites the hook meets and the light emission per sprite.
 */
@EventBusSubscriber(modid = TubeFx.MOD_ID, value = Dist.CLIENT)
public final class TubeFxClient {
    private static final Identifier WATER_STILL = Identifier.withDefaultNamespace("block/water_still");
    private static final Identifier WATER_FLOW = Identifier.withDefaultNamespace("block/water_flow");

    /** Fluid sprites seen by the hooks, by sprite id; filled on meshing threads. */
    static final Map<Identifier, Material.Baked> FLUID_SPRITES = new ConcurrentHashMap<>();
    /**
     * Light emission of the sprites that glow: fluid sprites by their fluid's light level, and the emissive effects.
     */
    static final Map<Identifier, Integer> EMISSION = new ConcurrentHashMap<>();

    private TubeFxClient() {
    }

    @SubscribeEvent
    static void registerModels(RegisterConduitModelsEvent event) {
        for (Identifier glowing : TubeFxModel.EMISSIVE) {
            EMISSION.put(glowing, 15);
        }
        ConduitModel.protoSprites = new ConduitModel.ProtoSprites() {
            @Override
            public Material.@Nullable Baked sprite(Identifier id) {
                return FLUID_SPRITES.get(id);
            }

            @Override
            public int lightEmission(Identifier id) {
                return EMISSION.getOrDefault(id, 0);
            }
        };
        event.register(TubeFx.TUBE, new TubeFxModel(false));
        event.register(TubeFx.FITTED_TUBE, new TubeFxModel(true));
    }

    @SubscribeEvent
    static void registerFluidModels(RegisterFluidModelsEvent event) {
        register(event, TubeFx.GEL, 0xFF5AC84A);
        register(event, TubeFx.CRYO, 0xFFC4E8FF);
    }

    private static void register(RegisterFluidModelsEvent event, TubeFx.TestFluid fluid, int argb) {
        FluidTintSource tint = FluidTintSources.constant(argb);
        event.register(new FluidModel.Unbaked(new Material(WATER_STILL), new Material(WATER_FLOW), null, tint),
                fluid.source.get(), fluid.flowing.get());
    }
}
