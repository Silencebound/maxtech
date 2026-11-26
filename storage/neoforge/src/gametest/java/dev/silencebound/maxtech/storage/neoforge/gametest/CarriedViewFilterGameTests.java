package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.carried.CarriedSlot;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cell.CellAssembly;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.cell.CellStorage;
import dev.silencebound.maxtech.storage.cell.CellFilter;
import dev.silencebound.maxtech.storage.cell.PortableCellSource;
import dev.silencebound.maxtech.storage.signal.SignalContent;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem;
import dev.silencebound.maxtech.storage.storagechest.StorageChestBlockEntity;
import dev.silencebound.maxtech.storage.storagechest.StorageChestContent;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.storage.terminal.TerminalSource;
import dev.silencebound.maxtech.storage.terminal.filter.CarriedFilters;
import dev.silencebound.maxtech.storage.terminal.filter.CarriedViewFilters;
import dev.silencebound.maxtech.storage.terminal.filter.ViewFilterContent;
import dev.silencebound.maxtech.storage.terminal.filter.ViewFilterItem;
import dev.silencebound.maxtech.storage.terminal.filter.ViewFilterSlots;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.jspecify.annotations.Nullable;

/**
 * View filters on the item terminals and the storage chest (#322): the wireless terminal and the portable cell keep
 * their filter strip in the item, through a reopen and a save and load of the item, and keep entries that no longer
 * load; the storage chest keeps its strip in its saved data and in the item it drops. A filter is never doubled or lost
 * while the terminal item is swapped to the other hand, replaced by a copy or dropped with the menu open.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class CarriedViewFilterGameTests {
    private static final String[] NAMES = {"carried_view_filters_persist", "carried_view_filters_chest_drop",
            "carried_view_filters_no_dupe"};
    /** Menu slot 5 is inventory slot 14: the menu lists the three main rows (9 to 35) before the hotbar. */
    private static final int INVENTORY_SLOT = 14;
    private static final int MENU_SLOT = 5;
    private static final CellFilter COBBLE = new CellFilter(List.of(CellFilter.Entry.of(ItemKey.of(Items.COBBLESTONE))),
            false, false);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(NAMES[0], CarriedViewFilterGameTests::persist);
        register(NAMES[1], CarriedViewFilterGameTests::chestDrop);
        register(NAMES[2], CarriedViewFilterGameTests::noDupe);
    }

    private CarriedViewFilterGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("carried_view_filter"), StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_42x4x27"), 200);
        }
    }

    /** The two item terminals: a wireless terminal bound to the test network, and a portable cell. */
    private enum Kind {
        WIRELESS, CELL;

        ItemStack create(GameTestHelper helper) {
            return this == WIRELESS
                    ? CarriedItemGameTests.charged(
                            CarriedItemGameTests.bound(helper, new ItemStack(SignalContent.WIRELESS_TERMINAL.get())))
                    : new ItemStack(CellContent.PORTABLE_STORAGE_CELL.get());
        }

        @Nullable
        TerminalSource open(ServerPlayer player) {
            return this == WIRELESS
                    ? WirelessTerminalItem.source(player, InteractionHand.MAIN_HAND)
                    : PortableCellSource.open(player, InteractionHand.MAIN_HAND);
        }
    }

    private static ItemStack viewFilter(CellFilter filter) {
        ItemStack stack = new ItemStack(ViewFilterContent.VIEW_FILTER.get());
        stack.set(ViewFilterContent.FILTER.get(), filter);
        return stack;
    }

    private static TerminalMenu menu(GameTestHelper helper, ServerPlayer player, Kind kind) {
        TerminalSource source = kind.open(player);
        if (source == null) {
            throw helper.assertionException(Component.literal(kind + " does not open"));
        }
        TerminalMenu menu = TerminalMenu.server(1, player.getInventory(), source, update -> {
        });
        helper.assertTrue(menu.hasViewFilters(), kind + ": the menu shows the filter strip");
        return menu;
    }

    private static int firstFilter(TerminalMenu menu) {
        return menu.slots.indexOf(menu.viewFilterSlot(0));
    }

    /** Puts a filter in inventory slot 14 and shift-clicks it into the strip. */
    private static void shiftIn(ServerPlayer player, TerminalMenu menu, CellFilter filter) {
        player.getInventory().setItem(INVENTORY_SLOT, viewFilter(filter));
        menu.quickMoveStack(player, MENU_SLOT);
    }

    /**
     * Each item terminal keeps its strip in the item: a filter shift-clicked in and one placed in slot 2 are in the
     * item's component, come back by slot after the menu closed, after a save and load of the item, and when one is
     * taken out the other stays. An entry that does not load is kept through a change of the strip.
     */
    private static void persist(GameTestHelper helper) {
        TestStorage.TestDevice drive = CarriedItemGameTests.network(helper);
        ServerPlayer player = CarriedItemGameTests.researched(helper, "view_filter_persist");
        helper.startSequence().thenWaitUntil(() -> CarriedItemGameTests.ready(helper, drive)).thenExecute(() -> {
            for (Kind kind : Kind.values()) {
                player.setItemInHand(InteractionHand.MAIN_HAND, kind.create(helper));
                TerminalMenu menu = menu(helper, player, kind);
                shiftIn(player, menu, COBBLE);
                helper.assertTrue(player.getInventory().getItem(INVENTORY_SLOT).isEmpty(), kind + ": moved in");
                menu.viewFilterSlot(2).safeInsert(viewFilter(CellFilter.EMPTY));
                CarriedFilters stored = CarriedViewFilters.of(player.getMainHandItem());
                helper.assertValueEqual(stored.entries().size(), 2, kind + ": both filters in the item");
                menu.removed(player);

                ItemStack held = player.getMainHandItem();
                Tag saved = ItemStack.CODEC.encodeStart(CellGameTests.ops(helper), held).getOrThrow();
                ItemStack loaded = ItemStack.CODEC.parse(CellGameTests.ops(helper), saved).getOrThrow();
                player.setItemInHand(InteractionHand.MAIN_HAND, loaded);
                TerminalMenu again = menu(helper, player, kind);
                helper.assertValueEqual(ViewFilterItem.filter(again.viewFilterSlot(0).getItem()), COBBLE,
                        kind + ": the list after a save and load");
                helper.assertTrue(ViewFilterItem.is(again.viewFilterSlot(2).getItem()), kind + ": each in its slot");
                again.quickMoveStack(player, firstFilter(again));
                helper.assertValueEqual(CarriedViewFilters.of(player.getMainHandItem()).entries().size(), 1,
                        kind + ": the other stays in the item");
                helper.assertValueEqual(count(player, again), 2, kind + ": the one taken out is in the inventory");
                again.removed(player);
                player.getInventory().clearContent();
            }
            keptEntries(helper, player);
        }).thenSucceed();
    }

    /** An entry of a removed mod stays in the component through a change of the strip. */
    private static void keptEntries(GameTestHelper helper, ServerPlayer player) {
        ListTag list = new ListTag();
        CompoundTag known = new CompoundTag();
        known.putInt("slot", 1);
        known.put("item", ItemStack.CODEC.encodeStart(CellGameTests.ops(helper), viewFilter(COBBLE)).getOrThrow());
        list.add(known);
        CompoundTag gone = new CompoundTag();
        gone.putInt("slot", 0);
        CompoundTag goneItem = new CompoundTag();
        goneItem.putString("id", "removed_mod:view_lens");
        gone.put("item", goneItem);
        list.add(gone);
        CarriedFilters parsed = CarriedFilters.CODEC.parse(CellGameTests.ops(helper), list).getOrThrow();
        helper.assertValueEqual(parsed.entries().size(), 1, "the readable entry");
        helper.assertValueEqual(parsed.kept().size(), 1, "the kept entry");

        ItemStack cell = new ItemStack(CellContent.PORTABLE_STORAGE_CELL.get());
        CellStorage storage = CellStorage.get(helper.getLevel().getServer());
        helper.assertTrue(CellAssembly.removeChip(cell, storage) != null, "a bare cell gives its chip back");
        cell.set(ViewFilterContent.CARRIED.get(), parsed);
        helper.assertTrue(CellAssembly.removeChip(cell, storage) == null,
                "a cell carrying view filters keeps its chip, so they are not lost with the cell item");
        player.setItemInHand(InteractionHand.MAIN_HAND, cell);
        CarriedViewFilters strip = new CarriedViewFilters(CarriedSlot.hand(player, InteractionHand.MAIN_HAND));
        helper.assertValueEqual(ViewFilterItem.filter(strip.getItem(1)), COBBLE, "the readable entry in slot 1");
        ItemStack taken = strip.removeItem(1, 1);
        helper.assertTrue(ViewFilterItem.is(taken), "taken out");
        CarriedFilters after = CarriedViewFilters.of(cell);
        helper.assertValueEqual(after.entries().size(), 0, "nothing readable left");
        helper.assertValueEqual(after.kept(), parsed.kept(), "the kept entry is still there");
        Tag written = CarriedFilters.CODEC.encodeStart(CellGameTests.ops(helper), after).getOrThrow();
        helper.assertTrue(written.toString().contains("removed_mod:view_lens"), "and written back");
        player.getInventory().clearContent();
    }

    /**
     * The storage chest's strip: a filter survives a save and load, keeps an otherwise empty chest's item from being
     * blank, and goes with the chest's item when it is broken instead of dropping loose.
     */
    private static void chestDrop(GameTestHelper helper) {
        BlockPos pos = new BlockPos(2, 1, 2);
        helper.setBlock(pos, StorageChestContent.STORAGE_CHEST.get());
        StorageChestBlockEntity chest = helper.getBlockEntity(pos, StorageChestBlockEntity.class);
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "view_filter_chest");
        player.setPos(helper.absoluteVec(pos.above().getCenter()));
        TerminalMenu menu = TerminalMenu.server(1, player.getInventory(), chest, update -> {
        });
        helper.assertTrue(menu.hasViewFilters(), "the chest's menu shows the filter strip");
        shiftIn(player, menu, COBBLE);
        helper.assertValueEqual(ViewFilterItem.filter(chest.viewFilters().getItem(0)), COBBLE, "in the strip");
        menu.removed(player);

        CompoundTag saved = chest.saveWithFullMetadata(helper.getLevel().registryAccess());
        if (!(BlockEntity.loadStatic(chest.getBlockPos(), chest.getBlockState(), saved,
                helper.getLevel().registryAccess()) instanceof StorageChestBlockEntity copy)) {
            throw helper.assertionException(Component.literal("the reloaded chest is no chest"));
        }
        helper.assertValueEqual(ViewFilterItem.filter(copy.viewFilters().getItem(0)), COBBLE, "after a reload");

        helper.getLevel().destroyBlock(helper.absolutePos(pos), true);
        helper.assertItemEntityCountIs(ViewFilterContent.VIEW_FILTER.get(), pos, 2, 0);
        ItemStack dropped = ItemStack.EMPTY;
        for (ItemEntity entity : helper.getEntities(EntityType.ITEM, pos, 2)) {
            if (entity.getItem().is(StorageChestContent.STORAGE_CHEST.item().get())) {
                dropped = entity.getItem();
            }
        }
        TypedEntityData<BlockEntityType<?>> data = dropped.get(DataComponents.BLOCK_ENTITY_DATA);
        if (data == null) {
            throw helper.assertionException(Component.literal("the dropped chest carries no data"));
        }
        StorageChestBlockEntity placed = new StorageChestBlockEntity(BlockPos.ZERO,
                StorageChestContent.STORAGE_CHEST.get().defaultBlockState());
        data.loadInto(placed, helper.getLevel().registryAccess());
        ViewFilterSlots strip = placed.viewFilters();
        helper.assertValueEqual(ViewFilterItem.filter(strip.getItem(0)), COBBLE, "the dropped chest keeps the filter");
        helper.succeed();
    }

    /**
     * Every view filter the player has, in the inventory, on the cursor of {@code menu} or in the strip component of
     * one of {@code carriers} (stacks that are no longer in the inventory, such as a dropped one).
     */
    private static int count(ServerPlayer player, TerminalMenu menu, ItemStack... carriers) {
        int count = ViewFilterItem.is(menu.getCarried()) ? 1 : 0;
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            count += ViewFilterItem.is(stack) ? stack.getCount() : 0;
            count += stack.isEmpty() ? 0 : CarriedViewFilters.of(stack).entries().size();
        }
        for (ItemStack carrier : carriers) {
            count += CarriedViewFilters.of(carrier).entries().size();
        }
        return count;
    }

    /** Tries every way out of and into filter slot 0 that the menu offers. */
    private static void tryAll(ServerPlayer player, TerminalMenu menu) {
        int first = firstFilter(menu);
        menu.quickMoveStack(player, first);
        menu.clicked(first, 0, ContainerInput.PICKUP, player);
        menu.clicked(first, 0, ContainerInput.SWAP, player);
        menu.clicked(first, 0, ContainerInput.THROW, player);
        if (!menu.getCarried().isEmpty()) {
            player.getInventory().add(menu.getCarried());
            menu.setCarried(ItemStack.EMPTY);
        }
    }

    /**
     * With a filter in the strip, the terminal swapped to the offhand for a copy of itself, and then dropped: the strip
     * refuses every click and the menu closes, so the player never holds the filter twice and never loses it; swapped
     * back, the filter comes out once.
     */
    private static void noDupe(GameTestHelper helper) {
        TestStorage.TestDevice drive = CarriedItemGameTests.network(helper);
        ServerPlayer player = CarriedItemGameTests.researched(helper, "view_filter_dupe");
        helper.startSequence().thenWaitUntil(() -> CarriedItemGameTests.ready(helper, drive)).thenExecute(() -> {
            for (Kind kind : Kind.values()) {
                ItemStack original = kind.create(helper);
                player.setItemInHand(InteractionHand.MAIN_HAND, original);
                TerminalMenu menu = menu(helper, player, kind);
                // A copy made after opening: the same network or the same cell id, without the filter.
                ItemStack copy = player.getMainHandItem().copy();
                shiftIn(player, menu, COBBLE);
                helper.assertValueEqual(count(player, menu), 1, kind + ": one filter, in the strip");

                // As the swap-hands key does: the same stacks change places.
                player.setItemInHand(InteractionHand.OFF_HAND, player.getMainHandItem());
                player.setItemInHand(InteractionHand.MAIN_HAND, copy);
                helper.assertFalse(menu.stillValid(player), kind + ": the menu closes once swapped");
                tryAll(player, menu);
                player.getInventory().setItem(INVENTORY_SLOT, viewFilter(CellFilter.EMPTY));
                helper.assertFalse(menu.viewFilterSlot(1).mayPlace(viewFilter(CellFilter.EMPTY)),
                        kind + ": nothing goes in once swapped");
                player.getInventory().setItem(INVENTORY_SLOT, ItemStack.EMPTY);
                helper.assertValueEqual(count(player, menu), 1, kind + ": still one filter after the swap");
                helper.assertValueEqual(CarriedViewFilters.of(copy).entries().size(), 0, kind + ": none in the copy");

                player.setItemInHand(InteractionHand.MAIN_HAND, player.getOffhandItem());
                player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                helper.assertTrue(menu.stillValid(player), kind + ": usable again when swapped back");
                ItemStack dropped = player.getInventory().removeFromSelected(true);
                helper.assertFalse(menu.stillValid(player), kind + ": the menu closes once dropped");
                tryAll(player, menu);
                helper.assertValueEqual(count(player, menu, dropped), 1, kind + ": still one filter after the drop");
                helper.assertValueEqual(CarriedViewFilters.of(dropped).entries().size(), 1,
                        kind + ": in the dropped item");
                menu.removed(player);
                player.getInventory().clearContent();
            }
        }).thenSucceed();
    }
}
