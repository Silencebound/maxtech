package dev.silencebound.maxtech.storage.neoforge.datagen;

import dev.silencebound.maxtech.storage.machine.MachineRecipes;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;

/**
 * Writes the machine recipes {@link MachineRecipes} declares; they are not crafting recipes, so no item chain has them.
 */
final class StorageMachineRecipeProvider extends RecipeProvider {
    private StorageMachineRecipeProvider(HolderLookup.Provider registries, RecipeOutput output) {
        super(registries, output);
    }

    @Override
    protected void buildRecipes() {
        MachineRecipes.build(items, output);
    }

    static final class Runner extends RecipeProvider.Runner {
        Runner(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
            super(output, registries);
        }

        @Override
        protected RecipeProvider createRecipeProvider(HolderLookup.Provider registries, RecipeOutput output) {
            return new StorageMachineRecipeProvider(registries, output);
        }

        @Override
        public String getName() {
            return "Storage machine recipes";
        }
    }
}
