package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.authlib.GameProfile;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.gui.transfer.CraftingGridMenus;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.SecuritySettings;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkState;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.world.item.ItemStackTemplate;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.terminal.TerminalAction;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.storage.terminal.crafting.CraftingPanel;
import dev.silencebound.maxtech.storage.terminal.crafting.CraftingTerminal;
import dev.silencebound.maxtech.storage.terminal.crafting.CraftingTerminalBlock;
import dev.silencebound.maxtech.storage.terminal.crafting.CraftingTerminalBlockEntity;
import dev.silencebound.maxtech.storage.terminal.crafting.CraftingTerminalContent;
import dev.silencebound.maxtech.storage.terminal.crafting.TerminalGrid;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The crafting terminal (B09 item 13): taking the result crafts one and refills the grid from the network, shift-click
 * crafts many with refills and stops at its cap or when the network runs out, remainders go to the network, a grid the
 * network cannot fill crafts nothing and the clear button returns it, the grid survives a save and drops when the block
 * or the cable with the part is broken, and a recipe viewer's payload fills the grid on the server. Items are counted
 * across grid, network, inventory and cursor at every step. Layout as in the terminal tests: controller and two test
 * drives along x at y = z = 1, the terminal on the second drive's south side.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class CraftingTerminalGameTests {
    private static final String[] NAMES = {"crafting_terminal_craft_and_refill", "crafting_terminal_shift_craft",
            "crafting_terminal_remainders_to_network", "crafting_terminal_missing_and_clear",
            "crafting_terminal_drops_grid", "crafting_terminal_viewer_transfer",
            "crafting_terminal_remainder_network_full", "crafting_terminal_remainder_without_deposit",
            "crafting_terminal_worn_tool_stays", "crafting_terminal_two_menus",
            "crafting_terminal_shift_keeps_remainder_room"};
    private static final StackKey PLANKS = ItemKey.of(Items.OAK_PLANKS);
    private static final int TERMINAL_X = 3;
    private static final RegistryHelper.Registrar<Item> ITEMS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.ITEM);
    /**
     * A tool that wears down by one per craft (its remainder is itself, damaged) and records the crafting player it
     * saw; the gametest data's {@code maxtech_test:worn_tool_sticks} makes two sticks from it and an oak log.
     */
    private static final RegistryEntry<Item, WornTool> WORN_TOOL = ITEMS.register("test_worn_tool", id -> new WornTool(
            new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id)).durability(8).setNoCombineRepair()));

    static {
        register(NAMES[6], CraftingTerminalGameTests::remainderNetworkFull);
        register(NAMES[7], CraftingTerminalGameTests::remainderWithoutDeposit);
        register(NAMES[8], CraftingTerminalGameTests::wornToolStays);
        register(NAMES[9], CraftingTerminalGameTests::twoMenus);
        register(NAMES[10], CraftingTerminalGameTests::shiftCraftKeepsRemainderRoom);
    }

    /** See {@link #WORN_TOOL}. */
    static final class WornTool extends Item {
        /** The crafting player the last remainder was asked with; only the worn tool test reads it. */
        static volatile @Nullable Player lastCrafter;

        WornTool(Properties properties) {
            super(properties);
        }

        @Override
        public @Nullable ItemStackTemplate getCraftingRemainder(ItemInstance instance) {
            lastCrafter = CommonHooks.getCraftingPlayer();
            if (!(instance instanceof ItemStack stack)) {
                return null;
            }
            ItemStack worn = stack.copyWithCount(1);
            worn.setDamageValue(worn.getDamageValue() + 1);
            return worn.getDamageValue() >= worn.getMaxDamage() ? null : ItemStackTemplate.fromNonEmptyStack(worn);
        }
    }

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(NAMES[0], CraftingTerminalGameTests::craftAndRefill);
        register(NAMES[1], CraftingTerminalGameTests::shiftCraft);
        register(NAMES[2], CraftingTerminalGameTests::remaindersToNetwork);
        register(NAMES[3], CraftingTerminalGameTests::missingAndClear);
        register(NAMES[4], CraftingTerminalGameTests::dropsGrid);
        register(NAMES[5], CraftingTerminalGameTests::viewerTransfer);
    }

    private CraftingTerminalGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("crafting_terminal"), StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 300);
        }
    }

    private static BlockPos terminalPos() {
        return NetworkGameTests.at(TERMINAL_X).south();
    }

    /** A powered controller, two test drives and a crafting terminal facing away from the second one. */
    private static CraftingTerminalBlockEntity network(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 100_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        NetworkGameTests.device(helper, NetworkGameTests.at(TERMINAL_X));
        helper.setBlock(terminalPos(), CraftingTerminalContent.TERMINAL.get().defaultBlockState()
                .setValue(CraftingTerminalBlock.FACING, Direction.SOUTH));
        return helper.getBlockEntity(terminalPos(), CraftingTerminalBlockEntity.class);
    }

    private static NetworkState net(GameTestHelper helper, CraftingTerminal terminal) {
        NetworkState network = terminal.network();
        if (network == null) {
            throw helper.assertionException(Component.literal("the crafting terminal has no network"));
        }
        return network;
    }

    /**
     * A survival player by the terminal with a connection that drops every packet: taking a crafting result awards its
     * recipe, which a connectionless player cannot.
     */
    private static ServerPlayer player(GameTestHelper helper, String name) {
        ServerPlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
        player.getAbilities().instabuild = false;
        player.setPos(helper.absoluteVec(NetworkGameTests.at(TERMINAL_X).above().getCenter()));
        return player;
    }

    private static TerminalMenu menu(CraftingTerminal terminal, ServerPlayer player) {
        return TerminalMenu.server(1, player.getInventory(), terminal, update -> {
        });
    }

    private static CraftingPanel panel(GameTestHelper helper, TerminalMenu menu) {
        CraftingPanel panel = menu.craftingPanel();
        if (panel == null) {
            throw helper.assertionException(Component.literal("the menu has no crafting panel"));
        }
        return panel;
    }

    /** Nine cells taking {@code items} in turn where {@code layout} has a '#' (the last repeats), row by row. */
    private static List<List<ItemStack>> cells(String layout, Item... items) {
        List<List<ItemStack>> cells = new ArrayList<>();
        int next = 0;
        for (int i = 0; i < 9; i++) {
            if (layout.charAt(i) == '#') {
                Item item = items[Math.min(next, items.length - 1)];
                next++;
                cells.add(List.of(new ItemStack(item)));
            } else {
                cells.add(List.of());
            }
        }
        return cells;
    }

    /** The crafting table: planks in a 2 x 2 block at the top left. */
    private static List<List<ItemStack>> table() {
        return cells("##.##....", Items.OAK_PLANKS);
    }

    private static int inGrid(TerminalGrid grid, Item item) {
        int total = 0;
        for (int cell = 0; cell < TerminalGrid.SIZE; cell++) {
            ItemStack stack = grid.getItem(cell);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int carried(ServerPlayer player, Item item) {
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static long stored(NetworkState network, Item item) {
        return network.contents().amount(ItemKey.of(item));
    }

    /**
     * Taking the result crafts one table and refills the four emptied cells from the network; a second take empties the
     * network and the grid stays empty, so a third take makes nothing. Planks are conserved at every step.
     */
    private static void craftAndRefill(GameTestHelper helper) {
        CraftingTerminalBlockEntity terminal = network(helper);
        ServerPlayer player = player(helper, "craft_refill");
        helper.startSequence().thenWaitUntil(() -> net(helper, terminal)).thenExecute(() -> {
            NetworkState network = net(helper, terminal);
            NetworkGameTests.insert(network, PLANKS, 8);
            TerminalMenu menu = menu(terminal, player);
            CraftingPanel panel = panel(helper, menu);
            menu.receiveRecipe(player, table(), false);
            helper.assertValueEqual(inGrid(terminal.grid(), Items.OAK_PLANKS), 4, "the grid holds four planks");
            helper.assertValueEqual(stored(network, Items.OAK_PLANKS), 4L, "taken from the network");
            helper.assertTrue(panel.result().is(Items.CRAFTING_TABLE), "the grid crafts a table");

            menu.clicked(TerminalMenu.RESULT_SLOT, 0, ContainerInput.PICKUP, player);
            helper.assertTrue(menu.getCarried().is(Items.CRAFTING_TABLE) && menu.getCarried().getCount() == 1,
                    "a table on the cursor");
            helper.assertValueEqual(inGrid(terminal.grid(), Items.OAK_PLANKS), 4, "the grid was refilled");
            helper.assertValueEqual(stored(network, Items.OAK_PLANKS), 0L, "from the network");
            helper.assertTrue(panel.result().is(Items.CRAFTING_TABLE), "the refilled grid crafts again");

            menu.clicked(TerminalMenu.RESULT_SLOT, 0, ContainerInput.PICKUP, player);
            helper.assertValueEqual(menu.getCarried().getCount(), 2, "two tables on the cursor");
            helper.assertValueEqual(inGrid(terminal.grid(), Items.OAK_PLANKS), 0, "nothing left to refill");
            helper.assertTrue(panel.result().isEmpty(), "no result from an empty grid");
            menu.clicked(TerminalMenu.RESULT_SLOT, 0, ContainerInput.PICKUP, player);
            helper.assertValueEqual(menu.getCarried().getCount(), 2, "an empty result makes nothing");
            long planks = stored(network, Items.OAK_PLANKS) + inGrid(terminal.grid(), Items.OAK_PLANKS)
                    + carried(player, Items.OAK_PLANKS) + 4L * menu.getCarried().getCount();
            helper.assertValueEqual(planks, 8L, "planks conserved");
            menu.removed(player);
        }).thenSucceed();
    }

    /**
     * Shift-click on sticks crafts {@link CraftingPanel#MAX_SHIFT_CRAFTS} times, refilling from the network, then stops
     * with planks left; on tables it crafts until the network runs out. Everything lands in the inventory.
     */
    private static void shiftCraft(GameTestHelper helper) {
        CraftingTerminalBlockEntity terminal = network(helper);
        ServerPlayer player = player(helper, "shift_craft");
        helper.startSequence().thenWaitUntil(() -> net(helper, terminal)).thenExecute(() -> {
            NetworkState network = net(helper, terminal);
            NetworkGameTests.insert(network, PLANKS, 200);
            TerminalMenu menu = menu(terminal, player);
            menu.receiveRecipe(player, cells("#..#.....", Items.OAK_PLANKS), false);
            helper.assertTrue(panel(helper, menu).result().is(Items.STICK), "the grid crafts sticks");
            menu.clicked(TerminalMenu.RESULT_SLOT, 0, ContainerInput.QUICK_MOVE, player);
            int crafts = CraftingPanel.MAX_SHIFT_CRAFTS;
            helper.assertValueEqual(carried(player, Items.STICK), 4 * crafts, "sticks of the capped crafts");
            helper.assertValueEqual(inGrid(terminal.grid(), Items.OAK_PLANKS), 2, "the grid stays filled");
            helper.assertValueEqual(stored(network, Items.OAK_PLANKS) + inGrid(terminal.grid(), Items.OAK_PLANKS),
                    200L - 2L * crafts, "two planks a craft");
            helper.assertTrue(menu.getCarried().isEmpty(), "nothing on the cursor");

            // Tables: 72 planks make 18, and then the network has none to refill with.
            menu.receiveRecipe(player, table(), false);
            menu.clicked(TerminalMenu.RESULT_SLOT, 0, ContainerInput.QUICK_MOVE, player);
            helper.assertValueEqual(carried(player, Items.CRAFTING_TABLE), 18, "tables until the planks ran out");
            helper.assertValueEqual(stored(network, Items.OAK_PLANKS), 0L, "the network is empty");
            helper.assertValueEqual(inGrid(terminal.grid(), Items.OAK_PLANKS), 0, "so is the grid");
            menu.removed(player);
        }).thenSucceed();
    }

    /** A cake's three milk buckets leave three empty buckets, which go to the network, not the grid. */
    private static void remaindersToNetwork(GameTestHelper helper) {
        CraftingTerminalBlockEntity terminal = network(helper);
        ServerPlayer player = player(helper, "remainders");
        helper.startSequence().thenWaitUntil(() -> net(helper, terminal)).thenExecute(() -> {
            NetworkState network = net(helper, terminal);
            NetworkGameTests.insert(network, ItemKey.of(Items.MILK_BUCKET), 3);
            NetworkGameTests.insert(network, ItemKey.of(Items.SUGAR), 2);
            NetworkGameTests.insert(network, ItemKey.of(Items.EGG), 1);
            NetworkGameTests.insert(network, ItemKey.of(Items.WHEAT), 3);
            TerminalMenu menu = menu(terminal, player);
            menu.receiveRecipe(player, cells("#########", Items.MILK_BUCKET, Items.MILK_BUCKET, Items.MILK_BUCKET,
                    Items.SUGAR, Items.EGG, Items.SUGAR, Items.WHEAT), false);
            helper.assertTrue(panel(helper, menu).result().is(Items.CAKE), "the grid crafts a cake");
            menu.clicked(TerminalMenu.RESULT_SLOT, 0, ContainerInput.PICKUP, player);
            helper.assertTrue(menu.getCarried().is(Items.CAKE), "a cake on the cursor");
            helper.assertValueEqual(stored(network, Items.BUCKET), 3L, "the empty buckets went to the network");
            helper.assertTrue(terminal.grid().isEmpty(), "nothing stays in the grid");
            helper.assertValueEqual(carried(player, Items.BUCKET), 0, "none in the inventory");
            menu.removed(player);
        }).thenSucceed();
    }

    /**
     * With three planks in the network the table's grid stays one short and crafts nothing; the request button has
     * nothing it can order (no pattern), and the clear button returns the grid to the network.
     */
    private static void missingAndClear(GameTestHelper helper) {
        CraftingTerminalBlockEntity terminal = network(helper);
        ServerPlayer player = player(helper, "missing");
        helper.startSequence().thenWaitUntil(() -> net(helper, terminal)).thenExecute(() -> {
            NetworkState network = net(helper, terminal);
            NetworkGameTests.insert(network, PLANKS, 3);
            TerminalMenu menu = menu(terminal, player);
            menu.receiveRecipe(player, table(), false);
            helper.assertValueEqual(inGrid(terminal.grid(), Items.OAK_PLANKS), 3, "three cells filled");
            helper.assertTrue(panel(helper, menu).result().isEmpty(), "no result one plank short");
            menu.clicked(TerminalMenu.RESULT_SLOT, 0, ContainerInput.PICKUP, player);
            menu.clicked(TerminalMenu.RESULT_SLOT, 0, ContainerInput.QUICK_MOVE, player);
            helper.assertTrue(menu.getCarried().isEmpty(), "nothing taken");
            helper.assertValueEqual(carried(player, Items.CRAFTING_TABLE), 0, "nothing crafted");
            helper.assertValueEqual(inGrid(terminal.grid(), Items.OAK_PLANKS), 3, "the grid is untouched");
            helper.assertTrue(!TerminalGameTests.click(menu, player, TerminalAction.Kind.GRID_REQUEST, -1),
                    "no pattern makes planks");

            helper.assertTrue(TerminalGameTests.click(menu, player, TerminalAction.Kind.GRID_CLEAR, -1), "cleared");
            helper.assertTrue(terminal.grid().isEmpty(), "the grid is empty");
            helper.assertValueEqual(stored(network, Items.OAK_PLANKS), 3L, "back in the network");
            menu.removed(player);
        }).thenSucceed();
    }

    /**
     * The block keeps its grid across a save and drops it when broken; a crafting terminal part keeps its grid in the
     * cable's save and drops it when the cable is broken.
     */
    private static void dropsGrid(GameTestHelper helper) {
        CraftingTerminalBlockEntity terminal = network(helper);
        ServerPlayer player = player(helper, "drops");
        NetworkGameTests.cables(helper, 6, 6, CableContent.STORAGE_CABLE_TIER_1.type().get());
        helper.startSequence().thenWaitUntil(() -> net(helper, terminal)).thenExecute(() -> {
            NetworkState network = net(helper, terminal);
            NetworkGameTests.insert(network, PLANKS, 4);
            TerminalMenu menu = menu(terminal, player);
            menu.receiveRecipe(player, table(), false);
            menu.removed(player);
            helper.assertValueEqual(inGrid(terminal.grid(), Items.OAK_PLANKS), 4, "the grid holds four planks");

            CompoundTag saved = terminal.saveWithoutMetadata(helper.getLevel().registryAccess());
            CraftingTerminalBlockEntity copy = new CraftingTerminalBlockEntity(terminal.getBlockPos(),
                    terminal.getBlockState());
            copy.loadWithComponents(
                    TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
            helper.assertValueEqual(inGrid(copy.grid(), Items.OAK_PLANKS), 4, "the grid after a reload");

            helper.getLevel().destroyBlock(helper.absolutePos(terminalPos()), true);
            helper.assertItemEntityCountIs(Items.OAK_PLANKS, terminalPos(), 1.5, 4);
            helper.assertItemEntityCountIs(CraftingTerminalContent.TERMINAL.item().get(), terminalPos(), 1.5, 1);
            helper.killAllEntitiesOfClass(ItemEntity.class);

            CableParts parts = PartGameTests.parts(helper, 6);
            helper.assertTrue(parts.place(Direction.UP, CraftingTerminalContent.TERMINAL_PART, null,
                    new ItemStack(CraftingTerminalContent.TERMINAL_PART_ITEM.get())), "part placed");
            CablePart part = PartGameTests.part(helper, parts, Direction.UP);
            if (!(part instanceof CraftingTerminal partTerminal)) {
                throw helper.assertionException(Component.literal("not a crafting terminal part"));
            }
            partTerminal.grid().setItem(0, new ItemStack(Items.OAK_PLANKS, 5));
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING,
                    helper.getLevel().registryAccess());
            partTerminal.grid().save(output);
            TerminalGrid loaded = TerminalGrid.detached();
            loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(),
                    output.buildResult()));
            helper.assertValueEqual(inGrid(loaded, Items.OAK_PLANKS), 5, "the part's grid after a reload");

            helper.getLevel().destroyBlock(helper.absolutePos(NetworkGameTests.at(6)), true);
            helper.assertItemEntityCountIs(Items.OAK_PLANKS, NetworkGameTests.at(6), 1.5, 5);
            helper.assertItemEntityCountIs(CraftingTerminalContent.TERMINAL_PART_ITEM.get(), NetworkGameTests.at(6),
                    1.5, 1);
        }).thenSucceed();
    }

    /**
     * A recipe viewer's payload through Lib's receiver, as the server gets it from JEI or REI: with max transfer every
     * cell takes a full stack from the network; a second payload in the same tick is refused.
     */
    private static void viewerTransfer(GameTestHelper helper) {
        CraftingTerminalBlockEntity terminal = network(helper);
        ServerPlayer player = player(helper, "viewer");
        helper.startSequence().thenWaitUntil(() -> net(helper, terminal)).thenExecute(() -> {
            NetworkState network = net(helper, terminal);
            NetworkGameTests.insert(network, PLANKS, 300);
            TerminalMenu menu = menu(terminal, player);
            player.containerMenu = menu;
            helper.assertTrue(
                    CraftingGridMenus.receive(new CraftingGridMenus.Transfer(menu.containerId, table(), true), player),
                    "the payload was taken");
            helper.assertValueEqual(inGrid(terminal.grid(), Items.OAK_PLANKS), 4 * 64, "a stack in each cell");
            helper.assertValueEqual(stored(network, Items.OAK_PLANKS), 300L - 4 * 64, "from the network");
            helper.assertTrue(panel(helper, menu).result().is(Items.CRAFTING_TABLE), "the grid crafts a table");
            helper.assertTrue(!CraftingGridMenus
                    .receive(new CraftingGridMenus.Transfer(menu.containerId, table(), false), player),
                    "a second payload in one tick");
            player.containerMenu = player.inventoryMenu;
            menu.removed(player);
        }).thenSucceed();
    }

    /** The cake: three milk buckets, sugar, egg, sugar, three wheat. */
    private static List<List<ItemStack>> cake() {
        return cells("#########", Items.MILK_BUCKET, Items.MILK_BUCKET, Items.MILK_BUCKET, Items.SUGAR, Items.EGG,
                Items.SUGAR, Items.WHEAT);
    }

    private static void give(ServerPlayer player, Item item, int count) {
        for (int i = 0; i < count; i++) {
            player.getInventory().add(new ItemStack(item));
        }
    }

    /** What the network would take of {@code item} now, tried in a transaction that is thrown away. */
    private static long room(NetworkState network, Item item) {
        try (Transaction transaction = Transaction.openRoot()) {
            return network.insert(ItemKey.of(item), 1, transaction);
        }
    }

    /**
     * A full network refuses the cake's empty buckets; the cells were refilled with milk from the inventory, so the
     * buckets go to the inventory. Nothing is lost or dropped.
     */
    private static void remainderNetworkFull(GameTestHelper helper) {
        CraftingTerminalBlockEntity terminal = network(helper);
        ServerPlayer player = player(helper, "network_full");
        helper.startSequence().thenWaitUntil(() -> net(helper, terminal)).thenExecute(() -> {
            NetworkState network = net(helper, terminal);
            NetworkGameTests.insert(network, ItemKey.of(Items.MILK_BUCKET), 3);
            NetworkGameTests.insert(network, ItemKey.of(Items.SUGAR), 2);
            NetworkGameTests.insert(network, ItemKey.of(Items.EGG), 1);
            NetworkGameTests.insert(network, ItemKey.of(Items.WHEAT), 3);
            give(player, Items.MILK_BUCKET, 3);
            TerminalMenu menu = menu(terminal, player);
            menu.receiveRecipe(player, cake(), false);
            helper.assertTrue(panel(helper, menu).result().is(Items.CAKE), "the grid crafts a cake");
            NetworkGameTests.insert(network, ItemKey.of(Items.COBBLESTONE), 10_000_000);
            helper.assertValueEqual(room(network, Items.BUCKET), 0L, "the network is full");
            menu.clicked(TerminalMenu.RESULT_SLOT, 0, ContainerInput.PICKUP, player);
            helper.assertTrue(menu.getCarried().is(Items.CAKE), "a cake on the cursor");
            helper.assertValueEqual(inGrid(terminal.grid(), Items.MILK_BUCKET), 3, "milk refilled from the inventory");
            helper.assertValueEqual(carried(player, Items.BUCKET), 3, "the buckets went to the inventory");
            helper.assertValueEqual(stored(network, Items.BUCKET), 0L, "none in the network");
            helper.assertItemEntityCountIs(Items.BUCKET, NetworkGameTests.at(TERMINAL_X), 4.0, 0);
            menu.removed(player);
        }).thenSucceed();
    }

    /**
     * Shift-click keeps a slot for each remainder: a view-only player (the network refuses the bottle) with a full
     * inventory that still takes the sugar, but has no slot for the honey bottle's glass bottle, crafts nothing, and
     * nothing lands on the ground. A cake cannot show this: refilling its milk buckets from the inventory frees the
     * slots its buckets need, and refilling from the network frees the room they need there.
     */
    private static void shiftCraftKeepsRemainderRoom(GameTestHelper helper) {
        CraftingTerminalBlockEntity terminal = network(helper);
        ServerPlayer viewer = player(helper, "full_inventory");
        BlockPos controller = NetworkGameTests.at(1);
        helper.startSequence().thenWaitUntil(() -> net(helper, terminal)).thenExecute(() -> {
            ControllerBlockEntity core = NetworkGameTests.controllerEntity(helper, controller);
            // A module's rules need an owned network: an unowned one lets everybody in.
            core.setOwner(OwnerRecord.of(player(helper, "room_owner")));
            ControllerClusterGameTests.expansion(helper, controller.above(), ExpansionContent.SECURITY_MODULE);
            core.setSecurity(SecuritySettings.DEFAULT.withPlayer(viewer.getUUID(), viewer.getGameProfile().name(),
                    AccessLevel.VIEW));
        }).thenWaitUntil(() -> helper.assertTrue(NetworkGameTests.controllerEntity(helper, controller).secured(),
                "module active")).thenExecute(() -> {
                    NetworkState network = net(helper, terminal);
                    viewer.getInventory().setItem(0, new ItemStack(Items.HONEY_BOTTLE, 16));
                    // Room for one craft's three sugar, and no free slot.
                    viewer.getInventory().setItem(1, new ItemStack(Items.SUGAR, 60));
                    for (int slot = 2; slot < 36; slot++) {
                        viewer.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
                    }
                    TerminalMenu menu = menu(terminal, viewer);
                    menu.receiveRecipe(viewer, cells("#........", Items.HONEY_BOTTLE), false);
                    helper.assertTrue(panel(helper, menu).result().is(Items.SUGAR), "the grid crafts sugar");
                    menu.clicked(TerminalMenu.RESULT_SLOT, 0, ContainerInput.QUICK_MOVE, viewer);
                    helper.assertValueEqual(carried(viewer, Items.SUGAR), 60, "no craft without a slot for the bottle");
                    helper.assertValueEqual(
                            inGrid(terminal.grid(), Items.HONEY_BOTTLE) + carried(viewer, Items.HONEY_BOTTLE), 16,
                            "honey untouched");
                    helper.assertValueEqual(stored(network, Items.GLASS_BOTTLE), 0L, "nothing deposited");
                    helper.assertItemEntityCountIs(Items.GLASS_BOTTLE, NetworkGameTests.at(TERMINAL_X), 6.0, 0);
                    helper.assertItemEntityCountIs(Items.SUGAR, NetworkGameTests.at(TERMINAL_X), 6.0, 0);
                    menu.removed(viewer);
                }).thenSucceed();
    }

    /**
     * A player who may only view the network crafts from the inventory: the grid fills and refills from it, and the
     * empty buckets go back to the inventory, not into the network.
     */
    private static void remainderWithoutDeposit(GameTestHelper helper) {
        CraftingTerminalBlockEntity terminal = network(helper);
        ServerPlayer viewer = player(helper, "viewer_only");
        BlockPos controller = NetworkGameTests.at(1);
        helper.startSequence().thenWaitUntil(() -> net(helper, terminal)).thenExecute(() -> {
            ControllerBlockEntity core = NetworkGameTests.controllerEntity(helper, controller);
            // A module's rules need an owned network: an unowned one lets everybody in.
            core.setOwner(OwnerRecord.of(player(helper, "network_owner")));
            ControllerClusterGameTests.expansion(helper, controller.above(), ExpansionContent.SECURITY_MODULE);
            core.setSecurity(SecuritySettings.DEFAULT.withPlayer(viewer.getUUID(), viewer.getGameProfile().name(),
                    AccessLevel.VIEW));
        }).thenWaitUntil(() -> helper.assertTrue(NetworkGameTests.controllerEntity(helper, controller).secured(),
                "module active")).thenExecute(() -> {
                    NetworkState network = net(helper, terminal);
                    helper.assertTrue(terminal.canAccess(viewer) && !terminal.canDeposit(viewer), "view only");
                    give(viewer, Items.MILK_BUCKET, 6);
                    viewer.getInventory().add(new ItemStack(Items.SUGAR, 2));
                    viewer.getInventory().add(new ItemStack(Items.EGG, 1));
                    viewer.getInventory().add(new ItemStack(Items.WHEAT, 3));
                    TerminalMenu menu = menu(terminal, viewer);
                    menu.receiveRecipe(viewer, cake(), false);
                    helper.assertTrue(panel(helper, menu).result().is(Items.CAKE), "filled from the inventory");
                    menu.clicked(TerminalMenu.RESULT_SLOT, 0, ContainerInput.PICKUP, viewer);
                    helper.assertTrue(menu.getCarried().is(Items.CAKE), "a cake on the cursor");
                    helper.assertValueEqual(stored(network, Items.BUCKET), 0L, "nothing deposited");
                    helper.assertValueEqual(carried(viewer, Items.BUCKET), 3, "the buckets are in the inventory");
                    helper.assertValueEqual(inGrid(terminal.grid(), Items.MILK_BUCKET), 3, "milk refilled");
                    menu.removed(viewer);
                }).thenSucceed();
    }

    /**
     * A tool that wears down stays in its cell, one damage further per craft, while the log beside it refills from the
     * network; its remainder is worked out with the crafting player set.
     */
    private static void wornToolStays(GameTestHelper helper) {
        CraftingTerminalBlockEntity terminal = network(helper);
        ServerPlayer player = player(helper, "worn_tool");
        Item tool = WORN_TOOL.get();
        helper.startSequence().thenWaitUntil(() -> net(helper, terminal)).thenExecute(() -> {
            NetworkState network = net(helper, terminal);
            NetworkGameTests.insert(network, ItemKey.of(tool), 1);
            NetworkGameTests.insert(network, ItemKey.of(Items.OAK_LOG), 2);
            TerminalMenu menu = menu(terminal, player);
            menu.receiveRecipe(player, cells("##.......", tool, Items.OAK_LOG), false);
            helper.assertTrue(panel(helper, menu).result().is(Items.STICK), "the grid crafts sticks");
            WornTool.lastCrafter = null;
            menu.clicked(TerminalMenu.RESULT_SLOT, 0, ContainerInput.PICKUP, player);
            helper.assertValueEqual(menu.getCarried().getCount(), 2, "two sticks");
            helper.assertTrue(java.util.Objects.equals(WornTool.lastCrafter, player),
                    "the remainder saw the crafting player");
            ItemStack kept = terminal.grid().getItem(0);
            helper.assertTrue(kept.is(tool) && kept.getDamageValue() == 1, "the worn tool stays in its cell");
            helper.assertValueEqual(inGrid(terminal.grid(), Items.OAK_LOG), 1, "the log refilled");
            helper.assertValueEqual(stored(network, tool), 0L, "the tool did not go to the network");
            menu.clicked(TerminalMenu.RESULT_SLOT, 0, ContainerInput.PICKUP, player);
            helper.assertValueEqual(menu.getCarried().getCount(), 4, "four sticks");
            helper.assertValueEqual(terminal.grid().getItem(0).getDamageValue(), 2, "worn once more");
            menu.removed(player);
        }).thenSucceed();
    }

    /**
     * Two players share one terminal's grid: after one clears it, the other's result, worked out before, cannot be
     * taken; after a refill the other shift-crafts from the shared grid and the first sees it empty.
     */
    private static void twoMenus(GameTestHelper helper) {
        CraftingTerminalBlockEntity terminal = network(helper);
        ServerPlayer first = player(helper, "first");
        ServerPlayer second = player(helper, "second");
        helper.startSequence().thenWaitUntil(() -> net(helper, terminal)).thenExecute(() -> {
            NetworkState network = net(helper, terminal);
            NetworkGameTests.insert(network, PLANKS, 8);
            TerminalMenu one = TerminalMenu.server(1, first.getInventory(), terminal, update -> {
            });
            TerminalMenu two = TerminalMenu.server(2, second.getInventory(), terminal, update -> {
            });
            one.receiveRecipe(first, table(), false);
            two.broadcastChanges();
            helper.assertTrue(panel(helper, two).result().is(Items.CRAFTING_TABLE), "the second sees the table");
            helper.assertTrue(TerminalGameTests.click(one, first, TerminalAction.Kind.GRID_CLEAR, -1), "cleared");
            two.clicked(TerminalMenu.RESULT_SLOT, 0, ContainerInput.PICKUP, second);
            helper.assertTrue(two.getCarried().isEmpty(), "no table from a cleared grid");
            helper.assertValueEqual(stored(network, Items.OAK_PLANKS), 8L, "all planks back");

            one.receiveRecipe(first, table(), false);
            two.clicked(TerminalMenu.RESULT_SLOT, 0, ContainerInput.QUICK_MOVE, second);
            helper.assertValueEqual(carried(second, Items.CRAFTING_TABLE), 2, "two tables from the shared grid");
            one.broadcastChanges();
            helper.assertTrue(panel(helper, one).result().isEmpty(), "the first sees the empty grid");
            helper.assertValueEqual(stored(network, Items.OAK_PLANKS) + inGrid(terminal.grid(), Items.OAK_PLANKS), 0L,
                    "planks used up");
            one.removed(first);
            two.removed(second);
        }).thenSucceed();
    }
}
