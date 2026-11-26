package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.content.ContentToggle;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.config.StorageModules;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.machine.MachineContent;
import dev.silencebound.maxtech.storage.machine.MachineKind;
import dev.silencebound.maxtech.storage.machine.ModuleContent;
import dev.silencebound.maxtech.storage.machine.ProcessingMachineBlockEntity;
import dev.silencebound.maxtech.storage.material.DataCrystalContent;
import dev.silencebound.maxtech.storage.material.MaterialContent;
import dev.silencebound.maxtech.storage.material.VoidCrystalContent;
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
import java.util.Optional;
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
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
// PLATFORM-LEAK: the tests empty a module slot directly, as the machine GameTests do.
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * M6f void materials: void geodes in the outer End biomes and the End's dimension type only, placed under an island's
 * surface and lined with void crystal; budding void crystal growing into a cluster that a breaker harvests, and
 * stopping with the voidGeodes switch off; the void lattice in a real lithograph and memory modules V and VI crafted,
 * sized and gated; the void module's parallel runs, its FE and how it stacks with an ember module.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class VoidMaterialGameTests {
    private static final int CHUNK_X = 3_210;
    private static final int CHUNK_Z = 3_210;
    private static final int CHUNK = 16;
    private static final int GROUND_BOTTOM = 20;
    private static final int SURFACE = 70;
    private static final BlockPos LITHOGRAPH = new BlockPos(1, 1, 1);
    private static final BlockPos BUDDING = new BlockPos(5, 1, 1);
    private static final Identifier FARM_STRUCTURE = MaxtechStorage.id("empty_16x12x16");
    private static final String FARM = "material_void_crystal_grows_and_farms";
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        StorageTests.function("worldgen_void_geode_where", VoidMaterialGameTests::geodeWhere);
        StorageTests.function("worldgen_void_geode_under_surface", VoidMaterialGameTests::geodeUnderSurface);
        StorageTests.function("material_void_geodes_module_off", VoidMaterialGameTests::moduleOff);
        StorageTests.function("machine_void_lattice_and_memory_5_6", VoidMaterialGameTests::latticeAndMemory);
        StorageTests.function("machine_void_module_parallel", VoidMaterialGameTests::voidModule);
        FUNCTIONS.register(FARM, () -> StorageTestEnvironment.clearing(VoidMaterialGameTests::farm));
    }

    private VoidMaterialGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("void_material"));
        StorageTests.test(event, environment, "worldgen_void_geode_where", StorageTests.SMALL, 20);
        StorageTests.test(event, environment, "worldgen_void_geode_under_surface", StorageTests.SMALL, 20);
        StorageTests.test(event, environment, "material_void_geodes_module_off", StorageTests.SMALL, 20);
        StorageTests.test(event, environment, "machine_void_lattice_and_memory_5_6", StorageTests.SMALL, 600);
        StorageTests.test(event, environment, "machine_void_module_parallel", StorageTests.SMALL, 1500);
        Holder<TestEnvironmentDefinition<?>> farm = event.registerEnvironment(MaxtechStorage.id("void_material_farm"),
                StorageTestEnvironment.plain());
        event.registerTest(MaxtechStorage.id(FARM),
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(FARM)),
                        new TestData<>(farm, FARM_STRUCTURE, 400, 0, true)));
    }

    /** Void geodes go to the outer End biomes through the biome tag, not the main island, and only to the End. */
    private static void geodeWhere(GameTestHelper helper) {
        Registry<Biome> biomes = helper.getLevel().registryAccess().lookupOrThrow(Registries.BIOME);
        PlacedFeature geode = helper.getLevel().registryAccess().lookupOrThrow(Registries.PLACED_FEATURE)
                .getValueOrThrow(WorldgenContent.VOID_GEODE_PLACED);
        for (ResourceKey<Biome> key : List.of(Biomes.END_HIGHLANDS, Biomes.END_MIDLANDS, Biomes.SMALL_END_ISLANDS,
                Biomes.END_BARRENS)) {
            helper.assertTrue(biomes.getValueOrThrow(key).getGenerationSettings().hasFeature(geode),
                    "void geodes in " + key.identifier());
        }
        for (ResourceKey<Biome> key : List.of(Biomes.THE_END, Biomes.PLAINS, Biomes.NETHER_WASTES)) {
            helper.assertFalse(biomes.getValueOrThrow(key).getGenerationSettings().hasFeature(geode),
                    "no void geodes in " + key.identifier());
        }
        Registry<DimensionType> types = helper.getLevel().registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE);
        helper.assertTrue(types.getOrThrow(BuiltinDimensionTypes.END).is(WorldgenContent.VOID_GEODE_DIMENSIONS),
                "the End may hold void geodes");
        helper.assertFalse(types.getOrThrow(BuiltinDimensionTypes.OVERWORLD).is(WorldgenContent.VOID_GEODE_DIMENSIONS),
                "the Overworld may not");
        helper.succeed();
    }

    /**
     * In a slab of end stone with its top at y 70, a geode goes in under the surface, lined with void crystal and with
     * budding void crystal, and nothing grows above the surface; a column without ground, or air at the chosen depth,
     * gets none, and the full feature places nothing outside the End.
     */
    private static void geodeUnderSurface(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x0 = CHUNK_X * CHUNK;
        int z0 = CHUNK_Z * CHUNK;
        int top = SURFACE + CHUNK;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        forceChunks(level, true);
        try {
            fillBox(level, x0, z0, GROUND_BOTTOM, top, Blocks.AIR.defaultBlockState());
            helper.assertFalse(WorldgenContent.VOID_GEODE.get().placeUnder(level, level.getChunkSource().getGenerator(),
                    RandomSource.create(7L), x0 + CHUNK, SURFACE, z0 + CHUNK), "no geode in air");
            helper.assertFalse(
                    WorldgenContent.VOID_GEODE.get().placeUnder(level, level.getChunkSource().getGenerator(),
                            RandomSource.create(7L), x0 + CHUNK, level.getMinY(), z0 + CHUNK),
                    "no geode in a column without ground");
            fillBox(level, x0, z0, GROUND_BOTTOM, SURFACE, Blocks.END_STONE.defaultBlockState());
            RandomSource random = RandomSource.create(7L);
            for (int i = 0; i < 50; i++) {
                helper.assertFalse(WorldgenContent.VOID_GEODE.get()
                        .place(new FeaturePlaceContext<>(Optional.empty(), level, level.getChunkSource().getGenerator(),
                                random, new BlockPos(x0 + CHUNK, 0, z0 + CHUNK), NoneFeatureConfiguration.INSTANCE)),
                        "the full feature places nothing outside the End");
            }
            boolean placed = WorldgenContent.VOID_GEODE.get().placeUnder(level, level.getChunkSource().getGenerator(),
                    RandomSource.create(7L), x0 + CHUNK, SURFACE, z0 + CHUNK);
            helper.assertTrue(placed, "the geode was placed");
            int budding = 0;
            int lining = 0;
            int highest = Integer.MIN_VALUE;
            for (int y = GROUND_BOTTOM; y <= top; y++) {
                for (int x = 0; x < 2 * CHUNK; x++) {
                    for (int z = 0; z < 2 * CHUNK; z++) {
                        BlockState state = level.getBlockState(pos.set(x0 + x, y, z0 + z));
                        if (state.is(VoidCrystalContent.BUDDING_VOID_CRYSTAL.get())) {
                            budding++;
                            highest = Math.max(highest, y);
                        } else if (state.is(VoidCrystalContent.VOID_CRYSTAL_BLOCK.get())) {
                            lining++;
                            highest = Math.max(highest, y);
                        } else if (y > SURFACE && !state.isAir()) {
                            throw helper.assertionException(net.minecraft.network.chat.Component
                                    .literal("geode block above the surface at y " + y + ": " + state));
                        }
                    }
                }
            }
            helper.assertTrue(budding > 0 && lining > 0, "budding " + budding + ", lining " + lining);
            int deepest = SURFACE - StorageServerConfig.VOID_GEODE_MAX_DEPTH.getAsInt();
            helper.assertTrue(highest < SURFACE && highest > deepest - CHUNK,
                    "crystal up to y " + highest + ", under the surface at " + SURFACE);
            helper.succeed();
        } finally {
            fillBox(level, x0, z0, GROUND_BOTTOM, top, Blocks.AIR.defaultBlockState());
            forceChunks(level, false);
        }
    }

    /**
     * With modules.voidGeodes off, budding void crystal leaves JEI and the creative tab and grows nothing on random
     * ticks; back on, it grows again.
     */
    private static void moduleOff(GameTestHelper helper) {
        helper.setBlock(BUDDING, VoidCrystalContent.BUDDING_VOID_CRYSTAL.get());
        Item budding = VoidCrystalContent.BUDDING_VOID_CRYSTAL.get().asItem();
        StorageModules.VOID_GEODES.overrideEnabled(false);
        try {
            helper.assertTrue(ContentToggle.hiddenItems().contains(budding), "budding hidden");
            randomTicks(helper, BUDDING, 200);
            helper.assertValueEqual(buds(helper, BUDDING), 0, "buds grown with void geodes off");
        } finally {
            StorageModules.VOID_GEODES.overrideEnabled(null);
        }
        helper.assertFalse(ContentToggle.hiddenItems().contains(budding), "budding shown again");
        randomTicks(helper, BUDDING, 200);
        helper.assertTrue(buds(helper, BUDDING) > 0, "buds grow with void geodes on");
        MachineGameTests.clear(helper);
        helper.succeed();
    }

    /**
     * A budding void crystal grows a bud through all four stages; a breaker port facing the cluster stores two void
     * crystal shards in the network and leaves the budding block.
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
        helper.setBlock(budding, VoidCrystalContent.BUDDING_VOID_CRYSTAL.get());
        ItemKey shard = ItemKey.of(VoidCrystalContent.VOID_CRYSTAL.get());
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.state(helper, NetworkGameTests.at(4)))
                .thenExecute(() -> {
                    for (AmethystClusterBlock stage : VoidCrystalContent.growthStages()) {
                        VoidCrystalContent.BUDDING_VOID_CRYSTAL.get().growOn(helper.getLevel(),
                                helper.absolutePos(budding), Direction.WEST);
                        BlockState grown = helper.getBlockState(target);
                        helper.assertTrue(grown.is(stage), "grew " + stage + ", have " + grown);
                    }
                    breaker.setMode(1);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.state(helper, NetworkGameTests.at(1)).contents().amount(shard), 2L,
                        "void crystal stored"))
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.AIR, target);
                    helper.assertBlockPresent(VoidCrystalContent.BUDDING_VOID_CRYSTAL.get(), budding);
                }).thenSucceed();
    }

    /**
     * A data crystal wafer and two void crystal dope into a void lattice in a real lithograph, through a recipe that
     * names the void lattice research; four lattices, two elite memory and two elite logic processors and a board make
     * memory module V (1 MB), four of those and five lattices memory module VI (16 MB), each gated in the workbench.
     */
    private static void latticeAndMemory(GameTestHelper helper) {
        helper.setBlock(LITHOGRAPH, MachineContent.block(MachineKind.LITHOGRAPH));
        ProcessingMachineBlockEntity lithograph = MachineChainGameTests.machine(helper, LITHOGRAPH);
        helper.assertValueEqual(DeepMaterialGameTests.research(helper, MachineKind.LITHOGRAPH, "void_lattice"),
                ResearchGates.VOID_LATTICE.toString(), "the lattice names its research");
        List<ItemStack> bag = new ArrayList<>();
        helper.startSequence()
                .thenExecute(() -> MachineChainGameTests.feed(lithograph, "dope",
                        new ItemStack(DataCrystalContent.DATA_CRYSTAL_WAFER.get()),
                        new ItemStack(VoidCrystalContent.VOID_CRYSTAL.get(), 2)))
                .thenWaitUntil(() -> MachineChainGameTests.collect(helper, lithograph,
                        VoidCrystalContent.VOID_LATTICE.get(), 1, bag))
                .thenExecute(() -> {
                    ItemStack lattice = new ItemStack(VoidCrystalContent.VOID_LATTICE.get());
                    ItemStack memory = new ItemStack(DataCrystalContent.ELITE_MEMORY_PROCESSOR.get());
                    ItemStack logic = new ItemStack(DataCrystalContent.ELITE_LOGIC_PROCESSOR.get());
                    ItemStack five = MachineChainGameTests.craft(helper, 3, 3, lattice, memory, lattice.copy(), logic,
                            new ItemStack(MaterialContent.CIRCUIT_BOARD.get()), logic.copy(), lattice.copy(),
                            memory.copy(), lattice.copy());
                    helper.assertTrue(five.is(CellContent.MEMORY_MODULE_5.get()), "crafted memory module V");
                    helper.assertValueEqual(CellContent.MEMORY_MODULE_5.get().bytes(), 1L << 20, "memory V bytes");
                    helper.assertValueEqual(Objects.toString(WorkbenchGates.unlock(five)),
                            ResearchGates.MEMORY_MODULE_5.toString(), "the workbench gates memory V");
                    ItemStack six = MachineChainGameTests.craft(helper, 3, 3, five, lattice.copy(), five.copy(),
                            lattice.copy(), lattice.copy(), lattice.copy(), five.copy(), lattice.copy(), five.copy());
                    helper.assertTrue(six.is(CellContent.MEMORY_MODULE_6.get()), "crafted memory module VI");
                    helper.assertValueEqual(CellContent.MEMORY_MODULE_6.get().bytes(), 1L << 24, "memory VI bytes");
                    helper.assertValueEqual(Objects.toString(WorkbenchGates.unlock(six)),
                            ResearchGates.MEMORY_MODULE_6.toString(), "the workbench gates memory VI");
                }).thenExecute(() -> MachineGameTests.clear(helper)).thenSucceed();
    }

    /**
     * Slicing silicon (8 FE a tick for 120 ticks, 960 FE, 3 wafers) with a void module: 5 silicon take two runs, 4
     * recipes at once and then the 1 left, 15 wafers for 2 x 2,880 FE (+200 % per run, however many it does); with a
     * speed module beside it 4 silicon go in one run of about 60 ticks for 7,200 FE. The machine takes one void module
     * beside one other module and no second of either.
     */
    private static void voidModule(GameTestHelper helper) {
        helper.setBlock(LITHOGRAPH, MachineContent.block(MachineKind.LITHOGRAPH));
        ProcessingMachineBlockEntity lithograph = MachineChainGameTests.machine(helper, LITHOGRAPH);
        Item wafer = MaterialContent.SILICON_WAFER.get();
        Item silicon = MaterialContent.SILICON.get();
        helper.assertValueEqual(insert(lithograph, ModuleContent.VOID_MODULE.get()), 1, "the void module goes in");
        helper.assertValueEqual(insert(lithograph, ModuleContent.VOID_MODULE.get()), 0, "one void module");
        long[] start = new long[1];
        int[] ticks = new int[1];
        helper.startSequence().thenExecute(() -> {
            MachineChainGameTests.feed(lithograph, "slice", new ItemStack(silicon, 5));
            start[0] = lithograph.energy().stored();
        }).thenWaitUntil(() -> helper.assertTrue(count(lithograph, wafer) >= 15, "15 wafers")).thenExecute(() -> {
            helper.assertValueEqual(count(lithograph, wafer), 15, "every silicon sliced");
            helper.assertTrue(lithograph.inputs().stack(0).isEmpty(), "no silicon left");
            helper.assertValueEqual(start[0] - lithograph.energy().stored(), 2L * 2880,
                    "two runs with the void module");
            emptyOutputs(lithograph);
            helper.assertValueEqual(insert(lithograph, ModuleContent.SPEED_MODULE.get()), 1,
                    "a speed module goes in beside it");
            helper.assertValueEqual(insert(lithograph, ModuleContent.EFFICIENCY_MODULE.get()), 0,
                    "no second ember module");
            MachineChainGameTests.feed(lithograph, "slice", new ItemStack(silicon, 4));
            start[0] = lithograph.energy().stored();
            ticks[0] = 0;
        }).thenWaitUntil(() -> {
            ticks[0]++;
            helper.assertTrue(count(lithograph, wafer) >= 12, "12 fast wafers");
        }).thenExecute(() -> {
            helper.assertValueEqual(start[0] - lithograph.energy().stored(), 7200L, "FE with void and speed");
            helper.assertTrue(ticks[0] <= 70, "a fast void run took " + ticks[0] + " ticks");
            lithograph.modules().set(0, ItemResource.EMPTY, 0);
            lithograph.modules().set(1, ItemResource.EMPTY, 0);
        }).thenExecute(() -> MachineGameTests.clear(helper)).thenSucceed();
    }

    private static int insert(ProcessingMachineBlockEntity machine, Item module) {
        return TierTwoMachineGameTests.insert(machine.modules(), new ItemStack(module));
    }

    private static int count(ProcessingMachineBlockEntity machine, Item item) {
        int found = 0;
        for (int slot = 0; slot < machine.outputs().size(); slot++) {
            if (machine.outputs().stack(slot).is(item)) {
                found += machine.outputs().stack(slot).getCount();
            }
        }
        return found;
    }

    private static void emptyOutputs(ProcessingMachineBlockEntity machine) {
        for (int slot = 0; slot < machine.outputs().size(); slot++) {
            machine.outputs().set(slot, ItemResource.EMPTY, 0);
        }
    }

    private static void randomTicks(GameTestHelper helper, BlockPos pos, int ticks) {
        RandomSource random = RandomSource.create(3);
        BlockPos at = helper.absolutePos(pos);
        for (int i = 0; i < ticks; i++) {
            BlockState state = helper.getLevel().getBlockState(at);
            state.randomTick(helper.getLevel(), at, random);
        }
    }

    private static int buds(GameTestHelper helper, BlockPos pos) {
        int buds = 0;
        for (Direction side : Direction.values()) {
            BlockPos at = pos.relative(side);
            if (helper.getBlockState(at).getBlock() instanceof AmethystClusterBlock) {
                buds++;
            }
        }
        return buds;
    }

    private static void forceChunks(ServerLevel level, boolean force) {
        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                level.setChunkForced(CHUNK_X + dx, CHUNK_Z + dz, force);
            }
        }
    }

    private static void fillBox(ServerLevel level, int x0, int z0, int bottom, int top, BlockState state) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = bottom; y <= top; y++) {
            for (int x = 0; x < 2 * CHUNK; x++) {
                for (int z = 0; z < 2 * CHUNK; z++) {
                    level.setBlock(pos.set(x0 + x, y, z0 + z), state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
        }
    }
}
