package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.cell.CellData;
import dev.silencebound.maxtech.storage.cell.CellInventory;
import dev.silencebound.maxtech.storage.cell.StorageCellItem;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.drive.DriveItem;
import dev.silencebound.maxtech.storage.drive.DriveMenu;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.priority.PriorityPayloads;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The drive's priority and screen (owner decision 2026-10-07): inserts go to the cells of the drive with the higher
 * priority, the priority survives saving and the drive item, and the screen's cell slots move cells in and out without
 * dupes.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class DriveGameTests {
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey STONE = ItemKey.of(Items.STONE);
    private static final long POWER = 10_000;
    /** Menu index of the first hotbar slot: after the drive's cells and the 27 main inventory slots. */
    private static final int HOTBAR_0 = DriveBlockEntity.SLOTS + 27;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<String> NAMES = new ArrayList<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        test("drive_priority_orders_inserts", DriveGameTests::priorityOrdersInserts);
        test("drive_priority_saved", DriveGameTests::prioritySaved);
        test("drive_menu_moves_cells", DriveGameTests::menuMovesCells);
        test("drive_menu_broken_drive", DriveGameTests::menuBrokenDrive);
        test("drive_menu_hotbar_swap", DriveGameTests::menuHotbarSwap);
        test("drive_menu_two_open", DriveGameTests::menuTwoOpen);
        test("drive_priority_reindexed_after_load", DriveGameTests::priorityReindexedAfterLoad);
    }

    private DriveGameTests() {
    }

    private static void test(String name, Consumer<GameTestHelper> function) {
        NAMES.add(name);
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(function));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("drives"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, StorageTests.SMALL, 200, 0, true)));
        }
    }

    /**
     * A new kind goes to the cell in the drive with the higher priority (MAX_VALUE over MIN_VALUE, set before the cells
     * went in); lowering that drive below the other sends the next new kind there. The range is the whole int range.
     */
    private static void priorityOrdersInserts(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        DriveBlockEntity low = CellGameTests.drive(helper, NetworkGameTests.at(2));
        DriveBlockEntity high = CellGameTests.drive(helper, NetworkGameTests.at(3));
        low.setPriority(Integer.MIN_VALUE);
        high.setPriority(Integer.MAX_VALUE);
        helper.assertValueEqual(high.priority(), Integer.MAX_VALUE, "full-range priority");
        low.insertCell(CellGameTests.cell(CellContent.ITEM_TYPE.id(), 1));
        high.insertCell(CellGameTests.cell(CellContent.ITEM_TYPE.id(), 1));
        helper.startSequence().thenWaitUntil(() -> {
            CellGameTests.mounted(helper, low, 0);
            CellGameTests.mounted(helper, high, 0);
            helper.assertTrue(low.network() != null && high.network() != null, "not in a network");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, POWER);
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(NetworkGameTests.insert(network, STONE, 10), 10L, "stone stored");
            helper.assertValueEqual(CellGameTests.mounted(helper, high, 0).amount(STONE), 10L, "stone in MAX_VALUE");
            helper.assertValueEqual(CellGameTests.mounted(helper, low, 0).amount(STONE), 0L, "stone in MIN_VALUE");
            high.setPriority(Integer.MIN_VALUE);
            low.setPriority(Integer.MAX_VALUE);
            helper.assertValueEqual(CellGameTests.mounted(helper, low, 0).priority(), Integer.MAX_VALUE,
                    "cell after the change");
            helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 10), 10L, "cobblestone stored");
            helper.assertValueEqual(CellGameTests.mounted(helper, low, 0).amount(COBBLE), 10L,
                    "cobblestone in MAX_VALUE");
            helper.assertValueEqual(CellGameTests.mounted(helper, high, 0).amount(COBBLE), 0L,
                    "cobblestone in MIN_VALUE");
        }).thenSucceed();
    }

    /**
     * The priority survives a save and load of the drive and travels in the drive item, also for a drive with no cells,
     * whose item names it in the tooltip data.
     */
    private static void prioritySaved(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        DriveBlockEntity drive = CellGameTests.drive(helper, pos);
        drive.setPriority(Integer.MIN_VALUE);
        CompoundTag saved = drive.saveWithoutMetadata(helper.getLevel().registryAccess());
        DriveBlockEntity copy = new DriveBlockEntity(drive.getBlockPos(), drive.getBlockState());
        copy.loadWithComponents(
                TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
        helper.assertValueEqual(copy.priority(), Integer.MIN_VALUE, "priority after a reload");
        ItemStack item = drive.toItemStack(helper.getLevel().registryAccess());
        helper.assertTrue(item.get(DataComponents.BLOCK_ENTITY_DATA) != null, "no data on an empty drive's item");
        helper.assertValueEqual(DriveItem.priority(item), Integer.MIN_VALUE, "priority in the item");
        helper.assertValueEqual(DriveItem.cells(item), 0, "cells in the item");
        drive.setPriority(0);
        helper.assertTrue(
                drive.toItemStack(helper.getLevel().registryAccess()).get(DataComponents.BLOCK_ENTITY_DATA) == null,
                "data on a plain drive's item");
        helper.succeed();
    }

    /**
     * Through the screen a shift-clicked cell goes into the drive and is mounted, a shift-click on its slot gives it
     * back with its id, stats and contents, picking it up and putting it down by hand works the same, and a creative
     * clone of a cell slot copies nothing; there is exactly one cell at every step. The screen's priority click sets
     * the drive's priority; a stranger can neither keep the screen open nor click.
     */
    private static void menuMovesCells(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        DriveBlockEntity drive = CellGameTests.drive(helper, pos);
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "drive_menu_owner");
        drive.setOwner(OwnerRecord.of(owner));
        DriveMenu menu = DriveMenu.forDrive(1, owner.getInventory(), drive);
        owner.getInventory().setItem(0, CellGameTests.cell(CellContent.ITEM_TYPE.id(), 1));
        menu.clicked(HOTBAR_0, 0, ContainerInput.QUICK_MOVE, owner);
        helper.assertValueEqual(drive.cellCount(), 1, "cells in the drive after the shift-click");
        assertOneCell(helper, menu, owner, drive, "after the shift-click in");
        long[] id = new long[1];
        helper.startSequence().thenWaitUntil(() -> CellGameTests.mounted(helper, drive, 0)).thenExecute(() -> {
            CellInventory cell = CellGameTests.mounted(helper, drive, 0);
            id[0] = cell.id();
            CellGameTests.insert(cell, COBBLE, 50);

            menu.clicked(0, 0, ContainerInput.QUICK_MOVE, owner);
            helper.assertValueEqual(drive.cellCount(), 0, "cells in the drive after the shift-click out");
            assertOneCell(helper, menu, owner, drive, "after the shift-click out");
            ItemStack back = findCell(owner);
            CellData data = CellGameTests.data(helper, back);
            helper.assertValueEqual(data.id(), id[0], "id of the cell taken out");
            helper.assertTrue(data.usedBytes() > 0, "stats of the cell taken out: " + data);
            helper.assertValueEqual(CellGameTests.storage(helper).amount(id[0], COBBLE), 50L, "contents kept");

            ItemStack held = back.copy();
            owner.getInventory().clearContent();
            menu.setCarried(held);
            menu.clicked(3, 0, ContainerInput.PICKUP, owner);
            helper.assertTrue(menu.getCarried().isEmpty(), "the cell stayed on the cursor");
            helper.assertValueEqual(drive.cellCount(), 1, "cells after putting it down");
            assertOneCell(helper, menu, owner, drive, "after putting it down");

            owner.getAbilities().instabuild = true;
            menu.clicked(3, 2, ContainerInput.CLONE, owner);
            owner.getAbilities().instabuild = false;
            assertOneCell(helper, menu, owner, drive, "after a creative clone");

            menu.clicked(3, 0, ContainerInput.PICKUP, owner);
            helper.assertValueEqual(drive.cellCount(), 0, "cells after picking it up");
            helper.assertValueEqual(CellGameTests.data(helper, menu.getCarried()).id(), id[0], "id on the cursor");
            assertOneCell(helper, menu, owner, drive, "after picking it up");

            helper.assertTrue(menu.applyPriority(owner, Integer.MAX_VALUE), "owner sets the priority");
            helper.assertValueEqual(drive.priority(), Integer.MAX_VALUE, "priority after the click");
            helper.assertValueEqual(menu.priority(), Integer.MAX_VALUE, "priority the menu syncs");
            ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "drive_menu_stranger");
            DriveMenu foreign = DriveMenu.forDrive(2, stranger.getInventory(), drive);
            helper.assertFalse(foreign.stillValid(stranger), "a stranger keeps the screen");
            helper.assertFalse(foreign.applyPriority(stranger, -3), "stranger's click");
            helper.assertValueEqual(drive.priority(), Integer.MAX_VALUE, "priority after the stranger");
            // The packet acts only for the open priority menu with its container id.
            owner.containerMenu = menu;
            helper.assertFalse(PriorityPayloads.apply(owner, 77, 5), "wrong container id");
            helper.assertValueEqual(drive.priority(), Integer.MAX_VALUE, "priority after a wrong id");
            helper.assertTrue(PriorityPayloads.apply(owner, menu.containerId, 5), "right container id");
            helper.assertValueEqual(drive.priority(), 5, "priority after the packet");
            owner.containerMenu = owner.inventoryMenu;
            helper.assertFalse(PriorityPayloads.apply(owner, owner.inventoryMenu.containerId, 9), "non-priority menu");
            helper.assertValueEqual(drive.priority(), 5, "priority after a non-priority menu");
        }).thenSucceed();
    }

    /**
     * A click that reaches the screen after its drive was broken (before the menu closes) changes nothing: the cell
     * that dropped with the drive item cannot also be picked out of the slot, and a cell shift-clicked from the
     * inventory stays there instead of vanishing into the broken drive.
     */
    private static void menuBrokenDrive(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        DriveBlockEntity drive = CellGameTests.drive(helper, pos);
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "drive_broken_user");
        DriveMenu menu = DriveMenu.forDrive(1, player.getInventory(), drive);
        drive.insertCell(CellGameTests.cell(CellContent.ITEM_TYPE.id(), 1));
        player.getInventory().setItem(0, CellGameTests.cell(CellContent.ITEM_TYPE.id(), 1));
        helper.startSequence().thenWaitUntil(() -> CellGameTests.mounted(helper, drive, 0)).thenExecute(() -> {
            helper.getLevel().destroyBlock(helper.absolutePos(pos), true);
            helper.assertValueEqual(DriveItem.cells(droppedDrive(helper, pos)), 1, "cells in the dropped drive");
            menu.clicked(0, 0, ContainerInput.PICKUP, player);
            helper.assertTrue(menu.getCarried().isEmpty(), "picked a cell out of the broken drive");
            menu.clicked(HOTBAR_0, 0, ContainerInput.QUICK_MOVE, player);
            helper.assertTrue(player.getInventory().getItem(0).getItem() instanceof StorageCellItem,
                    "the shift-clicked cell went into the broken drive");
        }).thenSucceed();
    }

    /**
     * The hotbar number keys swap a cell into an empty drive slot and back out with its id; a non-cell item cannot be
     * swapped onto a filled slot. One cell at every step.
     */
    private static void menuHotbarSwap(GameTestHelper helper) {
        DriveBlockEntity drive = CellGameTests.drive(helper, new BlockPos(1, 1, 1));
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "drive_swap_user");
        DriveMenu menu = DriveMenu.forDrive(1, player.getInventory(), drive);
        player.getInventory().setItem(0, CellGameTests.cell(CellContent.ITEM_TYPE.id(), 1));
        player.getInventory().setItem(1, new ItemStack(Items.DIRT, 5));
        menu.clicked(2, 0, ContainerInput.SWAP, player);
        helper.assertValueEqual(drive.cellCount(), 1, "cells after swapping one in");
        helper.assertTrue(player.getInventory().getItem(0).isEmpty(), "the swapped cell stayed in the hotbar");
        assertOneCell(helper, menu, player, drive, "after swapping in");
        helper.startSequence().thenWaitUntil(() -> CellGameTests.mounted(helper, drive, 2)).thenExecute(() -> {
            long id = CellGameTests.mounted(helper, drive, 2).id();
            menu.clicked(2, 1, ContainerInput.SWAP, player);
            helper.assertValueEqual(drive.cellCount(), 1, "cells after swapping dirt onto the cell");
            helper.assertValueEqual(player.getInventory().getItem(1).getCount(), 5, "dirt after the refused swap");
            assertOneCell(helper, menu, player, drive, "after the refused swap");
            menu.clicked(2, 0, ContainerInput.SWAP, player);
            helper.assertValueEqual(drive.cellCount(), 0, "cells after swapping it out");
            helper.assertValueEqual(CellGameTests.data(helper, player.getInventory().getItem(0)).id(), id,
                    "id of the swapped-out cell");
            assertOneCell(helper, menu, player, drive, "after swapping out");
        }).thenSucceed();
    }

    /**
     * Two players with the screen open on one drive see the same slots: a cell one puts in, the other can take, after
     * which the first finds the slot empty; there is exactly one cell between both players and the drive throughout.
     */
    private static void menuTwoOpen(GameTestHelper helper) {
        DriveBlockEntity drive = CellGameTests.drive(helper, new BlockPos(1, 1, 1));
        ServerPlayer first = NetworkGameTests.player(helper.getLevel(), "drive_two_first");
        ServerPlayer second = NetworkGameTests.player(helper.getLevel(), "drive_two_second");
        DriveMenu a = DriveMenu.forDrive(1, first.getInventory(), drive);
        DriveMenu b = DriveMenu.forDrive(2, second.getInventory(), drive);
        first.getInventory().setItem(0, CellGameTests.cell(CellContent.ITEM_TYPE.id(), 1));
        a.clicked(HOTBAR_0, 0, ContainerInput.QUICK_MOVE, first);
        helper.assertTrue(b.getSlot(0).getItem().getItem() instanceof StorageCellItem,
                "the second screen sees no cell");
        helper.startSequence().thenWaitUntil(() -> CellGameTests.mounted(helper, drive, 0)).thenExecute(() -> {
            b.clicked(0, 0, ContainerInput.PICKUP, second);
            a.clicked(0, 0, ContainerInput.PICKUP, first);
            helper.assertTrue(b.getCarried().getItem() instanceof StorageCellItem, "the second player holds no cell");
            helper.assertTrue(a.getCarried().isEmpty(), "the first player took the cell too");
            assertCells(helper, drive, 1, "after both picked", a, first, b, second);
            b.clicked(5, 0, ContainerInput.PICKUP, second);
            helper.assertTrue(a.getSlot(5).getItem().getItem() instanceof StorageCellItem,
                    "the first screen sees no cell");
            a.clicked(5, 0, ContainerInput.QUICK_MOVE, first);
            b.clicked(5, 0, ContainerInput.QUICK_MOVE, second);
            helper.assertValueEqual(drive.cellCount(), 0, "cells after the first shift-clicked it out");
            assertCells(helper, drive, 1, "after both shift-clicked", a, first, b, second);
        }).thenSucceed();
    }

    /**
     * A drive broken and placed again from its item (its saved data loaded into a drive already in the network) mounts
     * its cells at its saved priority and the network re-sorts: a new kind goes to it at 5, not to the drive at 2.
     */
    private static void priorityReindexedAfterLoad(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        BlockPos highPos = NetworkGameTests.at(3);
        DriveBlockEntity low = CellGameTests.drive(helper, NetworkGameTests.at(2));
        DriveBlockEntity high = CellGameTests.drive(helper, highPos);
        low.setPriority(2);
        high.setPriority(5);
        low.insertCell(CellGameTests.cell(CellContent.ITEM_TYPE.id(), 1));
        high.insertCell(CellGameTests.cell(CellContent.ITEM_TYPE.id(), 1));
        DriveBlockEntity[] placed = new DriveBlockEntity[1];
        helper.startSequence().thenWaitUntil(() -> {
            CellGameTests.mounted(helper, low, 0);
            CellGameTests.mounted(helper, high, 0);
        }).thenExecute(() -> {
            helper.getLevel().destroyBlock(helper.absolutePos(highPos), true);
            ItemStack item = droppedDrive(helper, highPos);
            TypedEntityData<BlockEntityType<?>> data = item.get(DataComponents.BLOCK_ENTITY_DATA);
            if (data == null) {
                throw helper.assertionException(Component.literal("no block entity data on the drive item"));
            }
            DriveBlockEntity again = CellGameTests.drive(helper, highPos);
            placed[0] = again;
            data.loadInto(again, helper.getLevel().registryAccess());
        }).thenWaitUntil(() -> {
            CellGameTests.mounted(helper, placed[0], 0);
            helper.assertTrue(placed[0].network() != null && low.network() != null, "not in a network");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, POWER);
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(placed[0].priority(), 5, "priority after placing again");
            helper.assertValueEqual(NetworkGameTests.insert(network, STONE, 10), 10L, "stone stored");
            helper.assertValueEqual(CellGameTests.mounted(helper, placed[0], 0).amount(STONE), 10L,
                    "stone in the placed drive at 5");
            helper.assertValueEqual(CellGameTests.mounted(helper, low, 0).amount(STONE), 0L, "stone in the drive at 2");
        }).thenSucceed();
    }

    /** The drive item that dropped at {@code pos}; every drop there is discarded. */
    private static ItemStack droppedDrive(GameTestHelper helper, BlockPos pos) {
        ItemStack item = ItemStack.EMPTY;
        for (ItemEntity drop : helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new AABB(helper.absolutePos(pos)).inflate(2))) {
            if (drop.getItem().getItem() instanceof DriveItem) {
                item = drop.getItem().copy();
            }
            drop.discard();
        }
        return item;
    }

    /** Fails unless the drive, both cursors and both inventories hold {@code expected} storage cells between them. */
    private static void assertCells(GameTestHelper helper, DriveBlockEntity drive, int expected, String when,
            DriveMenu a, ServerPlayer first, DriveMenu b, ServerPlayer second) {
        int count = drive.cellCount() + cells(a.getCarried()) + cells(b.getCarried());
        for (ItemStack stack : first.getInventory().getNonEquipmentItems()) {
            count += cells(stack);
        }
        for (ItemStack stack : second.getInventory().getNonEquipmentItems()) {
            count += cells(stack);
        }
        helper.assertValueEqual(count, expected, "cells " + when);
    }

    private static int cells(ItemStack stack) {
        return stack.getItem() instanceof StorageCellItem ? stack.getCount() : 0;
    }

    /** Fails unless the drive, the cursor and the player's inventory hold exactly one storage cell between them. */
    private static void assertOneCell(GameTestHelper helper, DriveMenu menu, ServerPlayer player,
            DriveBlockEntity drive, String when) {
        int count = drive.cellCount() + (menu.getCarried().getItem() instanceof StorageCellItem ? 1 : 0);
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.getItem() instanceof StorageCellItem) {
                count += stack.getCount();
            }
        }
        helper.assertValueEqual(count, 1, "cells " + when);
    }

    private static ItemStack findCell(ServerPlayer player) {
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.getItem() instanceof StorageCellItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }
}
