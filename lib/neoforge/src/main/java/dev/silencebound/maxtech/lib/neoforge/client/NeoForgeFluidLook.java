package dev.silencebound.maxtech.lib.neoforge.client;

import dev.silencebound.maxtech.lib.api.client.gui.FluidLook;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.client.fluid.FluidTintSource;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * {@link FluidLook} from the fluid's baked fluid model, which holds its sprites and tint in 26.1
 * ({@code IClientFluidTypeExtensions} no longer does), and its fluid type's name and light. A fluid without a model
 * gets the missing model's sprites. The tint is the fluid's default one, taken without a level or position, so it
 * ignores the biome (water is the same blue everywhere): conduit contents bake it into parts shared by every conduit
 * that shows the same fluid. The fluid model set is replaced on a resource reload, so a look is read again after one.
 * Client thread only.
 */
final class NeoForgeFluidLook implements FluidLook {
    @Override
    public TextureAtlasSprite sprite(FluidKey fluid) {
        return model(fluid).stillMaterial().sprite();
    }

    @Override
    public Optional<TextureAtlasSprite> flowingSprite(FluidKey fluid) {
        return Optional.of(model(fluid).flowingMaterial().sprite());
    }

    @Override
    public int tint(FluidKey fluid) {
        FluidTintSource source = model(fluid).fluidTintSource();
        return source == null ? 0xFFFFFFFF : ARGB.opaque(source.colorAsStack(stack(fluid)));
    }

    @Override
    public Component name(FluidKey fluid) {
        return stack(fluid).getHoverName();
    }

    /** The fluid type's light level or its block's, whichever is higher (lava 15). */
    @Override
    public int lightEmission(FluidKey key) {
        Fluid fluid = key.fluid();
        int block = fluid.defaultFluidState().createLegacyBlock().getLightEmission();
        return Math.clamp(Math.max(fluid.getFluidType().getLightLevel(), block), 0, ConduitQuad.MAX_LIGHT);
    }

    private static FluidModel model(FluidKey fluid) {
        return Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fluid.fluid().defaultFluidState());
    }

    private static FluidStack stack(FluidKey fluid) {
        return new FluidStack(fluid.fluid(), 1, fluid.components());
    }
}
