// port-override: base=62cc3b0cf1d8fcca
package dev.silencebound.maxtech.lib.neoforge.datagen;

import dev.silencebound.maxtech.lib.content.Content;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;

/**
 * The crafting recipes a mod's blocks, items and machines declared with the content builder. On 1.21.1 the recipe
 * provider is the data provider itself, so {@link Runner} is the provider. Its name is final there ("Recipes"), and a
 * data run refuses two providers of one name: a mod's run holds one recipe provider.
 */
final class ContentRecipeProvider {
    private ContentRecipeProvider() {
    }

    static final class Runner extends RecipeProvider {
        private final Content content;

        Runner(PackOutput output, CompletableFuture<HolderLookup.Provider> registries, Content content) {
            super(output, registries);
            this.content = content;
        }

        @Override
        protected void buildRecipes(RecipeOutput output, HolderLookup.Provider registries) {
            ContentDatagen.recipes(content, registries.lookupOrThrow(Registries.ITEM), output);
        }
    }
}
