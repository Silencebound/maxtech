package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.authlib.GameProfile;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cell.CellFilter;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternTerminalBlock;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternTerminalContent;
import dev.silencebound.maxtech.storage.part.PartType;
import dev.silencebound.maxtech.storage.terminal.crafting.CraftingTerminalBlock;
import dev.silencebound.maxtech.storage.terminal.crafting.CraftingTerminalContent;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import dev.silencebound.maxtech.storage.terminal.TerminalBlock;
import dev.silencebound.maxtech.storage.terminal.TerminalBlockEntity;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import dev.silencebound.maxtech.storage.terminal.TerminalLayout;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.storage.terminal.TerminalSource;
import dev.silencebound.maxtech.storage.terminal.TerminalView;
import dev.silencebound.maxtech.storage.terminal.filter.ViewFilterContent;
import dev.silencebound.maxtech.storage.terminal.filter.ViewFilterItem;
import dev.silencebound.maxtech.storage.terminal.filter.ViewFilterMenu;
import dev.silencebound.maxtech.storage.terminal.filter.ViewFilterSlots;
import dev.silencebound.maxtech.storage.terminal.filter.ViewFilters;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.jspecify.annotations.Nullable;

/**
 * View filters (B09 item 24): what the grid's filter lets through for exact, fuzzy and tag entries, show and hide mode
 * and several filters together; the view filter GUI's entry clicks, buttons and recipe viewer drops on the held filter,
 * which stays in the hand; and a terminal block's and part's filter slots: only view filters go in, shift-clicks move
 * them in and out, they survive a save and drop when the terminal is broken (terminal, crafting and pattern terminal,
 * block and part). Also the terminal menu's relayout for the size button (B09 item 21c).
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class ViewFilterGameTests {
    private static final String[] NAMES = {"view_filter_matching", "view_filter_menu_edits",
            "view_filter_slots_persist_and_drop", "view_filter_crafting_slots_persist_and_drop",
            "view_filter_pattern_slots_persist_and_drop", "terminal_relayout_keeps_slots"};
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey DIRT = ItemKey.of(Items.DIRT);
    private static final StackKey STONE = ItemKey.of(Items.STONE);
    private static final StackKey OAK_LOG = ItemKey.of(Items.OAK_LOG);
    private static final StackKey BIRCH_LOG = ItemKey.of(Items.BIRCH_LOG);
    private static final StackKey PLANKS = ItemKey.of(Items.OAK_PLANKS);
    private static final StackKey SWORD = ItemKey.of(Items.DIAMOND_SWORD);
    private static final StackKey WATER = FluidKey.of(Fluids.WATER);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(NAMES[0], ViewFilterGameTests::matching);
        register(NAMES[1], ViewFilterGameTests::menuEdits);
        register(NAMES[2], ViewFilterGameTests::slotsPersistAndDrop);
        register(NAMES[3], helper -> {
            BlockPos pos = new BlockPos(2, 1, 2);
            helper.setBlock(pos, CraftingTerminalContent.TERMINAL.get().defaultBlockState()
                    .setValue(CraftingTerminalBlock.FACING, Direction.SOUTH));
            persistAndDrop(helper, pos, CraftingTerminalContent.TERMINAL_PART,
                    CraftingTerminalContent.TERMINAL_PART_ITEM.get());
        });
        register(NAMES[4], helper -> {
            BlockPos pos = new BlockPos(2, 1, 2);
            helper.setBlock(pos, PatternTerminalContent.TERMINAL.get().defaultBlockState()
                    .setValue(PatternTerminalBlock.FACING, Direction.SOUTH));
            persistAndDrop(helper, pos, PatternTerminalContent.TERMINAL_PART,
                    PatternTerminalContent.TERMINAL_PART_ITEM.get());
        });
        register(NAMES[5], ViewFilterGameTests::relayoutKeepsSlots);
    }

    private ViewFilterGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("view_filter"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 100);
        }
    }

    private static CellFilter filter(boolean fuzzy, boolean deny, CellFilter.Entry... entries) {
        return new CellFilter(List.of(entries), fuzzy, deny);
    }

    private static boolean shows(@Nullable Predicate<StackKey> filter, StackKey key) {
        return filter == null || filter.test(key);
    }

    private static void check(GameTestHelper helper, @Nullable Predicate<StackKey> filter, StackKey key,
            boolean expected, String what) {
        helper.assertValueEqual(shows(filter, key), expected, what);
    }

    /** Exact, fuzzy and tag entries, show and hide mode, the union of several filters and the view's kinds. */
    private static void matching(GameTestHelper helper) {
        ItemStack worn = new ItemStack(Items.DIAMOND_SWORD);
        worn.setDamageValue(10);
        StackKey wornSword = ItemKey.of(worn);
        TerminalView all = TerminalView.ALL;

        Predicate<StackKey> exact = ViewFilters.of(all, List.of(filter(false, false, CellFilter.Entry.of(SWORD))));
        check(helper, exact, SWORD, true, "exact: the sword itself");
        check(helper, exact, wornSword, false, "exact: not a worn sword");
        check(helper, exact, COBBLE, false, "exact: nothing else");

        Predicate<StackKey> fuzzy = ViewFilters.of(all, List.of(filter(true, false, CellFilter.Entry.of(SWORD))));
        check(helper, fuzzy, wornSword, true, "fuzzy: a worn sword too");
        check(helper, fuzzy, COBBLE, false, "fuzzy: nothing else");

        CellFilter.Entry logs = new CellFilter.Entry(OAK_LOG, Optional.of(Identifier.withDefaultNamespace("logs")));
        Predicate<StackKey> tag = ViewFilters.of(all, List.of(filter(false, false, logs)));
        check(helper, tag, BIRCH_LOG, true, "tag: another log");
        check(helper, tag, PLANKS, false, "tag: not planks");

        Predicate<StackKey> hide = ViewFilters.of(all, List.of(filter(false, true, CellFilter.Entry.of(COBBLE))));
        check(helper, hide, COBBLE, false, "hide: the listed item");
        check(helper, hide, DIRT, true, "hide: everything else");
        check(helper, hide, WATER, true, "hide: fluids too");

        CellFilter cobble = filter(false, false, CellFilter.Entry.of(COBBLE));
        CellFilter dirt = filter(false, false, CellFilter.Entry.of(DIRT));
        Predicate<StackKey> union = ViewFilters.of(all, List.of(cobble, dirt));
        check(helper, union, COBBLE, true, "union: the first filter's item");
        check(helper, union, DIRT, true, "union: the second filter's item");
        check(helper, union, STONE, false, "union: neither filter's");

        Predicate<StackKey> showAndHide = ViewFilters.of(all,
                List.of(cobble, filter(false, true, CellFilter.Entry.of(COBBLE))));
        check(helper, showAndHide, COBBLE, true, "show cobble or hide cobble: cobble shows");
        check(helper, showAndHide, STONE, true, "show cobble or hide cobble: so does the rest");

        helper.assertTrue(ViewFilters.of(all, List.of(CellFilter.EMPTY, CellFilter.EMPTY)) == null,
                "blank filters limit nothing");
        Predicate<StackKey> blankAndCobble = ViewFilters.of(all, List.of(CellFilter.EMPTY, cobble));
        check(helper, blankAndCobble, DIRT, false, "a blank filter beside another adds nothing");

        Predicate<StackKey> fluids = ViewFilters.of(all, List.of(filter(false, false, CellFilter.Entry.of(WATER))));
        check(helper, fluids, WATER, true, "a fluid entry");
        Predicate<StackKey> itemsOnly = ViewFilters.of(TerminalView.ITEMS,
                List.of(filter(false, false, CellFilter.Entry.of(WATER), CellFilter.Entry.of(COBBLE))));
        check(helper, itemsOnly, WATER, false, "the items view hides fluids a filter shows");
        check(helper, itemsOnly, COBBLE, true, "the items view with a filter");
        helper.succeed();
    }

    private static ServerPlayer player(GameTestHelper helper, String name) {
        ServerPlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
        player.getAbilities().instabuild = false;
        player.setPos(helper.absoluteVec(new BlockPos(2, 2, 2).getCenter()));
        return player;
    }

    /**
     * The GUI edits the held filter: a carried item names itself, a bucket its fluid, an empty right click cycles the
     * entry's tags, an empty left click clears; the buttons switch fuzzy and hide; a recipe viewer's drop sets an
     * entry; the held filter's slot takes no clicks.
     */
    private static void menuEdits(GameTestHelper helper) {
        ServerPlayer player = player(helper, "filter");
        player.getInventory().setSelectedSlot(0);
        player.getInventory().setItem(0, new ItemStack(ViewFilterContent.VIEW_FILTER.get()));
        ViewFilterMenu menu = ViewFilterMenu.server(1, player.getInventory());
        player.containerMenu = menu;
        helper.assertTrue(menu.filter().isBlank(), "a new filter is blank");

        menu.setCarried(new ItemStack(Items.OAK_LOG, 3));
        menu.clicked(0, 0, ContainerInput.PICKUP, player);
        menu.setCarried(new ItemStack(Items.WATER_BUCKET));
        menu.clicked(1, 0, ContainerInput.PICKUP, player);
        menu.setCarried(ItemStack.EMPTY);
        helper.assertValueEqual(menu.filter().entries(),
                List.of(CellFilter.Entry.of(OAK_LOG), CellFilter.Entry.of(WATER)), "a log and water");
        helper.assertValueEqual(player.getInventory().getItem(0).getCount(), 1, "the held filter is still one item");

        menu.clicked(0, 1, ContainerInput.PICKUP, player);
        helper.assertTrue(menu.filter().entries().getFirst().tag().isPresent(), "a right click picks a tag");
        menu.clicked(1, 0, ContainerInput.PICKUP, player);
        helper.assertValueEqual(menu.filter().entries().size(), 1, "a left click clears the water");

        menu.clickMenuButton(player, ViewFilterMenu.FUZZY);
        menu.clickMenuButton(player, ViewFilterMenu.DENY);
        helper.assertTrue(menu.filter().fuzzy() && menu.filter().deny(), "fuzzy and hide");
        helper.assertTrue(menu.setEntry(4, COBBLE), "a recipe viewer's drop");
        helper.assertTrue(!menu.setEntry(CellFilter.SIZE, DIRT), "no tenth entry");
        helper.assertValueEqual(menu.filter().entries().size(), 2, "the drop appended cobblestone");

        int held = ViewFilterMenu.FIRST_INVENTORY + 27;
        menu.clicked(held, 0, ContainerInput.PICKUP, player);
        menu.clicked(held, 0, ContainerInput.QUICK_MOVE, player);
        menu.clicked(ViewFilterMenu.FIRST_INVENTORY, 0, ContainerInput.SWAP, player);
        helper.assertTrue(menu.getCarried().isEmpty() && ViewFilterItem.is(player.getInventory().getItem(0)),
                "the held filter stays in the hand");
        helper.assertTrue(menu.stillValid(player), "the menu stays open");

        player.getInventory().setItem(0, ItemStack.EMPTY);
        helper.assertTrue(!menu.stillValid(player), "the menu closes once the filter left the hand");
        helper.succeed();
    }

    private static ItemStack viewFilter(CellFilter filter) {
        ItemStack stack = new ItemStack(ViewFilterContent.VIEW_FILTER.get());
        stack.set(ViewFilterContent.FILTER.get(), filter);
        return stack;
    }

    private static int filters(ViewFilterSlots slots) {
        int count = 0;
        for (int i = 0; i < slots.getContainerSize(); i++) {
            count += ViewFilterItem.is(slots.getItem(i)) ? 1 : 0;
        }
        return count;
    }

    private static ViewFilterSlots slotsOf(GameTestHelper helper, TerminalSource source) {
        if (!(source.viewFilters() instanceof ViewFilterSlots slots)) {
            throw helper.assertionException(Component.literal("the terminal has no view filter slots"));
        }
        return slots;
    }

    /**
     * A terminal block's menu has four filter slots that take only view filters; shift-clicks move a view filter in and
     * back out; the filters survive a save and drop when the block is broken; a terminal part keeps and drops its own.
     */
    private static void slotsPersistAndDrop(GameTestHelper helper) {
        BlockPos pos = new BlockPos(2, 1, 2);
        helper.setBlock(pos,
                TerminalContent.TERMINAL.get().defaultBlockState().setValue(TerminalBlock.FACING, Direction.SOUTH));
        TerminalBlockEntity terminal = helper.getBlockEntity(pos, TerminalBlockEntity.class);
        ViewFilterSlots slots = slotsOf(helper, terminal);
        ServerPlayer player = player(helper, "slots");
        TerminalMenu menu = TerminalMenu.server(1, player.getInventory(), terminal, update -> {
        });
        helper.assertTrue(menu.hasViewFilters(), "the menu shows the filter slots");
        helper.assertTrue(!menu.viewFilterSlot(0).mayPlace(new ItemStack(Items.DIRT)), "no dirt in a filter slot");
        helper.assertTrue(menu.viewFilterSlot(0).mayPlace(viewFilter(CellFilter.EMPTY)), "a view filter goes in");

        CellFilter cobble = filter(false, false, CellFilter.Entry.of(COBBLE));
        // Menu slot 5 is inventory slot 14: the menu lists the three main rows (9 to 35) before the hotbar.
        player.getInventory().setItem(14, viewFilter(cobble));
        menu.quickMoveStack(player, 5);
        helper.assertTrue(player.getInventory().getItem(14).isEmpty(), "shift-click moved the filter out");
        helper.assertValueEqual(filters(slots), 1, "into a filter slot");
        helper.assertValueEqual(ViewFilterItem.filter(slots.getItem(0)), cobble, "with its list");
        int firstFilter = menu.slots.indexOf(menu.viewFilterSlot(0));
        menu.quickMoveStack(player, firstFilter);
        helper.assertValueEqual(filters(slots), 0, "shift-click took it back out");
        int carried = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            carried += ViewFilterItem.is(player.getInventory().getItem(i)) ? 1 : 0;
        }
        helper.assertValueEqual(carried, 1, "into the inventory");
        persistAndDrop(helper, pos, TerminalContent.TERMINAL_PART, TerminalContent.TERMINAL_PART_ITEM.get());
    }

    /**
     * The terminal block at {@code pos} keeps two filters by slot across a save and drops them when broken; a part of
     * {@code partType} on a cable keeps one and drops it when the cable is broken.
     */
    private static void persistAndDrop(GameTestHelper helper, BlockPos pos, PartType partType, Item partItem) {
        BlockEntity entity = helper.getBlockEntity(pos, BlockEntity.class);
        if (!(entity instanceof TerminalSource terminal)) {
            throw helper.assertionException(Component.literal("not a terminal at " + pos));
        }
        ViewFilterSlots slots = slotsOf(helper, terminal);
        CellFilter cobble = filter(false, false, CellFilter.Entry.of(COBBLE));
        slots.setItem(0, viewFilter(cobble));
        slots.setItem(2, viewFilter(CellFilter.EMPTY));
        CompoundTag saved = entity.saveWithFullMetadata(helper.getLevel().registryAccess());
        BlockEntity copy = BlockEntity.loadStatic(entity.getBlockPos(), entity.getBlockState(), saved,
                helper.getLevel().registryAccess());
        if (!(copy instanceof TerminalSource copied)) {
            throw helper.assertionException(Component.literal("the reloaded terminal is no terminal"));
        }
        ViewFilterSlots loaded = slotsOf(helper, copied);
        helper.assertValueEqual(filters(loaded), 2, "both filters after a reload");
        helper.assertValueEqual(ViewFilterItem.filter(loaded.getItem(0)), cobble, "the list after a reload");
        helper.assertTrue(ViewFilterItem.is(loaded.getItem(2)), "each in its slot");

        helper.getLevel().destroyBlock(helper.absolutePos(pos), true);
        helper.assertItemEntityCountIs(ViewFilterContent.VIEW_FILTER.get(), pos, 1.5, 2);
        helper.killAllEntitiesOfClass(ItemEntity.class);

        NetworkGameTests.cables(helper, 6, 6, CableContent.STORAGE_CABLE_TIER_1.type().get());
        CableParts parts = PartGameTests.parts(helper, 6);
        helper.assertTrue(parts.place(Direction.UP, partType, null, new ItemStack(partItem)), "part placed");
        CablePart part = PartGameTests.part(helper, parts, Direction.UP);
        if (!(part instanceof TerminalSource partTerminal)) {
            throw helper.assertionException(Component.literal("not a terminal part"));
        }
        slotsOf(helper, partTerminal).setItem(TerminalLayout.FILTER_SLOTS - 1, viewFilter(cobble));
        helper.getLevel().destroyBlock(helper.absolutePos(NetworkGameTests.at(6)), true);
        helper.assertItemEntityCountIs(ViewFilterContent.VIEW_FILTER.get(), NetworkGameTests.at(6), 1.5, 1);
        helper.succeed();
    }

    /**
     * A size change moves the client's slots and nothing else (B09 item 21c): for each terminal kind, with filter
     * slots, every relayout keeps the slot count, each slot's index, container, container slot and class, and puts the
     * inventory, crafting grid, result and filters where the new layout says.
     */
    @SuppressWarnings("ReferenceEquality") // the same container object, not an equal one
    private static void relayoutKeepsSlots(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        for (int kind = 0; kind < 3; kind++) {
            RegistryFriendlyByteBuf open = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                    helper.getLevel().registryAccess());
            open.writeBlockPos(BlockPos.ZERO);
            open.writeBoolean(true);
            TerminalMenu menu = switch (kind) {
                case 0 -> TerminalMenu.fromNetwork(1, player.getInventory(), open);
                case 1 -> TerminalMenu.fromNetworkCrafting(1, player.getInventory(), open);
                default -> TerminalMenu.fromNetworkPattern(1, player.getInventory(), open);
            };
            boolean panel = kind > 0;
            List<Slot> before = List.copyOf(menu.slots);
            for (int extra : new int[]{5, -2, 20, 0}) {
                TerminalLayout next = new TerminalLayout(extra, panel);
                menu.relayout(next);
                String what = "kind " + kind + ", " + extra + " extra rows: ";
                helper.assertValueEqual(menu.slots.size(), before.size(), what + "slot count");
                for (int i = 0; i < before.size(); i++) {
                    Slot now = menu.slots.get(i);
                    Slot was = before.get(i);
                    helper.assertValueEqual(now.index, i, what + "index of slot " + i);
                    helper.assertTrue(now.container == was.container, what + "container of slot " + i);
                    helper.assertValueEqual(now.getContainerSlot(), was.getContainerSlot(),
                            what + "container slot of slot " + i);
                    helper.assertTrue(now.getClass() == was.getClass(), what + "class of slot " + i);
                }
                helper.assertValueEqual(menu.slots.get(0).y, next.inventoryY(), what + "inventory");
                helper.assertValueEqual(menu.viewFilterSlot(0).x, next.filterX(0), what + "filter x");
                helper.assertValueEqual(menu.viewFilterSlot(3).y, next.filterY(3), what + "filter y");
                if (kind == 1) {
                    helper.assertValueEqual(menu.slots.get(TerminalMenu.GRID_SLOT + 4).y, next.cellY(4),
                            what + "crafting grid");
                    helper.assertValueEqual(menu.slots.get(TerminalMenu.RESULT_SLOT).x, next.resultX(),
                            what + "result");
                }
            }
        }
        helper.succeed();
    }
}
