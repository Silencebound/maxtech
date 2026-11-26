package dev.silencebound.maxtech.lib.neoforge.client;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.registry.ModDataComponents;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/**
 * The facade item drawn as the item of the block it mimics, so a stack of facades shows which block they are. A block
 * without an item falls back to the missing model.
 */
public final class FacadeItemModel implements ItemModel {
    private final ItemModel missing;
    // One stack per mimicked block item, reused: item models are updated on the render thread only, and the stack is
    // only read.
    private final Reference2ObjectOpenHashMap<Item, ItemStack> stacks = new Reference2ObjectOpenHashMap<>();

    private FacadeItemModel(ItemModel missing) {
        this.missing = missing;
    }

    @Override
    public void update(ItemStackRenderState output, ItemStack stack, ItemModelResolver resolver,
            ItemDisplayContext displayContext, @Nullable ClientLevel level, @Nullable ItemOwner owner, int seed) {
        output.appendModelIdentityElement(this);
        BlockState facade = stack.get(ModDataComponents.FACADE.get());
        // A block without an item gives air, whose stack is empty.
        Item item = facade == null ? Items.AIR : facade.getBlock().asItem();
        ItemStack look = stacks.get(item);
        if (look == null) {
            look = new ItemStack(item);
            stacks.put(item, look);
        }
        if (look.isEmpty()) {
            missing.update(output, stack, resolver, displayContext, level, owner, seed);
            return;
        }
        resolver.appendItemLayers(output, look, displayContext, level, owner, seed);
    }

    /** The {@code maxtech_lib:facade} item model type; it takes no fields. */
    public record Unbaked() implements ItemModel.Unbaked {
        public static final Identifier ID = MaxtechLib.id("facade");
        public static final MapCodec<Unbaked> CODEC = MapCodec.unit(new Unbaked());

        @Override
        public MapCodec<Unbaked> type() {
            return CODEC;
        }

        @Override
        public void resolveDependencies(ResolvableModel.Resolver resolver) {
            // Draws other items' models, which are baked on their own.
        }

        @Override
        public ItemModel bake(ItemModel.BakingContext context, Matrix4fc transformation) {
            return new FacadeItemModel(context.missingItemModel(transformation));
        }
    }
}
