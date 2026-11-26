package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.energy.FeBuffer;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.material.MaterialContent;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMenu;
import dev.silencebound.maxtech.storage.port.PortMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Storage ports (M6a area 4): import and export through real handlers, the filter, the rate, the energy cap, and the
 * anti-duplication cases (PRD 7.2, Performance): a full network, an inventory that takes less than it promised, and a
 * network whose index still lists a chest emptied behind its back. Layout along x at y = 1, z = 1: the controller at x
 * 1 with a test drive behind it (z 2), a tier-1 cable at x 2, the port at x 3 facing east into the inventory at x 4.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
// Networks are compared by identity: which one a port is in is the point.
@SuppressWarnings("ReferenceEquality")
public final class PortGameTests {
    private static final String ENERGY_CAPS = "port_energy_caps_operation";
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final BlockPos CONTROLLER = new BlockPos(1, 1, 1);
    private static final BlockPos DRIVE = new BlockPos(1, 1, 2);
    private static final BlockPos PORT = new BlockPos(3, 1, 1);
    private static final BlockPos INVENTORY = new BlockPos(4, 1, 1);
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey DIRT = ItemKey.of(Items.DIRT);
    private static final long POWER = 1000;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<String> TESTS = new ArrayList<>();

    static {
        TestStorage.init();
        PortTestContent.init();
        StorageTestEnvironment.init();
        register("port_import_moves_items", PortGameTests::importMovesItems);
        register("port_rate_limit", PortGameTests::rateLimit);
        register("port_import_mixed_kinds", PortGameTests::importMixedKinds);
        register("port_mixed_one_slot_breaks", PortGameTests::mixedOneSlotBreaks);
        register("port_operation_unpaid_or_rolled_back", PortGameTests::operationUnpaidOrRolledBack);
        register("port_export_moves_items", PortGameTests::exportMovesItems);
        register("port_export_mixed_kinds", PortGameTests::exportMixedKinds);
        register("port_export_blank_filter_moves_nothing", PortGameTests::exportBlankFilter);
        register("port_import_filter", PortGameTests::importFilter);
        register("port_network_full_keeps_rest", PortGameTests::networkFull);
        register("port_rollback_on_short_extract", PortGameTests::rollbackOnShortExtract);
        register("port_anti_dupe_chest_emptied", PortGameTests::antiDupeChestEmptied);
        FUNCTIONS.register(ENERGY_CAPS, () -> StorageTestEnvironment.clearing(PortGameTests::energyCapsOperation));
        register("port_links_only_on_back", PortGameTests::linksOnlyOnBack);
        register("port_fluid_import", PortGameTests::fluidImport);
        register("port_menu_ghost_filter", PortGameTests::menuGhostFilter);
        register("port_recipes_craft", PortGameTests::recipesCraft);
        register("port_import_through_controller_join", PortGameTests::importThroughControllerJoin);
    }

