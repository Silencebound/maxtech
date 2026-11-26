package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.machine.MachineContent;
import dev.silencebound.maxtech.storage.machine.MachineKind;
import dev.silencebound.maxtech.storage.machine.ModuleContent;
import dev.silencebound.maxtech.storage.machine.ProcessingMachineBlockEntity;
import dev.silencebound.maxtech.storage.machine.StorageMachineRecipe;
import dev.silencebound.maxtech.storage.material.BuddingDataCrystalBlock;
import dev.silencebound.maxtech.storage.material.DataCrystalContent;
import dev.silencebound.maxtech.storage.material.EmberMaterialContent;
import dev.silencebound.maxtech.storage.material.MaterialContent;
import dev.silencebound.maxtech.storage.material.TierTwoMaterialContent;
import dev.silencebound.maxtech.storage.research.ResearchContent;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import dev.silencebound.maxtech.storage.workbench.WorkbenchGates;
import dev.silencebound.maxtech.storage.world.WorldContent;
import dev.silencebound.maxtech.storage.world.WorldDeviceBlock;
import dev.silencebound.maxtech.storage.world.WorldDeviceBlockEntity;
import dev.silencebound.maxtech.storage.world.WorldWorker;
import dev.silencebound.maxtech.storage.worldgen.WorldgenContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * M6d materials: ember quartz in the Nether's netherrack within its configured heights; deep data geodes in Overworld
 * biomes and the Overworld dimension type, lined with data crystal and budding data crystal; buds growing into a
 * cluster that a breaker port harvests into the network while leaving the budding block; the elite chain from a shard
 * to an elite processor and memory module IV in real machines; the speed and efficiency modules' time and FE.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class DeepMaterialGameTests {
    private static final int CHUNK_X = 3_010;
    private static final int CHUNK_Z = 3_010;
    private static final int CHUNK = 16;
    private static final int MARGIN = 24;
    private static final BlockPos LITHOGRAPH = new BlockPos(1, 1, 1);
    private static final BlockPos FABRICATOR = new BlockPos(3, 1, 1);
    private static final BlockPos ASSEMBLER = new BlockPos(5, 1, 1);
    private static final Identifier FARM_STRUCTURE = MaxtechStorage.id("empty_16x12x16");
    private static final String FARM = "material_data_crystal_grows_and_farms";
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        StorageTests.function("worldgen_ember_quartz_in_nether", DeepMaterialGameTests::emberInNether);
        StorageTests.function("worldgen_ember_quartz_netherrack_within_config", DeepMaterialGameTests::emberWithin);
        StorageTests.function("worldgen_deep_data_geode_where", DeepMaterialGameTests::geodeWhere);
        StorageTests.function("worldgen_deep_data_geode_lined", DeepMaterialGameTests::geodeLined);
        StorageTests.function("machine_elite_chain_and_memory_4", DeepMaterialGameTests::eliteChain);
        StorageTests.function("machine_ember_modules", DeepMaterialGameTests::emberModules);
        FUNCTIONS.register(FARM, () -> StorageTestEnvironment.clearing(DeepMaterialGameTests::farm));
    }

    private DeepMaterialGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("deep_material"));
        StorageTests.test(event, environment, "worldgen_ember_quartz_in_nether", StorageTests.SMALL, 20);
        StorageTests.test(event, environment, "worldgen_ember_quartz_netherrack_within_config", StorageTests.SMALL, 20);
        StorageTests.test(event, environment, "worldgen_deep_data_geode_where", StorageTests.SMALL, 20);
        StorageTests.test(event, environment, "worldgen_deep_data_geode_lined", StorageTests.SMALL, 20);
        StorageTests.test(event, environment, "machine_elite_chain_and_memory_4", StorageTests.SMALL, 3000);
        StorageTests.test(event, environment, "machine_ember_modules", StorageTests.SMALL, 1500);
        Holder<TestEnvironmentDefinition<?>> farm = event.registerEnvironment(MaxtechStorage.id("deep_material_farm"),
                StorageTestEnvironment.plain());
        event.registerTest(MaxtechStorage.id(FARM),
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(FARM)),
                        new TestData<>(farm, FARM_STRUCTURE, 400, 0, true)));
    }

    private static void emberInNether(GameTestHelper helper) {
        Registry<Biome> biomes = helper.getLevel().registryAccess().lookupOrThrow(Registries.BIOME);
        PlacedFeature ore = helper.getLevel().registryAccess().lookupOrThrow(Registries.PLACED_FEATURE)
                .getValueOrThrow(WorldgenContent.EMBER_QUARTZ_ORE_PLACED);
        for (ResourceKey<Biome> key : List.of(Biomes.NETHER_WASTES, Biomes.BASALT_DELTAS, Biomes.CRIMSON_FOREST)) {
            helper.assertTrue(biomes.getValueOrThrow(key).getGenerationSettings().hasFeature(ore),
                    "ember quartz in " + key.identifier());
        }
        for (ResourceKey<Biome> key : List.of(Biomes.PLAINS, Biomes.THE_END)) {
            helper.assertFalse(biomes.getValueOrThrow(key).getGenerationSettings().hasFeature(ore),
                    "no ember quartz in " + key.identifier());
        }
        helper.succeed();
    }

    /**
     * One run of the feature on a chunk of netherrack places ember quartz only in netherrack within the configured
     * heights (with a vein's reach); a chunk of stone gets none.
     */
    private static void emberWithin(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int minY = StorageServerConfig.EMBER_QUARTZ_MIN_Y.getAsInt();
        int maxY = StorageServerConfig.EMBER_QUARTZ_MAX_Y.getAsInt();
        int reach = StorageServerConfig.EMBER_QUARTZ_VEIN_SIZE.getAsInt() / 2 + 3;
        int bottom = Math.max(level.getMinY(), minY - MARGIN);
        int top = Math.min(level.getMaxY(), maxY + MARGIN);
        int x0 = CHUNK_X * CHUNK;
        int z0 = CHUNK_Z * CHUNK;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        level.setChunkForced(CHUNK_X, CHUNK_Z, true);
        try {
            fill(level, x0, z0, bottom, top, Blocks.NETHERRACK.defaultBlockState());
            // The Overworld is not in ember quartz's dimension tag, so the full feature places nothing here.
            helper.assertFalse(WorldgenContent.EMBER_QUARTZ_ORE.get().place(NoneFeatureConfiguration.INSTANCE, level,
                    level.getChunkSource().getGenerator(), RandomSource.create(42L), new BlockPos(x0, bottom, z0)),
                    "ember quartz outside its dimension tag");
            boolean placed = WorldgenContent.EMBER_QUARTZ_ORE.get().placeVeins(level,
                    level.getChunkSource().getGenerator(), RandomSource.create(42L), new BlockPos(x0, bottom, z0));
            Block ore = EmberMaterialContent.EMBER_QUARTZ_ORE.get();
            int ores = 0;
            for (int y = bottom; y <= top; y++) {
                for (int x = 0; x < CHUNK; x++) {
                    for (int z = 0; z < CHUNK; z++) {
                        if (level.getBlockState(pos.set(x0 + x, y, z0 + z)).is(ore)) {
                            ores++;
                            helper.assertTrue(y >= minY - reach && y <= maxY + reach,
                                    "ember quartz at y " + y + " outside " + minY + ".." + maxY);
                        }
                    }
                }
            }
            helper.assertTrue(placed && ores > 0, "the feature placed ember quartz");
            fill(level, x0, z0, bottom, top, Blocks.STONE.defaultBlockState());
            helper.assertFalse(WorldgenContent.EMBER_QUARTZ_ORE.get().placeVeins(level,
                    level.getChunkSource().getGenerator(), RandomSource.create(42L), new BlockPos(x0, bottom, z0)),
                    "no ember quartz in stone");
            helper.succeed();
        } finally {
            fill(level, x0, z0, bottom, top, Blocks.AIR.defaultBlockState());
            level.setChunkForced(CHUNK_X, CHUNK_Z, false);
        }
    }

    /** Geodes go to Overworld biomes through the biome tag and only to the Overworld's dimension type. */
    private static void geodeWhere(GameTestHelper helper) {
        Registry<Biome> biomes = helper.getLevel().registryAccess().lookupOrThrow(Registries.BIOME);
        PlacedFeature geode = helper.getLevel().registryAccess().lookupOrThrow(Registries.PLACED_FEATURE)
                .getValueOrThrow(WorldgenContent.DEEP_DATA_GEODE_PLACED);
        for (ResourceKey<Biome> key : List.of(Biomes.PLAINS, Biomes.DEEP_DARK)) {
            helper.assertTrue(biomes.getValueOrThrow(key).getGenerationSettings().hasFeature(geode),
                    "geodes in " + key.identifier());
        }
        for (ResourceKey<Biome> key : List.of(Biomes.NETHER_WASTES, Biomes.THE_END)) {
            helper.assertFalse(biomes.getValueOrThrow(key).getGenerationSettings().hasFeature(geode),
                    "no geodes in " + key.identifier());
        }
        Registry<DimensionType> types = helper.getLevel().registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE);
        helper.assertTrue(
                types.getOrThrow(BuiltinDimensionTypes.OVERWORLD).is(WorldgenContent.DEEP_DATA_GEODE_DIMENSIONS),
                "the Overworld may hold geodes");
        helper.assertFalse(
                types.getOrThrow(BuiltinDimensionTypes.NETHER).is(WorldgenContent.DEEP_DATA_GEODE_DIMENSIONS),
                "the Nether may not");
        helper.succeed();
    }

    /** A geode placed into solid deepslate is lined with data crystal and has budding data crystal. */
    private static void geodeLined(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x0 = CHUNK_X * CHUNK;
        int z0 = CHUNK_Z * CHUNK;
        int bottom = -50;
        int top = -10;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        level.setChunkForced(CHUNK_X, CHUNK_Z, true);
        level.setChunkForced(CHUNK_X + 1, CHUNK_Z, true);
        level.setChunkForced(CHUNK_X, CHUNK_Z + 1, true);
        level.setChunkForced(CHUNK_X + 1, CHUNK_Z + 1, true);
        try {
            fillBox(level, x0, z0, 2 * CHUNK, bottom, top, Blocks.DEEPSLATE.defaultBlockState());
            boolean placed = WorldgenContent.DEEP_DATA_GEODE.get().placeGeode(level,
                    level.getChunkSource().getGenerator(), RandomSource.create(7L),
                    new BlockPos(x0 + CHUNK, -30, z0 + CHUNK));
            helper.assertTrue(placed, "the geode was placed");
            int budding = 0;
            int lining = 0;
            for (int y = bottom; y <= top; y++) {
                for (int x = 0; x < 2 * CHUNK; x++) {
                    for (int z = 0; z < 2 * CHUNK; z++) {
                        BlockState state = level.getBlockState(pos.set(x0 + x, y, z0 + z));
                        if (state.is(DataCrystalContent.BUDDING_DATA_CRYSTAL.get())) {
                            budding++;
                        } else if (state.is(DataCrystalContent.DATA_CRYSTAL_BLOCK.get())) {
                            lining++;
                        }
                    }
                }
            }
            helper.assertTrue(budding > 0 && lining > 0, "budding " + budding + ", lining " + lining);
            helper.succeed();
        } finally {
            fillBox(level, x0, z0, 2 * CHUNK, bottom, top, Blocks.AIR.defaultBlockState());
            level.setChunkForced(CHUNK_X, CHUNK_Z, false);
            level.setChunkForced(CHUNK_X + 1, CHUNK_Z, false);
            level.setChunkForced(CHUNK_X, CHUNK_Z + 1, false);
            level.setChunkForced(CHUNK_X + 1, CHUNK_Z + 1, false);
        }
    }

    /**
     * A budding data crystal grows a bud through all four stages on its face; a breaker port facing the cluster stores
     * its shards in the network (two without a tool, as amethyst), and one facing the budding block leaves it.
     */
    private static void farm(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 10_000);
        NetworkGameTests.cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_1.type().get());
        NetworkGameTests.device(helper, NetworkGameTests.at(3));
        BlockPos device = NetworkGameTests.at(4);
        BlockPos target = NetworkGameTests.at(5);
        BlockPos budding = NetworkGameTests.at(6);
        helper.setBlock(device,
                WorldContent.BREAKER.get().defaultBlockState().setValue(WorldDeviceBlock.FACING, Direction.EAST));
        WorldWorker breaker = helper.getBlockEntity(device, WorldDeviceBlockEntity.class).worker();
        helper.setBlock(budding, DataCrystalContent.BUDDING_DATA_CRYSTAL.get());
        ItemKey shard = ItemKey.of(DataCrystalContent.DATA_CRYSTAL.get());
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.state(helper, NetworkGameTests.at(4)))
                .thenExecute(() -> {
                    for (AmethystClusterBlock stage : DataCrystalContent.growthStages()) {
                        BuddingDataCrystalBlock.grow(helper.getLevel(), helper.absolutePos(budding), Direction.WEST);
                        BlockState grown = helper.getBlockState(target);
                        helper.assertTrue(grown.is(stage), "grew " + stage + ", have " + grown);
                        helper.assertValueEqual(grown.getValue(AmethystClusterBlock.FACING), Direction.WEST,
                                "facing away from the budding block");
                    }
                    breaker.setMode(1);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.state(helper, NetworkGameTests.at(1)).contents().amount(shard), 2L,
                        "shards stored"))
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.AIR, target);
                    helper.assertBlockPresent(DataCrystalContent.BUDDING_DATA_CRYSTAL.get(), budding);
                    helper.setBlock(budding, Blocks.AIR);
                    helper.setBlock(target, DataCrystalContent.BUDDING_DATA_CRYSTAL.get());
                }).thenIdle(40)
                .thenExecute(() -> helper.assertBlockPresent(DataCrystalContent.BUDDING_DATA_CRYSTAL.get(), target))
                .thenSucceed();
    }

    /**
     * Shard to wafer, ember doping, an elite memory die printed with the memory mask and an elite memory processor
     * assembled with superconductor wire, all through loaded recipes in real machines that name the elite research;
     * four modules III round it make memory module IV, 64 KB, which the workbench gates on its research.
     */
    private static void eliteChain(GameTestHelper helper) {
        helper.setBlock(LITHOGRAPH, MachineContent.block(MachineKind.LITHOGRAPH));
        helper.setBlock(ASSEMBLER, MachineContent.block(MachineKind.ASSEMBLER));
        ProcessingMachineBlockEntity lithograph = MachineChainGameTests.machine(helper, LITHOGRAPH);
        ProcessingMachineBlockEntity assembler = MachineChainGameTests.machine(helper, ASSEMBLER);
        List<ItemStack> bag = new ArrayList<>();
        helper.assertValueEqual(research(helper, MachineKind.LITHOGRAPH, "ember_doped_wafer"),
                ResearchGates.ELITE_PROCESSORS.toString(), "doping names the research");
        helper.assertValueEqual(research(helper, MachineKind.ASSEMBLER, "elite_io_processor"),
                ResearchGates.ELITE_PROCESSORS.toString(), "assembly names the research");
        helper.startSequence()
                .thenExecute(() -> MachineChainGameTests.feed(lithograph, "slice",
                        new ItemStack(DataCrystalContent.DATA_CRYSTAL.get())))
                .thenWaitUntil(() -> MachineChainGameTests.collect(helper, lithograph,
                        DataCrystalContent.DATA_CRYSTAL_WAFER.get(), 2, bag))
                .thenExecute(() -> MachineChainGameTests.feed(lithograph, "dope",
                        MachineChainGameTests.take(bag, DataCrystalContent.DATA_CRYSTAL_WAFER.get()),
                        new ItemStack(EmberMaterialContent.EMBER_QUARTZ.get())))
                .thenWaitUntil(() -> MachineChainGameTests.collect(helper, lithograph,
                        DataCrystalContent.EMBER_DOPED_WAFER.get(), 1, bag))
                .thenExecute(() -> MachineChainGameTests.feed(lithograph, "print",
                        MachineChainGameTests.take(bag, DataCrystalContent.EMBER_DOPED_WAFER.get()),
                        new ItemStack(TierTwoMaterialContent.MEMORY_MASK.get())))
                .thenWaitUntil(() -> MachineChainGameTests.collect(helper, lithograph,
                        DataCrystalContent.ELITE_MEMORY_DIE.get(), 2, bag))
                .thenExecute(() -> MachineChainGameTests.feed(assembler, "",
                        MachineChainGameTests.take(bag, DataCrystalContent.ELITE_MEMORY_DIE.get()),
                        new ItemStack(TierTwoMaterialContent.SUPERCONDUCTOR_WIRE.get(), 2),
                        new ItemStack(TierTwoMaterialContent.TANTALUM_CAPACITOR.get()),
                        new ItemStack(MaterialContent.CIRCUIT_BOARD.get())))
                .thenWaitUntil(() -> MachineChainGameTests.collect(helper, assembler,
                        DataCrystalContent.ELITE_MEMORY_PROCESSOR.get(), 1, bag))
                .thenExecute(() -> {
                    ItemStack processor = MachineChainGameTests.take(bag,
                            DataCrystalContent.ELITE_MEMORY_PROCESSOR.get());
                    helper.assertTrue(processor.is(ResearchContent.PROCESSORS), "elite processors count as processors");
                    ItemStack three = new ItemStack(CellContent.MEMORY_MODULE_3.get());
                    ItemStack wire = new ItemStack(TierTwoMaterialContent.SUPERCONDUCTOR_WIRE.get());
                    ItemStack capacitor = new ItemStack(TierTwoMaterialContent.TANTALUM_CAPACITOR.get());
                    ItemStack module = MachineChainGameTests.craft(helper, 3, 3, three, wire, three.copy(), capacitor,
                            processor, capacitor.copy(), three.copy(),
                            new ItemStack(MaterialContent.CIRCUIT_BOARD.get()), three.copy());
                    helper.assertTrue(module.is(CellContent.MEMORY_MODULE_4.get()), "crafted memory module IV");
                    helper.assertValueEqual(CellContent.MEMORY_MODULE_4.get().bytes(), 64L * 1024, "memory IV bytes");
                    helper.assertValueEqual(Objects.toString(WorkbenchGates.unlock(module)),
                            ResearchGates.MEMORY_MODULE_4.toString(), "the workbench gates memory IV");
                }).thenExecute(() -> MachineGameTests.clear(helper)).thenSucceed();
    }

    static String research(GameTestHelper helper, MachineKind kind, String name) {
        Identifier id = MaxtechStorage.id(kind.id() + "/" + name);
        for (RecipeHolder<StorageMachineRecipe> holder : helper.getLevel().recipeAccess().recipeMap()
                .byType(MachineContent.recipeType(kind))) {
            if (holder.id().identifier().equals(id)) {
                return holder.value().research().map(Identifier::toString).orElse("none");
            }
        }
        throw helper.assertionException(net.minecraft.network.chat.Component.literal("No recipe " + id));
    }

    /**
     * Slicing silicon (8 FE a tick for 120 ticks, 960 FE) takes 60 ticks and 2,400 FE with a speed module (+100 %
     * speed, +150 % FE) and 576 FE over 120 ticks with an efficiency module (-40 %); the slot takes one module, and the
     * fabricator takes ember modules too.
     */
    private static void emberModules(GameTestHelper helper) {
        helper.setBlock(LITHOGRAPH, MachineContent.block(MachineKind.LITHOGRAPH));
        helper.setBlock(FABRICATOR, MachineContent.block(MachineKind.FABRICATOR));
        ProcessingMachineBlockEntity lithograph = MachineChainGameTests.machine(helper, LITHOGRAPH);
        ProcessingMachineBlockEntity fabricator = MachineChainGameTests.machine(helper, FABRICATOR);
        helper.assertValueEqual(
                TierTwoMachineGameTests.insert(fabricator.modules(),
                        new ItemStack(ModuleContent.EFFICIENCY_MODULE.get())),
                1, "the fabricator takes an ember module");
        helper.assertValueEqual(
                TierTwoMachineGameTests.insert(lithograph.modules(), new ItemStack(ModuleContent.SPEED_MODULE.get())),
                1, "the lithograph takes the speed module");
        helper.assertValueEqual(TierTwoMachineGameTests.insert(lithograph.modules(),
                new ItemStack(ModuleContent.PRECISION_MODULE.get())), 0, "one module slot");
        List<ItemStack> bag = new ArrayList<>();
        Item wafer = MaterialContent.SILICON_WAFER.get();
        long[] start = new long[1];
        int[] ticks = new int[1];
        helper.startSequence().thenExecute(() -> {
            MachineChainGameTests.feed(lithograph, "slice", new ItemStack(MaterialContent.SILICON.get()));
            start[0] = lithograph.energy().stored();
            ticks[0] = 0;
        }).thenWaitUntil(() -> {
            ticks[0]++;
            MachineChainGameTests.collect(helper, lithograph, wafer, 3, bag);
        }).thenExecute(() -> {
            helper.assertValueEqual(start[0] - lithograph.energy().stored(), 2400L, "FE with the speed module");
            helper.assertTrue(ticks[0] <= 70, "a fast run took " + ticks[0] + " ticks");
            lithograph.modules().set(0, net.neoforged.neoforge.transfer.item.ItemResource.EMPTY, 0);
            TierTwoMachineGameTests.insert(lithograph.modules(), new ItemStack(ModuleContent.EFFICIENCY_MODULE.get()));
            MachineChainGameTests.feed(lithograph, "slice", new ItemStack(MaterialContent.SILICON.get()));
            start[0] = lithograph.energy().stored();
            ticks[0] = 0;
        }).thenWaitUntil(() -> {
            ticks[0]++;
            MachineChainGameTests.collect(helper, lithograph, wafer, 3, bag);
        }).thenExecute(() -> {
            helper.assertValueEqual(start[0] - lithograph.energy().stored(), 576L, "FE with the efficiency module");
            helper.assertTrue(ticks[0] >= 115, "an efficient run took " + ticks[0] + " ticks");
        }).thenExecute(() -> MachineGameTests.clear(helper)).thenSucceed();
    }

    private static void fill(ServerLevel level, int x0, int z0, int bottom, int top, BlockState state) {
        fillBox(level, x0, z0, CHUNK, bottom, top, state);
    }

    private static void fillBox(ServerLevel level, int x0, int z0, int size, int bottom, int top, BlockState state) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = bottom; y <= top; y++) {
            for (int x = 0; x < size; x++) {
                for (int z = 0; z < size; z++) {
                    level.setBlock(pos.set(x0 + x, y, z0 + z), state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
        }
    }
}
