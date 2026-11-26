package dev.silencebound.maxtech.storage.neoforge.datagen;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.worldgen.WorldgenContent;
import java.util.List;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.BiomeModifiers;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Storage's ores' and geodes' configured and placed features and the stock NeoForge biome modifiers that add them:
 * lumen quartz and tantalite to the underground ores and ember quartz to the underground decoration of the biomes in
 * each ore's biome tag, deep data geodes to the biomes of {@code maxtech_storage:has_deep_data_geode} and void geodes
 * to those of {@code maxtech_storage:has_void_geode}. The placed features have no placement modifiers: each feature
 * reads its counts and heights from config once per chunk.
 */
final class StorageWorldgenData {
    static final ResourceKey<BiomeModifier> LUMEN_QUARTZ_MODIFIER = ResourceKey
            .create(NeoForgeRegistries.Keys.BIOME_MODIFIERS, MaxtechStorage.id("lumen_quartz_ore"));
    static final ResourceKey<BiomeModifier> TANTALITE_MODIFIER = ResourceKey
            .create(NeoForgeRegistries.Keys.BIOME_MODIFIERS, MaxtechStorage.id("tantalite_ore"));
    static final ResourceKey<BiomeModifier> EMBER_QUARTZ_MODIFIER = ResourceKey
            .create(NeoForgeRegistries.Keys.BIOME_MODIFIERS, MaxtechStorage.id("ember_quartz_ore"));
    static final ResourceKey<BiomeModifier> DEEP_DATA_GEODE_MODIFIER = ResourceKey
            .create(NeoForgeRegistries.Keys.BIOME_MODIFIERS, MaxtechStorage.id("deep_data_geode"));
    static final ResourceKey<BiomeModifier> VOID_GEODE_MODIFIER = ResourceKey
            .create(NeoForgeRegistries.Keys.BIOME_MODIFIERS, MaxtechStorage.id("void_geode"));

    private StorageWorldgenData() {
    }

    static RegistrySetBuilder registries() {
        return new RegistrySetBuilder().add(Registries.CONFIGURED_FEATURE, StorageWorldgenData::configuredFeatures)
                .add(Registries.PLACED_FEATURE, StorageWorldgenData::placedFeatures)
                .add(NeoForgeRegistries.Keys.BIOME_MODIFIERS, StorageWorldgenData::biomeModifiers);
    }

    private static void configuredFeatures(BootstrapContext<ConfiguredFeature<?, ?>> context) {
        context.register(WorldgenContent.LUMEN_QUARTZ_ORE_CONFIGURED,
                new ConfiguredFeature<>(WorldgenContent.LUMEN_QUARTZ_ORE.get(), NoneFeatureConfiguration.INSTANCE));
        context.register(WorldgenContent.TANTALITE_ORE_CONFIGURED,
                new ConfiguredFeature<>(WorldgenContent.TANTALITE_ORE.get(), NoneFeatureConfiguration.INSTANCE));
        context.register(WorldgenContent.EMBER_QUARTZ_ORE_CONFIGURED,
                new ConfiguredFeature<>(WorldgenContent.EMBER_QUARTZ_ORE.get(), NoneFeatureConfiguration.INSTANCE));
        context.register(WorldgenContent.DEEP_DATA_GEODE_CONFIGURED,
                new ConfiguredFeature<>(WorldgenContent.DEEP_DATA_GEODE.get(), NoneFeatureConfiguration.INSTANCE));
        context.register(WorldgenContent.VOID_GEODE_CONFIGURED,
                new ConfiguredFeature<>(WorldgenContent.VOID_GEODE.get(), NoneFeatureConfiguration.INSTANCE));
    }

    private static void placedFeatures(BootstrapContext<PlacedFeature> context) {
        placed(context, WorldgenContent.LUMEN_QUARTZ_ORE_PLACED, WorldgenContent.LUMEN_QUARTZ_ORE_CONFIGURED);
        placed(context, WorldgenContent.TANTALITE_ORE_PLACED, WorldgenContent.TANTALITE_ORE_CONFIGURED);
        placed(context, WorldgenContent.EMBER_QUARTZ_ORE_PLACED, WorldgenContent.EMBER_QUARTZ_ORE_CONFIGURED);
        placed(context, WorldgenContent.DEEP_DATA_GEODE_PLACED, WorldgenContent.DEEP_DATA_GEODE_CONFIGURED);
        placed(context, WorldgenContent.VOID_GEODE_PLACED, WorldgenContent.VOID_GEODE_CONFIGURED);
    }

    private static void placed(BootstrapContext<PlacedFeature> context, ResourceKey<PlacedFeature> key,
            ResourceKey<ConfiguredFeature<?, ?>> feature) {
        context.register(key,
                new PlacedFeature(context.lookup(Registries.CONFIGURED_FEATURE).getOrThrow(feature), List.of()));
    }

    private static void biomeModifiers(BootstrapContext<BiomeModifier> context) {
        modifier(context, LUMEN_QUARTZ_MODIFIER, WorldgenContent.LUMEN_QUARTZ_BIOMES,
                WorldgenContent.LUMEN_QUARTZ_ORE_PLACED, GenerationStep.Decoration.UNDERGROUND_ORES);
        modifier(context, TANTALITE_MODIFIER, WorldgenContent.TANTALITE_BIOMES, WorldgenContent.TANTALITE_ORE_PLACED,
                GenerationStep.Decoration.UNDERGROUND_ORES);
        // Vanilla's nether ores and amethyst geodes sit in these steps.
        modifier(context, EMBER_QUARTZ_MODIFIER, WorldgenContent.EMBER_QUARTZ_BIOMES,
                WorldgenContent.EMBER_QUARTZ_ORE_PLACED, GenerationStep.Decoration.UNDERGROUND_DECORATION);
        modifier(context, DEEP_DATA_GEODE_MODIFIER, WorldgenContent.HAS_DEEP_DATA_GEODE,
                WorldgenContent.DEEP_DATA_GEODE_PLACED, GenerationStep.Decoration.LOCAL_MODIFICATIONS);
        modifier(context, VOID_GEODE_MODIFIER, WorldgenContent.HAS_VOID_GEODE, WorldgenContent.VOID_GEODE_PLACED,
                GenerationStep.Decoration.LOCAL_MODIFICATIONS);
    }

    private static void modifier(BootstrapContext<BiomeModifier> context, ResourceKey<BiomeModifier> key,
            TagKey<Biome> biomes, ResourceKey<PlacedFeature> feature, GenerationStep.Decoration step) {
        context.register(key,
                new BiomeModifiers.AddFeaturesBiomeModifier(context.lookup(Registries.BIOME).getOrThrow(biomes),
                        HolderSet.direct(context.lookup(Registries.PLACED_FEATURE).getOrThrow(feature)), step));
    }
}
