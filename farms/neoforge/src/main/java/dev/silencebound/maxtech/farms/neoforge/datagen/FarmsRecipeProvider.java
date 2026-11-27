package dev.silencebound.maxtech.farms.neoforge.datagen;

import dev.silencebound.maxtech.farms.lab.LabTubes;
import dev.silencebound.maxtech.farms.registry.FarmsContent;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.Tags;

/** Farms' crafting recipes; inputs by {@code c:} tags where one exists. */
final class FarmsRecipeProvider extends RecipeProvider {
    private FarmsRecipeProvider(HolderLookup.Provider registries, RecipeOutput output) {
        super(registries, output);
    }

    @Override
    protected void buildRecipes() {
        shaped(RecipeCategory.TOOLS, FarmsContent.SAMPLER.get()).pattern(" B ").pattern("IRI").pattern(" C ")
                .define('B', Items.GLASS_BOTTLE).define('I', Tags.Items.INGOTS_IRON)
                .define('R', Tags.Items.DUSTS_REDSTONE).define('C', Tags.Items.INGOTS_COPPER)
                .unlockedBy("has_redstone", has(Tags.Items.DUSTS_REDSTONE)).save(output);
        shaped(RecipeCategory.MISC, FarmsContent.LAB_HUB_ITEM.get()).pattern("IGI").pattern("CFC").pattern("IRI")
                .define('I', Tags.Items.INGOTS_IRON).define('G', Tags.Items.GLASS_BLOCKS)
                .define('C', Tags.Items.INGOTS_COPPER).define('F', Tags.Items.PLAYER_WORKSTATIONS_FURNACES)
                .define('R', Tags.Items.DUSTS_REDSTONE).unlockedBy("has_copper", has(Tags.Items.INGOTS_COPPER))
                .save(output);
        shaped(RecipeCategory.MISC, LabTubes.TUBE.item().get(), 4).pattern("GCG").define('G', Tags.Items.GLASS_PANES)
                .define('C', Tags.Items.INGOTS_COPPER).unlockedBy("has_copper", has(Tags.Items.INGOTS_COPPER))
                .save(output);
        shaped(RecipeCategory.MISC, FarmsContent.FLUID_PORT_ITEM.get()).pattern("ITI").pattern("CBC").pattern("ITI")
                .define('I', Tags.Items.INGOTS_IRON).define('T', LabTubes.TUBE.item().get())
                .define('C', Tags.Items.INGOTS_COPPER).define('B', Tags.Items.BUCKETS_EMPTY)
                .unlockedBy("has_lab_tube", has(LabTubes.TUBE.item().get())).save(output);
    }

    /** Runs the provider in {@code runData}. */
    static final class Runner extends RecipeProvider.Runner {
        Runner(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
            super(output, registries);
        }

        @Override
        protected RecipeProvider createRecipeProvider(HolderLookup.Provider registries, RecipeOutput output) {
            return new FarmsRecipeProvider(registries, output);
        }

        @Override
        public String getName() {
            return "Farms recipes";
        }
    }
}
