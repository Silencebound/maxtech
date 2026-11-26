package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.iface.InterfaceContent;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.port.BusAccess;
import dev.silencebound.maxtech.storage.port.BusStatus;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMenu;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The storage bus (B09 item 8): a port in storage mode mounts the chest it faces as network storage, read through.
 * Layout along x at y = 1, z = 1 as in {@link PortGameTests}: the controller at x 1 with a test drive behind it (z 2),
 * a tier-1 cable at x 2, the port at x 3 facing east into the chest at x 4.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
// Networks are compared by identity: which one a device is in is the point.
@SuppressWarnings("ReferenceEquality")
public final class StorageBusGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final BlockPos CONTROLLER = new BlockPos(1, 1, 1);
    private static final BlockPos DRIVE = new BlockPos(1, 1, 2);
    private static final BlockPos PORT = new BlockPos(3, 1, 1);
    private static final BlockPos INVENTORY = new BlockPos(4, 1, 1);
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey DIRT = ItemKey.of(Items.DIRT);
    private static final StackKey SAND = ItemKey.of(Items.SAND);
    private static final long POWER = 100_000;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<String> TESTS = new ArrayList<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("storage_bus_reads_through", StorageBusGameTests::readsThrough);
        register("storage_bus_no_dupe_with_hopper", StorageBusGameTests::noDupeWithHopper);
        register("storage_bus_priority_against_drive", StorageBusGameTests::priorityAgainstDrive);
        register("storage_bus_filter", StorageBusGameTests::filter);
        register("storage_bus_read_only_and_write_only", StorageBusGameTests::readOnlyAndWriteOnly);
        register("storage_bus_refuses_own_interface", StorageBusGameTests::refusesOwnInterface);
        register("storage_bus_refuses_loop_between_networks", StorageBusGameTests::refusesLoopBetweenNetworks);
        register("storage_bus_target_unloaded", StorageBusGameTests::targetUnloaded);
        register("storage_bus_target_or_bus_broken", StorageBusGameTests::targetOrBusBroken);
        register("storage_bus_settings_kept", StorageBusGameTests::settingsKept);
        register("storage_bus_abort_nested", StorageBusGameTests::abortNested);
        register("storage_bus_fluid", StorageBusGameTests::fluid);
        BusTestContent.init();
    }

    private StorageBusGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        TESTS.add(name);
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("storage_bus"),
                StorageTestEnvironment.plain());
        for (String name : TESTS) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, STRUCTURE, 400, 0, true)));
        }
    }

    /**
     * The chest's contents show in the network; a player take and store go straight to the chest and count at once; a
     * change made behind the network's back shows after the next look, and the port's rate does not apply (a take of
     * 100 at a tier-1 port moves them in one go).
     */
    private static void readsThrough(GameTestHelper helper) {
        network(helper);
        ChestBlockEntity chest = chest(helper, new ItemStack(Items.COBBLESTONE, 64),
                new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.DIRT, 10));
        PortBlockEntity port = bus(helper);
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, CONTROLLER);
            helper.assertValueEqual(network.contents().amount(COBBLE), 128L, "indexed cobblestone");
            helper.assertValueEqual(network.contents().amount(DIRT), 10L, "indexed dirt");
            helper.assertValueEqual(port.worker().busStatus(), BusStatus.MOUNTED, "status");
        }).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, CONTROLLER);
            helper.assertValueEqual(extract(network, COBBLE, 100), 100L, "taken");
            helper.assertValueEqual(count(chest, Items.COBBLESTONE), 28, "cobblestone left in the chest");
            helper.assertValueEqual(network.contents().amount(COBBLE), 28L, "indexed after the take");
            helper.assertValueEqual(NetworkGameTests.insert(network, DIRT, 20), 20L, "stored");
            helper.assertValueEqual(count(chest, Items.DIRT), 30, "dirt in the chest");
            helper.assertValueEqual(network.contents().amount(DIRT), 30L, "indexed after the store");
            // Behind the network's back.
            chest.setItem(20, new ItemStack(Items.COBBLESTONE, 7));
        }).thenWaitUntil(
                () -> helper.assertValueEqual(NetworkGameTests.state(helper, CONTROLLER).contents().amount(COBBLE), 35L,
                        "the new cobblestone"))
                .thenSucceed();
    }

    /**
     * A hopper drains the chest while the network takes from it every tick, asking for all it believes the chest holds:
     * at every tick the chest, the hopper and what the network handed out add up to the 64 the chest started with, and
     * the index never claims more than the chest holds once the bus looked again.
     */
    private static void noDupeWithHopper(GameTestHelper helper) {
        network(helper);
        BlockPos busPos = PORT.above();
        BlockPos chestPos = INVENTORY.above();
        ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(new BlockPos(2, 2, 1)),
                CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
        // Locked until the bus has mounted the chest, or it drains the chest before the test starts.
        BlockPos lock = INVENTORY.east();
        helper.setBlock(lock, Blocks.REDSTONE_BLOCK);
        helper.setBlock(INVENTORY, Blocks.HOPPER);
        helper.setBlock(chestPos, Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(chestPos, ChestBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        helper.setBlock(busPos, PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, Direction.EAST)
                .setValue(PortBlock.MODE, PortMode.STORAGE));
        long[] handedOut = new long[1];
        int[] ticks = new int[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.state(helper, CONTROLLER).contents().amount(COBBLE), 64L, "indexed"))
                .thenExecute(() -> helper.setBlock(lock, Blocks.AIR)).thenExecuteFor(120, () -> {
                    NetworkState network = NetworkGameTests.state(helper, CONTROLLER);
                    if (ticks[0]++ % 3 == 0) {
                        // Ask for everything the index shows, stale or not: only what is really there may come out.
                        long shown = network.contents().amount(COBBLE);
                        int before = count(chest, Items.COBBLESTONE);
                        long got = extract(network, COBBLE, Math.min(shown, 4));
                        helper.assertTrue(got <= before, "took " + got + " from a chest holding " + before);
                        handedOut[0] += got;
                    }
                    long total = count(chest, Items.COBBLESTONE) + hopper(helper, INVENTORY) + handedOut[0];
                    helper.assertValueEqual(total, 64L, "cobblestone in chest, hopper and handed out");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.state(helper, CONTROLLER).contents().amount(COBBLE),
                        (long) count(chest, Items.COBBLESTONE), "indexed against the chest"))
                .thenExecute(() -> helper.assertTrue(hopper(helper, INVENTORY) > 0 && handedOut[0] > 0,
                        "both drained: hopper " + hopper(helper, INVENTORY) + ", network " + handedOut[0]))
                .thenSucceed();
    }

    /**
     * With priority 10 the bus takes a new kind before the drive (priority 0); at -10 the drive takes the next new
     * kind. A kind the chest already holds goes there whatever the priority, as for every provider.
     */
    private static void priorityAgainstDrive(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ChestBlockEntity chest = chest(helper);
        PortBlockEntity port = bus(helper);
        port.setPriority(10);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(port.worker().busStatus(), BusStatus.MOUNTED, "mounted"))
                .thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, CONTROLLER);
                    helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 16), 16L, "cobblestone stored");
                    helper.assertValueEqual(count(chest, Items.COBBLESTONE), 16, "cobblestone in the chest");
                    helper.assertValueEqual(drive.provider.amount(COBBLE), 0L, "cobblestone in the drive");
                    port.setPriority(-10);
                    NetworkState again = NetworkGameTests.state(helper, CONTROLLER);
                    helper.assertValueEqual(NetworkGameTests.insert(again, DIRT, 16), 16L, "dirt stored");
                    helper.assertValueEqual(drive.provider.amount(DIRT), 16L, "dirt in the drive");
                    helper.assertValueEqual(count(chest, Items.DIRT), 0, "dirt in the chest");
                    helper.assertValueEqual(again.contents().amount(COBBLE), 16L, "cobblestone still indexed");
                }).thenSucceed();
    }

    /**
     * A filter naming dirt shows only the chest's dirt, stores only dirt there (cobblestone goes to the drive) and
     * takes only dirt from it.
     */
    private static void filter(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ChestBlockEntity chest = chest(helper, new ItemStack(Items.COBBLESTONE, 32), new ItemStack(Items.DIRT, 32));
        PortBlockEntity port = bus(helper);
        port.setPriority(10);
        port.filter().setItem(0, new ItemStack(Items.DIRT));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.state(helper, CONTROLLER).contents().amount(DIRT), 32L, "indexed dirt"))
                .thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, CONTROLLER);
                    helper.assertValueEqual(network.contents().amount(COBBLE), 0L, "unfiltered cobblestone shown");
                    helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 10), 10L, "cobblestone stored");
                    helper.assertValueEqual(drive.provider.amount(COBBLE), 10L, "cobblestone in the drive");
                    helper.assertValueEqual(count(chest, Items.COBBLESTONE), 32, "cobblestone in the chest");
                    helper.assertValueEqual(NetworkGameTests.insert(network, DIRT, 10), 10L, "dirt stored");
                    helper.assertValueEqual(count(chest, Items.DIRT), 42, "dirt in the chest");
                    helper.assertValueEqual(extract(network, COBBLE, 64), 10L, "cobblestone taken");
                    helper.assertValueEqual(count(chest, Items.COBBLESTONE), 32, "cobblestone left in the chest");
                }).thenSucceed();
    }

    /**
     * Read only: the chest shows and gives, but a store goes to the drive. Write only: the chest's contents leave the
     * index, a store goes into the chest and is not shown, and nothing comes out of it.
     */
    private static void readOnlyAndWriteOnly(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ChestBlockEntity chest = chest(helper, new ItemStack(Items.COBBLESTONE, 20));
        PortBlockEntity port = bus(helper);
        port.setPriority(10);
        port.worker().setBusAccess(BusAccess.READ);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.state(helper, CONTROLLER).contents().amount(COBBLE), 20L, "indexed"))
                .thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, CONTROLLER);
                    helper.assertValueEqual(NetworkGameTests.insert(network, SAND, 5), 5L, "sand stored");
                    helper.assertValueEqual(drive.provider.amount(SAND), 5L, "sand in the drive (read only)");
                    helper.assertValueEqual(extract(network, COBBLE, 5), 5L, "cobblestone taken (read only)");
                    helper.assertValueEqual(count(chest, Items.COBBLESTONE), 15, "cobblestone in the chest");
                    port.worker().setBusAccess(BusAccess.WRITE);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.state(helper, CONTROLLER).contents().amount(COBBLE), 0L,
                        "write only shows nothing"))
                .thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, CONTROLLER);
                    helper.assertValueEqual(NetworkGameTests.insert(network, DIRT, 8), 8L, "dirt stored");
                    helper.assertValueEqual(count(chest, Items.DIRT), 8, "dirt in the chest (write only)");
                    helper.assertValueEqual(network.contents().amount(DIRT), 0L, "dirt shown");
                    helper.assertValueEqual(extract(network, COBBLE, 5), 0L, "cobblestone taken (write only)");
                    helper.assertValueEqual(count(chest, Items.COBBLESTONE), 15, "cobblestone in the chest");
                }).thenSucceed();
    }

    /**
     * A bus facing an interface of its own network refuses it: nothing shows and a store goes elsewhere, so the network
     * never stores into itself through the interface.
     */
    private static void refusesOwnInterface(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        helper.setBlock(INVENTORY, InterfaceContent.INTERFACE.get());
        for (int x = 2; x <= 4; x++) {
            ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(new BlockPos(x, 1, 2)),
                    CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
        }
        PortBlockEntity port = bus(helper);
        port.setPriority(10);
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(NetworkGameTests.network(helper, INVENTORY) == NetworkGameTests.state(helper, CONTROLLER),
                    "interface not in the network");
            helper.assertValueEqual(port.worker().busStatus(), BusStatus.LOOP, "status");
        }).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, CONTROLLER);
            helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 10), 10L, "stored");
            helper.assertValueEqual(drive.provider.amount(COBBLE), 10L, "cobblestone in the drive");
            helper.assertValueEqual(network.contents().amount(COBBLE), 10L, "indexed once");
        }).thenSucceed();
    }

    /**
     * Network A's bus mounts network B's interface; B's bus facing A's interface would close a loop and refuses, while
     * A keeps its mount.
     */
    private static void refusesLoopBetweenNetworks(GameTestHelper helper) {
        BlockPos controllerA = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controllerA, POWER);
        BlockPos controllerB = NetworkGameTests.controller(helper, 7);
        NetworkGameTests.power(helper, controllerB, POWER);
        cable(helper, 2, 1);
        cable(helper, 6, 1);
        cable(helper, 5, 1);
        // B's interface at x 4 (linked through x 5), faced by A's bus at x 3.
        helper.setBlock(new BlockPos(4, 1, 1), InterfaceContent.INTERFACE.get());
        PortBlockEntity busA = bus(helper);
        // A's interface at (4, 1, 4), linked through x 2 and (3, 1, 4); B's bus at (5, 1, 4) faces it, linked through x
        // 6.
        for (int z = 2; z <= 4; z++) {
            cable(helper, 2, z);
            cable(helper, 6, z);
        }
        cable(helper, 3, 4);
        helper.setBlock(new BlockPos(4, 1, 4), InterfaceContent.INTERFACE.get());
        helper.setBlock(new BlockPos(5, 1, 4), PortContent.PORT.get().defaultBlockState()
                .setValue(PortBlock.FACING, Direction.WEST).setValue(PortBlock.MODE, PortMode.IMPORT));
        PortBlockEntity busB = helper.getBlockEntity(new BlockPos(5, 1, 4), PortBlockEntity.class);
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState a = NetworkGameTests.state(helper, controllerA);
            NetworkState b = NetworkGameTests.state(helper, controllerB);
            helper.assertTrue(a != b, "one network");
            helper.assertTrue(NetworkGameTests.network(helper, new BlockPos(4, 1, 1)) == b, "B's interface");
            helper.assertTrue(NetworkGameTests.network(helper, new BlockPos(4, 1, 4)) == a, "A's interface");
            helper.assertTrue(busA.network() == a && busB.network() == b, "buses");
            helper.assertValueEqual(busA.worker().busStatus(), BusStatus.MOUNTED, "A's bus");
        }).thenExecute(() -> busB.setMode(PortMode.STORAGE))
                .thenWaitUntil(() -> helper.assertValueEqual(busB.worker().busStatus(), BusStatus.LOOP, "B's bus"))
                .thenIdle(20)
                .thenExecute(() -> helper.assertValueEqual(busA.worker().busStatus(), BusStatus.MOUNTED, "A's bus"))
                .thenSucceed();
    }

    /**
     * The chest's block entity unloads as with its chunk (the hook, then it leaves the chunk; the block state stays):
     * nothing can be taken in that tick, its contents leave the index, and they come back when it loads again. A whole
     * chunk unload makes the bus's cached lookup answer null instead, which shows nothing the same way.
     */
    private static void targetUnloaded(GameTestHelper helper) {
        network(helper);
        ChestBlockEntity chest = chest(helper, new ItemStack(Items.COBBLESTONE, 40));
        bus(helper);
        CompoundTag[] saved = new CompoundTag[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.state(helper, CONTROLLER).contents().amount(COBBLE), 40L, "indexed"))
                .thenExecute(() -> {
                    saved[0] = chest.saveWithFullMetadata(helper.getLevel().registryAccess());
                    chest.onChunkUnloaded();
                    BlockPos abs = helper.absolutePos(INVENTORY);
                    helper.getLevel().getChunkAt(abs).removeBlockEntity(abs);
                    helper.assertValueEqual(extract(NetworkGameTests.state(helper, CONTROLLER), COBBLE, 40), 0L,
                            "taken while unloaded");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.state(helper, CONTROLLER).contents().amount(COBBLE), 0L, "while unloaded"))
                .thenExecute(() -> {
                    BlockPos abs = helper.absolutePos(INVENTORY);
                    BlockEntity loaded = BlockEntity.loadStatic(abs, helper.getLevel().getBlockState(abs), saved[0],
                            helper.getLevel().registryAccess());
                    if (loaded == null) {
                        throw helper.assertionException(Component.literal("the chest did not load"));
                    }
                    helper.getLevel().getChunkAt(abs).addAndRegisterBlockEntity(loaded);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.state(helper, CONTROLLER).contents().amount(COBBLE), 40L, "loaded again"))
                .thenSucceed();
    }

    /**
     * The chest is broken (it drops its 40 items) and the network asks for them in the same tick: nothing comes out,
     * and the index drops them. Then a second chest's bus is broken: the chest keeps its items, nothing comes out, and
     * the index drops them once the bus left.
     */
    private static void targetOrBusBroken(GameTestHelper helper) {
        network(helper);
        chest(helper, new ItemStack(Items.COBBLESTONE, 40));
        bus(helper);
        BlockPos secondBus = new BlockPos(3, 2, 1);
        BlockPos secondChest = new BlockPos(4, 2, 1);
        ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(new BlockPos(2, 2, 1)),
                CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
        helper.setBlock(secondChest, Blocks.CHEST);
        ChestBlockEntity other = helper.getBlockEntity(secondChest, ChestBlockEntity.class);
        other.setItem(0, new ItemStack(Items.DIRT, 30));
        helper.setBlock(secondBus, PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, Direction.EAST)
                .setValue(PortBlock.MODE, PortMode.STORAGE));
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, CONTROLLER);
            helper.assertValueEqual(network.contents().amount(COBBLE), 40L, "indexed cobblestone");
            helper.assertValueEqual(network.contents().amount(DIRT), 30L, "indexed dirt");
        }).thenExecute(() -> {
            helper.getLevel().destroyBlock(helper.absolutePos(INVENTORY), true);
            NetworkState network = NetworkGameTests.state(helper, CONTROLLER);
            helper.assertValueEqual(extract(network, COBBLE, 40), 0L, "taken from the broken chest");
        }).thenWaitUntil(() -> {
            helper.assertValueEqual(NetworkGameTests.state(helper, CONTROLLER).contents().amount(COBBLE), 0L,
                    "indexed after the break");
            helper.assertValueEqual(dropped(helper, Items.COBBLESTONE), 40, "dropped cobblestone");
        }).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, CONTROLLER);
            helper.getLevel().destroyBlock(helper.absolutePos(secondBus), false);
            helper.assertValueEqual(extract(network, DIRT, 30), 0L, "taken through the broken bus");
            helper.assertValueEqual(count(other, Items.DIRT), 30, "dirt in the second chest");
        }).thenWaitUntil(() -> helper.assertValueEqual(
                NetworkGameTests.state(helper, CONTROLLER).contents().amount(DIRT), 0L, "indexed after the bus broke"))
                .thenExecute(() -> helper.assertValueEqual(count(other, Items.DIRT), 30, "dirt in the second chest"))
                .thenSucceed();
    }

    /**
     * The mode, access and priority go into the dropped item and come back when it is placed; the menu's buttons cycle
     * the three modes and the access, and its priority page sets the priority.
     */
    private static void settingsKept(GameTestHelper helper) {
        network(helper);
        chest(helper);
        PortBlockEntity port = bus(helper);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(helper.absoluteVec(PORT.getCenter()));
        PortMenu menu = PortMenu.forPort(1, player.getInventory(), port);
        helper.assertTrue(menu.clickMenuButton(player, PortMenu.CYCLE_ACCESS), "access button");
        helper.assertValueEqual(port.worker().busAccess(), BusAccess.READ, "access after one click");
        helper.assertTrue(menu.applyPriority(player, 42), "priority page");
        helper.assertValueEqual(port.priority(), 42, "priority");
        DataComponentMap components = port.collectComponents();
        helper.assertValueEqual(components.getOrDefault(ModDataComponents.PORT_MODE.get(), PortMode.IMPORT),
                PortMode.STORAGE, "mode in item");
        helper.assertValueEqual(components.getOrDefault(ModDataComponents.PORT_ACCESS.get(), BusAccess.BOTH),
                BusAccess.READ, "access in item");
        helper.assertValueEqual(components.getOrDefault(ModDataComponents.PORT_PRIORITY.get(), 0), 42,
                "priority in item");
        ItemStack item = new ItemStack(PortContent.PORT.item().get());
        item.applyComponents(components);
        BlockPos other = new BlockPos(3, 1, 6);
        BlockPos target = helper.absolutePos(other);
        player.setItemInHand(InteractionHand.MAIN_HAND, item);
        helper.assertTrue(
                item.useOn(new UseOnContext(helper.getLevel(), player, InteractionHand.MAIN_HAND, item,
                        new BlockHitResult(target.getCenter(), Direction.UP, target, false))).consumesAction(),
                "placing");
        PortBlockEntity placed = helper.getBlockEntity(other, PortBlockEntity.class);
        helper.assertValueEqual(placed.mode(), PortMode.STORAGE, "placed mode");
        helper.assertValueEqual(placed.worker().busAccess(), BusAccess.READ, "placed access");
        helper.assertValueEqual(placed.priority(), 42, "placed priority");
        helper.assertTrue(menu.clickMenuButton(player, PortMenu.CYCLE_MODE), "mode button");
        helper.assertValueEqual(port.mode(), PortMode.IMPORT, "storage cycles to import");
        menu.clickMenuButton(player, PortMenu.CYCLE_MODE);
        helper.assertValueEqual(port.mode(), PortMode.EXPORT, "then export");
        menu.clickMenuButton(player, PortMenu.CYCLE_MODE);
        helper.assertValueEqual(port.mode(), PortMode.STORAGE, "then storage");
        helper.succeed();
    }

    /**
     * Stores and takes through the bus in transactions that do not commit: a store and a take in an aborted root, and a
     * nested store and take that commit inside an aborted root. The chest and the network's index stay as they were,
     * and afterwards a committed take and a change behind the network's back count exactly (no move left over from the
     * aborted transactions).
     */
    private static void abortNested(GameTestHelper helper) {
        network(helper);
        ChestBlockEntity chest = chest(helper, new ItemStack(Items.COBBLESTONE, 40));
        PortBlockEntity port = bus(helper);
        port.setPriority(10);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.state(helper, CONTROLLER).contents().amount(COBBLE), 40L, "indexed"))
                .thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, CONTROLLER);
                    try (Transaction root = Transaction.openRoot()) {
                        helper.assertValueEqual(network.insert(DIRT, 10, root), 10L, "store in the aborted root");
                        helper.assertValueEqual(network.extract(COBBLE, 15, root), 15L, "take in the aborted root");
                        helper.assertValueEqual(count(chest, Items.DIRT), 10, "dirt in the chest before the abort");
                    }
                    try (Transaction root = Transaction.openRoot()) {
                        try (Transaction nested = Transaction.open(root)) {
                            helper.assertValueEqual(network.insert(DIRT, 7, nested), 7L, "nested store");
                            helper.assertValueEqual(network.extract(COBBLE, 9, nested), 9L, "nested take");
                            nested.commit();
                        }
                        try (Transaction aborted = Transaction.open(root)) {
                            helper.assertValueEqual(network.extract(COBBLE, 4, aborted), 4L, "aborted nested take");
                        }
                    }
                    helper.assertValueEqual(count(chest, Items.COBBLESTONE), 40, "cobblestone after the aborts");
                    helper.assertValueEqual(count(chest, Items.DIRT), 0, "dirt after the aborts");
                    helper.assertValueEqual(network.contents().amount(COBBLE), 40L, "indexed cobblestone");
                    helper.assertValueEqual(network.contents().amount(DIRT), 0L, "indexed dirt");
                    helper.assertValueEqual(extract(network, COBBLE, 5), 5L, "committed take");
                    helper.assertValueEqual(count(chest, Items.COBBLESTONE), 35, "cobblestone after the take");
                    helper.assertValueEqual(network.contents().amount(COBBLE), 35L, "indexed after the take");
                    helper.assertValueEqual(network.contents().amount(DIRT), 0L, "indexed dirt after the take");
                    chest.setItem(10, new ItemStack(Items.DIRT, 3));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.state(helper, CONTROLLER).contents().amount(DIRT), 3L, "dirt looked at"))
                .thenExecute(() -> helper.assertValueEqual(
                        NetworkGameTests.state(helper, CONTROLLER).contents().amount(COBBLE), 35L,
                        "cobblestone after the look"))
                .thenSucceed();
    }

    /**
     * A bus on a tank: the network sees its water to the mB, takes 1,234 mB and stores 567 mB exactly, and lava, which
     * the full-of-water tank cannot take, goes to the drive.
     */
    private static void fluid(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        helper.setBlock(INVENTORY, BusTestContent.TANK.get());
        BusTestContent.Tank tank = helper.getBlockEntity(INVENTORY, BusTestContent.Tank.class);
        tank.handler.set(0, FluidResource.of(Fluids.WATER), 5_000);
        PortBlockEntity port = bus(helper);
        port.setPriority(10);
        StackKey water = FluidKey.of(Fluids.WATER);
        StackKey lava = FluidKey.of(Fluids.LAVA);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.state(helper, CONTROLLER).contents().amount(water), 5_000L, "indexed water"))
                .thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, CONTROLLER);
                    helper.assertValueEqual(extract(network, water, 1_234), 1_234L, "water taken");
                    helper.assertValueEqual(tank.handler.getAmountAsLong(0), 3_766L, "water in the tank");
                    helper.assertValueEqual(network.contents().amount(water), 3_766L, "indexed after the take");
                    helper.assertValueEqual(NetworkGameTests.insert(network, water, 567), 567L, "water stored");
                    helper.assertValueEqual(tank.handler.getAmountAsLong(0), 4_333L, "water in the tank");
                    helper.assertValueEqual(network.contents().amount(water), 4_333L, "indexed after the store");
                    helper.assertValueEqual(NetworkGameTests.insert(network, lava, 1_000), 1_000L, "lava stored");
                    helper.assertValueEqual(drive.provider.amount(lava), 1_000L, "lava in the drive");
                    helper.assertValueEqual(tank.handler.getAmountAsLong(0), 4_333L, "water in the tank");
                    helper.assertValueEqual(drive.provider.amount(water), 0L, "water in the drive");
                }).thenSucceed();
    }

    // --- Helpers ---------------------------------------------------------------------------------------------------

    private static long extract(NetworkState network, StackKey key, long amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            long moved = network.extract(key, amount, transaction);
            transaction.commit();
            return moved;
        }
    }

    private static void cable(GameTestHelper helper, int x, int z) {
        ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(new BlockPos(x, 1, z)),
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

    private static PortBlockEntity bus(GameTestHelper helper) {
        helper.setBlock(PORT, PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, Direction.EAST)
                .setValue(PortBlock.MODE, PortMode.STORAGE));
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

    private static int count(Container container, Item item) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int hopper(GameTestHelper helper, BlockPos pos) {
        return count(helper.getBlockEntity(pos, HopperBlockEntity.class), Items.COBBLESTONE);
    }

    private static int dropped(GameTestHelper helper, Item item) {
        AABB area = new AABB(helper.absolutePos(INVENTORY)).inflate(3);
        int total = 0;
        for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class, area)) {
            if (entity.getItem().is(item)) {
                total += entity.getItem().getCount();
            }
        }
        return total;
    }
}
