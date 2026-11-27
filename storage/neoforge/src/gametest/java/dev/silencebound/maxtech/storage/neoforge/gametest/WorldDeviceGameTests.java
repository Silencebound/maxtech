package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.schedule.LevelTasks;
import dev.silencebound.maxtech.lib.api.network.NetworkGraph;
import dev.silencebound.maxtech.lib.api.network.NetworkManager;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.world.CollectorIndex;
import dev.silencebound.maxtech.storage.world.WorldContent;
import dev.silencebound.maxtech.storage.world.WorldDeviceBlock;
import dev.silencebound.maxtech.storage.world.WorldDeviceBlockEntity;
import dev.silencebound.maxtech.storage.world.WorldDevicePart;
import dev.silencebound.maxtech.storage.world.WorldSettings;
import dev.silencebound.maxtech.storage.world.WorldWorker;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import org.slf4j.Logger;

/**
 * The collector, placer and breaker (M6c area E): the collector takes item entities in its radius into the network and
 * sleeps without them; the placer and breaker act on pulses or continuously, through a fake player that claims can
 * refuse, within the breaker's hardness cap and blacklist, with drops and tools as described; both forms keep their
 * settings. Layouts run along x at y = 1, z = 1: controller, cable, test drive, then the device. The perf test times
 * 100 idle collectors and 100 idle breakers.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class WorldDeviceGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_16x12x16");
    private static final Identifier PERF_STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final String[] NAMES = {"world_collector_radius_and_sleep", "world_placer_modes",
            "world_breaker_modes", "world_devices_part_round_trip", "world_placer_drop", "world_placer_drop_cap"};
    private static final String PERF = "perf_storage_idle_world_devices";
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey DIRT = ItemKey.of(Items.DIRT);
    private static final StackKey GRASS = ItemKey.of(Items.GRASS_BLOCK);
    private static final StackKey STONE = ItemKey.of(Items.STONE);
    private static final long POWER = 10_000;
    private static final int COLUMNS = 20;
    private static final int ROWS = 10;
    private static final int WARMUP_TICKS = 200;
    /**
     * Ticks without a device run that count as settled. The scheduler's time budget per tick lets a slow machine (a
     * busy CI runner, cold code) run only a few of the 200 devices' first runs per tick, so a fixed warm-up can end
     * before they are all asleep (issue #3).
     */
    private static final int QUIET_TICKS = 40;
    /** Ticks after the warm-up within which the devices must have settled. */
    private static final int SETTLE_TICKS = 600;
    private static final int MEASURED_TICKS = 200;
    /** Mean per tick for 200 idle devices; PRD 8.1 gives 500 idle machines 0.1 ms, the ports' test 0.03 ms. */
    private static final long IDLE_BUDGET_NANOS = 30_000;
    private static final RegistryHelper.Registrar<Item> ITEMS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.ITEM);
    /** Places cobblestone without being used up, as some modded block items and creative-like actors do. */
    private static final RegistryEntry<Item, Item> UNCONSUMED = ITEMS.register("test_unconsumed_cobblestone",
            id -> new BlockItem(Blocks.COBBLESTONE,
                    new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id))) {
                @Override
                public InteractionResult place(BlockPlaceContext context) {
                    InteractionResult result = super.place(context);
                    if (result.consumesAction()) {
                        context.getItemInHand().grow(1);
                    }
                    return result;
                }
            });
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    /** Absolute positions where protection refuses fake players, as a claim mod would. */
    private static final Set<BlockPos> DENIED = new HashSet<>();
    /** Absolute positions where item entities may not join the level, as an entity-limiting mod would. */
    private static final Set<BlockPos> NO_ITEMS = new HashSet<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(NAMES[0], WorldDeviceGameTests::collectorRadiusAndSleep);
        register(NAMES[1], WorldDeviceGameTests::placerModes);
        register(NAMES[2], WorldDeviceGameTests::breakerModes);
        register(NAMES[3], WorldDeviceGameTests::partRoundTrip);
        register(NAMES[4], WorldDeviceGameTests::placerDrop);
        register(NAMES[5], WorldDeviceGameTests::placerDropCap);
        register(PERF, WorldDeviceGameTests::idleDevices);
    }

    private WorldDeviceGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("world_device"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, STRUCTURE, 400, 0, true)));
        }
        Identifier perf = MaxtechStorage.id(PERF);
        event.registerTest(perf,
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, perf),
                        new TestData<>(event.registerEnvironment(perf, StorageTestEnvironment.roomy("", "", true)),
                                PERF_STRUCTURE, WARMUP_TICKS + SETTLE_TICKS + 2 * (MEASURED_TICKS + 1) + 200, 0,
                                true)));
    }

    @SubscribeEvent
    static void place(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof FakePlayer && DENIED.contains(event.getPos())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void breakBlock(BreakBlockEvent event) {
        if (event.getPlayer() instanceof FakePlayer && DENIED.contains(event.getPos())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void join(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof ItemEntity && NO_ITEMS.contains(event.getEntity().blockPosition())) {
            event.setCanceled(true);
        }
    }

    /** A powered controller, a cable and a test drive at x = 1 to 3. */
    private static void network(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, POWER);
        NetworkGameTests.cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_1.type().get());
        NetworkGameTests.device(helper, NetworkGameTests.at(3));
    }

    /** A device block of {@code block} at x = 4 facing east, touching the drive. */
    private static WorldWorker device(GameTestHelper helper, WorldDeviceBlock block) {
        BlockPos pos = NetworkGameTests.at(4);
        helper.setBlock(pos, block.defaultBlockState().setValue(WorldDeviceBlock.FACING, Direction.EAST));
        return helper.getBlockEntity(pos, WorldDeviceBlockEntity.class).worker();
    }

    private static long amount(GameTestHelper helper, StackKey key) {
        return NetworkGameTests.state(helper, NetworkGameTests.at(1)).contents().amount(key);
    }

    private static long runs(GameTestHelper helper) {
        return NetworkGameTests.taskRuns(NetworkGameTests.state(helper, NetworkGameTests.at(1)));
    }

    private static ItemEntity entity(GameTestHelper helper, Item item, int count, double x, double y, double z) {
        ItemEntity entity = helper.spawnItem(item, new Vec3(x, y, z));
        entity.setItem(new ItemStack(item, count));
        return entity;
    }

    /**
     * Item entities within the radius go into the network; one landing beyond the radius but within the drift margin
     * stays until it moves into range (as water would carry it), then goes in; with nothing left near, the collector
     * runs no more; a new drop in range wakes it, and a filter limits what it takes.
     */
    private static void collectorRadiusAndSleep(GameTestHelper helper) {
        network(helper);
        WorldWorker collector = device(helper, WorldContent.COLLECTOR.get());
        long[] before = new long[1];
        ItemEntity[] drifting = new ItemEntity[1];
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.state(helper, NetworkGameTests.at(4)))
                .thenExecute(() -> {
                    entity(helper, Items.COBBLESTONE, 12, 4.5, 2.5, 3.5);
                    entity(helper, Items.DIRT, 3, 6.5, 1.5, 1.5);
                    drifting[0] = entity(helper, Items.COBBLESTONE, 5, 12.5, 2.5, 1.5);
                }).thenWaitUntil(() -> {
                    helper.assertValueEqual(amount(helper, COBBLE), 12L, "cobblestone collected");
                    helper.assertValueEqual(amount(helper, DIRT), 3L, "dirt collected");
                }).thenIdle(40).thenExecute(() -> {
                    helper.assertItemEntityCountIs(Items.COBBLESTONE, new BlockPos(12, 1, 1), 3.0, 5);
                    // Drifts into range after the collector last looked, without a new item joining the level.
                    drifting[0].setPos(helper.absoluteVec(new Vec3(5.5, 1.5, 1.5)));
                }).thenWaitUntil(() -> helper.assertValueEqual(amount(helper, COBBLE), 17L, "drifted in")).thenIdle(25)
                .thenExecute(() -> {
                    before[0] = runs(helper);
                    collector.filter().setItem(0, new ItemStack(Items.COBBLESTONE));
                }).thenIdle(100).thenExecute(() -> {
                    // The filter change woke it once; then it sleeps.
                    helper.assertTrue(runs(helper) - before[0] <= 2,
                            (runs(helper) - before[0]) + " runs without items near");
                    entity(helper, Items.DIRT, 2, 3.5, 2.5, 4.5);
                    entity(helper, Items.COBBLESTONE, 4, 3.5, 2.5, 4.5);
                }).thenWaitUntil(() -> helper.assertValueEqual(amount(helper, COBBLE), 21L, "woken by a new drop"))
                .thenIdle(30).thenExecute(() -> {
                    helper.assertValueEqual(amount(helper, DIRT), 3L, "dirt the filter refused");
                    helper.assertItemEntityCountIs(Items.DIRT, new BlockPos(3, 1, 4), 3.0, 2);
                    helper.assertTrue(CollectorIndex.entries(helper.getLevel()) > 0, "no collector indexed");
                }).thenSucceed();
    }

    /**
     * Pulse mode places one block per rising edge and nothing without one; continuous mode fills the space again
     * whenever it empties; a refusing claim leaves the space empty and the item in the network.
     */
    private static void placerModes(GameTestHelper helper) {
        network(helper);
        WorldWorker placer = device(helper, WorldContent.PLACER.get());
        BlockPos target = NetworkGameTests.at(5);
        BlockPos power = NetworkGameTests.at(4).above();
        long[] energy = new long[1];
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.state(helper, NetworkGameTests.at(4)))
                .thenExecute(() -> {
                    NetworkGameTests.insert(NetworkGameTests.state(helper, NetworkGameTests.at(1)), COBBLE, 5);
                    placer.filter().setItem(0, new ItemStack(Items.COBBLESTONE));
                }).thenIdle(20).thenExecute(() -> {
                    helper.assertBlockNotPresent(Blocks.COBBLESTONE, target);
                    helper.setBlock(power, Blocks.REDSTONE_BLOCK);
                }).thenWaitUntil(() -> helper.assertBlockPresent(Blocks.COBBLESTONE, target)).thenIdle(20)
                .thenExecute(() -> {
                    helper.assertValueEqual(amount(helper, COBBLE), 4L, "one placed per pulse");
                    helper.setBlock(target, Blocks.AIR);
                }).thenIdle(20).thenExecute(() -> {
                    helper.assertBlockNotPresent(Blocks.COBBLESTONE, target);
                    helper.setBlock(power, Blocks.AIR);
                    DENIED.add(helper.absolutePos(target));
                }).thenIdle(2).thenExecute(() -> helper.setBlock(power, Blocks.REDSTONE_BLOCK)).thenIdle(20)
                .thenExecute(() -> {
                    DENIED.remove(helper.absolutePos(target));
                    helper.assertBlockNotPresent(Blocks.COBBLESTONE, target);
                    helper.assertValueEqual(amount(helper, COBBLE), 4L, "a refused placement kept the item");
                    placer.setMode(1);
                }).thenWaitUntil(() -> helper.assertBlockPresent(Blocks.COBBLESTONE, target)).thenExecute(() -> {
                    helper.assertValueEqual(amount(helper, COBBLE), 3L, "continuous placement");
                    helper.setBlock(target, Blocks.AIR);
                }).thenWaitUntil(() -> helper.assertBlockPresent(Blocks.COBBLESTONE, target)).thenExecute(() -> {
                    helper.assertValueEqual(amount(helper, COBBLE), 2L, "placed again");
                    placer.setMode(0);
                    placer.filter().setItem(0, new ItemStack(UNCONSUMED.get()));
                    NetworkGameTests.insert(NetworkGameTests.state(helper, NetworkGameTests.at(1)),
                            ItemKey.of(UNCONSUMED.get()), 1);
                    helper.setBlock(target, Blocks.AIR);
                    helper.setBlock(power, Blocks.AIR);
                }).thenIdle(2).thenExecute(() -> {
                    energy[0] = NetworkGameTests.state(helper, NetworkGameTests.at(1)).storedEnergy();
                    helper.setBlock(power, Blocks.REDSTONE_BLOCK);
                }).thenWaitUntil(() -> helper.assertBlockPresent(Blocks.COBBLESTONE, target)).thenIdle(5)
                .thenExecute(() -> {
                    helper.assertValueEqual(amount(helper, ItemKey.of(UNCONSUMED.get())), 1L,
                            "the item left in the stack went back");
                    // Committed: the take and the give-back were each one operation (4 FE).
                    helper.assertValueEqual(
                            energy[0] - NetworkGameTests.state(helper, NetworkGameTests.at(1)).storedEnergy(), 8L,
                            "FE for the placement and the give-back");
                }).thenSucceed();
    }

    /**
     * A continuous breaker breaks stone into cobblestone in the network, leaves obsidian (over the hardness cap),
     * budding amethyst (blacklisted) and whatever a refusing claim protects, takes silk touch from a kept enchanted
     * book, and limits itself to what a filter names; in pulse mode it waits for a pulse.
     */
    private static void breakerModes(GameTestHelper helper) {
        network(helper);
        WorldWorker breaker = device(helper, WorldContent.BREAKER.get());
        BlockPos target = NetworkGameTests.at(5);
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.state(helper, NetworkGameTests.at(4)))
                .thenExecute(() -> {
                    breaker.setMode(1);
                    helper.setBlock(target, Blocks.STONE);
                }).thenWaitUntil(() -> helper.assertValueEqual(amount(helper, COBBLE), 1L, "stone broken"))
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.AIR, target);
                    helper.setBlock(target, Blocks.OBSIDIAN);
                }).thenIdle(30).thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.OBSIDIAN, target);
                    helper.setBlock(target, Blocks.BUDDING_AMETHYST);
                }).thenIdle(30).thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.BUDDING_AMETHYST, target);
                    DENIED.add(helper.absolutePos(target));
                    helper.setBlock(target, Blocks.GRASS_BLOCK);
                }).thenIdle(30).thenExecute(() -> {
                    DENIED.remove(helper.absolutePos(target));
                    helper.assertBlockPresent(Blocks.GRASS_BLOCK, target);
                    helper.setBlock(target, Blocks.AIR);
                }).thenIdle(1).thenExecute(() -> helper.setBlock(target, Blocks.GRASS_BLOCK))
                .thenWaitUntil(() -> helper.assertValueEqual(amount(helper, DIRT), 1L, "grass without a tool"))
                .thenExecute(() -> {
                    ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
                    book.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                            .getOrThrow(Enchantments.SILK_TOUCH), 1);
                    tool(helper, breaker).setItem(0, book);
                    helper.setBlock(target, Blocks.GRASS_BLOCK);
                }).thenWaitUntil(() -> helper.assertValueEqual(amount(helper, GRASS), 1L, "grass with silk touch"))
                .thenExecute(() -> {
                    breaker.filter().setItem(0, new ItemStack(Items.DIRT));
                    helper.setBlock(target, Blocks.STONE);
                }).thenIdle(30).thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.STONE, target);
                    breaker.filter().setItem(0, ItemStack.EMPTY);
                    breaker.setMode(0);
                }).thenIdle(30).thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.STONE, target);
                    helper.setBlock(NetworkGameTests.at(4).above(), Blocks.REDSTONE_BLOCK);
                }).thenWaitUntil(() -> helper.assertValueEqual(amount(helper, STONE), 1L, "silk-touched on a pulse"))
                .thenSucceed();
    }

    private static Container tool(GameTestHelper helper, WorldWorker worker) {
        Container tool = worker.tool();
        if (tool == null) {
            throw helper.assertionException(Component.literal(worker.kind() + " keeps no tool"));
        }
        return tool;
    }

    private static ItemStack craft(GameTestHelper helper, ItemStack input) {
        CraftingInput grid = CraftingInput.of(3, 3, PartGameTests.grid(input.copy()));
        return CrafterBlock.getPotentialResults(helper.getLevel(), grid).map(recipe -> recipe.value().assemble(grid))
                .orElseThrow(() -> helper.assertionException(Component.literal("No recipe for " + input)));
    }

    /**
     * A breaker part breaks the block beyond its face with its kept book; its settings and tool go into its item,
     * through the conversion recipe into a placed block, and out again as a drop when a creative player breaks the
     * block (which drops nothing otherwise). A collector part collects around its cable.
     */
    private static void partRoundTrip(GameTestHelper helper) {
        network(helper);
        NetworkGameTests.cables(helper, 4, 4, CableContent.STORAGE_CABLE_TIER_1.type().get());
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockPos target = NetworkGameTests.at(5);
        WorldWorker[] breaker = new WorldWorker[1];
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.state(helper, NetworkGameTests.at(4)))
                .thenExecute(() -> {
                    helper.assertTrue(
                            PartGameTests.usePart(helper, player, 4, Direction.EAST,
                                    new ItemStack(WorldContent.BREAKER_PART_ITEM.get())).consumesAction(),
                            "breaker part");
                    helper.assertTrue(
                            PartGameTests.usePart(helper, player, 4, Direction.UP,
                                    new ItemStack(WorldContent.COLLECTOR_PART_ITEM.get())).consumesAction(),
                            "collector part");
                    for (Direction face : new Direction[]{Direction.EAST, Direction.UP}) {
                        if (PartGameTests.part(helper, PartGameTests.parts(helper, 4),
                                face) instanceof WorldDevicePart part) {
                            // A mock player's team lacks the research; an unowned device works.
                            part.setOwner(OwnerRecord.UNOWNED);
                            if (face == Direction.EAST) {
                                breaker[0] = part.worker();
                            }
                        }
                    }
                    ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
                    book.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                            .getOrThrow(Enchantments.SILK_TOUCH), 1);
                    tool(helper, breaker[0]).setItem(0, book);
                    breaker[0].filter().setItem(0, new ItemStack(Items.STONE));
                    breaker[0].setMode(1);
                    helper.setBlock(target, Blocks.STONE);
                    entity(helper, Items.DIRT, 2, 4.5, 3.5, 2.5);
                }).thenWaitUntil(() -> {
                    helper.assertValueEqual(amount(helper, STONE), 1L, "the part broke stone with silk touch");
                    helper.assertValueEqual(amount(helper, DIRT), 2L, "the collector part collected");
                }).thenExecute(() -> {
                    CablePart removed = PartGameTests.parts(helper, 4).remove(Direction.EAST);
                    ItemStack item = removed == null ? ItemStack.EMPTY : removed.toItem();
                    assertSettings(helper, item, "after taking the part off");
                    ItemStack block = craft(helper, item);
                    helper.assertTrue(block.is(WorldContent.BREAKER.item().get()), "crafted " + block);
                    assertSettings(helper, block, "after crafting the block");
                    BlockPos placeAt = helper.absolutePos(NetworkGameTests.at(7));
                    player.setItemInHand(InteractionHand.MAIN_HAND, block);
                    helper.assertTrue(block
                            .useOn(new UseOnContext(helper.getLevel(), player, InteractionHand.MAIN_HAND, block,
                                    new BlockHitResult(placeAt.getCenter(), Direction.UP, placeAt, false)))
                            .consumesAction(), "placing the block");
                    WorldDeviceBlockEntity placed = helper.getBlockEntity(NetworkGameTests.at(7),
                            WorldDeviceBlockEntity.class);
                    helper.assertTrue(tool(helper, placed.worker()).getItem(0).is(Items.ENCHANTED_BOOK), "placed tool");
                    helper.assertValueEqual(placed.worker().mode(), 1, "placed mode");
                    Player creative = helper.makeMockPlayer(GameType.CREATIVE);
                    creative.getAbilities().instabuild = true;
                    BlockState state = helper.getBlockState(NetworkGameTests.at(7));
                    state.getBlock().playerWillDestroy(helper.getLevel(), placeAt, state, creative);
                    List<ItemEntity> drops = helper.getEntities(EntityType.ITEM, NetworkGameTests.at(7), 1.5);
                    helper.assertValueEqual(drops.size(), 1, "creative break drops");
                    assertSettings(helper, drops.getFirst().getItem(), "in the creative drop");
                }).thenSucceed();
    }

    /** How many of {@code item} lie as item entities around {@code pos}. */
    private static int lying(GameTestHelper helper, Item item, BlockPos pos) {
        int total = 0;
        for (ItemEntity entity : helper.getEntities(EntityType.ITEM, pos, 1.0)) {
            if (entity.getItem().is(item)) {
                total += entity.getItem().getCount();
            }
        }
        return total;
    }

    /**
     * B09 item 25: in a drop action the placer drops exactly its filtered item in front, a stack and then a single item
     * per pulse and nothing without one; an item entity the level refuses extracts nothing; a solid block in front
     * stops it; continuous mode drops on its cadence and pulse mode stops it again; the action survives the settings
     * codec.
     */
    private static void placerDrop(GameTestHelper helper) {
        network(helper);
        WorldWorker placer = device(helper, WorldContent.PLACER.get());
        BlockPos target = NetworkGameTests.at(5);
        BlockPos power = NetworkGameTests.at(4).above();
        long[] before = new long[1];
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.state(helper, NetworkGameTests.at(4)))
                .thenExecute(() -> {
                    helper.setBlock(target.below(), Blocks.STONE);
                    NetworkGameTests.insert(NetworkGameTests.state(helper, NetworkGameTests.at(1)), COBBLE, 100);
                    NetworkGameTests.insert(NetworkGameTests.state(helper, NetworkGameTests.at(1)), DIRT, 10);
                    placer.filter().setItem(0, new ItemStack(Items.COBBLESTONE));
                    placer.setAction(1);
                }).thenIdle(20).thenExecute(() -> {
                    helper.assertValueEqual(lying(helper, Items.COBBLESTONE, target), 0, "dropped without a pulse");
                    helper.setBlock(power, Blocks.REDSTONE_BLOCK);
                }).thenWaitUntil(() -> helper.assertValueEqual(lying(helper, Items.COBBLESTONE, target), 64, "a stack"))
                .thenIdle(20).thenExecute(() -> {
                    helper.assertValueEqual(amount(helper, COBBLE), 36L, "one stack per pulse");
                    helper.assertValueEqual(amount(helper, DIRT), 10L, "dirt is not in the filter");
                    helper.assertValueEqual(lying(helper, Items.DIRT, target), 0, "dropped dirt");
                    placer.setAction(2);
                    helper.setBlock(power, Blocks.AIR);
                }).thenIdle(2).thenExecute(() -> helper.setBlock(power, Blocks.REDSTONE_BLOCK))
                .thenWaitUntil(() -> helper.assertValueEqual(amount(helper, COBBLE), 35L, "one item per pulse"))
                .thenIdle(20).thenExecute(() -> {
                    helper.assertValueEqual(lying(helper, Items.COBBLESTONE, target), 65, "the single item lies there");
                    helper.setBlock(power, Blocks.AIR);
                    NO_ITEMS.add(helper.absolutePos(target));
                }).thenIdle(2).thenExecute(() -> helper.setBlock(power, Blocks.REDSTONE_BLOCK)).thenIdle(20)
                .thenExecute(() -> {
                    NO_ITEMS.remove(helper.absolutePos(target));
                    helper.assertValueEqual(amount(helper, COBBLE), 35L, "a refused entity extracted nothing");
                    helper.despawnItem(target, 1.0);
                    helper.setBlock(target, Blocks.STONE);
                    placer.setMode(1);
                }).thenIdle(30).thenExecute(() -> {
                    helper.assertValueEqual(amount(helper, COBBLE), 35L, "dropped into a solid block");
                    helper.setBlock(target, Blocks.AIR);
                }).thenWaitUntil(() -> helper.assertTrue(amount(helper, COBBLE) <= 33L, "continuous drops"))
                .thenExecute(() -> {
                    placer.setMode(0);
                    before[0] = amount(helper, COBBLE);
                }).thenIdle(30).thenExecute(() -> {
                    helper.assertValueEqual(amount(helper, COBBLE), before[0], "pulse mode without a new pulse");
                    WorldSettings settings = WorldSettings.of(placer);
                    if (settings == null) {
                        throw helper.assertionException(Component.literal("no settings"));
                    }
                    var ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
                    WorldSettings decoded = WorldSettings.CODEC
                            .parse(ops, WorldSettings.CODEC.encodeStart(ops, settings).getOrThrow()).getOrThrow();
                    helper.assertValueEqual(decoded.action(), "drop_item", "the saved action");
                }).thenSucceed();
    }

    /**
     * A continuous dropping placer pauses while the cap's item entities lie in front, drops once one is gone (and is at
     * the cap again with its own drop), and drops on once the space is clear.
     */
    private static void placerDropCap(GameTestHelper helper) {
        network(helper);
        WorldWorker placer = device(helper, WorldContent.PLACER.get());
        BlockPos target = NetworkGameTests.at(5);
        int cap = StorageServerConfig.PLACER_DROP_ENTITY_CAP.getAsInt();
        List<ItemEntity> sticks = new ArrayList<>();
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.state(helper, NetworkGameTests.at(4)))
                .thenExecute(() -> {
                    helper.setBlock(target.below(), Blocks.STONE);
                    for (int i = 0; i < cap; i++) {
                        ItemEntity stick = helper.spawnItem(Items.STICK, new Vec3(5.5, 1.2, 1.5));
                        // Never picked up, so they never merge into fewer entities.
                        stick.setNeverPickUp();
                        sticks.add(stick);
                    }
                    NetworkGameTests.insert(NetworkGameTests.state(helper, NetworkGameTests.at(1)), COBBLE, 100);
                    placer.filter().setItem(0, new ItemStack(Items.COBBLESTONE));
                    placer.setAction(2);
                    placer.setMode(1);
                }).thenIdle(40).thenExecute(() -> {
                    helper.assertValueEqual(amount(helper, COBBLE), 100L, "dropped at the cap");
                    sticks.removeFirst().discard();
                }).thenWaitUntil(() -> helper.assertValueEqual(amount(helper, COBBLE), 99L, "dropped below the cap"))
                .thenIdle(40).thenExecute(() -> {
                    helper.assertValueEqual(amount(helper, COBBLE), 99L, "its own drop filled the cap");
                    for (ItemEntity stick : sticks) {
                        stick.discard();
                    }
                }).thenWaitUntil(() -> helper.assertTrue(amount(helper, COBBLE) <= 97L, "drops on")).thenSucceed();
    }

    private static void assertSettings(GameTestHelper helper, ItemStack stack, String step) {
        WorldSettings settings = stack.get(ModDataComponents.WORLD_SETTINGS.get());
        if (settings == null) {
            throw helper.assertionException(Component.literal("no settings in the item " + step));
        }
        helper.assertTrue(settings.filter().getFirst().is(Items.STONE), "the filter " + step);
        helper.assertTrue(settings.tool().is(Items.ENCHANTED_BOOK), "the tool " + step);
        helper.assertValueEqual(settings.mode(), 1, "the mode " + step);
    }

    /**
     * 100 collectors with nothing in range and 100 continuous breakers facing air, on a cable floor: once settled they
     * run no tasks, and the level's scheduler plus the network's tick stay within budget.
     */
    private static void idleDevices(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 1000);
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                BlockPos floor = new BlockPos(2 + column, 1, 1 + row);
                ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(floor),
                        CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
                boolean collector = (row + column) % 2 == 0;
                Block block = collector ? WorldContent.COLLECTOR.get() : WorldContent.BREAKER.get();
                helper.setBlock(floor.above(),
                        block.defaultBlockState().setValue(WorldDeviceBlock.FACING, Direction.UP));
                if (!collector) {
                    // Continuous, facing the air above: nothing to break, so it sleeps.
                    helper.getBlockEntity(floor.above(), WorldDeviceBlockEntity.class).worker().setMode(1);
                }
            }
        }
        NetworkGraph graph = NetworkManager.get(helper.getLevel()).graph(StorageNetworks.TYPE);
        long[] total = new long[1];
        int[] measured = new int[1];
        long[] runsAtStart = new long[1];
        // runs at the last change, quiet ticks since
        long[] settle = {-1, 0};
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(network.deviceCount(), COLUMNS * ROWS + 1, "devices in the network");
        }).thenIdle(WARMUP_TICKS).thenWaitUntil(() -> {
            // Settled once nothing is queued and no device ran for QUIET_TICKS ticks.
            NetworkState network = NetworkGameTests.state(helper, controller);
            long runs = NetworkGameTests.taskRuns(network);
            if (runs != settle[0] || network.scheduler().queued() > 0) {
                settle[0] = runs;
                settle[1] = 0;
            }
            helper.assertTrue(++settle[1] >= QUIET_TICKS, "world devices still settling");
        }).thenWaitUntil(PerfBudget.measureTicks(helper, PERF, MEASURED_TICKS, IDLE_BUDGET_NANOS,
                PerfBudget.TickMeasurement.of(() -> {
                    total[0] = 0;
                    measured[0] = 0;
                    runsAtStart[0] = NetworkGameTests.taskRuns(NetworkGameTests.state(helper, controller));
                }, () -> {
                    long tasks = LevelTasks.lastTickNanos(helper.getLevel());
                    long network = graph.lastTickNanos();
                    if (tasks >= 0 && network >= 0) {
                        total[0] += tasks + network;
                        measured[0]++;
                    }
                }, () -> {
                    helper.assertTrue(measured[0] > MEASURED_TICKS / 2, "only " + measured[0] + " ticks timed");
                    long mean = total[0] / measured[0];
                    long ran = NetworkGameTests.taskRuns(NetworkGameTests.state(helper, controller)) - runsAtStart[0];
                    LOGGER.info("{}: {} idle world devices, {} task runs in {} ticks; mean tick {} ms (budget {} ms).",
                            PERF, COLUMNS * ROWS, ran, MEASURED_TICKS, mean / 1_000_000.0,
                            IDLE_BUDGET_NANOS / 1_000_000.0);
                    helper.assertTrue(ran == 0, ran + " runs: idle world devices are not asleep");
                    return mean;
                }), mean -> "200 idle world devices take " + mean + " ns per tick, over the budget")).thenSucceed();
    }
}
