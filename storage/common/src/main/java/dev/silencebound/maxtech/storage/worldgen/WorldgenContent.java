package dev.silencebound.maxtech.storage.worldgen;

import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageModules;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.material.DataCrystalContent;
import dev.silencebound.maxtech.storage.material.EmberMaterialContent;
import dev.silencebound.maxtech.storage.material.MaterialContent;
import dev.silencebound.maxtech.storage.material.TierTwoMaterialContent;
import dev.silencebound.maxtech.storage.material.VoidCrystalContent;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.GeodeBlockSettings;
import net.minecraft.world.level.levelgen.GeodeCrackSettings;
import net.minecraft.world.level.levelgen.GeodeLayerSettings;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.GeodeConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;

/**
 * Storage's ore and geode features and the keys of their configured and placed features, which Storage's datagen writes
 * with a biome modifier each: lumen quartz in stone and deepslate and tantalite in deepslate only, in the biomes of
 * their biome tag's underground ores; ember quartz in netherrack in the underground decoration of its biome tag's
 * biomes (the step of vanilla's nether ores); deep data geodes in the biomes of {@link #HAS_DEEP_DATA_GEODE}, in the
 * step of vanilla's amethyst geodes; void geodes under the surface of the islands in the biomes of
 * {@link #HAS_VOID_GEODE}, in the same step.
 */
