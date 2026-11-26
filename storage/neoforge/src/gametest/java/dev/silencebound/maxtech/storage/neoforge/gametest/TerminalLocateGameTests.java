package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.highlight.HighlightRequest;
import dev.silencebound.maxtech.lib.highlight.Highlights;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.terminal.ItemLocator;
import dev.silencebound.maxtech.storage.terminal.TerminalAction;
import dev.silencebound.maxtech.storage.terminal.TerminalBlock;
import dev.silencebound.maxtech.storage.terminal.TerminalBlockEntity;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The terminal's locate action (M7C item 4): it names every block holding an item with the amount and, for a drive, the
 * cell slot, largest first, and outlines those blocks on the Locate channel; a player without access to the network
 * gets nothing. Layout along x at y = 1, z = 1: controller, a drive with two cells, a test device; the terminal hangs
 * off the test device's south side.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class TerminalLocateGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_16x12x16");
    private static final String[] NAMES = {"terminal_locate_places", "terminal_locate_refused"};
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(NAMES[0], TerminalLocateGameTests::places);
        register(NAMES[1], TerminalLocateGameTests::refused);
    }

    private TerminalLocateGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("terminal_locate"), StorageTestEnvironment.plain());
        for (String name : NAMES) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, STRUCTURE, 200, 0, true)));
        }
    }

    /** The network: returns the terminal; the drive at x 2 gets two cells, the test device sits at x 3. */
    private static TerminalBlockEntity network(GameTestHelper helper, DriveBlockEntity[] drive) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 10_000);
        drive[0] = CellGameTests.drive(helper, NetworkGameTests.at(2));
        drive[0].insertCell(CellGameTests.cell(CellContent.ITEM_TYPE.id(), 1));
        drive[0].insertCell(CellGameTests.cell(CellContent.ITEM_TYPE.id(), 1));
        NetworkGameTests.device(helper, NetworkGameTests.at(3));
        BlockPos terminal = NetworkGameTests.at(3).south();
        BlockState state = TerminalContent.TERMINAL.get().defaultBlockState().setValue(TerminalBlock.FACING,
                Direction.SOUTH);
        helper.setBlock(terminal, state);
        return helper.getBlockEntity(terminal, TerminalBlockEntity.class);
    }

    private static NetworkState net(GameTestHelper helper, TerminalBlockEntity terminal) {
        NetworkState network = terminal.network();
        if (network == null) {
            throw helper.assertionException(Component.literal("Terminal has no network"));
        }
        return network;
    }

    private static ServerPlayer player(GameTestHelper helper, String name) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), name);
        player.setPos(helper.absoluteVec(NetworkGameTests.at(3).above().getCenter()));
        return player;
    }

    /** Fills the drive's second cell and the test device with cobblestone, 30 and 50. */
    private static void fill(GameTestHelper helper, DriveBlockEntity drive) {
        try (Transaction transaction = Transaction.openRoot()) {
            CellGameTests.mounted(helper, drive, 1).insert(COBBLE, 30, transaction);
            transaction.commit();
        }
        helper.getBlockEntity(NetworkGameTests.at(3), TestStorage.TestDevice.class).provider.setDirect(COBBLE, 50);
    }

    private static boolean outlined(GameTestHelper helper, HighlightRequest request, BlockPos relative) {
        BlockPos pos = helper.absolutePos(relative);
        for (int i = 0; i < request.boxCount(); i++) {
            if (Math.floor(request.box(i, 0) + 0.01) == pos.getX() && Math.floor(request.box(i, 1) + 0.01) == pos.getY()
                    && Math.floor(request.box(i, 2) + 0.01) == pos.getZ()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Locate lists the test device (50) before the drive's second cell (30) and outlines both through walls on the
     * Locate channel; the chat lines give the total and each place; a second press within the rate limit does nothing.
     */
    private static void places(GameTestHelper helper) {
        DriveBlockEntity[] drive = new DriveBlockEntity[1];
        TerminalBlockEntity terminal = network(helper, drive);
        ServerPlayer player = player(helper, "locate_user");
        helper.startSequence().thenWaitUntil(() -> {
            CellGameTests.mounted(helper, drive[0], 1);
            net(helper, terminal);
        }).thenExecute(() -> fill(helper, drive[0])).thenExecute(() -> {
            NetworkState network = net(helper, terminal);
            List<ItemLocator.Place> places = ItemLocator.places(network, COBBLE);
            helper.assertValueEqual(places.size(), 2, "two places");
            helper.assertValueEqual(places.get(0).pos(), helper.absolutePos(NetworkGameTests.at(3)), "device first");
            helper.assertValueEqual(places.get(0).amount(), 50L, "device amount");
            helper.assertValueEqual(places.get(0).slot(), -1, "a device has no slot");
            helper.assertValueEqual(places.get(1).pos(), helper.absolutePos(NetworkGameTests.at(2)), "then the drive");
            helper.assertValueEqual(places.get(1).amount(), 30L, "cell amount");
            helper.assertValueEqual(places.get(1).slot(), 1, "the second cell");
            helper.assertTrue(ItemLocator.places(network, ItemKey.of(Items.DIRT)).isEmpty(), "dirt is nowhere");
            List<Component> lines = ItemLocator.lines(places, Component.literal("Cobblestone"));
            helper.assertValueEqual(lines.size(), 3, "a total and two places");
            helper.assertTrue(lines.get(0).getContents() instanceof TranslatableContents found
                    && found.getKey().equals(ItemLocator.FOUND_KEY) && "80".equals(String.valueOf(found.getArgs()[1])),
                    "the total is 80");
            helper.assertTrue(lines.get(2).getContents() instanceof TranslatableContents cell
                    && cell.getKey().equals(ItemLocator.CELL_LINE_KEY) && "2".equals(String.valueOf(cell.getArgs()[2])),
                    "the drive's line names cell 2");

            TerminalMenu menu = TerminalMenu.server(1, player.getInventory(), terminal, update -> {
            });
            int id = network.contents().keyId(COBBLE);
            List<HighlightRequest> sent = new ArrayList<>();
            Highlights.setObserver((to, request) -> {
                if (to.getUUID().equals(player.getUUID())) {
                    sent.add(request);
                }
            });
            try {
                helper.assertTrue(TerminalGameTests.click(menu, player, TerminalAction.Kind.LOCATE, id), "located");
                helper.assertFalse(TerminalGameTests.click(menu, player, TerminalAction.Kind.LOCATE, id),
                        "rate limited");
            } finally {
                Highlights.setObserver(null);
            }
            helper.assertValueEqual(sent.size(), 1, "one highlight");
            HighlightRequest request = sent.get(0);
            helper.assertValueEqual(request.channel(), Highlights.LOCATE, "the Locate channel");
            helper.assertTrue(request.throughWalls(), "through walls");
            helper.assertValueEqual(request.boxCount(), 2, "two outlines");
            helper.assertTrue(outlined(helper, request, NetworkGameTests.at(3)), "the device is outlined");
            helper.assertTrue(outlined(helper, request, NetworkGameTests.at(2)), "the drive is outlined");
        }).thenSucceed();
    }

    /** A player the owner has not let in locates nothing and gets no outline. */
    private static void refused(GameTestHelper helper) {
        DriveBlockEntity[] drive = new DriveBlockEntity[1];
        TerminalBlockEntity terminal = network(helper, drive);
        ServerPlayer owner = player(helper, "locate_owner");
        ServerPlayer stranger = player(helper, "locate_stranger");
        BlockPos controller = NetworkGameTests.at(1);
        BlockState state = helper.getBlockState(controller);
        state.getBlock().setPlacedBy(helper.getLevel(), helper.absolutePos(controller), state, owner,
                new ItemStack(ControllerContent.CONTROLLER.item().get()));
        helper.startSequence().thenWaitUntil(() -> {
            CellGameTests.mounted(helper, drive[0], 1);
            helper.assertTrue(net(helper, terminal).owner().isOwner(owner.getUUID()), "owner");
        }).thenExecute(() -> fill(helper, drive[0])).thenExecute(() -> {
            NetworkState network = net(helper, terminal);
            int id = network.contents().keyId(COBBLE);
            TerminalMenu menu = TerminalMenu.server(1, stranger.getInventory(), terminal, update -> {
            });
            List<HighlightRequest> sent = new ArrayList<>();
            Highlights.setObserver((to, request) -> sent.add(request));
            try {
                helper.assertFalse(TerminalGameTests.click(menu, stranger, TerminalAction.Kind.LOCATE, id), "refused");
            } finally {
                Highlights.setObserver(null);
            }
            helper.assertTrue(sent.isEmpty(), "no outline for the stranger");
        }).thenSucceed();
    }
}
