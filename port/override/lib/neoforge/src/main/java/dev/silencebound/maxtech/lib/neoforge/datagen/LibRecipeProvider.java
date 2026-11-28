// port-override: base=c6f87ffb8e7d20f9
package dev.silencebound.maxtech.lib.neoforge.datagen;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.facade.FacadeContent;
import dev.silencebound.maxtech.lib.facade.FacadeRecipe;
import dev.silencebound.maxtech.lib.shim.misc.ItemStackTemplate;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.common.conditions.NotCondition;
import net.neoforged.neoforge.common.conditions.TagEmptyCondition;

/**
 * The facade recipes: any block a facade may mimic plus one iron plate gives four facades. A game where no mod tags an
 * iron plate (Storage alone) binds with an iron ingot instead: the two recipes carry opposite
 * {@code neoforge:tag_empty} conditions on {@code c:plates/iron}, which recipe loading tests against the tags being
 * loaded, so exactly one of them loads. On 1.21.1 the recipe provider is the data provider itself, so {@link Runner}
 * is the provider.
 */
final class LibRecipeProvider {
    private static final TagKey<Item> IRON_PLATES = ItemTags
            .create(ResourceLocation.fromNamespaceAndPath("c", "plates/iron"));
    private static final int PER_CRAFT = 4;

    private LibRecipeProvider() {
    }

    /** Runs the provider in {@code runData}. */
    static final class Runner extends RecipeProvider {
        Runner(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
            super(output, registries);
        }

        @Override
        protected void buildRecipes(RecipeOutput output) {
            TagEmptyCondition noPlates = new TagEmptyCondition(IRON_PLATES.location());
            facade(output.withConditions(new NotCondition(noPlates)), IRON_PLATES, "facade");
            facade(output.withConditions(noPlates), Tags.Items.INGOTS_IRON, "facade_from_ingot");
        }

        private static void facade(RecipeOutput out, TagKey<Item> binder, String path) {
            out.accept(MaxtechLib.id(path), new FacadeRecipe(Ingredient.of(binder),
                    new ItemStackTemplate(FacadeContent.FACADE.get(), PER_CRAFT)), null);
        }

        @Override
        public String getName() {
            return "Lib recipes";
        }
    }
}
