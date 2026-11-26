package dev.silencebound.maxtech.storage.part;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeBuilder;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/**
 * A one-ingredient shapeless recipe whose result takes over the ingredient's data components (its patch over the item's
 * defaults): a device's part and block forms convert into each other with their settings (a port's filter and mode).
 * Shown and matched like any shapeless recipe; only {@link #assemble} differs.
 */
public final class ComponentCopyingRecipe extends ShapelessRecipe {
    public static final MapCodec<ComponentCopyingRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i
            .group(Recipe.CommonInfo.MAP_CODEC.forGetter(o -> o.commonInfo),
                    CraftingRecipe.CraftingBookInfo.MAP_CODEC.forGetter(o -> o.bookInfo),
                    ItemStackTemplate.CODEC.fieldOf("result").forGetter(ComponentCopyingRecipe::template),
                    Ingredient.CODEC.fieldOf("ingredient").forGetter(o -> o.ingredient))
            .apply(i, ComponentCopyingRecipe::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, ComponentCopyingRecipe> STREAM_CODEC = StreamCodec
            .composite(Recipe.CommonInfo.STREAM_CODEC, o -> o.commonInfo, CraftingRecipe.CraftingBookInfo.STREAM_CODEC,
                    o -> o.bookInfo, ItemStackTemplate.STREAM_CODEC, ComponentCopyingRecipe::template,
                    Ingredient.CONTENTS_STREAM_CODEC, o -> o.ingredient, ComponentCopyingRecipe::new);
    public static final RegistryEntry<RecipeSerializer<?>, RecipeSerializer<ComponentCopyingRecipe>> SERIALIZER = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.RECIPE_SERIALIZER)
            .register("component_copying", () -> new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC));

    private final ItemStackTemplate template;
    private final Ingredient ingredient;

    public ComponentCopyingRecipe(Recipe.CommonInfo commonInfo, CraftingRecipe.CraftingBookInfo bookInfo,
            ItemStackTemplate result, Ingredient ingredient) {
        super(commonInfo, bookInfo, result, List.of(ingredient));
        this.template = result;
        this.ingredient = ingredient;
    }

    private ItemStackTemplate template() {
        return template;
    }

    /** Writes a recipe turning one {@code ingredient} into {@code result} with its components, under {@code id}. */
    public static void save(RecipeOutput output, ResourceKey<Recipe<?>> id, RecipeCategory category,
            ItemStackTemplate result, Ingredient ingredient) {
        output.accept(id, new ComponentCopyingRecipe(RecipeBuilder.createCraftingCommonInfo(true),
                RecipeBuilder.createCraftingBookInfo(category, null), result, ingredient), null);
    }

    @Override
    public ItemStack assemble(CraftingInput input) {
        ItemStack result = template.create();
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (!stack.isEmpty()) {
                result.applyComponents(stack.getComponentsPatch());
                break;
            }
        }
        return result;
    }

    @Override
    public RecipeSerializer<ShapelessRecipe> getSerializer() {
        @SuppressWarnings("unchecked") // the serializer writes this subclass; vanilla's return type names the parent
        RecipeSerializer<ShapelessRecipe> serializer = (RecipeSerializer<ShapelessRecipe>) (RecipeSerializer<?>) SERIALIZER
                .get();
        return serializer;
    }

    /** Loads the class during mod construction so the serializer is registered. */
    public static void init() {
    }
}
