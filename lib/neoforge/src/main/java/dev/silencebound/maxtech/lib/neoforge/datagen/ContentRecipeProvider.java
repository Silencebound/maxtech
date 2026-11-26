package dev.silencebound.maxtech.lib.neoforge.datagen;

import dev.silencebound.maxtech.lib.content.Content;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;

/** The crafting recipes a mod's blocks, items and machines declared with the content builder. */
final class ContentRecipeProvider extends RecipeProvider {
    private final Content content;

    private ContentRecipeProvider(HolderLookup.Provider registries, RecipeOutput output, Content content) {
        super(registries, output);
        this.content = content;
    }

    @Override
    protected void buildRecipes() {
        ContentDatagen.recipes(content, items, output);
    }

    static final class Runner extends RecipeProvider.Runner {
        private final Content content;

        Runner(PackOutput output, CompletableFuture<HolderLookup.Provider> registries, Content content) {
            super(output, registries);
            this.content = content;
        }

        @Override
        protected RecipeProvider createRecipeProvider(HolderLookup.Provider registries, RecipeOutput output) {
            return new ContentRecipeProvider(registries, output, content);
        }

        @Override
        public String getName() {
            return "Content recipes - " + content.modId();
        }
    }
}