public final class WorldgenContent {
    private static final RegistryHelper.Registrar<Feature<?>> FEATURES = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.FEATURE);

    /** Biomes the lumen quartz ore's biome modifier adds it to; the Overworld's by default. */
    public static final TagKey<Biome> LUMEN_QUARTZ_BIOMES = TagKey.create(Registries.BIOME,
            MaxtechStorage.id("has_ore/lumen_quartz"));
    /** Biomes the tantalite ore's biome modifier adds it to; the Overworld's by default. */
    public static final TagKey<Biome> TANTALITE_BIOMES = TagKey.create(Registries.BIOME,
            MaxtechStorage.id("has_ore/tantalite"));
    /**
     * Dimension types lumen quartz generates in, checked as the feature runs; the Overworld's by default. Lets a pack
     * keep the ore out of a modded dimension that reuses Overworld biomes.
     */
    public static final TagKey<DimensionType> LUMEN_QUARTZ_DIMENSIONS = TagKey.create(Registries.DIMENSION_TYPE,
            MaxtechStorage.id("has_ore/lumen_quartz"));
    /** Dimension types tantalite generates in; the Overworld's by default. */
    public static final TagKey<DimensionType> TANTALITE_DIMENSIONS = TagKey.create(Registries.DIMENSION_TYPE,
            MaxtechStorage.id("has_ore/tantalite"));
    /** Biomes the ember quartz ore's biome modifier adds it to; the Nether's by default. */
    public static final TagKey<Biome> EMBER_QUARTZ_BIOMES = TagKey.create(Registries.BIOME,
            MaxtechStorage.id("has_ore/ember_quartz"));
    /** Dimension types ember quartz generates in; the Nether's by default. */
    public static final TagKey<DimensionType> EMBER_QUARTZ_DIMENSIONS = TagKey.create(Registries.DIMENSION_TYPE,
            MaxtechStorage.id("has_ore/ember_quartz"));

    public static final RegistryEntry<Feature<?>, ConfigOreFeature> LUMEN_QUARTZ_ORE = FEATURES.register(
            "lumen_quartz_ore",
            () -> new ConfigOreFeature(NoneFeatureConfiguration.CODEC, LUMEN_QUARTZ_DIMENSIONS,
                    StorageServerConfig.LUMEN_QUARTZ_VEINS, StorageServerConfig.LUMEN_QUARTZ_VEIN_SIZE,
                    StorageServerConfig.LUMEN_QUARTZ_MIN_Y, StorageServerConfig.LUMEN_QUARTZ_MAX_Y,
                    size -> new OreConfiguration(List.of(
                            OreConfiguration.target(new TagMatchTest(BlockTags.STONE_ORE_REPLACEABLES),
                                    MaterialContent.LUMEN_QUARTZ_ORE.get().defaultBlockState()),
                            OreConfiguration.target(new TagMatchTest(BlockTags.DEEPSLATE_ORE_REPLACEABLES),
                                    MaterialContent.DEEPSLATE_LUMEN_QUARTZ_ORE.get().defaultBlockState())),
                            size)));
    public static final RegistryEntry<Feature<?>, ConfigOreFeature> TANTALITE_ORE = FEATURES.register("tantalite_ore",
            () -> new ConfigOreFeature(NoneFeatureConfiguration.CODEC, TANTALITE_DIMENSIONS,
                    StorageServerConfig.TANTALITE_VEINS, StorageServerConfig.TANTALITE_VEIN_SIZE,
                    StorageServerConfig.TANTALITE_MIN_Y, StorageServerConfig.TANTALITE_MAX_Y,
                    size -> new OreConfiguration(
                            List.of(OreConfiguration.target(new TagMatchTest(BlockTags.DEEPSLATE_ORE_REPLACEABLES),
                                    TierTwoMaterialContent.DEEPSLATE_TANTALITE_ORE.get().defaultBlockState())),
                            size)));

    public static final RegistryEntry<Feature<?>, ConfigOreFeature> EMBER_QUARTZ_ORE = FEATURES.register(
            "ember_quartz_ore",
            () -> new ConfigOreFeature(NoneFeatureConfiguration.CODEC, EMBER_QUARTZ_DIMENSIONS,
                    StorageServerConfig.EMBER_QUARTZ_VEINS, StorageServerConfig.EMBER_QUARTZ_VEIN_SIZE,
                    StorageServerConfig.EMBER_QUARTZ_MIN_Y, StorageServerConfig.EMBER_QUARTZ_MAX_Y,
                    size -> new OreConfiguration(List.of(OreConfiguration.target(new BlockMatchTest(Blocks.NETHERRACK),
                            EmberMaterialContent.EMBER_QUARTZ_ORE.get().defaultBlockState())), size)));
    /** Biomes that may hold deep data geodes; Storage ships every Overworld biome. */
    public static final TagKey<Biome> HAS_DEEP_DATA_GEODE = TagKey.create(Registries.BIOME,
            MaxtechStorage.id("has_deep_data_geode"));
    /** Dimension types that may hold deep data geodes; Storage ships the Overworld's. */
    public static final TagKey<DimensionType> DEEP_DATA_GEODE_DIMENSIONS = TagKey.create(Registries.DIMENSION_TYPE,
            MaxtechStorage.id("has_deep_data_geode"));
    public static final RegistryEntry<Feature<?>, ConfigGeodeFeature> DEEP_DATA_GEODE = FEATURES
            .register("deep_data_geode", () -> new ConfigGeodeFeature(NoneFeatureConfiguration.CODEC,
                    // The dataGeodes switch turns them off like a rarity of 0.
                    () -> StorageModules.DATA_GEODES.isEnabled()
                            ? StorageServerConfig.DEEP_DATA_GEODE_RARITY.getAsInt()
                            : 0,
                    StorageServerConfig.DEEP_DATA_GEODE_MIN_Y, StorageServerConfig.DEEP_DATA_GEODE_MAX_Y,
                    DEEP_DATA_GEODE_DIMENSIONS, WorldgenContent::deepDataGeode));

    /** Biomes that may hold void geodes; Storage ships the outer End islands' biomes, not the main island's. */
    public static final TagKey<Biome> HAS_VOID_GEODE = TagKey.create(Registries.BIOME,
            MaxtechStorage.id("has_void_geode"));
    /** Dimension types that may hold void geodes; Storage ships the End's. */
    public static final TagKey<DimensionType> VOID_GEODE_DIMENSIONS = TagKey.create(Registries.DIMENSION_TYPE,
            MaxtechStorage.id("has_void_geode"));
    public static final RegistryEntry<Feature<?>, SurfaceGeodeFeature> VOID_GEODE = FEATURES.register("void_geode",
            () -> new SurfaceGeodeFeature(NoneFeatureConfiguration.CODEC,
                    // The voidGeodes switch turns them off like a rarity of 0.
                    () -> StorageModules.VOID_GEODES.isEnabled() ? StorageServerConfig.VOID_GEODE_RARITY.getAsInt() : 0,
                    StorageServerConfig.VOID_GEODE_MIN_DEPTH, StorageServerConfig.VOID_GEODE_MAX_DEPTH,
                    VOID_GEODE_DIMENSIONS, WorldgenContent::voidGeode));

    public static final ResourceKey<ConfiguredFeature<?, ?>> LUMEN_QUARTZ_ORE_CONFIGURED = configured(
            "lumen_quartz_ore");
    public static final ResourceKey<PlacedFeature> LUMEN_QUARTZ_ORE_PLACED = placed("lumen_quartz_ore");
    public static final ResourceKey<ConfiguredFeature<?, ?>> TANTALITE_ORE_CONFIGURED = configured("tantalite_ore");
    public static final ResourceKey<PlacedFeature> TANTALITE_ORE_PLACED = placed("tantalite_ore");

    public static final ResourceKey<ConfiguredFeature<?, ?>> EMBER_QUARTZ_ORE_CONFIGURED = configured(
            "ember_quartz_ore");
    public static final ResourceKey<PlacedFeature> EMBER_QUARTZ_ORE_PLACED = placed("ember_quartz_ore");
    public static final ResourceKey<ConfiguredFeature<?, ?>> DEEP_DATA_GEODE_CONFIGURED = configured("deep_data_geode");
    public static final ResourceKey<PlacedFeature> DEEP_DATA_GEODE_PLACED = placed("deep_data_geode");
    public static final ResourceKey<ConfiguredFeature<?, ?>> VOID_GEODE_CONFIGURED = configured("void_geode");
    public static final ResourceKey<PlacedFeature> VOID_GEODE_PLACED = placed("void_geode");

    private WorldgenContent() {
    }

    /** Loads the class during mod construction so the features are queued before registration runs. */
    public static void init() {
    }

    /**
     * Vanilla's amethyst geode with Storage's blocks: an air pocket lined with data crystal and budding data crystal
     * (buds on the budding blocks' free faces), inside tuff and a cobbled deepslate shell, so it sits unseen in the
     * deepslate layer.
     */
    private static GeodeConfiguration deepDataGeode() {
        return new GeodeConfiguration(
                new GeodeBlockSettings(BlockStateProvider.simple(Blocks.AIR),
                        BlockStateProvider.simple(DataCrystalContent.DATA_CRYSTAL_BLOCK.get()),
                        BlockStateProvider.simple(DataCrystalContent.BUDDING_DATA_CRYSTAL.get()),
                        BlockStateProvider.simple(Blocks.TUFF), BlockStateProvider.simple(Blocks.COBBLED_DEEPSLATE),
                        DataCrystalContent.growthStages().stream().map(block -> block.defaultBlockState()).toList(),
                        BlockTags.FEATURES_CANNOT_REPLACE, BlockTags.GEODE_INVALID_BLOCKS),
                new GeodeLayerSettings(1.7, 2.2, 3.2, 4.2), new GeodeCrackSettings(0.95, 2.0, 2), 0.35, 0.083, true,
                UniformInt.of(4, 6), UniformInt.of(3, 4), UniformInt.of(1, 2), -16, 16, 0.05, 1);
    }

    /**
     * The amethyst geode's shape with void crystal: an air pocket lined with void crystal and budding void crystal
     * (buds on the budding blocks' free faces), inside obsidian and an end stone shell, so it sits unseen in an island.
     */
    private static GeodeConfiguration voidGeode() {
        return new GeodeConfiguration(
                new GeodeBlockSettings(BlockStateProvider.simple(Blocks.AIR),
                        BlockStateProvider.simple(VoidCrystalContent.VOID_CRYSTAL_BLOCK.get()),
                        BlockStateProvider.simple(VoidCrystalContent.BUDDING_VOID_CRYSTAL.get()),
                        BlockStateProvider.simple(Blocks.OBSIDIAN), BlockStateProvider.simple(Blocks.END_STONE),
                        VoidCrystalContent.growthStages().stream().map(block -> block.defaultBlockState()).toList(),
                        BlockTags.FEATURES_CANNOT_REPLACE, BlockTags.GEODE_INVALID_BLOCKS),
                new GeodeLayerSettings(1.7, 2.2, 3.2, 4.2), new GeodeCrackSettings(0.95, 2.0, 2), 0.35, 0.083, true,
                UniformInt.of(4, 6), UniformInt.of(3, 4), UniformInt.of(1, 2), -16, 16, 0.05, 1);
    }

    private static ResourceKey<ConfiguredFeature<?, ?>> configured(String name) {
        return ResourceKey.create(Registries.CONFIGURED_FEATURE, MaxtechStorage.id(name));
    }

    private static ResourceKey<PlacedFeature> placed(String name) {
        return ResourceKey.create(Registries.PLACED_FEATURE, MaxtechStorage.id(name));
    }
}