    private PortGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        TESTS.add(name);
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("port"),
                StorageTestEnvironment.plain());
        for (String name : TESTS) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, STRUCTURE, 400, 0, true)));
        }
        // A cost per item, which the presets leave at 0, so a low buffer shrinks an operation.
        Holder<TestEnvironmentDefinition<?>> perItem = event.registerEnvironment(MaxtechStorage.id(ENERGY_CAPS),
                new StorageTestEnvironment("network.energy.perUnit", "1", false));
        event.registerTest(MaxtechStorage.id(ENERGY_CAPS),
                new FunctionGameTestInstance(
                        ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(ENERGY_CAPS)),
                        new TestData<>(perItem, STRUCTURE, 400, 0, true)));
    }

    /**
     * 40 cobblestone leave the chest in three operations (16, 16, 8), all arrive in the drive, and the network pays 4
     * FE per operation whatever it moves; idle runs afterwards cost nothing.
     */
    private static void importMovesItems(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ChestBlockEntity chest = chest(helper, new ItemStack(Items.COBBLESTONE, 40));
        port(helper, PortMode.IMPORT);
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertValueEqual(drive.provider.amount(COBBLE), 40L, "cobblestone in the drive");
            helper.assertValueEqual(count(chest, Items.COBBLESTONE), 0, "cobblestone left in the chest");
        }).thenIdle(60).thenExecute(() -> helper.assertValueEqual(NetworkGameTests.net(helper, drive).storedEnergy(),
                POWER - 3 * 4, "FE after three operations")).thenSucceed();
    }

    /**
     * Twelve kinds of four items each (M7E decision 4): one operation takes several slots up to the port's step of 16,
     * so the 48 items leave in three operations (12 FE), not twelve; every item arrives once.
     */
    private static void importMixedKinds(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        Item[] kinds = {Items.COBBLESTONE, Items.DIRT, Items.SAND, Items.GRAVEL, Items.STONE, Items.GRANITE,
                Items.DIORITE, Items.ANDESITE, Items.OAK_LOG, Items.BIRCH_LOG, Items.IRON_INGOT, Items.GOLD_INGOT};
        ItemStack[] stacks = new ItemStack[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            stacks[i] = new ItemStack(kinds[i], 4);
        }
        ChestBlockEntity chest = chest(helper, stacks);
        port(helper, PortMode.IMPORT);
        helper.startSequence().thenWaitUntil(() -> {
            for (Item kind : kinds) {
                helper.assertValueEqual(drive.provider.amount(ItemKey.of(kind)), 4L, kind + " in the drive");
            }
        }).thenIdle(40).thenExecute(() -> {
            for (Item kind : kinds) {
                helper.assertValueEqual(drive.provider.amount(ItemKey.of(kind)), 4L, kind + " in the drive");
                helper.assertValueEqual(count(chest, kind), 0, kind + " left in the chest");
            }
            helper.assertValueEqual(NetworkGameTests.net(helper, drive).storedEnergy(), POWER - 3 * 4,
                    "FE after three operations");
        }).thenSucceed();
    }

    /**
     * Three kinds in one operation, the middle slot breaking its promise every time: cobblestone and sand arrive
     * exactly, the dirt stays in the inventory, and the network pays and counts one operation for the eight items.
     */
    private static void mixedOneSlotBreaks(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        helper.setBlock(INVENTORY, PortTestContent.FLAKY.get());
        PortTestContent.FlakyInventory inventory = helper.getBlockEntity(INVENTORY,
                PortTestContent.FlakyInventory.class);
        inventory.handler.flakySlot = 1;
        inventory.handler.set(0, ItemResource.of(Items.COBBLESTONE), 4);
        inventory.handler.set(1, ItemResource.of(Items.DIRT), 4);
        inventory.handler.set(2, ItemResource.of(Items.SAND), 4);
        long[] before = {-1};
        PortBlockEntity[] port = new PortBlockEntity[1];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.net(helper, drive);
            before[0] = network.operations();
        }).thenExecute(() -> port[0] = port(helper, PortMode.IMPORT))
                .thenWaitUntil(() -> helper.assertValueEqual(drive.provider.amount(ItemKey.of(Items.SAND)), 4L, "sand"))
                .thenIdle(60).thenExecute(() -> {
                    NetworkState network = NetworkGameTests.net(helper, drive);
                    helper.assertValueEqual(drive.provider.amount(COBBLE), 4L, "cobblestone in the drive");
                    helper.assertValueEqual(drive.provider.amount(DIRT), 0L, "dirt in the drive");
                    helper.assertValueEqual(network.contents().amount(DIRT), 0L, "indexed dirt");
                    helper.assertValueEqual(inventory.handler.getAmountAsLong(0), 0L, "cobblestone left");
                    helper.assertValueEqual(inventory.handler.getAmountAsLong(1), 4L, "dirt left");
                    helper.assertValueEqual(inventory.handler.getAmountAsLong(2), 0L, "sand left");
                    helper.assertValueEqual(network.storedEnergy(), POWER - 4, "FE after one operation");
                    helper.assertValueEqual(network.operations() - before[0], 1L, "operations counted");
                }).thenSucceed();
    }

    /**
     * The multi-key operation contract on a port's own transfer: when the FE is gone by the time the operation is paid,
     * it refuses and the rolled-back moves leave nothing stored or counted; a paid operation whose root transaction
     * then rolls back is not counted either, and its FE comes back. A finished operation is closed, so the next insert
     * pays for itself again.
     */
    private static void operationUnpaidOrRolledBack(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        PortBlockEntity port = port(helper, PortMode.IMPORT);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(port.network() != null, "port not in a network"))
                .thenExecute(() -> {
                    NetworkState network = NetworkGameTests.net(helper, drive);
                    FeBuffer buffer = NetworkGameTests.controllerEntity(helper, CONTROLLER).buffer();
                    StorageTransfer transfer = port.deviceLink().transfer();
                    if (transfer == null) {
                        throw helper.assertionException("no transfer");
                    }
                    long operations = network.operations();
                    // The energy runs out between the moves and the payment.
                    helper.assertValueEqual(transfer.beginOperation(StackKey.Kind.ITEM), 64L, "budget");
                    try (Transaction root = Transaction.openRoot()) {
                        helper.assertValueEqual(transfer.insert(COBBLE, 10, root), 10L, "cobblestone stored");
                        helper.assertValueEqual(transfer.insert(DIRT, 10, root), 10L, "dirt stored");
                        buffer.load(0);
                        helper.assertFalse(transfer.finishOperation(StackKey.Kind.ITEM, 20, root), "paid");
                    }
                    helper.assertValueEqual(drive.provider.amount(COBBLE) + drive.provider.amount(DIRT), 0L,
                            "items in the drive after a refused payment");
                    helper.assertValueEqual(network.operations(), operations, "operations after a refused payment");
                    // Paid, then the root rolls back as if the caller had thrown.
                    buffer.load(POWER);
                    helper.assertValueEqual(transfer.beginOperation(StackKey.Kind.ITEM), 64L, "budget");
                    try (Transaction root = Transaction.openRoot()) {
                        transfer.insert(COBBLE, 10, root);
                        helper.assertTrue(transfer.finishOperation(StackKey.Kind.ITEM, 10, root), "not paid");
                    }
                    helper.assertValueEqual(drive.provider.amount(COBBLE), 0L, "cobblestone after the rollback");
                    helper.assertValueEqual(network.operations(), operations, "operations after the rollback");
                    helper.assertValueEqual(network.storedEnergy(), POWER, "FE after the rollback");
                    // Paid and committed: counted once.
                    helper.assertValueEqual(transfer.beginOperation(StackKey.Kind.ITEM), 64L, "budget");
                    try (Transaction root = Transaction.openRoot()) {
                        transfer.insert(COBBLE, 10, root);
                        transfer.insert(DIRT, 10, root);
                        helper.assertTrue(transfer.finishOperation(StackKey.Kind.ITEM, 20, root), "not paid");
                        root.commit();
                    }
                    helper.assertValueEqual(network.operations(), operations + 1, "operations after a commit");
                    helper.assertValueEqual(network.storedEnergy(), POWER - 4, "FE after a commit");
                    // Closed: a plain insert is one paid operation of its own again.
                    try (Transaction root = Transaction.openRoot()) {
                        transfer.insert(COBBLE, 10, root);
                        root.commit();
                    }
                    helper.assertValueEqual(drive.provider.amount(COBBLE), 20L, "cobblestone after a plain insert");
                    helper.assertValueEqual(network.storedEnergy(), POWER - 8, "FE after a plain insert");
                    helper.assertValueEqual(network.operations(), operations + 2, "operations after a plain insert");
                }).thenSucceed();
    }

    /**
     * From a full chest a tier-1 port moves its one-second start credit at once, then 16 items a second: 4 or 5 steps
     * of 16 in the next 100 ticks, never more.
     */
    private static void rateLimit(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ChestBlockEntity chest = chest(helper);
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            chest.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        port(helper, PortMode.IMPORT);
        long[] start = new long[1];
        helper.startSequence().thenWaitUntil(() -> {
            start[0] = drive.provider.amount(COBBLE);
            helper.assertTrue(start[0] > 0, "nothing moved yet");
        }).thenExecute(() -> helper.assertValueEqual(start[0], 16L, "the start credit")).thenIdle(100)
                .thenExecute(() -> {
                    long moved = drive.provider.amount(COBBLE) - start[0];
                    helper.assertTrue(moved >= 64 && moved <= 80, moved + " items in 100 ticks at 16 a second");
                    helper.assertValueEqual(count(chest, Items.COBBLESTONE) + drive.provider.amount(COBBLE), 27L * 64,
                            "items in the chest and the drive");
                }).thenSucceed();
    }

    /**
     * An export port with cobblestone in its filter empties the drive's 100 cobblestone into the chest, nothing else.
     */
    private static void exportMovesItems(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        drive.provider.setDirect(COBBLE, 100);
        drive.provider.setDirect(DIRT, 50);
        ChestBlockEntity chest = chest(helper);
        PortBlockEntity port = port(helper, PortMode.EXPORT);
        port.filter().setItem(0, new ItemStack(Items.COBBLESTONE));
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertValueEqual(count(chest, Items.COBBLESTONE), 100, "cobblestone in the chest");
            helper.assertValueEqual(drive.provider.amount(COBBLE), 0L, "cobblestone left in the drive");
        }).thenExecute(() -> {
            helper.assertValueEqual(count(chest, Items.DIRT), 0, "dirt in the chest");
            helper.assertValueEqual(NetworkGameTests.net(helper, drive).contents().amount(DIRT), 50L, "indexed dirt");
            helper.assertValueEqual(NetworkGameTests.net(helper, drive).contents().amount(COBBLE), 0L,
                    "indexed cobblestone");
        }).thenSucceed();
    }

    /**
     * An export port with nine kinds in its filter, four of each in the drive: one operation fills up to the port's
     * step of 16 from several kinds (M7E follow-up), so the 36 items leave in three operations (12 FE), not nine; every
     * item arrives once and a kind outside the filter stays.
     */
    private static void exportMixedKinds(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        Item[] kinds = {Items.COBBLESTONE, Items.DIRT, Items.SAND, Items.GRAVEL, Items.STONE, Items.GRANITE,
                Items.DIORITE, Items.ANDESITE, Items.OAK_LOG};
        for (Item kind : kinds) {
            drive.provider.setDirect(ItemKey.of(kind), 4);
        }
        drive.provider.setDirect(ItemKey.of(Items.BIRCH_LOG), 4);
        ChestBlockEntity chest = chest(helper);
        PortBlockEntity port = port(helper, PortMode.EXPORT);
        for (int i = 0; i < kinds.length; i++) {
            port.filter().setItem(i, new ItemStack(kinds[i]));
        }
        helper.startSequence().thenWaitUntil(() -> {
            for (Item kind : kinds) {
                helper.assertValueEqual(count(chest, kind), 4, kind + " in the chest");
            }
        }).thenIdle(40).thenExecute(() -> {
            for (Item kind : kinds) {
                helper.assertValueEqual(count(chest, kind), 4, kind + " in the chest");
                helper.assertValueEqual(drive.provider.amount(ItemKey.of(kind)), 0L, kind + " left in the drive");
            }
            helper.assertValueEqual(drive.provider.amount(ItemKey.of(Items.BIRCH_LOG)), 4L, "birch outside the filter");
            helper.assertValueEqual(NetworkGameTests.net(helper, drive).storedEnergy(), POWER - 3 * 4,
                    "FE after three operations");
        }).thenSucceed();
    }

    /** An export port with an empty filter names nothing, so it moves nothing. */
    private static void exportBlankFilter(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        drive.provider.setDirect(COBBLE, 100);
        ChestBlockEntity chest = chest(helper);
        PortBlockEntity port = port(helper, PortMode.EXPORT);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(port.network() != null, "port not in a network"))
                .thenIdle(60).thenExecute(() -> {
                    helper.assertValueEqual(count(chest, Items.COBBLESTONE), 0, "cobblestone in the chest");
                    helper.assertValueEqual(drive.provider.amount(COBBLE), 100L, "cobblestone in the drive");
                }).thenSucceed();
    }

    /** With dirt in its filter an import port takes the dirt and leaves the cobblestone. */
    private static void importFilter(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ChestBlockEntity chest = chest(helper, new ItemStack(Items.COBBLESTONE, 32), new ItemStack(Items.DIRT, 32));
        PortBlockEntity port = port(helper, PortMode.IMPORT);
        port.filter().setItem(4, new ItemStack(Items.DIRT));
        helper.startSequence().thenWaitUntil(() -> helper.assertValueEqual(drive.provider.amount(DIRT), 32L, "dirt"))
                .thenIdle(40).thenExecute(() -> {
                    helper.assertValueEqual(drive.provider.amount(COBBLE), 0L, "cobblestone in the drive");
                    helper.assertValueEqual(count(chest, Items.COBBLESTONE), 32, "cobblestone in the chest");
                }).thenSucceed();
    }

    /**
     * With room for 10 more items in the network, the port moves exactly 10; the chest keeps the other 54 and nothing
     * is lost or made.
     */
    private static void networkFull(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        drive.provider.setDirect(DIRT, TestStorage.DEVICE_CAPACITY - 10);
        ChestBlockEntity chest = chest(helper, new ItemStack(Items.COBBLESTONE, 64));
        port(helper, PortMode.IMPORT);
        helper.startSequence().thenWaitUntil(() -> helper.assertValueEqual(drive.provider.amount(COBBLE), 10L, "moved"))
                .thenIdle(60).thenExecute(() -> {
                    helper.assertValueEqual(drive.provider.amount(COBBLE), 10L, "cobblestone in the drive");
                    helper.assertValueEqual(count(chest, Items.COBBLESTONE), 54, "cobblestone in the chest");
                    helper.assertValueEqual(NetworkGameTests.net(helper, drive).contents().amount(COBBLE), 10L,
                            "indexed cobblestone");
                }).thenSucceed();
    }

    /**
     * An inventory whose real extraction takes half of what its simulation offered: every move rolls back, so the
     * inventory keeps all 64, the network gains nothing and pays no energy.
     */
    private static void rollbackOnShortExtract(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        helper.setBlock(INVENTORY, PortTestContent.FLAKY.get());
        PortTestContent.FlakyInventory inventory = helper.getBlockEntity(INVENTORY,
                PortTestContent.FlakyInventory.class);
        inventory.handler.set(0, ItemResource.of(Items.COBBLESTONE), 64);
        PortBlockEntity port = port(helper, PortMode.IMPORT);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(port.network() != null, "port not in a network"))
                .thenIdle(60).thenExecute(() -> {
                    helper.assertValueEqual(inventory.handler.getAmountAsLong(0), 64L, "items in the inventory");
                    helper.assertValueEqual(drive.provider.amount(COBBLE), 0L, "cobblestone in the drive");
                    NetworkState network = NetworkGameTests.net(helper, drive);
                    helper.assertValueEqual(network.contents().amount(COBBLE), 0L, "indexed cobblestone");
                    helper.assertValueEqual(network.storedEnergy(), POWER, "FE");
                }).thenSucceed();
    }

    /**
     * The network's storage is a chest read into its index once; the chest is then emptied by hand, so the index still
     * says 64. An export port asking for them, and a player extract, move nothing: no item appears in the target chest,
     * the index changes only by what moved (nothing), and no energy is spent.
     */
    private static void antiDupeChestEmptied(GameTestHelper helper) {
        BlockPos source = DRIVE.above();
        NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, CONTROLLER, POWER);
        helper.setBlock(DRIVE, PortTestContent.CHEST_DEVICE.get());
        helper.setBlock(source, Blocks.CHEST);
        helper.getBlockEntity(source, ChestBlockEntity.class).setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        PortTestContent.ChestDevice drive = helper.getBlockEntity(DRIVE, PortTestContent.ChestDevice.class);
        NetworkGameTests.cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_1.type().get());
        ChestBlockEntity target = chest(helper);
        PortBlockEntity[] port = new PortBlockEntity[1];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, CONTROLLER);
            helper.assertValueEqual(network.contents().amount(COBBLE), 64L, "indexed cobblestone");
            helper.assertTrue(drive.network() == network, "chest device not in the network");
        }).thenExecute(() -> {
            // Behind the network's back: the provider reports nothing about this.
            helper.getBlockEntity(source, ChestBlockEntity.class).clearContent();
            port[0] = port(helper, PortMode.EXPORT);
            port[0].filter().setItem(0, new ItemStack(Items.COBBLESTONE));
        }).thenWaitUntil(() -> helper.assertTrue(port[0].network() != null, "port not in a network")).thenIdle(60)
                .thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, CONTROLLER);
                    helper.assertValueEqual(count(target, Items.COBBLESTONE), 0, "cobblestone in the target chest");
                    helper.assertValueEqual(network.contents().amount(COBBLE), 64L,
                            "the index changed without anything moving");
                    try (Transaction transaction = Transaction.openRoot()) {
                        helper.assertValueEqual(network.extract(COBBLE, 64, transaction), 0L, "player extract");
                        transaction.commit();
                    }
                    helper.assertValueEqual(network.storedEnergy(), POWER, "FE");
                    helper.assertValueEqual(
                            count(helper.getBlockEntity(source, ChestBlockEntity.class), Items.COBBLESTONE), 0,
                            "cobblestone back in the source chest");
                }).thenSucceed();
    }

    /**
     * With 1 FE per item on top of 4 per operation, 10 FE make the port's first operation move the 6 items those pay
     * for instead of refusing its step of 16; a player insert with 20 FE stores 16 of 64.
     */
    private static void energyCapsOperation(GameTestHelper helper) {
        NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, CONTROLLER, 10);
        TestStorage.TestDevice drive = NetworkGameTests.device(helper, DRIVE);
        NetworkGameTests.cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_1.type().get());
        ChestBlockEntity chest = chest(helper, new ItemStack(Items.COBBLESTONE, 64));
        port(helper, PortMode.IMPORT);
        helper.startSequence().thenWaitUntil(() -> helper.assertValueEqual(drive.provider.amount(COBBLE), 6L, "moved"))
                .thenIdle(40).thenExecute(() -> {
                    helper.assertValueEqual(drive.provider.amount(COBBLE), 6L, "cobblestone in the drive");
                    helper.assertValueEqual(count(chest, Items.COBBLESTONE), 58, "cobblestone in the chest");
                    NetworkState network = NetworkGameTests.net(helper, drive);
                    helper.assertValueEqual(network.storedEnergy(), 0L, "FE");
                    NetworkGameTests.power(helper, CONTROLLER, 20);
                    helper.assertValueEqual(NetworkGameTests.insert(network, DIRT, 64), 16L, "player insert");
                    helper.assertValueEqual(network.storedEnergy(), 0L, "FE after the player insert");
                }).thenSucceed();
    }

    /**
     * A port links to the network only through its back: a cable beside it stays apart (and draws no arm to it), a
     * cable behind it joins it.
     */
    private static void linksOnlyOnBack(GameTestHelper helper) {
        NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_1.type().get());
        // Facing north, its back is south: the cable at x 2 touches its west side.
        helper.setBlock(PORT, PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, Direction.NORTH));
        PortBlockEntity port = helper.getBlockEntity(PORT, PortBlockEntity.class);
        helper.startSequence().thenIdle(10)
                .thenExecute(() -> helper.assertTrue(
                        port.network() == null || port.network() != NetworkGameTests.state(helper, CONTROLLER),
                        "linked through its side"))
                .thenExecute(() -> {
                    for (BlockPos pos : List.of(new BlockPos(2, 1, 2), new BlockPos(3, 1, 2))) {
                        ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(pos),
                                CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
                    }
                }).thenWaitUntil(() -> helper.assertTrue(port.network() == NetworkGameTests.state(helper, CONTROLLER),
                        "not linked through its back"))
                .thenSucceed();
    }

    /** A water cauldron gives the port its 1,000 mB in one operation and is left empty. */
    private static void fluidImport(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        helper.setBlock(INVENTORY, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
        port(helper, PortMode.IMPORT);
        helper.startSequence().thenWaitUntil(() -> helper
                .assertValueEqual(drive.provider.amount(FluidKey.of(Fluids.WATER)), 1000L, "water in the drive"))
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.CAULDRON, INVENTORY);
                    helper.assertValueEqual(NetworkGameTests.net(helper, drive).storedEnergy(), POWER - 4, "FE");
                }).thenSucceed();
    }

    /**
     * The GUI's filter slots are ghosts: a click names the carried item without taking it, a shift-click clears, an
     * inventory shift-click names the stack in the first free slot and keeps it in the inventory. The button switches
     * the mode, and the filter survives saving.
     */
    private static void menuGhostFilter(GameTestHelper helper) {
        PortBlockEntity port = port(helper, PortMode.IMPORT);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        PortMenu menu = PortMenu.forPort(1, player.getInventory(), port);
        menu.setCarried(new ItemStack(Items.DIRT, 10));
        menu.clicked(0, 0, ContainerInput.PICKUP, player);
        helper.assertTrue(ItemStack.isSameItemSameComponents(port.filter().getItem(0), new ItemStack(Items.DIRT)),
                "slot 0 names dirt");
        helper.assertValueEqual(port.filter().getItem(0).getCount(), 1, "a ghost holds one");
        helper.assertValueEqual(menu.getCarried().getCount(), 10, "carried dirt");
        // A double-click on an empty hotbar slot gathers matching stacks; the ghost must stay put.
        menu.clicked(9 + 27, 0, ContainerInput.PICKUP_ALL, player);
        helper.assertValueEqual(menu.getCarried().getCount(), 10, "carried dirt after a double-click");
        helper.assertTrue(port.filter().getItem(0).is(Items.DIRT), "ghost after a double-click");
        menu.clicked(0, 0, ContainerInput.QUICK_MOVE, player);
        helper.assertTrue(port.filter().getItem(0).isEmpty(), "shift-click clears");
        player.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 5));
        // The hotbar comes after the 27 main slots, behind the 9 filter slots.
        menu.quickMoveStack(player, 9 + 27);
        helper.assertTrue(port.filter().getItem(0).is(Items.COBBLESTONE), "named by shift-click");
        helper.assertValueEqual(player.getInventory().getItem(0).getCount(), 5, "cobblestone kept");
        menu.quickMoveStack(player, 9 + 27);
        helper.assertTrue(port.filter().getItem(1).isEmpty(), "named twice");
        helper.assertTrue(menu.clickMenuButton(player, PortMenu.CYCLE_MODE), "button refused");
        helper.assertValueEqual(port.mode(), PortMode.EXPORT, "mode after the button");
        CompoundTag saved = port.saveCustomOnly(helper.getLevel().registryAccess());
        PortBlockEntity copy = new PortBlockEntity(port.getBlockPos(), port.getBlockState());
        copy.loadCustomOnly(
                TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
        helper.assertTrue(copy.filter().getItem(0).is(Items.COBBLESTONE), "filter after loading");
        helper.assertTrue(copy.filter().accepts(ItemResource.of(Items.COBBLESTONE)), "loaded filter accepts it");
        helper.assertTrue(!copy.filter().accepts(ItemResource.of(Items.DIRT)), "loaded filter accepts dirt");
        helper.succeed();
    }

    /**
     * The port, both cables and the controller craft from area 5's parts in the vanilla Crafter's own lookup, so the
     * tags they name hold those parts and the recipes are automatable.
     */
    private static void recipesCraft(GameTestHelper helper) {
        ItemStack plate = new ItemStack(MaterialContent.CASING_PLATE.get());
        ItemStack wire = new ItemStack(MaterialContent.INSULATED_COPPER_WIRE.get());
        ItemStack fibre = new ItemStack(MaterialContent.GLASS_FIBRE.get());
        ItemStack dust = new ItemStack(MaterialContent.LUMEN_QUARTZ_DUST.get());
        ItemStack quartz = new ItemStack(MaterialContent.LUMEN_QUARTZ.get());
        ItemStack cable = new ItemStack(CableContent.STORAGE_CABLE_TIER_1.item().get());
        crafts(helper,
                List.of(plate, new ItemStack(Items.HOPPER), plate, wire,
                        new ItemStack(MaterialContent.BASIC_IO_PROCESSOR.get()), wire, plate, plate, plate),
                PortContent.PORT.get().asItem(), 1);
        crafts(helper, List.of(wire, wire, wire, fibre, dust, fibre, wire, wire, wire),
                CableContent.STORAGE_CABLE_TIER_1.item().get(), 8);
        crafts(helper, List.of(cable, plate, cable, cable, new ItemStack(MaterialContent.BASIC_SIGNAL_PROCESSOR.get()),
                cable, cable, plate, cable), CableContent.STORAGE_CABLE_TIER_2.item().get(), 4);
        crafts(helper,
                List.of(plate, new ItemStack(MaterialContent.BASIC_LOGIC_PROCESSOR.get()), plate, quartz,
                        new ItemStack(MaterialContent.DISPLAY_GLASS.get()), quartz, plate,
                        new ItemStack(MaterialContent.BASIC_MEMORY_PROCESSOR.get()), plate),
                ControllerContent.CONTROLLER.get().asItem(), 1);
        helper.succeed();
    }

    private static void crafts(GameTestHelper helper, List<ItemStack> grid, Item output, int count) {
        CraftingInput input = CraftingInput.of(3, 3, new ArrayList<>(grid));
        Optional<RecipeHolder<CraftingRecipe>> recipe = CrafterBlock.getPotentialResults(helper.getLevel(), input);
        helper.assertTrue(recipe.isPresent(), "a crafting recipe makes " + output);
        ItemStack result = recipe.orElseThrow().value().assemble(input);
        helper.assertTrue(result.is(output), "crafted " + result + ", wanted " + output);
        helper.assertValueEqual(result.getCount(), count, output + " count");
    }

    /**
     * An import port keeps moving while a second network with its own controller is cabled into its network (M7N item
     * 3): the second controller goes on standby, the port goes on, and every item is in the chest or one of the two
     * drives, none duplicated or lost. The second network: its controller at x 1 z 5, its drive at z 6 and a cable at z
     * 4; the joining cable at z 3 touches the first drive.
     */
    private static void importThroughControllerJoin(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ChestBlockEntity chest = chest(helper);
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            chest.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        port(helper, PortMode.IMPORT);
        BlockPos second = new BlockPos(1, 1, 5);
        TestStorage.TestDevice[] other = new TestStorage.TestDevice[1];
        long total = 27L * 64;
        long[] atJoin = new long[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(drive.provider.amount(COBBLE) > 0, "nothing moved yet"))
                .thenExecute(() -> {
                    helper.setBlock(second, ControllerContent.CONTROLLER.get());
                    NetworkGameTests.power(helper, second, POWER);
                    other[0] = NetworkGameTests.device(helper, new BlockPos(1, 1, 6));
                    cable(helper, new BlockPos(1, 1, 4));
                }).thenWaitUntil(() -> {
                    NetworkState own = NetworkGameTests.net(helper, other[0]);
                    helper.assertTrue(own != drive.network(), "the second network on its own");
                    helper.assertTrue(own.controllerDevice() == NetworkGameTests.controllerEntity(helper, second),
                            "the second controller runs it");
                }).thenExecute(() -> {
                    atJoin[0] = drive.provider.amount(COBBLE);
                    cable(helper, new BlockPos(1, 1, 3));
                }).thenWaitUntil(() -> {
                    helper.assertTrue(other[0].network() == NetworkGameTests.net(helper, drive), "joined");
                    helper.assertTrue(NetworkGameTests.controllerEntity(helper, second).isStandby(),
                            "the second controller on standby");
                }).thenIdle(40).thenExecute(() -> {
                    long stored = drive.provider.amount(COBBLE) + other[0].provider.amount(COBBLE);
                    helper.assertValueEqual(count(chest, Items.COBBLESTONE) + stored, total,
                            "items in the chest and both drives");
                    helper.assertTrue(stored > atJoin[0] + 16, "the port kept moving: " + atJoin[0] + " -> " + stored);
                    helper.assertTrue(NetworkGameTests.net(helper, drive).controllerDevice() == NetworkGameTests
                            .controllerEntity(helper, CONTROLLER), "the first controller in charge");
                }).thenSucceed();
    }

    private static void cable(GameTestHelper helper, BlockPos pos) {
        ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(pos),
                CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
    }

    /** A powered controller with a test drive behind it and the cable towards the port. */
    private static TestStorage.TestDevice network(GameTestHelper helper) {
        NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, CONTROLLER, POWER);
        TestStorage.TestDevice drive = NetworkGameTests.device(helper, DRIVE);
        NetworkGameTests.cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_1.type().get());
        return drive;
    }

    private static PortBlockEntity port(GameTestHelper helper, PortMode mode) {
        helper.setBlock(PORT, PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, Direction.EAST)
                .setValue(PortBlock.MODE, mode));
        return helper.getBlockEntity(PORT, PortBlockEntity.class);
    }

    private static ChestBlockEntity chest(GameTestHelper helper, ItemStack... stacks) {
        helper.setBlock(INVENTORY, Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(INVENTORY, ChestBlockEntity.class);
        for (int i = 0; i < stacks.length; i++) {
            chest.setItem(i, stacks[i]);
        }
        return chest;
    }

    private static int count(ChestBlockEntity chest, Item item) {
        int total = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack stack = chest.getItem(i);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
