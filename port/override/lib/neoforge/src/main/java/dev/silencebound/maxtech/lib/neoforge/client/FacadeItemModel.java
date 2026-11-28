// port-override: base=fc5ce6c0b5aef4b0
package dev.silencebound.maxtech.lib.neoforge.client;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.registry.ModDataComponents;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IGeometryLoader;
import net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry;
import org.jspecify.annotations.Nullable;

/**
 * The facade item drawn as the item of the block it mimics, so a stack of facades shows which block they are. A block
 * without an item falls back to the missing model. 1.21.1 port: the model itself draws nothing; its overrides hand
 * the item renderer the mimicked block item's model.
 */
public final class FacadeItemModel implements BakedModel {
    private final ItemOverrides overrides = new Overrides();
    // One stack per mimicked block item, reused: item models are resolved on the render thread only, and the stack is
    // only read.
    private final Reference2ObjectOpenHashMap<Item, ItemStack> stacks = new Reference2ObjectOpenHashMap<>();

    private FacadeItemModel() {
    }

    /** Looked up when drawn: the model manager holds the new missing model only once the bake is applied. */
    private static BakedModel missing() {
        return Minecraft.getInstance().getModelManager().getMissingModel();
    }

    private final class Overrides extends ItemOverrides {
        @Override
        public @Nullable BakedModel resolve(BakedModel model, ItemStack stack, @Nullable ClientLevel level,
                @Nullable LivingEntity entity, int seed) {
            BlockState facade = stack.get(ModDataComponents.FACADE.get());
            // A block without an item gives air, whose stack is empty.
            Item item = facade == null ? Items.AIR : facade.getBlock().asItem();
            ItemStack look = stacks.get(item);
            if (look == null) {
                look = new ItemStack(item);
                stacks.put(item, look);
            }
            if (look.isEmpty()) {
                return missing();
            }
            return Minecraft.getInstance().getItemRenderer().getModel(look, level, entity, seed);
        }
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource random) {
        return missing().getQuads(state, side, random);
    }

    @Override
    public boolean useAmbientOcclusion() {
        return missing().useAmbientOcclusion();
    }

    @Override
    public boolean isGui3d() {
        return missing().isGui3d();
    }

    @Override
    public boolean usesBlockLight() {
        return missing().usesBlockLight();
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return missing().getParticleIcon();
    }

    @Override
    public ItemOverrides getOverrides() {
        return overrides;
    }

    /** The {@code maxtech_lib:facade} item model loader; it takes no fields. */
    public record Unbaked() implements IUnbakedGeometry<Unbaked> {
        public static final ResourceLocation ID = MaxtechLib.id("facade");
        public static final IGeometryLoader<Unbaked> LOADER = (JsonObject json,
                JsonDeserializationContext context) -> new Unbaked();

        @Override
        public BakedModel bake(IGeometryBakingContext context, ModelBaker baker,
                Function<Material, TextureAtlasSprite> spriteGetter, ModelState modelState, ItemOverrides overrides) {
            // Draws other items' models, which are baked on their own.
            return new FacadeItemModel();
        }
    }
}
