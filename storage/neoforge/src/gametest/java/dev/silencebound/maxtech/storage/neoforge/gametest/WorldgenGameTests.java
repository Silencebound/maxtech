package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.material.MaterialContent;
import dev.silencebound.maxtech.storage.material.TierTwoMaterialContent;
import dev.silencebound.maxtech.storage.worldgen.WorldgenContent;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Ore generation: the biome modifiers put lumen quartz and tantalite into Overworld biomes only, and one run of each
 * feature on a chunk of stone over deepslate places ore of the right host only within the configured heights (tantalite
 * in deepslate only).
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class WorldgenGameTests {
    // Far from every test structure; the chunk is force-loaded for the test and emptied again afterwards.
    private static final int CHUNK_X = 3_000;
    private static final int CHUNK_Z = 3_000;
    private static final int CHUNK = 16;
    private static final int MARGIN = 32;

    static {
        StorageTests.function("worldgen_lumen_quartz_in_overworld",
                helper -> inOverworld(helper, WorldgenContent.LUMEN_QUARTZ_ORE_PLACED));
        StorageTests.function("worldgen_lumen_quartz_within_config", WorldgenGameTests::withinConfig);
        StorageTests.function("worldgen_tantalite_in_overworld",
                helper -> inOverworld(helper, WorldgenContent.TANTALITE_ORE_PLACED));
        StorageTests.function("worldgen_tantalite_deepslate_within_config", WorldgenGameTests::tantaliteWithinConfig);
    }

    private WorldgenGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("worldgen"));
        StorageTests.test(event, environment, "worldgen_lumen_quartz_in_overworld", StorageTests.SMALL, 20);
        StorageTests.test(event, environment, "worldgen_lumen_quartz_within_config", StorageTests.SMALL, 20);
        StorageTests.test(event, environment, "worldgen_tantalite_in_overworld", StorageTests.SMALL, 20);
        StorageTests.test(event, environment, "worldgen_tantalite_deepslate_within_config", StorageTests.SMALL, 20);
    }

    private static void inOverworld(GameTestHelper helper, ResourceKey<PlacedFeature> feature) {
        Registry<Biome> biomes = helper.getLevel().registryAccess().lookupOrThrow(Registries.BIOME);
        PlacedFeature ore = helper.getLevel().registryAccess().lookupOrThrow(Registries.PLACED_FEATURE)
                .getValueOrThrow(feature);
        for (ResourceKey<Biome> key : List.of(Biomes.PLAINS, Biomes.DEEP_DARK, Biomes.DESERT)) {
            helper.assertTrue(biomes.getValueOrThrow(key).getGenerationSettings().hasFeature(ore),
                    feature.identifier() + " in " + key.identifier());
        }
        for (ResourceKey<Biome> key : List.of(Biomes.NETHER_WASTES, Biomes.THE_END)) {
            helper.assertFalse(biomes.getValueOrThrow(key).getGenerationSettings().hasFeature(ore),
                    "no " + feature.identifier() + " in " + key.identifier());
        }
        helper.succeed();
    }

    /**
     * Fills one chunk with stone above y 0 and deepslate below, well beyond the configured heights, runs the feature
     * once and checks every ore it placed.
     */
    private static void withinConfig(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int minY = StorageServerConfig.LUMEN_QUARTZ_MIN_Y.getAsInt();
        int maxY = StorageServerConfig.LUMEN_QUARTZ_MAX_Y.getAsInt();
        // A vanilla ore blob of size n stays well within n / 2 + 3 blocks of its centre (OreFeature).
        int reach = StorageServerConfig.LUMEN_QUARTZ_VEIN_SIZE.getAsInt() / 2 + 3;
        // Host rock well beyond the range, so ore placed outside it would be found.
        int bottom = Math.max(level.getMinY(), minY - MARGIN);
        int top = Math.min(level.getMaxY(), maxY + MARGIN);
        int x0 = CHUNK_X * CHUNK;
        int z0 = CHUNK_Z * CHUNK;
        level.setChunkForced(CHUNK_X, CHUNK_Z, true);
        try {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            fill(level, x0, z0, bottom, top, pos, false);
            boolean placed = WorldgenContent.LUMEN_QUARTZ_ORE.get().place(NoneFeatureConfiguration.INSTANCE, level,
                    level.getChunkSource().getGenerator(), RandomSource.create(42L), new BlockPos(x0, bottom, z0));
            Block stoneOre = MaterialContent.LUMEN_QUARTZ_ORE.get();
            Block deepslateOre = MaterialContent.DEEPSLATE_LUMEN_QUARTZ_ORE.get();
            int ores = 0;
            for (int y = bottom; y <= top; y++) {
                for (int x = 0; x < CHUNK; x++) {
                    for (int z = 0; z < CHUNK; z++) {
                        BlockState state = level.getBlockState(pos.set(x0 + x, y, z0 + z));
                        if (state.is(stoneOre) || state.is(deepslateOre)) {
                            ores++;
                            helper.assertTrue(y >= minY - reach && y <= maxY + reach,
                                    "ore at y " + y + " outside " + minY + ".." + maxY);
                            helper.assertTrue(state.is(y < 0 ? deepslateOre : stoneOre),
                                    "ore at y " + y + " matches its host");
                        }
                    }
                }
            }
            helper.assertTrue(placed && ores > 0, "the feature placed lumen quartz");
            helper.succeed();
        } finally {
            fill(level, x0, z0, bottom, top, new BlockPos.MutableBlockPos(), true);
            level.setChunkForced(CHUNK_X, CHUNK_Z, false);
        }
    }

    /**
     * Fills one chunk with stone above y 0 and deepslate below, from the bottom of the world to well above y 0, runs
     * the tantalite feature once and checks that every tantalite sits in deepslate within the configured heights, then
     * that the feature places nothing in stone.
     */
    private static void tantaliteWithinConfig(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int minY = StorageServerConfig.TANTALITE_MIN_Y.getAsInt();
        int maxY = StorageServerConfig.TANTALITE_MAX_Y.getAsInt();
        int reach = StorageServerConfig.TANTALITE_VEIN_SIZE.getAsInt() / 2 + 3;
        int bottom = Math.max(level.getMinY(), minY - MARGIN);
        // Stone well above y 0, so a vein reaching into stone would be found.
        int top = Math.min(level.getMaxY(), Math.max(maxY, 0) + MARGIN);
        int x0 = CHUNK_X * CHUNK;
        int z0 = CHUNK_Z * CHUNK;
        level.setChunkForced(CHUNK_X, CHUNK_Z, true);
        try {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            fill(level, x0, z0, bottom, top, pos, false);
            boolean placed = WorldgenContent.TANTALITE_ORE.get().place(NoneFeatureConfiguration.INSTANCE, level,
                    level.getChunkSource().getGenerator(), RandomSource.create(42L), new BlockPos(x0, bottom, z0));
            Block ore = TierTwoMaterialContent.DEEPSLATE_TANTALITE_ORE.get();
            int ores = 0;
            for (int y = bottom; y <= top; y++) {
                for (int x = 0; x < CHUNK; x++) {
                    for (int z = 0; z < CHUNK; z++) {
                        if (level.getBlockState(pos.set(x0 + x, y, z0 + z)).is(ore)) {
                            ores++;
                            helper.assertTrue(y >= minY - reach && y <= maxY + reach,
                                    "tantalite at y " + y + " outside " + minY + ".." + maxY);
                            helper.assertTrue(y < 0, "tantalite at y " + y + " replaced stone");
                        }
                    }
                }
            }
            helper.assertTrue(placed && ores > 0, "the feature placed tantalite");
            // The same run in a chunk of nothing but stone places nothing: deepslate is tantalite's only host.
            for (int y = bottom; y <= top; y++) {
                for (int x = 0; x < CHUNK; x++) {
                    for (int z = 0; z < CHUNK; z++) {
                        level.setBlock(pos.set(x0 + x, y, z0 + z), Blocks.STONE.defaultBlockState(),
                                Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    }
                }
            }
            helper.assertFalse(WorldgenContent.TANTALITE_ORE.get().place(NoneFeatureConfiguration.INSTANCE, level,
                    level.getChunkSource().getGenerator(), RandomSource.create(42L), new BlockPos(x0, bottom, z0)),
                    "no tantalite in stone");
            helper.succeed();
        } finally {
            fill(level, x0, z0, bottom, top, new BlockPos.MutableBlockPos(), true);
            level.setChunkForced(CHUNK_X, CHUNK_Z, false);
        }
    }

    private static void fill(ServerLevel level, int x0, int z0, int bottom, int top, BlockPos.MutableBlockPos pos,
            boolean clear) {
        for (int y = bottom; y <= top; y++) {
            BlockState state = clear
                    ? Blocks.AIR.defaultBlockState()
                    : y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
            for (int x = 0; x < CHUNK; x++) {
                for (int z = 0; z < CHUNK; z++) {
                    level.setBlock(pos.set(x0 + x, y, z0 + z), state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
        }
    }
}
