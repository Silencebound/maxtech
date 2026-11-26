package dev.silencebound.maxtech.storage.neoforge.datagen;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.worldgen.WorldgenContent;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.KeyTagProvider;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * Where Storage's ores, deep data geodes and void geodes may generate: the biome tags their biome modifiers name and
 * the dimension type tags their features check. A pack edits a tag to move or remove one.
 */
final class StorageWorldgenTags {
    private StorageWorldgenTags() {
    }

    /** {@code data/maxtech_storage/tags/worldgen/biome/}. */
    static final class Biomes extends KeyTagProvider<Biome> {
        Biomes(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
            super(output, Registries.BIOME, registries, MaxtechStorage.MOD_ID);
        }

        @Override
        protected void addTags(HolderLookup.Provider registries) {
            tag(WorldgenContent.LUMEN_QUARTZ_BIOMES).addTag(BiomeTags.IS_OVERWORLD);
            tag(WorldgenContent.TANTALITE_BIOMES).addTag(BiomeTags.IS_OVERWORLD);
            tag(WorldgenContent.EMBER_QUARTZ_BIOMES).addTag(BiomeTags.IS_NETHER);
            tag(WorldgenContent.HAS_DEEP_DATA_GEODE).addTag(BiomeTags.IS_OVERWORLD);
            // The outer islands only: the main island holds the exit portal and the dragon fight.
            tag(WorldgenContent.HAS_VOID_GEODE).add(net.minecraft.world.level.biome.Biomes.END_HIGHLANDS,
                    net.minecraft.world.level.biome.Biomes.END_MIDLANDS,
                    net.minecraft.world.level.biome.Biomes.SMALL_END_ISLANDS,
                    net.minecraft.world.level.biome.Biomes.END_BARRENS);
        }
    }

    /** {@code data/maxtech_storage/tags/dimension_type/}. */
    static final class DimensionTypes extends KeyTagProvider<DimensionType> {
        DimensionTypes(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
            super(output, Registries.DIMENSION_TYPE, registries, MaxtechStorage.MOD_ID);
        }

        @Override
        protected void addTags(HolderLookup.Provider registries) {
            tag(WorldgenContent.LUMEN_QUARTZ_DIMENSIONS).add(BuiltinDimensionTypes.OVERWORLD,
                    BuiltinDimensionTypes.OVERWORLD_CAVES);
            tag(WorldgenContent.TANTALITE_DIMENSIONS).add(BuiltinDimensionTypes.OVERWORLD,
                    BuiltinDimensionTypes.OVERWORLD_CAVES);
            tag(WorldgenContent.EMBER_QUARTZ_DIMENSIONS).add(BuiltinDimensionTypes.NETHER);
            tag(WorldgenContent.DEEP_DATA_GEODE_DIMENSIONS).add(BuiltinDimensionTypes.OVERWORLD);
            tag(WorldgenContent.VOID_GEODE_DIMENSIONS).add(BuiltinDimensionTypes.END);
        }
    }
}
