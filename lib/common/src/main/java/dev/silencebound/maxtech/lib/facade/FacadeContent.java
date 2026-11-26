package dev.silencebound.maxtech.lib.facade;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.registry.ModDataComponents;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The facade item and its recipe serializer. Lib's one player-facing item (ADR-0014): every mod with conduits needs
 * facades, and Storage runs on Lib alone. The recipes are Lib's datagen ({@code LibRecipeProvider}); Lib has no
 * creative tab, so Storage's tab lists {@link #tabStacks()}.
 */
public final class FacadeContent {
    public static final RegistryEntry<RecipeSerializer<?>, RecipeSerializer<FacadeRecipe>> RECIPE_SERIALIZER = Platform
            .get().registry().registrar(MaxtechLib.MOD_ID, Registries.RECIPE_SERIALIZER)
            .register("facade", () -> FacadeRecipe.SERIALIZER);

    // Stone by default, so a facade stack never lacks a block: the component only needs saving when it differs.
    public static final RegistryEntry<Item, FacadeItem> FACADE = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.ITEM).register("facade",
                    id -> new FacadeItem(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id))
                            .component(ModDataComponents.FACADE.get(), Blocks.STONE.defaultBlockState())));

    private FacadeContent() {
    }

    /**
     * The facades a creative tab shows: the default stone one, then a few examples. Called when the tab is built, after
     * registration.
     */
    public static List<ItemStack> tabStacks() {
        List<ItemStack> stacks = new ArrayList<>();
        stacks.add(new ItemStack(FACADE.get()));
        for (BlockState example : List.of(Blocks.STONE_BRICKS.defaultBlockState(),
                Blocks.SMOOTH_STONE.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState(),
                Blocks.BRICKS.defaultBlockState())) {
            stacks.add(Facades.stack(example));
        }
        return stacks;
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
