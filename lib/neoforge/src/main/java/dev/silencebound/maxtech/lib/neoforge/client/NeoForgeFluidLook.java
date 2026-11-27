package dev.silencebound.maxtech.lib.neoforge.client;

import dev.silencebound.maxtech.lib.api.client.gui.FluidLook;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.neoforged.neoforge.client.fluid.FluidTintSource;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * {@link FluidLook} from the fluid's baked fluid model, which holds its sprites and tint in 26.1
 * ({@code IClientFluidTypeExtensions} no longer does), and its fluid type's name. A fluid without a model gets the
 * missing model's sprite. Client thread only.
 */
final class NeoForgeFluidLook implements FluidLook {
    @Override
    public TextureAtlasSprite sprite(FluidKey fluid) {
        return model(fluid).stillMaterial().sprite();
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

    private static FluidModel model(FluidKey fluid) {
        return Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fluid.fluid().defaultFluidState());
    }

    private static FluidStack stack(FluidKey fluid) {
        return new FluidStack(fluid.fluid(), 1, fluid.components());
    }
}
