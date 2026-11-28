// port-override: base=8f28054f8a35add4
package dev.silencebound.maxtech.lib.neoforge.client;

import dev.silencebound.maxtech.lib.api.client.gui.FluidLook;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jspecify.annotations.Nullable;

/**
 * {@link FluidLook} from the fluid type's client extensions, which hold its sprites and tint in 1.21.1 (26.1 moved them
 * to the baked fluid model), and its fluid type's name and light. A fluid without textures gets the block atlas's
 * missing sprite. The tint is the fluid's stack tint, taken without a level or position, so it ignores the biome (water
 * is the same blue everywhere): conduit contents bake it into parts shared by every conduit that shows the same fluid.
 * The atlas is replaced on a resource reload, so a look is read again after one. Client thread only.
 */
final class NeoForgeFluidLook implements FluidLook {
    @Override
    public TextureAtlasSprite sprite(FluidKey fluid) {
        FluidStack stack = stack(fluid);
        return atlas(IClientFluidTypeExtensions.of(fluid.fluid()).getStillTexture(stack));
    }

    @Override
    public Optional<TextureAtlasSprite> flowingSprite(FluidKey fluid) {
        FluidStack stack = stack(fluid);
        return Optional.of(atlas(IClientFluidTypeExtensions.of(fluid.fluid()).getFlowingTexture(stack)));
    }

    @Override
    public int tint(FluidKey fluid) {
        return 0xFF000000 | IClientFluidTypeExtensions.of(fluid.fluid()).getTintColor(stack(fluid));
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

    private static TextureAtlasSprite atlas(@Nullable ResourceLocation texture) {
        return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(texture == null ? MissingTextureAtlasSprite.getLocation() : texture);
    }

    private static FluidStack stack(FluidKey fluid) {
        return new FluidStack(fluid.fluid().builtInRegistryHolder(), 1, fluid.components());
    }
}
