package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.cell.CellFilter;
import dev.silencebound.maxtech.storage.cell.CellInventory;
import dev.silencebound.maxtech.storage.cell.StorageCellItem;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.drive.CellTransfer;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.drive.DriveMenu;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The drive's cell transfer (M7C item 12): a cell empties into the network or fills from it in bounded batches, every
 * item accounted for at every step, stops cleanly when the other side is full or has nothing more, needs full access,
 * and goes on after its chunk unloads and loads again.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class CellTransferGameTests {
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey STONE = ItemKey.of(Items.STONE);
    private static final StackKey DIRT = ItemKey.of(Items.DIRT);
    /** The controller's whole buffer. */
    private static final long POWER = 10_000;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<String> NAMES = new ArrayList<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        test("cell_transfer_empty_exact", CellTransferGameTests::emptyExact);
        test("cell_transfer_fill_partition", CellTransferGameTests::fillPartition);
        test("cell_transfer_empty_network_full", CellTransferGameTests::emptyNetworkFull);
        test("cell_transfer_fill_cell_full", CellTransferGameTests::fillCellFull);
        test("cell_transfer_access_refused", CellTransferGameTests::accessRefused);
        test("cell_transfer_unload_resume", CellTransferGameTests::unloadResume);
        test("cell_transfer_draining_refuses", CellTransferGameTests::drainingRefuses);
        test("cell_transfer_stop_midway", CellTransferGameTests::stopMidway);
        test("cell_transfer_cell_removed", CellTransferGameTests::cellRemoved);
    }

    private CellTransferGameTests() {
    }

    private static void test(String name, Consumer<GameTestHelper> function) {
        NAMES.add(name);
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(function));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("cell_transfers"), StorageTestEnvironment.plain());
        for (String name : NAMES) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, StorageTests.SMALL, 400, 0, true)));
        }
    }

    /**
     * Three kinds empty from one drive's cell into the other drive's: the first run moves at most one tick's budget,
     * the totals of both cells add up after every tick, and the end leaves the source empty, not draining, and the
     * network's totals as before.
     */
    private static void emptyExact(GameTestHelper helper) {
        Setup setup = setup(helper, 1, 2, List.of());
        long[] firstMove = {-1};
        setup.then(helper, () -> {
            CellInventory source = setup.source(helper);
            CellGameTests.insert(source, COBBLE, 1_000);
            CellGameTests.insert(source, STONE, 500);
            CellGameTests.insert(source, DIRT, 37);
            click(helper, setup, CellTransfer.EMPTY);
        }).thenWaitUntil(() -> {
            long left = total(setup.source(helper));
            assertTotals(helper, setup, 1_537, "while emptying");
            if (firstMove[0] < 0) {
                helper.assertTrue(left < 1_537, "nothing moved yet");
                firstMove[0] = 1_537 - left;
                helper.assertTrue(firstMove[0] <= budget(), "first tick moved " + firstMove[0]);
            }
            helper.assertValueEqual(setup.source.transfer().result(0), CellTransfer.FINISHED, "result");
        }).thenExecute(() -> {
            CellInventory source = setup.source(helper);
            CellInventory target = setup.target(helper);
            helper.assertValueEqual(source.kinds(), 0, "kinds left in the source");
            helper.assertValueEqual(target.amount(COBBLE), 1_000L, "cobblestone moved");
            helper.assertValueEqual(target.amount(STONE), 500L, "stone moved");
            helper.assertValueEqual(target.amount(DIRT), 37L, "dirt moved");
            helper.assertValueEqual(setup.source.transfer().mode(0), CellTransfer.NONE, "mode after the end");
            helper.assertTrue(!source.draining(), "the source still refuses inserts");
            NetworkState network = NetworkGameTests.state(helper, setup.controller);
            helper.assertValueEqual(network.contents().amount(COBBLE), 1_000L, "network cobblestone");
        }).thenSucceed();
    }

    /**
     * A partitioned cell filling from the network takes only the stone its filter names, every unit of it, and ends
     * when the network has no more; the cobblestone stays where it was.
     */
    private static void fillPartition(GameTestHelper helper) {
        CellFilter stone = new CellFilter(List.of(CellFilter.Entry.of(STONE)), false);
        Setup setup = setup(helper, 1, 2, List.of(CellContent.PARTITION_ADDON.get()), stone);
        setup.then(helper, () -> {
            CellInventory other = setup.target(helper);
            CellGameTests.insert(other, COBBLE, 300);
            CellGameTests.insert(other, STONE, 200);
            click(helper, setup, CellTransfer.FILL);
        }).thenWaitUntil(() -> {
            assertTotals(helper, setup, 500, "while filling");
            helper.assertTrue(setup.source.transfer().result(0) != CellTransfer.NONE, "still filling");
        }).thenExecute(() -> {
            helper.assertValueEqual(setup.source.transfer().result(0), CellTransfer.BLOCKED, "result");
            helper.assertValueEqual(setup.source(helper).amount(STONE), 200L, "stone in the partitioned cell");
            helper.assertValueEqual(setup.source(helper).amount(COBBLE), 0L, "cobblestone in the partitioned cell");
            helper.assertValueEqual(setup.target(helper).amount(STONE), 0L, "stone left behind");
            helper.assertValueEqual(setup.target(helper).amount(COBBLE), 300L, "cobblestone left behind");
        }).thenSucceed();
    }

    /**
     * Emptying into a network with too little room stops once a pass moves nothing: the other cell is full, the rest
     * stays in the source, and no unit is lost or made up.
     */
    private static void emptyNetworkFull(GameTestHelper helper) {
        Setup setup = setup(helper, 1, 1, List.of());
        setup.then(helper, () -> {
            CellGameTests.insert(setup.source(helper), COBBLE, 5_000);
            CellGameTests.insert(setup.source(helper), STONE, 100);
            CellGameTests.insert(setup.target(helper), COBBLE, 4_000);
            click(helper, setup, CellTransfer.EMPTY);
        }).thenWaitUntil(() -> {
            assertTotals(helper, setup, 9_100, "while emptying");
            helper.assertTrue(setup.source.transfer().result(0) != CellTransfer.NONE, "still emptying");
        }).thenExecute(() -> {
            CellInventory source = setup.source(helper);
            CellInventory target = setup.target(helper);
            helper.assertValueEqual(setup.source.transfer().result(0), CellTransfer.BLOCKED, "result");
            helper.assertValueEqual(source.amount(COBBLE) + target.amount(COBBLE), 9_000L, "cobblestone");
            helper.assertValueEqual(source.amount(STONE) + target.amount(STONE), 100L, "stone");
            helper.assertTrue(total(source) > 0, "the source is empty although the network was full");
            helper.assertTrue(target.totalBytes() - target.usedBytes() < 8,
                    "room left in the network: " + (target.totalBytes() - target.usedBytes()) + " bytes");
            helper.assertTrue(!source.draining(), "the source still refuses inserts");
        }).thenSucceed();
    }

    /** Filling a cell from a network that holds more than it fits ends when the cell is full, nothing lost. */
    private static void fillCellFull(GameTestHelper helper) {
        Setup setup = setup(helper, 1, 2, List.of());
        setup.then(helper, () -> {
            CellGameTests.insert(setup.target(helper), COBBLE, 9_000);
            click(helper, setup, CellTransfer.FILL);
        }).thenWaitUntil(() -> {
            assertTotals(helper, setup, 9_000, "while filling");
            helper.assertTrue(setup.source.transfer().result(0) != CellTransfer.NONE, "still filling");
        }).thenExecute(() -> {
            CellInventory source = setup.source(helper);
            helper.assertValueEqual(setup.source.transfer().result(0), CellTransfer.FINISHED, "result");
            helper.assertValueEqual(source.usedBytes(), source.totalBytes(), "bytes used in the filled cell");
            helper.assertValueEqual(source.amount(COBBLE) + setup.target(helper).amount(COBBLE), 9_000L, "cobblestone");
        }).thenSucceed();
    }

    /**
     * A player without full access cannot start a transfer through the screen's button, and a slot without a cell
     * starts nothing for the owner either; the owner's same click then moves everything.
     */
    private static void accessRefused(GameTestHelper helper) {
        Setup setup = setup(helper, 1, 1, List.of());
        setup.then(helper, () -> {
            CellGameTests.insert(setup.source(helper), COBBLE, 100);
            ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "cell_transfer_stranger");
            DriveMenu menu = DriveMenu.forDrive(2, stranger.getInventory(), setup.source);
            helper.assertTrue(!menu.clickMenuButton(stranger, DriveMenu.transferButton(0, CellTransfer.EMPTY)),
                    "a stranger started a transfer");
            helper.assertValueEqual(setup.source.transfer().mode(0), CellTransfer.NONE, "mode after the stranger");
            DriveMenu own = DriveMenu.forDrive(3, setup.owner.getInventory(), setup.source);
            helper.assertTrue(!own.clickMenuButton(setup.owner, DriveMenu.transferButton(5, CellTransfer.FILL)),
                    "a transfer started in an empty slot");
            helper.assertValueEqual(setup.source.transfer().mode(5), CellTransfer.NONE, "mode of the empty slot");
        }).thenExecuteAfter(5, () -> {
            helper.assertValueEqual(setup.source(helper).amount(COBBLE), 100L, "cobblestone in the source");
            helper.assertValueEqual(setup.target(helper).amount(COBBLE), 0L, "cobblestone in the other cell");
            // The control: the same click by the owner moves it all.
            click(helper, setup, CellTransfer.EMPTY);
        }).thenWaitUntil(() -> helper.assertValueEqual(setup.target(helper).amount(COBBLE), 100L,
                "cobblestone moved by the owner")).thenSucceed();
    }

    /**
     * The source drive's chunk unloads in the middle of emptying: while it is away nothing moves and nothing is lost,
     * and the drive loaded again from its saved data mounts the cell draining and finishes the transfer.
     */
    private static void unloadResume(GameTestHelper helper) {
        Setup setup = setup(helper, 1, 2, List.of());
        CompoundTag[] saved = new CompoundTag[1];
        long[] away = new long[1];
        DriveBlockEntity[] again = new DriveBlockEntity[1];
        setup.then(helper, () -> {
            CellGameTests.insert(setup.source(helper), COBBLE, 3_000);
            click(helper, setup, CellTransfer.EMPTY);
        }).thenWaitUntil(() -> helper.assertTrue(total(setup.target(helper)) > 0, "nothing moved yet"))
                .thenExecute(() -> {
                    helper.assertTrue(total(setup.target(helper)) < 3_000, "finished before the unload");
                    long id = setup.source(helper).id();
                    away[0] = id;
                    saved[0] = setup.source.saveWithFullMetadata(helper.getLevel().registryAccess());
                    // As a chunk unload: the hook first, then the block entity leaves the chunk (setRemoved).
                    setup.source.onChunkUnloaded();
                    BlockPos abs = helper.absolutePos(setup.sourcePos);
                    helper.getLevel().getChunkAt(abs).removeBlockEntity(abs);
                }).thenExecuteAfter(10, () -> {
                    long inSource = CellGameTests.storage(helper).amount(away[0], COBBLE);
                    long inTarget = setup.target(helper).amount(COBBLE);
                    helper.assertValueEqual(inSource + inTarget, 3_000L, "cobblestone while unloaded");
                    BlockPos abs = helper.absolutePos(setup.sourcePos);
                    BlockEntity loaded = BlockEntity.loadStatic(abs, helper.getLevel().getBlockState(abs), saved[0],
                            helper.getLevel().registryAccess());
                    if (!(loaded instanceof DriveBlockEntity drive)) {
                        throw helper.assertionException(Component.literal("the drive did not load"));
                    }
                    again[0] = drive;
                    helper.getLevel().getChunkAt(abs).addAndRegisterBlockEntity(drive);
                    helper.assertValueEqual(drive.transfer().mode(0), CellTransfer.EMPTY, "mode after loading");
                }).thenWaitUntil(() -> {
                    CellInventory source = CellGameTests.mounted(helper, again[0], 0);
                    // Mounted again: before the next run it must be draining with cobblestone left to move.
                    helper.assertTrue(source.draining(), "loaded cell not draining");
                    helper.assertTrue(source.amount(COBBLE) > 0, "finished before the check");
                }).thenWaitUntil(() -> {
                    CellInventory source = CellGameTests.mounted(helper, again[0], 0);
                    helper.assertValueEqual(source.amount(COBBLE) + setup.target(helper).amount(COBBLE), 3_000L,
                            "cobblestone after loading");
                    helper.assertValueEqual(again[0].transfer().result(0), CellTransfer.FINISHED, "result");
                }).thenExecute(() -> {
                    helper.assertValueEqual(setup.target(helper).amount(COBBLE), 3_000L, "cobblestone moved");
                    helper.assertValueEqual(CellGameTests.mounted(helper, again[0], 0).kinds(), 0, "kinds left");
                }).thenSucceed();
    }

    /**
     * While a cell empties, inserts from outside go to the other cell: a player's insert and a direct one into the
     * draining cell store nothing there, and every unit is in one of the cells.
     */
    private static void drainingRefuses(GameTestHelper helper) {
        Setup setup = setup(helper, 1, 2, List.of());
        setup.then(helper, () -> {
            CellGameTests.insert(setup.source(helper), COBBLE, 3_000);
            click(helper, setup, CellTransfer.EMPTY);
        }).thenWaitUntil(() -> helper.assertTrue(total(setup.target(helper)) > 0, "nothing moved yet"))
                .thenExecute(() -> {
                    helper.assertTrue(setup.source(helper).amount(COBBLE) > 0, "finished too early");
                    NetworkState network = NetworkGameTests.state(helper, setup.controller);
                    helper.assertValueEqual(NetworkGameTests.insert(network, STONE, 100), 100L, "stone stored");
                    helper.assertValueEqual(CellGameTests.insert(setup.source(helper), DIRT, 5), 0L,
                            "dirt into the draining cell");
                    helper.assertValueEqual(setup.source(helper).amount(STONE), 0L, "stone in the draining cell");
                    helper.assertValueEqual(setup.target(helper).amount(STONE), 100L, "stone in the other cell");
                    assertTotals(helper, setup, 3_100, "after the outside inserts");
                }).thenWaitUntil(() -> helper.assertValueEqual(setup.source.transfer().result(0), CellTransfer.FINISHED,
                        "result"))
                .thenExecute(() -> {
                    helper.assertValueEqual(setup.target(helper).amount(COBBLE), 3_000L, "cobblestone moved");
                    helper.assertValueEqual(setup.target(helper).amount(STONE), 100L, "stone");
                }).thenSucceed();
    }

    /**
     * A second click on the running button stops the transfer at once: no mode, no result, the cell takes inserts
     * again, and nothing moves afterwards.
     */
    private static void stopMidway(GameTestHelper helper) {
        Setup setup = setup(helper, 1, 2, List.of());
        long[] atStop = new long[1];
        setup.then(helper, () -> {
            CellGameTests.insert(setup.source(helper), COBBLE, 3_000);
            click(helper, setup, CellTransfer.EMPTY);
        }).thenWaitUntil(() -> helper.assertTrue(total(setup.target(helper)) > 0, "nothing moved yet"))
                .thenExecute(() -> {
                    DriveMenu menu = DriveMenu.forDrive(1, setup.owner.getInventory(), setup.source);
                    helper.assertTrue(
                            menu.clickMenuButton(setup.owner, DriveMenu.transferButton(0, CellTransfer.EMPTY)),
                            "the stop click");
                    helper.assertValueEqual(setup.source.transfer().mode(0), CellTransfer.NONE, "mode after stop");
                    helper.assertValueEqual(setup.source.transfer().result(0), CellTransfer.NONE, "result after stop");
                    helper.assertTrue(!setup.source(helper).draining(), "still draining after stop");
                    atStop[0] = setup.source(helper).amount(COBBLE);
                    helper.assertTrue(atStop[0] > 0, "finished before the stop");
                }).thenExecuteAfter(10, () -> {
                    helper.assertValueEqual(setup.source(helper).amount(COBBLE), atStop[0], "moved after stop");
                    assertTotals(helper, setup, 3_000, "after stop");
                }).thenSucceed();
    }

    /**
     * Taking the cell out through the screen mid-transfer clears its slot's transfer; its contents stay with its id,
     * nothing more moves, and put back it mounts plain, not draining.
     */
    private static void cellRemoved(GameTestHelper helper) {
        Setup setup = setup(helper, 1, 2, List.of());
        long[] id = new long[1];
        long[] atRemoval = new long[1];
        setup.then(helper, () -> {
            CellGameTests.insert(setup.source(helper), COBBLE, 3_000);
            id[0] = setup.source(helper).id();
            click(helper, setup, CellTransfer.EMPTY);
        }).thenWaitUntil(() -> helper.assertTrue(total(setup.target(helper)) > 0, "nothing moved yet"))
                .thenExecute(() -> {
                    DriveMenu menu = DriveMenu.forDrive(1, setup.owner.getInventory(), setup.source);
                    menu.clicked(0, 0, ContainerInput.QUICK_MOVE, setup.owner);
                    helper.assertValueEqual(setup.source.cellCount(), 0, "cells in the drive after taking it out");
                    helper.assertValueEqual(setup.source.transfer().mode(0), CellTransfer.NONE, "mode after removal");
                    atRemoval[0] = CellGameTests.storage(helper).amount(id[0], COBBLE);
                    helper.assertTrue(atRemoval[0] > 0, "finished before the removal");
                    helper.assertValueEqual(atRemoval[0] + setup.target(helper).amount(COBBLE), 3_000L,
                            "cobblestone at the removal");
                }).thenExecuteAfter(10, () -> {
                    helper.assertValueEqual(CellGameTests.storage(helper).amount(id[0], COBBLE), atRemoval[0],
                            "moved after the removal");
                    ItemStack cell = ItemStack.EMPTY;
                    for (ItemStack stack : setup.owner.getInventory().getNonEquipmentItems()) {
                        if (stack.getItem() instanceof StorageCellItem) {
                            cell = stack;
                        }
                    }
                    helper.assertTrue(setup.source.insertCell(cell), "the cell did not go back in");
                }).thenWaitUntil(() -> {
                    CellInventory back = setup.source(helper);
                    helper.assertTrue(!back.draining(), "the cell came back draining");
                    helper.assertValueEqual(back.amount(COBBLE), atRemoval[0], "cobblestone in the cell put back");
                    helper.assertValueEqual(setup.source.transfer().mode(0), CellTransfer.NONE, "mode put back");
                }).thenSucceed();
    }

    /** Most items one task run moves: the configured operations, one network batch each. */
    private static long budget() {
        return (long) StorageServerConfig.CELL_TRANSFER_OPERATIONS.getAsInt()
                * StorageServerConfig.BATCH_ITEMS.getAsInt();
    }

    private static long total(CellInventory cell) {
        return cell.amount(COBBLE) + cell.amount(STONE) + cell.amount(DIRT);
    }

    private static void assertTotals(GameTestHelper helper, Setup setup, long expected, String when) {
        helper.assertValueEqual(total(setup.source(helper)) + total(setup.target(helper)), expected,
                "items in both cells " + when);
    }

    private static void click(GameTestHelper helper, Setup setup, int mode) {
        DriveMenu menu = DriveMenu.forDrive(1, setup.owner.getInventory(), setup.source);
        helper.assertTrue(menu.clickMenuButton(setup.owner, DriveMenu.transferButton(0, mode)), "the owner's click");
        helper.assertValueEqual(setup.source.transfer().mode(0), mode, "mode after the click");
    }

    private static Setup setup(GameTestHelper helper, int sourceModules, int targetModules, List<Item> addons) {
        return setup(helper, sourceModules, targetModules, addons, CellFilter.EMPTY);
    }

    /**
     * A controller with the source drive and the other drive next to it, one cell each (memory module I), both owned by
     * one player; the source's cell gets {@code addons} and {@code filter}.
     */
    private static Setup setup(GameTestHelper helper, int sourceModules, int targetModules, List<Item> addons,
            CellFilter filter) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        BlockPos sourcePos = NetworkGameTests.at(2);
        DriveBlockEntity source = CellGameTests.drive(helper, sourcePos);
        DriveBlockEntity target = CellGameTests.drive(helper, NetworkGameTests.at(3));
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "cell_transfer_owner");
        source.setOwner(OwnerRecord.of(owner));
        target.setOwner(OwnerRecord.of(owner));
        List<Item> memory = new ArrayList<>();
        for (int i = 0; i < sourceModules; i++) {
            memory.add(CellContent.MEMORY_MODULE_1.get());
        }
        source.insertCell(CellPartsGameTests.cell(CellContent.SMALL_STORAGE_CELL.get(), memory, addons, filter));
        target.insertCell(CellGameTests.cell(CellContent.ITEM_TYPE.id(), targetModules));
        return new Setup(controller, sourcePos, source, target, owner);
    }

    private record Setup(BlockPos controller, BlockPos sourcePos, DriveBlockEntity source, DriveBlockEntity target,
            ServerPlayer owner) {
        CellInventory source(GameTestHelper helper) {
            return CellGameTests.mounted(helper, source, 0);
        }

        CellInventory target(GameTestHelper helper) {
            return CellGameTests.mounted(helper, target, 0);
        }

        /** Waits for both cells and the network, powers it, then runs {@code start}. */
        GameTestSequence then(GameTestHelper helper, Runnable start) {
            return helper.startSequence().thenWaitUntil(() -> {
                source(helper);
                target(helper);
                helper.assertTrue(source.network() != null && target.network() != null, "not in a network");
            }).thenExecute(() -> {
                NetworkGameTests.power(helper, controller, POWER);
                start.run();
            });
        }
    }
}
