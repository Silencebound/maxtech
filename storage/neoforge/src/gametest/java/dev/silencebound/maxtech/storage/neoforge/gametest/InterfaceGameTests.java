package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.iface.InterfaceBlockEntity;
import dev.silencebound.maxtech.storage.iface.InterfaceContent;
import dev.silencebound.maxtech.storage.iface.InterfaceItems;
import dev.silencebound.maxtech.storage.iface.InterfaceMenu;
import dev.silencebound.maxtech.storage.iface.InterfacePart;
import dev.silencebound.maxtech.storage.iface.InterfaceWorker;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * The interface (M6c area B): inserts and pulls as network operations under energy, stock that refills and then sleeps,
 * waits for a missing key and returns surplus, no duplication on rollback, the part on a cable face, the item keeping
 * settings and stock, the research gate and the GUI's ghost slots. Layout: the controller at x 1 with a test drive
 * behind it (z 2), a tier-1 cable at x 2, the interface block at x 3; all at y 1, z 1.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
// Networks are compared by identity.
@SuppressWarnings("ReferenceEquality")
public final class InterfaceGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final BlockPos CONTROLLER = new BlockPos(1, 1, 1);
    private static final BlockPos DRIVE = new BlockPos(1, 1, 2);
    private static final BlockPos INTERFACE = new BlockPos(3, 1, 1);
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey DIRT = ItemKey.of(Items.DIRT);
    private static final StackKey WATER = FluidKey.of(Fluids.WATER);
    private static final long POWER = 10_000;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<String> TESTS = new ArrayList<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("iface_insert_is_one_operation", InterfaceGameTests::insertIsOneOperation);
        register("iface_surplus_mixed_kinds", InterfaceGameTests::surplusMixedKinds);
        register("iface_stock_refills_and_sleeps", InterfaceGameTests::stockRefillsAndSleeps);
        register("iface_fluid_stock", InterfaceGameTests::fluidStock);
        register("iface_no_dupe_on_rollback", InterfaceGameTests::noDupeOnRollback);
        register("iface_part_on_cable_face", InterfaceGameTests::partOnCableFace);
        register("iface_drop_keeps_settings_and_stock", InterfaceGameTests::dropKeepsSettingsAndStock);
        register("iface_research_gate", InterfaceGameTests::researchGate);
        register("iface_menu_ghost_slots", InterfaceGameTests::menuGhostSlots);
        register("iface_off_thread_does_nothing", InterfaceGameTests::offThreadDoesNothing);
    }

    private InterfaceGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        TESTS.add(name);
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("iface"),
                StorageTestEnvironment.plain());
        for (String name : TESTS) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, STRUCTURE, 400, 0, true)));
        }
    }

    /**
     * Unpowered, an insert takes nothing. Powered, each insert is one operation: 4 FE whatever it moves (M7E), one
     * batch at most (100 items asked, 64 taken; 20,000 mB of lava asked, 16,000 taken); a rolled-back insert keeps
     * nothing and refunds the FE. Forty one-item inserts in one tick all pass: since channels (M7N) nothing on the
     * route holds them back, and none walks the route.
     */
    private static void insertIsOneOperation(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper, 0);
        InterfaceBlockEntity iface = interfaceBlock(helper);
        helper.startSequence().thenWaitUntil(() -> inNetwork(helper, drive, iface)).thenExecute(() -> {
            ResourceHandler<ItemResource> items = items(helper, INTERFACE, Direction.EAST);
            helper.assertValueEqual(insert(items, cobble(), 10, true), 0, "inserted without power");
            NetworkGameTests.power(helper, CONTROLLER, POWER);
            NetworkState network = NetworkGameTests.net(helper, drive);
            helper.assertValueEqual(insert(items, cobble(), 10, true), 10, "inserted");
            helper.assertValueEqual(network.storedEnergy(), POWER - 4, "FE after one operation of 10");
            helper.assertValueEqual(insert(items, cobble(), 100, true), 64, "one batch");
            helper.assertValueEqual(network.storedEnergy(), POWER - 8, "FE after one operation of 64");
            helper.assertValueEqual(insert(items, cobble(), 5, false), 5, "simulated");
            helper.assertValueEqual(network.storedEnergy(), POWER - 8, "FE after a rollback");
            helper.assertValueEqual(drive.provider.amount(COBBLE), 74L, "in the drive");
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(ResourceHandlerUtil.insertStacking(fluids(helper),
                        FluidResource.of(Fluids.LAVA), 20_000, transaction), 16_000, "one batch of fluid");
                transaction.commit();
            }
            helper.assertValueEqual(network.storedEnergy(), POWER - 12, "FE after one operation of 16,000 mB");
            helper.assertValueEqual(drive.provider.amount(FluidKey.of(Fluids.LAVA)), 16_000L, "lava in the drive");
            for (int i = 0; i < 40; i++) {
                helper.assertValueEqual(insert(items, cobble(), 1, false), 1, "a probing insert");
            }
            helper.assertValueEqual(network.storedEnergy(), POWER - 12, "FE after forty rolled-back inserts");
            long steps = network.channelSteps();
            int passed = 0;
            for (int i = 0; i < 40; i++) {
                passed += insert(items, cobble(), 1, true);
            }
            helper.assertValueEqual(passed, 40, "one-item inserts in one tick");
            helper.assertValueEqual(network.channelSteps(), steps, "route steps walked by the inserts");
            helper.assertValueEqual(drive.provider.amount(COBBLE), 74L + passed, "in the drive after the burst");
        }).thenSucceed();
    }

    /**
     * Eight slots stocked with four of eight kinds, then every target cleared: the 32 items go back to the network in
     * one operation (4 FE), not one per slot (M7E follow-up), and each kind arrives exactly once.
     */
    private static void surplusMixedKinds(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper, POWER);
        Item[] kinds = {Items.COBBLESTONE, Items.DIRT, Items.SAND, Items.GRAVEL, Items.STONE, Items.GRANITE,
                Items.DIORITE, Items.ANDESITE};
        for (Item kind : kinds) {
            drive.provider.setDirect(ItemKey.of(kind), 4);
        }
        InterfaceBlockEntity iface = interfaceBlock(helper);
        InterfaceWorker worker = iface.worker();
        long[] before = new long[1];
        helper.startSequence().thenWaitUntil(() -> inNetwork(helper, drive, iface)).thenExecute(() -> {
            for (int i = 0; i < kinds.length; i++) {
                worker.config().setItem(i, new ItemStack(kinds[i], 4));
            }
        }).thenWaitUntil(() -> {
            for (Item kind : kinds) {
                helper.assertValueEqual(worker.stockAmount(ItemKey.of(kind)), 4L, kind + " stocked");
            }
        }).thenIdle(5).thenExecute(() -> {
            before[0] = NetworkGameTests.net(helper, drive).storedEnergy();
            for (int i = 0; i < kinds.length; i++) {
                worker.config().setItem(i, ItemStack.EMPTY);
            }
        }).thenWaitUntil(() -> {
            for (Item kind : kinds) {
                helper.assertValueEqual(drive.provider.amount(ItemKey.of(kind)), 4L, kind + " back in the drive");
            }
        }).thenIdle(5).thenExecute(() -> {
            for (Item kind : kinds) {
                helper.assertValueEqual(worker.stockAmount(ItemKey.of(kind)), 0L, kind + " left in the stock");
                helper.assertValueEqual(drive.provider.amount(ItemKey.of(kind)), 4L, kind + " in the drive");
            }
            helper.assertValueEqual(before[0] - NetworkGameTests.net(helper, drive).storedEnergy(), 4L,
                    "FE to give back eight kinds");
        }).thenSucceed();
    }

    /**
     * A slot set to 16 cobblestone fills from the network and the interface then sleeps (no task runs for 60 ticks). A
     * pull of 5 is refilled; a pull of 20 takes 16 from the stock and 4 from the network. A slot for dirt the network
     * lacks waits without running and fills when dirt arrives. Lowering a target and clearing a slot return the rest.
     * Runs are the interface task's own: the network's scheduler also runs the controller's alarm panel, whose storage
     * look after the refills lands whenever the controller's load refresh comes round.
     */
    private static void stockRefillsAndSleeps(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper, POWER);
        InterfaceBlockEntity iface = interfaceBlock(helper);
        InterfaceWorker worker = iface.worker();
        long[] runs = new long[1];
        helper.startSequence().thenWaitUntil(() -> inNetwork(helper, drive, iface)).thenExecute(() -> {
            drive.provider.setDirect(COBBLE, 100);
            worker.config().setItem(0, new ItemStack(Items.COBBLESTONE, 16));
        }).thenWaitUntil(() -> {
            helper.assertValueEqual(worker.itemStock().getAmountAsLong(0), 16L, "cobblestone in stock");
            helper.assertValueEqual(drive.provider.amount(COBBLE), 84L, "cobblestone in the drive");
        }).thenExecute(() -> {
            helper.assertTrue(worker.isSatisfied(), "not satisfied");
            runs[0] = worker.taskRuns();
        }).thenIdle(60).thenExecute(() -> {
            helper.assertValueEqual(worker.taskRuns(), runs[0], "task runs while satisfied");
            ResourceHandler<ItemResource> items = items(helper, INTERFACE, Direction.UP);
            helper.assertValueEqual(extract(items, 0, cobble(), 5, true), 5, "pulled from the stock");
        }).thenWaitUntil(() -> {
            helper.assertValueEqual(worker.itemStock().getAmountAsLong(0), 16L, "refilled");
            helper.assertValueEqual(drive.provider.amount(COBBLE), 79L, "drive after the refill");
        }).thenExecute(() -> {
            ResourceHandler<ItemResource> items = items(helper, INTERFACE, Direction.UP);
            helper.assertValueEqual(extract(items, 0, cobble(), 20, true), 20, "pulled beyond the stock");
            helper.assertValueEqual(drive.provider.amount(COBBLE), 75L, "four came from the network");
        }).thenWaitUntil(() -> {
            helper.assertValueEqual(worker.itemStock().getAmountAsLong(0), 16L, "refilled again");
            helper.assertValueEqual(drive.provider.amount(COBBLE), 59L, "drive after the second refill");
        }).thenExecute(() -> {
            runs[0] = worker.taskRuns();
            worker.config().setItem(1, new ItemStack(Items.DIRT, 8));
        }).thenWaitUntil(() -> {
            helper.assertTrue(worker.taskRuns() > runs[0], "no run after setting dirt");
        }).thenExecute(() -> {
            helper.assertFalse(worker.isSatisfied(), "satisfied without dirt");
            runs[0] = worker.taskRuns();
        }).thenIdle(40).thenExecute(() -> {
            NetworkState network = NetworkGameTests.net(helper, drive);
            helper.assertValueEqual(worker.taskRuns(), runs[0], "task runs while waiting for dirt");
            helper.assertValueEqual(NetworkGameTests.insert(network, DIRT, 20), 20L, "dirt stored");
        }).thenWaitUntil(() -> {
            helper.assertValueEqual(worker.itemStock().getAmountAsLong(1), 8L, "dirt in stock");
            helper.assertValueEqual(NetworkGameTests.net(helper, drive).contents().amount(DIRT), 12L, "dirt left");
        }).thenExecute(() -> {
            worker.config().setItem(0, new ItemStack(Items.COBBLESTONE, 4));
            worker.config().setItem(1, ItemStack.EMPTY);
        }).thenWaitUntil(() -> {
            helper.assertValueEqual(worker.itemStock().getAmountAsLong(0), 4L, "cobblestone after lowering");
            helper.assertValueEqual(worker.itemStock().getAmountAsLong(1), 0L, "dirt after clearing");
            helper.assertValueEqual(drive.provider.amount(COBBLE), 71L, "cobblestone returned");
            helper.assertValueEqual(drive.provider.amount(DIRT), 20L, "dirt returned");
            helper.assertTrue(worker.isSatisfied(), "not satisfied at the end");
        }).thenSucceed();
    }

    /**
     * Two water buckets in a fluid slot keep 2,000 mB; a pull of 500 mB through the fluid handler is refilled; an
     * insert of lava goes into the network.
     */
    private static void fluidStock(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper, POWER);
        InterfaceBlockEntity iface = interfaceBlock(helper);
        InterfaceWorker worker = iface.worker();
        helper.startSequence().thenWaitUntil(() -> inNetwork(helper, drive, iface)).thenExecute(() -> {
            drive.provider.setDirect(WATER, 5000);
            worker.config().setItem(InterfaceWorker.ITEM_SLOTS, new ItemStack(Items.WATER_BUCKET, 2));
        }).thenWaitUntil(() -> {
            helper.assertValueEqual(worker.fluidStock().getAmountAsLong(0), 2000L, "water in stock");
            helper.assertValueEqual(drive.provider.amount(WATER), 3000L, "water in the drive");
        }).thenExecute(() -> {
            ResourceHandler<FluidResource> fluids = fluids(helper);
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(fluids.extract(0, FluidResource.of(Fluids.WATER), 500, transaction), 500,
                        "water pulled");
                transaction.commit();
            }
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(
                        ResourceHandlerUtil.insertStacking(fluids, FluidResource.of(Fluids.LAVA), 1000, transaction),
                        1000, "lava inserted");
                transaction.commit();
            }
        }).thenWaitUntil(() -> {
            helper.assertValueEqual(worker.fluidStock().getAmountAsLong(0), 2000L, "water refilled");
            helper.assertValueEqual(drive.provider.amount(WATER), 2500L, "water left in the drive");
            helper.assertValueEqual(drive.provider.amount(FluidKey.of(Fluids.LAVA)), 1000L, "lava in the drive");
        }).thenSucceed();
    }

    /**
     * Rolled back, a pull of 20 (16 from the stock, 4 from the network) and an insert leave the stock, the network and
     * the FE as they were; the stock task does not refill anything that never left.
     */
    private static void noDupeOnRollback(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper, POWER);
        InterfaceBlockEntity iface = interfaceBlock(helper);
        InterfaceWorker worker = iface.worker();
        long[] energy = new long[1];
        helper.startSequence().thenWaitUntil(() -> inNetwork(helper, drive, iface)).thenExecute(() -> {
            drive.provider.setDirect(COBBLE, 100);
            worker.config().setItem(0, new ItemStack(Items.COBBLESTONE, 16));
        }).thenWaitUntil(() -> helper.assertValueEqual(worker.itemStock().getAmountAsLong(0), 16L, "stocked"))
                .thenExecute(() -> {
                    NetworkState network = NetworkGameTests.net(helper, drive);
                    energy[0] = network.storedEnergy();
                    ResourceHandler<ItemResource> items = items(helper, INTERFACE, Direction.UP);
                    helper.assertValueEqual(extract(items, 0, cobble(), 20, false), 20, "simulated pull");
                    helper.assertValueEqual(insert(items, cobble(), 30, false), 30, "simulated insert");
                    helper.assertValueEqual(worker.itemStock().getAmountAsLong(0), 16L, "stock after rollbacks");
                    helper.assertValueEqual(drive.provider.amount(COBBLE), 84L, "drive after rollbacks");
                    helper.assertValueEqual(network.contents().amount(COBBLE), 84L, "index after rollbacks");
                    helper.assertValueEqual(network.storedEnergy(), energy[0], "FE after rollbacks");
                }).thenIdle(20).thenExecute(() -> {
                    helper.assertValueEqual(worker.itemStock().getAmountAsLong(0), 16L, "stock later");
                    helper.assertValueEqual(drive.provider.amount(COBBLE), 84L, "drive later");
                }).thenSucceed();
    }

    /**
     * An interface part on a cable's east face offers handlers on that face only, stores inserts in the network and
     * keeps its stock; taken off, its item carries settings and stock onto another face, and the old part's handler
     * does nothing more.
     */
    private static void partOnCableFace(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper, POWER);
        NetworkGameTests.cables(helper, 3, 3, CableContent.STORAGE_CABLE_TIER_1.type().get());
        CableParts parts = PartGameTests.parts(helper, 3);
        helper.assertTrue(parts.place(Direction.EAST, InterfaceContent.INTERFACE_PART, null,
                new ItemStack(InterfaceContent.INTERFACE_PART_ITEM.get())), "part not placed");
        InterfacePart part = (InterfacePart) PartGameTests.part(helper, parts, Direction.EAST);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        part.deviceLink().network() != null && part.deviceLink().network() == drive.network(),
                        "part not in the network"))
                .thenExecute(() -> {
                    helper.assertTrue(capability(helper, INTERFACE, Direction.WEST) == null, "a handler on the west");
                    ResourceHandler<ItemResource> items = items(helper, INTERFACE, Direction.EAST);
                    helper.assertValueEqual(insert(items, cobble(), 40, true), 40, "inserted through the part");
                    helper.assertValueEqual(drive.provider.amount(COBBLE), 40L, "in the drive");
                    part.worker().config().setItem(2, new ItemStack(Items.COBBLESTONE, 8));
                }).thenWaitUntil(() -> helper.assertValueEqual(part.worker().itemStock().getAmountAsLong(2), 8L,
                        "stocked by the part"))
                .thenExecute(() -> {
                    ResourceHandler<ItemResource> stale = items(helper, INTERFACE, Direction.EAST);
                    CablePart removed = parts.remove(Direction.EAST);
                    helper.assertTrue(removed == part, "not removed");
                    ItemStack item = part.toItem();
                    helper.assertTrue(item.has(DataComponents.CONTAINER), "stock not in the item");
                    helper.assertTrue(item.has(ModDataComponents.INTERFACE_CONFIG.get()), "settings not in the item");
                    helper.assertValueEqual(insert(stale, cobble(), 5, true), 0, "the removed part took items");
                    helper.assertValueEqual(extract(stale, 2, cobble(), 5, true), 0, "the removed part gave items");
                    helper.assertTrue(parts.place(Direction.UP, InterfaceContent.INTERFACE_PART, null, item),
                            "placed again");
                    InterfacePart again = (InterfacePart) PartGameTests.part(helper, parts, Direction.UP);
                    helper.assertValueEqual(again.worker().itemStock().getAmountAsLong(2), 8L, "stock after moving");
                    helper.assertValueEqual(again.worker().config().getItem(2).getCount(), 8, "target after moving");
                    helper.assertValueEqual(drive.provider.amount(COBBLE), 32L, "nothing made or lost");
                }).thenSucceed();
    }

    /** A broken interface drops one item with its settings, stock and tooltip; placed again, both come back. */
    private static void dropKeepsSettingsAndStock(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper, POWER);
        InterfaceBlockEntity iface = interfaceBlock(helper);
        helper.startSequence().thenWaitUntil(() -> inNetwork(helper, drive, iface)).thenExecute(() -> {
            drive.provider.setDirect(COBBLE, 50);
            iface.worker().config().setItem(4, new ItemStack(Items.COBBLESTONE, 10));
        }).thenWaitUntil(() -> helper.assertValueEqual(iface.worker().itemStock().getAmountAsLong(4), 10L, "stocked"))
                .thenExecute(() -> {
                    helper.getLevel().destroyBlock(helper.absolutePos(INTERFACE), true);
                    List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                            new AABB(helper.absolutePos(INTERFACE)).inflate(2));
                    helper.assertValueEqual(drops.size(), 1, "drops");
                    ItemStack item = drops.getFirst().getItem();
                    drops.getFirst().discard();
                    helper.assertTrue(item.is(InterfaceContent.INTERFACE.item().get()), "not the interface");
                    List<Component> lines = new ArrayList<>();
                    InterfaceItems.appendTooltip(item, lines::add);
                    helper.assertValueEqual(lines.size(), 1, "tooltip lines");
                    InterfaceBlockEntity again = interfaceBlock(helper);
                    again.applyComponentsFromItemStack(item);
                    helper.assertValueEqual(again.worker().itemStock().getAmountAsLong(4), 10L, "stock back");
                    helper.assertValueEqual(again.worker().config().getItem(4).getCount(), 10, "target back");
                    helper.assertValueEqual(drive.provider.amount(COBBLE), 40L, "nothing made or lost");
                }).thenSucceed();
    }

    /**
     * An owned interface whose team lacks the unlock takes no insert and stocks nothing, and does not run; paying for
     * Port Tier 2 and Interface wakes it and it stocks.
     */
    private static void researchGate(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper, POWER);
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "iface_research");
        ResearchTeam team = Research.team(owner, true);
        InterfaceBlockEntity iface = interfaceBlock(helper);
        BlockState state = helper.getBlockState(INTERFACE);
        state.getBlock().setPlacedBy(helper.getLevel(), helper.absolutePos(INTERFACE), state, owner, ItemStack.EMPTY);
        MinecraftServer server = helper.getLevel().getServer();
        long[] runs = new long[1];
        helper.startSequence().thenWaitUntil(() -> inNetwork(helper, drive, iface)).thenExecute(() -> {
            drive.provider.setDirect(COBBLE, 50);
            iface.worker().config().setItem(0, new ItemStack(Items.COBBLESTONE, 10));
        }).thenIdle(20).thenExecute(() -> {
            helper.assertValueEqual(iface.worker().itemStock().getAmountAsLong(0), 0L, "stocked without research");
            helper.assertValueEqual(insert(items(helper, INTERFACE, Direction.UP), cobble(), 5, true), 0,
                    "inserted without research");
            runs[0] = NetworkGameTests.taskRuns(NetworkGameTests.net(helper, drive));
        }).thenIdle(20).thenExecute(() -> {
            helper.assertValueEqual(NetworkGameTests.taskRuns(NetworkGameTests.net(helper, drive)), runs[0],
                    "task runs while locked");
            Research.addPoints(server, team, 1000);
            helper.assertValueEqual(Research.spendPoints(server, team, MaxtechStorage.id("port_tier_2")),
                    Research.Result.DONE, "Port Tier 2");
            helper.assertValueEqual(Research.spendPoints(server, team, MaxtechStorage.id("interface")),
                    Research.Result.DONE, "Interface");
        }).thenWaitUntil(() -> helper.assertValueEqual(iface.worker().itemStock().getAmountAsLong(0), 10L,
                "stocked after research")).thenSucceed();
    }

    /**
     * The GUI's ghost slots: a click with a stack sets item and count, a right-click with the same item adds one, an
     * empty-handed right-click takes one, an empty-handed click clears; a fluid slot takes a filled bucket only, one
     * bucket per click; a shift-click names the first free slot; nothing leaves the player's inventory, and a stranger
     * changes nothing.
     */
    private static void menuGhostSlots(GameTestHelper helper) {
        network(helper, POWER);
        InterfaceBlockEntity iface = interfaceBlock(helper);
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "iface_menu");
        player.setPos(helper.absoluteVec(INTERFACE.above().getCenter()));
        InterfaceMenu menu = InterfaceMenu.forDevice(1, player.getInventory(), iface);
        menu.setCarried(new ItemStack(Items.COBBLESTONE, 12));
        menu.clicked(0, 0, ContainerInput.PICKUP, player);
        helper.assertValueEqual(iface.worker().config().getItem(0).getCount(), 12, "set from the stack");
        menu.clicked(0, 1, ContainerInput.PICKUP, player);
        helper.assertValueEqual(iface.worker().config().getItem(0).getCount(), 13, "right-click adds one");
        helper.assertValueEqual(menu.getCarried().getCount(), 12, "the carried stack");
        menu.setCarried(ItemStack.EMPTY);
        menu.clicked(0, 1, ContainerInput.PICKUP, player);
        helper.assertValueEqual(iface.worker().config().getItem(0).getCount(), 12, "right-click takes one");
        menu.clicked(0, 0, ContainerInput.PICKUP, player);
        helper.assertTrue(iface.worker().config().getItem(0).isEmpty(), "click clears");
        int fluidSlot = InterfaceWorker.ITEM_SLOTS;
        menu.setCarried(new ItemStack(Items.COBBLESTONE));
        menu.clicked(fluidSlot, 0, ContainerInput.PICKUP, player);
        helper.assertTrue(iface.worker().config().getItem(fluidSlot).isEmpty(), "an item in a fluid slot");
        menu.setCarried(new ItemStack(Items.WATER_BUCKET));
        menu.clicked(fluidSlot, 0, ContainerInput.PICKUP, player);
        menu.clicked(fluidSlot, 1, ContainerInput.PICKUP, player);
        menu.clicked(fluidSlot, 1, ContainerInput.PICKUP, player);
        helper.assertValueEqual(iface.worker().config().getItem(fluidSlot).getCount(), 3, "three buckets");
        helper.assertValueEqual(iface.worker().fluidTarget(0), 3000, "fluid target");
        menu.setCarried(ItemStack.EMPTY);
        player.getInventory().setItem(0, new ItemStack(Items.DIRT, 7));
        int inventorySlot = InterfaceMenu.CARD_SLOT + 1 + 27;
        menu.quickMoveStack(player, inventorySlot);
        helper.assertValueEqual(iface.worker().config().getItem(0).getCount(), 7, "shift-click names dirt");
        helper.assertValueEqual(player.getInventory().getItem(0).getCount(), 7, "dirt left the inventory");
        iface.setOwner(OwnerRecord.of(NetworkGameTests.player(helper.getLevel(), "iface_owner")));
        menu.clicked(0, 0, ContainerInput.PICKUP, player);
        helper.assertValueEqual(iface.worker().config().getItem(0).getCount(), 7, "a stranger changed a slot");
        helper.succeed();
    }

    /** Made when used: item components are not bound while the class loads. */
    private static ItemResource cobble() {
        return ItemResource.of(Items.COBBLESTONE);
    }

    /**
     * A neighbour calling from another thread (another mod's worker) moves nothing: its insert and its pulls from the
     * stock and from the network all return 0, and the stock, the drive and the FE stay as they were.
     */
    private static void offThreadDoesNothing(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper, POWER);
        InterfaceBlockEntity iface = interfaceBlock(helper);
        helper.startSequence().thenWaitUntil(() -> inNetwork(helper, drive, iface)).thenExecute(() -> {
            drive.provider.setDirect(COBBLE, 100);
            iface.worker().config().setItem(0, new ItemStack(Items.COBBLESTONE, 16));
            drive.provider.setDirect(WATER, 1000);
            iface.worker().config().setItem(InterfaceWorker.ITEM_SLOTS, new ItemStack(Items.WATER_BUCKET));
        }).thenWaitUntil(() -> {
            helper.assertValueEqual(iface.worker().itemStock().getAmountAsLong(0), 16L, "stocked");
            helper.assertValueEqual(iface.worker().fluidStock().getAmountAsLong(0), 1000L, "water stocked");
        }).thenExecute(() -> {
            ResourceHandler<ItemResource> items = items(helper, INTERFACE, Direction.UP);
            ResourceHandler<FluidResource> fluids = fluids(helper);
            long energy = NetworkGameTests.net(helper, drive).storedEnergy();
            int[] moved = new int[4];
            Thread other = new Thread(() -> {
                moved[0] = insert(items, cobble(), 10, true);
                moved[1] = extract(items, 0, cobble(), 20, true);
                try (Transaction transaction = Transaction.openRoot()) {
                    moved[2] = fluids.extract(0, FluidResource.of(Fluids.WATER), 500, transaction);
                    moved[3] = ResourceHandlerUtil.insertStacking(fluids, FluidResource.of(Fluids.LAVA), 500,
                            transaction);
                    transaction.commit();
                }
            }, "iface-off-thread");
            other.start();
            try {
                other.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            helper.assertFalse(other.isAlive(), "the other thread did not finish");
            helper.assertValueEqual(moved[0] + moved[1] + moved[2] + moved[3], 0, "moved off the server thread");
            helper.assertValueEqual(iface.worker().itemStock().getAmountAsLong(0), 16L, "item stock");
            helper.assertValueEqual(iface.worker().fluidStock().getAmountAsLong(0), 1000L, "fluid stock");
            helper.assertValueEqual(drive.provider.amount(COBBLE), 84L, "cobblestone in the drive");
            helper.assertValueEqual(NetworkGameTests.net(helper, drive).storedEnergy(), energy, "FE");
        }).thenSucceed();
    }

    /** A powered controller with a test drive behind it and the cable towards the interface. */
    private static TestStorage.TestDevice network(GameTestHelper helper, long power) {
        NetworkGameTests.controller(helper, 1);
        if (power > 0) {
            NetworkGameTests.power(helper, CONTROLLER, power);
        }
        TestStorage.TestDevice drive = NetworkGameTests.device(helper, DRIVE);
        NetworkGameTests.cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_1.type().get());
        return drive;
    }

    /** Places a fresh interface block at {@link #INTERFACE}. */
    private static InterfaceBlockEntity interfaceBlock(GameTestHelper helper) {
        helper.setBlock(INTERFACE, InterfaceContent.INTERFACE.get());
        return helper.getBlockEntity(INTERFACE, InterfaceBlockEntity.class);
    }

    private static void inNetwork(GameTestHelper helper, TestStorage.TestDevice drive, InterfaceBlockEntity iface) {
        NetworkState network = NetworkGameTests.net(helper, drive);
        helper.assertTrue(network == iface.network(), "interface not in the network");
        helper.assertTrue(network.routeRoot() == helper.absolutePos(CONTROLLER).asLong(), "no routes yet");
    }

    private static @Nullable ResourceHandler<ItemResource> capability(GameTestHelper helper, BlockPos pos,
            Direction side) {
        return helper.getLevel().getCapability(Capabilities.Item.BLOCK, helper.absolutePos(pos), side);
    }

    private static ResourceHandler<ItemResource> items(GameTestHelper helper, BlockPos pos, Direction side) {
        ResourceHandler<ItemResource> handler = capability(helper, pos, side);
        if (handler == null) {
            throw helper.assertionException(Component.literal("No item handler at " + pos + " " + side));
        }
        return handler;
    }

    private static ResourceHandler<FluidResource> fluids(GameTestHelper helper) {
        ResourceHandler<FluidResource> handler = helper.getLevel().getCapability(Capabilities.Fluid.BLOCK,
                helper.absolutePos(INTERFACE), Direction.UP);
        if (handler == null) {
            throw helper.assertionException(Component.literal("No fluid handler"));
        }
        return handler;
    }

    private static int insert(ResourceHandler<ItemResource> handler, ItemResource resource, int amount,
            boolean commit) {
        try (Transaction transaction = Transaction.openRoot()) {
            int moved = ResourceHandlerUtil.insertStacking(handler, resource, amount, transaction);
            if (commit) {
                transaction.commit();
            }
            return moved;
        }
    }

    private static int extract(ResourceHandler<ItemResource> handler, int index, ItemResource resource, int amount,
            boolean commit) {
        try (Transaction transaction = Transaction.openRoot()) {
            int moved = handler.extract(index, resource, amount, transaction);
            if (commit) {
                transaction.commit();
            }
            return moved;
        }
    }
}
