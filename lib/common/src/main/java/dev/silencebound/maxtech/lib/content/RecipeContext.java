package dev.silencebound.maxtech.lib.content;

import java.util.function.BiFunction;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.criterion.InventoryChangeTrigger;
import net.minecraft.advancements.criterion.ItemPredicate;
import net.minecraft.core.HolderGetter;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.data.recipes.ShapelessRecipeBuilder;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.ItemLike;
import org.jspecify.annotations.Nullable;

/**
 * What a {@link ContentRecipe} builds with: the helpers vanilla's {@code RecipeProvider} gives its subclasses,
 * producing the same output. Only vanilla types, which the dedicated server jar ships too ({@code net.minecraft.data}
 * is there, {@code net.minecraft.client.data} is not), so recipes can be declared in common. A recipe provider makes
 * one per run.
 */
public final class RecipeContext {
    private final HolderGetter<Item> items;
    private final RecipeOutput output;
    private final @Nullable BiFunction<RecipeOutput, String, RecipeOutput> withoutMod;

    public RecipeContext(HolderGetter<Item> items, RecipeOutput output) {
        this(items, output, null);
    }

    /**
     * {@code withoutMod} wraps an output so that what is saved through it loads only without the given mod; the
     * loader's datagen passes it, since recipe conditions are loader types common code cannot name.
     */
    public RecipeContext(HolderGetter<Item> items, RecipeOutput output,
            @Nullable BiFunction<RecipeOutput, String, RecipeOutput> withoutMod) {
        this.items = items;
        this.output = output;
        this.withoutMod = withoutMod;
    }

    /**
     * A context whose recipes, and their unlock advancements, load only without {@code modId}: a fallback for a recipe
     * that mod writes its own way (its ids are its own, so both can exist in datagen). Throws when the datagen that
     * made this context gave no way to write conditions.
     */
    public RecipeContext withoutMod(String modId) {
        BiFunction<RecipeOutput, String, RecipeOutput> condition = withoutMod;
        if (condition == null) {
            throw new IllegalStateException("This recipe context cannot write mod conditions");
        }
        return new RecipeContext(items, condition.apply(output, modId), condition);
    }

    public ShapedRecipeBuilder shaped(RecipeCategory category, ItemLike result) {
        return ShapedRecipeBuilder.shaped(items, category, result);
    }

    public ShapedRecipeBuilder shaped(RecipeCategory category, ItemLike result, int count) {
        return ShapedRecipeBuilder.shaped(items, category, result, count);
    }

    public ShapelessRecipeBuilder shapeless(RecipeCategory category, ItemLike result) {
        return ShapelessRecipeBuilder.shapeless(items, category, result);
    }

    public ShapelessRecipeBuilder shapeless(RecipeCategory category, ItemLike result, int count) {
        return ShapelessRecipeBuilder.shapeless(items, category, result, count);
    }

    /** The unlock criterion {@code RecipeProvider.has(TagKey)} makes. */
    public Criterion<InventoryChangeTrigger.TriggerInstance> has(TagKey<Item> tag) {
        return InventoryChangeTrigger.TriggerInstance.hasItems(ItemPredicate.Builder.item().of(items, tag));
    }

    /** The unlock criterion {@code RecipeProvider.has(ItemLike)} makes. */
    public Criterion<InventoryChangeTrigger.TriggerInstance> has(ItemLike item) {
        return InventoryChangeTrigger.TriggerInstance.hasItems(ItemPredicate.Builder.item().of(items, item));
    }

    /** An ingredient matching {@code tag}, for builders that take no tag directly (cooking, counted shapeless). */
    public Ingredient ingredient(TagKey<Item> tag) {
        return Ingredient.of(items.getOrThrow(tag));
    }

    public RecipeOutput output() {
        return output;
    }
}
