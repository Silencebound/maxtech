package dev.silencebound.maxtech.lib.neoforge.datagen;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.facade.FacadeContent;
import dev.silencebound.maxtech.lib.facade.FacadeRecipe;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.SpecialRecipeBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.common.conditions.NotCondition;
import net.neoforged.neoforge.common.conditions.TagEmptyCondition;

/**
 * The facade recipes: any block a facade may mimic plus one iron plate gives four facades. A game where no mod tags an
 * iron plate (Storage alone) binds with an iron ingot instead: the two recipes carry opposite
 * {@code neoforge:tag_empty} conditions on {@code c:plates/iron}, which recipe loading tests against the tags being
 * loaded, so exactly one of them loads.
 */
final class LibRecipeProvider extends RecipeProvider {
    private static final TagKey<Item> IRON_PLATES = ItemTags
            .create(Identifier.fromNamespaceAndPath("c", "plates/iron"));
    private static final int PER_CRAFT = 4;

    private LibRecipeProvider(HolderLookup.Provider registries, RecipeOutput output) {
        super(registries, output);
    }

    @Override
    protected void buildRecipes() {
        TagEmptyCondition<Item> noPlates = new TagEmptyCondition<>(IRON_PLATES);
        facade(output.withConditions(new NotCondition(noPlates)), IRON_PLATES, "facade");
        facade(output.withConditions(noPlates), Tags.Items.INGOTS_IRON, "facade_from_ingot");
    }

    private void facade(RecipeOutput out, TagKey<Item> binder, String path) {
        Ingredient ingredient = Ingredient.of(items.getOrThrow(binder));
        SpecialRecipeBuilder.special(
                () -> new FacadeRecipe(ingredient, new ItemStackTemplate(FacadeContent.FACADE.get(), PER_CRAFT)))
                .save(out, ResourceKey.<Recipe<?>>create(Registries.RECIPE, MaxtechLib.id(path)));
    }

    /** Runs the provider in {@code runData}. */
    static final class Runner extends RecipeProvider.Runner {
        Runner(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
            super(output, registries);
        }

        @Override
        protected RecipeProvider createRecipeProvider(HolderLookup.Provider registries, RecipeOutput output) {
            return new LibRecipeProvider(registries, output);
        }

        @Override
        public String getName() {
            return "Lib recipes";
        }
    }
}
