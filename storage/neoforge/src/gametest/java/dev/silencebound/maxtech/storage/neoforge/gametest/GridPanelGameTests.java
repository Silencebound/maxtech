package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.board.BoardContent;
import dev.silencebound.maxtech.storage.board.BoardData;
import dev.silencebound.maxtech.storage.board.BoardMenu;
import dev.silencebound.maxtech.storage.board.BoardService;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternContent;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternDraft;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternEncoder;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternPanel;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternPanel.Action;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternTerminal;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternTerminalBlockEntity;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternTerminalContent;
import dev.silencebound.maxtech.storage.mail.MailData;
import dev.silencebound.maxtech.storage.mail.ServiceBlockEntity;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.registry.ModMenus;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The network grid on the pattern terminal and the request board (B09 item 30): a grid click sets a draft slot without
 * moving any item; a draft built from grid clicks encodes the same pattern as one built from carried stacks; the
 * pattern terminal block and part keep their ids and open as terminal menus with the pattern panel, their draft kept
 * across a save; the board shows its network to a player with view access only, posts a request for an item picked on
 * its grid with nothing held, and fulfils it as before. Layout: controller and two test drives along x, the terminal on
 * the second drive's south side, the board on its north side.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class GridPanelGameTests {
    private static final String[] NAMES = {"grid_panel_pattern_ghost", "grid_panel_encode_same",
            "grid_panel_old_terminal_opens", "grid_panel_board_post", "grid_panel_board_fulfil",
            "grid_panel_board_revoked"};
    private static final StackKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final StackKey ORE = ItemKey.of(Items.IRON_ORE);
    private static final StackKey PLANKS = ItemKey.of(Items.OAK_PLANKS);
    private static final StackKey DIAMOND = ItemKey.of(Items.DIAMOND);
    private static final int DRIVE_X = 3;

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(NAMES[0], GridPanelGameTests::patternGhost);
        register(NAMES[1], GridPanelGameTests::encodeSame);
        register(NAMES[2], GridPanelGameTests::oldTerminalOpens);
        register(NAMES[3], GridPanelGameTests::boardPost);
        register(NAMES[4], GridPanelGameTests::boardFulfil);
        register(NAMES[5], GridPanelGameTests::boardRevoked);
    }

    private GridPanelGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("grid_panel"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 300);
        }
    }

    /** A powered controller and two test drives; the pattern terminal on the second drive's south side. */
    private static PatternTerminalBlockEntity network(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 100_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        NetworkGameTests.device(helper, NetworkGameTests.at(DRIVE_X));
        BlockPos pos = NetworkGameTests.at(DRIVE_X).south();
        helper.setBlock(pos, PatternTerminalContent.TERMINAL.get());
        return helper.getBlockEntity(pos, PatternTerminalBlockEntity.class);
    }

    private static NetworkState net(GameTestHelper helper, PatternTerminal terminal) {
        NetworkState network = terminal.network();
        if (network == null) {
            throw helper.assertionException(Component.literal("the pattern terminal has no network"));
        }
        return network;
    }

    /** A player standing by the drives, so the menus stay valid. */
    private static ServerPlayer player(GameTestHelper helper, String name) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), name);
        player.setPos(helper.absoluteVec(NetworkGameTests.at(DRIVE_X).above().getCenter()));
        return player;
    }

    private static TerminalMenu menu(PatternTerminal terminal, ServerPlayer player) {
        return TerminalMenu.server(1, player.getInventory(), terminal, update -> {
        });
    }

    private static PatternPanel panel(GameTestHelper helper, TerminalMenu menu) {
        PatternPanel panel = menu.patternPanel();
        if (panel == null) {
            throw helper.assertionException(Component.literal("the menu has no pattern panel"));
        }
        return panel;
    }

    private static void click(TerminalMenu menu, ServerPlayer player, Action action, int slot) {
        menu.clickMenuButton(player, action.button(slot));
    }

    private static int count(ServerPlayer player) {
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            total += player.getInventory().getItem(i).getCount();
        }
        return total;
    }

    /**
     * Grid clicks (ghosts) set an input, an output and a fluid input of a processing draft as one item or a bucket; the
     * network keeps every ingot and the player's inventory and cursor stay empty. A slot past the outputs is refused.
     */
    private static void patternGhost(GameTestHelper helper) {
        PatternTerminalBlockEntity terminal = network(helper);
        ServerPlayer player = player(helper, "grid_ghost");
        helper.startSequence().thenWaitUntil(() -> net(helper, terminal)).thenExecute(() -> {
            NetworkState network = net(helper, terminal);
            NetworkGameTests.insert(network, IRON, 10);
            TerminalMenu menu = menu(terminal, player);
            PatternPanel panel = panel(helper, menu);
            helper.assertTrue(java.util.Objects.equals(menu.getType(), ModMenus.PATTERN_TERMINAL.get()),
                    "the pattern terminal's menu type");
            helper.assertTrue(terminal.contents().containsKey(network.contents().keyId(IRON)),
                    "the grid lists the ingots");
            click(menu, player, Action.MODE_PROCESSING, 0);
            helper.assertTrue(panel.ghost(player, 0, IRON), "input set");
            helper.assertTrue(panel.ghost(player, PatternDraft.INPUTS, IRON), "output set");
            helper.assertTrue(panel.ghost(player, 1, FluidKey.of(Fluids.WATER)), "fluid input set");
            helper.assertFalse(panel.ghost(player, PatternPanel.SLOTS, IRON), "a slot out of range");
            PatternDraft draft = panel.draft();
            helper.assertValueEqual(draft.inputs().get(0).orElseThrow().key(), IRON, "the input's key");
            helper.assertValueEqual(draft.inputs().get(0).orElseThrow().amount(), 1L, "one ingot");
            helper.assertValueEqual(draft.inputs().get(1).orElseThrow().amount(), 1000L, "a bucket of water");
            helper.assertValueEqual(draft.outputs().get(0).orElseThrow().key(), IRON, "the output's key");
            helper.assertValueEqual(network.contents().amount(IRON), 10L, "the network keeps every ingot");
            helper.assertValueEqual(count(player), 0, "nothing in the inventory");
            helper.assertTrue(menu.getCarried().isEmpty(), "nothing on the cursor");
        }).thenSucceed();
    }

    /** Encodes the menu's draft onto a blank and returns the encoded stack. */
    private static ItemStack encode(GameTestHelper helper, TerminalMenu menu, ServerPlayer player) {
        player.getInventory().clearContent();
        player.getInventory().add(new ItemStack(PatternContent.BLANK_PATTERN.get()));
        click(menu, player, Action.ENCODE, 0);
        helper.assertTrue(panel(helper, menu).result() == PatternEncoder.Result.ENCODED, "encoded");
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(PatternContent.ENCODED_PATTERN.get())) {
                return stack.copy();
            }
        }
        throw helper.assertionException(Component.literal("no encoded pattern"));
    }

    private static void carried(TerminalMenu menu, ServerPlayer player, Action action, int slot, Item item, int n) {
        menu.setCarried(new ItemStack(item, n));
        click(menu, player, action, slot);
        menu.setCarried(ItemStack.EMPTY);
    }

    /**
     * The same processing and crafting patterns built from grid clicks and from carried stacks (the path the terminal
     * had before the grid) encode to identical stacks: one pattern format whichever way the draft was filled.
     */
    private static void encodeSame(GameTestHelper helper) {
        BlockPos first = NetworkGameTests.at(2);
        BlockPos second = NetworkGameTests.at(4);
        helper.setBlock(first, PatternTerminalContent.TERMINAL.get());
        helper.setBlock(second, PatternTerminalContent.TERMINAL.get());
        ServerPlayer player = player(helper, "grid_same");
        player.setPos(helper.absoluteVec(NetworkGameTests.at(3).getCenter()));
        TerminalMenu ghosts = menu(helper.getBlockEntity(first, PatternTerminalBlockEntity.class), player);
        TerminalMenu stacks = menu(helper.getBlockEntity(second, PatternTerminalBlockEntity.class), player);
        PatternPanel panel = panel(helper, ghosts);

        click(ghosts, player, Action.MODE_PROCESSING, 0);
        panel.ghost(player, 0, ORE);
        click(ghosts, player, Action.MORE, 0);
        panel.ghost(player, 1, FluidKey.of(Fluids.WATER));
        panel.ghost(player, PatternDraft.INPUTS, IRON);
        click(stacks, player, Action.MODE_PROCESSING, 0);
        carried(stacks, player, Action.SET, 0, Items.IRON_ORE, 2);
        carried(stacks, player, Action.SET_FLUID, 1, Items.WATER_BUCKET, 1);
        carried(stacks, player, Action.SET, PatternDraft.INPUTS, Items.IRON_INGOT, 1);
        compare(helper, encode(helper, ghosts, player), encode(helper, stacks, player), "processing");

        click(ghosts, player, Action.CLEAR, 0);
        click(stacks, player, Action.CLEAR, 0);
        click(ghosts, player, Action.MODE_CRAFTING, 0);
        click(stacks, player, Action.MODE_CRAFTING, 0);
        for (int cell : new int[]{0, 1, 3, 4}) {
            panel.ghost(player, cell, PLANKS);
            carried(stacks, player, Action.SET, cell, Items.OAK_PLANKS, 1);
        }
        compare(helper, encode(helper, ghosts, player), encode(helper, stacks, player), "crafting");
        helper.succeed();
    }

    private static void compare(GameTestHelper helper, ItemStack ghosts, ItemStack stacks, String what) {
        CraftingPattern fromGhosts = PatternItem.pattern(ghosts);
        CraftingPattern fromStacks = PatternItem.pattern(stacks);
        helper.assertTrue(fromGhosts != null && fromGhosts.equals(fromStacks), what + ": the same pattern");
        helper.assertTrue(ItemStack.isSameItemSameComponents(ghosts, stacks), what + ": the same stack");
    }

    /**
     * The pattern terminal block and part keep their ids and open as terminal menus with the pattern panel; a draft
     * saved by a terminal loads into another and shows in its menu.
     */
    private static void oldTerminalOpens(GameTestHelper helper) {
        PatternTerminalBlockEntity terminal = network(helper);
        NetworkGameTests.cables(helper, 6, 6, CableContent.STORAGE_CABLE_TIER_1.type().get());
        ServerPlayer player = player(helper, "grid_old");
        helper.startSequence().thenWaitUntil(() -> net(helper, terminal)).thenExecute(() -> {
            helper.assertTrue(
                    java.util.Objects.equals(MaxtechStorage.id("pattern_terminal"),
                            BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(PatternTerminalContent.BLOCK_ENTITY.get())),
                    "the block entity id");
            helper.assertTrue(java.util.Objects.equals(MaxtechStorage.id("pattern_terminal"),
                    BuiltInRegistries.MENU.getKey(ModMenus.PATTERN_TERMINAL.get())), "the menu id");
            terminal.encoder().setKey(0, IRON);
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING,
                    helper.getLevel().registryAccess());
            terminal.encoder().save(output);

            CableParts parts = PartGameTests.parts(helper, 6);
            helper.assertTrue(parts.place(Direction.UP, PatternTerminalContent.TERMINAL_PART, null,
                    new ItemStack(PatternTerminalContent.TERMINAL_PART_ITEM.get())), "part placed");
            CablePart part = PartGameTests.part(helper, parts, Direction.UP);
            if (!(part instanceof PatternTerminal partTerminal)) {
                throw helper.assertionException(Component.literal("not a pattern terminal part"));
            }
            partTerminal.encoder().load(TagValueInput.create(ProblemReporter.DISCARDING,
                    helper.getLevel().registryAccess(), output.buildResult()));
            player.setPos(helper.absoluteVec(NetworkGameTests.at(6).above().getCenter()));
            for (PatternTerminal opened : List.of(terminal, partTerminal)) {
                player.setPos(helper.absoluteVec(
                        (opened == terminal ? NetworkGameTests.at(DRIVE_X) : NetworkGameTests.at(6)).getCenter()));
                TerminalMenu menu = menu(opened, player);
                helper.assertTrue(java.util.Objects.equals(menu.getType(), ModMenus.PATTERN_TERMINAL.get()),
                        "the pattern menu type");
                helper.assertTrue(menu.stillValid(player), "the menu stays open");
                helper.assertValueEqual(panel(helper, menu).draft().inputs().get(0).orElseThrow().key(), IRON,
                        "the saved draft");
            }
        }).thenSucceed();
    }

    /** A controller, a test drive and a request board owned by {@code owner}; the board's position. */
    private static BlockPos boardNetwork(GameTestHelper helper, ServerPlayer owner) {
        BlockPos core = new BlockPos(5, 1, 5);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        TestStorage.TestDevice device = NetworkGameTests.device(helper, core.north());
        BlockPos boardPos = core.east();
        helper.setBlock(boardPos, BoardContent.REQUEST_BOARD.get());
        OwnerRecord record = OwnerRecord.of(owner);
        board(helper, boardPos).setOwner(record);
        NetworkGameTests.controllerEntity(helper, core).setOwner(record);
        device.setOwner(record);
        NetworkGameTests.power(helper, core, 50_000);
        return boardPos;
    }

    private static ServiceBlockEntity board(GameTestHelper helper, BlockPos pos) {
        return helper.getBlockEntity(pos, ServiceBlockEntity.class);
    }

    private static NetworkState boardNet(GameTestHelper helper, BlockPos pos) {
        NetworkState network = board(helper, pos).network();
        if (network == null) {
            throw helper.assertionException(Component.literal("the board has no network"));
        }
        return network;
    }

    private static ServerPlayer boardPlayer(GameTestHelper helper, String name, BlockPos boardPos) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(),
                name + helper.getLevel().getRandom().nextInt(100_000));
        player.setPos(helper.absoluteVec(boardPos.above().getCenter()));
        return player;
    }

    private static List<BoardData.Request> requests(GameTestHelper helper, ServiceBlockEntity board) {
        return BoardData.get(helper.getLevel().getServer())
                .requests(BoardService.team(helper.getLevel().getServer(), board));
    }

    /**
     * The owner's board menu lists the network (a dump goes out and the menu watches it); a stranger's lists nothing.
     * An item picked on the grid is posted with an empty hand: 16 diamonds wanted, nothing taken from the network or
     * the player; a second action in the same tick and an amount out of range are refused.
     */
    private static void boardPost(GameTestHelper helper) {
        ServerPlayer owner = boardPlayer(helper, "gbown_", BlockPos.ZERO);
        BlockPos boardPos = boardNetwork(helper, owner);
        owner.setPos(helper.absoluteVec(boardPos.above().getCenter()));
        ServerPlayer stranger = boardPlayer(helper, "gbstr_", boardPos);
        helper.startSequence().thenWaitUntil(() -> boardNet(helper, boardPos)).thenExecute(() -> {
            NetworkState network = boardNet(helper, boardPos);
            NetworkGameTests.insert(network, DIAMOND, 5);
            ServiceBlockEntity board = board(helper, boardPos);
            int[] dumps = {0};
            BoardMenu menu = BoardMenu.server(3, owner, board, update -> dumps[0]++);
            menu.broadcastChanges();
            helper.assertTrue(menu.watched() == network, "the owner's grid watches the network");
            helper.assertTrue(dumps[0] > 0, "the contents went out");
            BoardMenu other = BoardMenu.server(4, stranger, board);
            other.broadcastChanges();
            helper.assertTrue(other.watched() == null, "a stranger sees nothing");

            helper.assertTrue(owner.getMainHandItem().isEmpty(), "nothing held");
            helper.assertTrue(menu.post(owner, (ItemKey) DIAMOND, 1), "posted from the grid");
            helper.assertFalse(menu.post(owner, (ItemKey) DIAMOND, 1), "one action per tick");
            List<BoardData.Request> requests = requests(helper, board);
            helper.assertValueEqual(requests.size(), 1, "one request");
            helper.assertTrue(requests.get(0).item().is(Items.DIAMOND), "for diamonds");
            helper.assertValueEqual(requests.get(0).wanted(), 16, "16 wanted");
            helper.assertValueEqual(network.contents().amount(DIAMOND), 5L, "nothing taken from the network");
            helper.assertValueEqual(count(owner), 0, "nothing taken from the player");
        }).thenIdle(1).thenExecute(() -> {
            BoardMenu menu = BoardMenu.server(5, owner, board(helper, boardPos));
            helper.assertFalse(menu.post(owner, (ItemKey) DIAMOND, BoardMenu.POST_COUNT), "an amount out of range");
            owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            helper.assertValueEqual(BoardService.post(owner, board(helper, boardPos), 1), BoardService.NOTHING_HELD_KEY,
                    "the held-item path is unchanged");
        }).thenSucceed();
    }

    /** A request posted from the grid is fulfilled from the board's menu as before: five diamonds go to the inbox. */
    private static void boardFulfil(GameTestHelper helper) {
        ServerPlayer owner = boardPlayer(helper, "gfown_", BlockPos.ZERO);
        BlockPos boardPos = boardNetwork(helper, owner);
        owner.setPos(helper.absoluteVec(boardPos.above().getCenter()));
        BoardMenu[] menu = new BoardMenu[1];
        helper.startSequence().thenWaitUntil(() -> boardNet(helper, boardPos)).thenExecute(() -> {
            NetworkGameTests.insert(boardNet(helper, boardPos), DIAMOND, 5);
            menu[0] = BoardMenu.server(3, owner, board(helper, boardPos));
            helper.assertTrue(menu[0].post(owner, (ItemKey) DIAMOND, 1), "posted from the grid");
        }).thenIdle(1).thenExecute(() -> {
            ServiceBlockEntity board = board(helper, boardPos);
            int id = requests(helper, board).get(0).id();
            helper.assertTrue(menu[0].clickMenuButton(owner, BoardMenu.FULFIL + id), "fulfil clicked");
            helper.assertValueEqual(boardNet(helper, boardPos).contents().amount(DIAMOND), 0L, "all five taken");
            helper.assertValueEqual(MailData.get(helper.getLevel().getServer()).size(owner.getUUID()), 1,
                    "one stack mailed");
            helper.assertValueEqual(requests(helper, board).get(0).remaining(), 11, "11 still wanted");
        }).thenSucceed();
    }

    /**
     * A trusted player posts from the grid and with a held item; once the owner takes the trust back, with the screen
     * still open, neither path posts and the board keeps one request each.
     */
    private static void boardRevoked(GameTestHelper helper) {
        ServerPlayer owner = boardPlayer(helper, "grown_", BlockPos.ZERO);
        BlockPos core = new BlockPos(5, 1, 5);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        TestStorage.TestDevice device = NetworkGameTests.device(helper, core.north());
        BlockPos boardPos = core.east();
        helper.setBlock(boardPos, BoardContent.REQUEST_BOARD.get());
        ServerPlayer guest = boardPlayer(helper, "grguest_", boardPos);
        OwnerRecord trusting = OwnerRecord.of(owner).withTrusted(guest.getUUID());
        board(helper, boardPos).setOwner(trusting);
        NetworkGameTests.controllerEntity(helper, core).setOwner(trusting);
        device.setOwner(trusting);
        NetworkGameTests.power(helper, core, 50_000);
        BoardMenu[] menu = new BoardMenu[1];
        helper.startSequence().thenWaitUntil(() -> boardNet(helper, boardPos)).thenExecute(() -> {
            menu[0] = BoardMenu.server(3, guest, board(helper, boardPos));
            helper.assertTrue(menu[0].post(guest, (ItemKey) DIAMOND, 0), "the trusted guest posts from the grid");
            guest.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.EMERALD));
            helper.assertValueEqual(BoardService.post(guest, board(helper, boardPos), 1), BoardService.POSTED_KEY,
                    "and with a held item");
            helper.assertValueEqual(requests(helper, board(helper, boardPos)).size(), 2, "two requests");
            OwnerRecord revoked = OwnerRecord.of(owner);
            board(helper, boardPos).setOwner(revoked);
            NetworkGameTests.controllerEntity(helper, core).setOwner(revoked);
            device.setOwner(revoked);
        }).thenIdle(1).thenExecute(() -> {
            ServiceBlockEntity board = board(helper, boardPos);
            menu[0].post(guest, (ItemKey) DIAMOND, 0);
            helper.assertValueEqual(BoardService.post(guest, board, 1), BoardService.POST_DENIED_KEY,
                    "the held item is refused");
            helper.assertValueEqual(requests(helper, board).size(), 2, "no request after the trust went");
        }).thenSucceed();
    }
}
