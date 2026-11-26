package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.energy.FeBuffer;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.board.BoardContent;
import dev.silencebound.maxtech.storage.board.BoardData;
import dev.silencebound.maxtech.storage.board.BoardMenu;
import dev.silencebound.maxtech.storage.board.BoardService;
import dev.silencebound.maxtech.storage.board.BoardView;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.module.LedgerBook;
import dev.silencebound.maxtech.storage.controller.recorder.LedgerRecorder;
import dev.silencebound.maxtech.storage.controller.recorder.RecordsView;
import dev.silencebound.maxtech.storage.mail.MailContent;
import dev.silencebound.maxtech.storage.mail.MailData;
import dev.silencebound.maxtech.storage.mail.MailService;
import dev.silencebound.maxtech.storage.mail.ServiceBlockEntity;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The network services of M6e area G: the controller's ledger (built in since M7R item 3) records what went in and out
 * per day and player but not a device joining with its contents; the mail port sends for FE per stack, refuses unknown
 * names, full inboxes and an empty buffer without losing anything, and the recipient collects into the network; the
 * request board posts, fulfils into the poster's inbox as far as stored, shows its list in the menu and takes requests
 * down for the poster only.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
// Networks are compared by identity.
@SuppressWarnings("ReferenceEquality")
public final class NetworkServicesGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey DIRT = ItemKey.of(Items.DIRT);
    private static final String LEDGER = "services_ledger_records_moves";
    private static final String MAIL = "services_mail_send_and_collect";
    private static final String MAIL_REFUSED = "services_mail_refusals_keep_items";
    private static final String BOARD = "services_board_post_fulfil_cancel";

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(LEDGER, NetworkServicesGameTests::ledger);
        register(MAIL, NetworkServicesGameTests::mail);
        register(MAIL_REFUSED, NetworkServicesGameTests::mailRefused);
        register(BOARD, NetworkServicesGameTests::board);
    }

    private NetworkServicesGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("network_services"),
                StorageTestEnvironment.plain());
        for (String name : List.of(LEDGER, MAIL, MAIL_REFUSED, BOARD)) {
            StorageTests.test(event, plain, name, STRUCTURE, 400);
        }
    }

    private static ServiceBlockEntity service(GameTestHelper helper, BlockPos pos) {
        return helper.getBlockEntity(pos, ServiceBlockEntity.class);
    }

    private static long extract(NetworkState network, StackKey key, long amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            long moved = network.extract(key, amount, transaction);
            transaction.commit();
            return moved;
        }
    }

    private static LedgerRecorder ledger(GameTestHelper helper, BlockPos core) {
        return NetworkGameTests.controllerEntity(helper, core).recorders().ledger();
    }

    private static long[] today(GameTestHelper helper, LedgerRecorder ledger, StackKey key) {
        return today(helper, ledger, LedgerBook.AUTOMATION, key);
    }

    private static long[] today(GameTestHelper helper, LedgerRecorder ledger, UUID player, StackKey key) {
        return today(helper, ledger.book(), player, key);
    }

    private static long[] today(GameTestHelper helper, LedgerBook<StackKey> book, UUID player, StackKey key) {
        return book.dayTotals(player, key, Math.floorDiv(helper.getLevel().getGameTime(), LedgerBook.TICKS_PER_DAY));
    }

    /**
     * 64 cobblestone in and 10 out with no player show as +64/-10 on today's automation row; Alice's 20 in and Bob's 7
     * out (one rolled back, which does not count) get rows of their own. A second device joining through a new cable
     * with 500 dirt adds nothing to the ledger, though the network now holds the dirt, while 3 cobblestone Bob takes in
     * that very tick do show. The view's filters are everyone, then each player busiest first, named from the mail's
     * name table. The book survives a save and load of the controller, with no ledger module anywhere.
     */
    private static void ledger(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        TestStorage.TestDevice device = NetworkGameTests.device(helper, core.north());
        BlockPos module = core;
        BlockPos gap = core.north(2);
        BlockPos far = core.north(3);
        ServerPlayer alice = NetworkGameTests.player(helper.getLevel(), "alice");
        ServerPlayer bob = NetworkGameTests.player(helper.getLevel(), "bob");
        MailData.get(helper.getLevel().getServer()).remember(alice.getUUID(), "alice");
        MailData.get(helper.getLevel().getServer()).remember(bob.getUUID(), "bob");
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, core);
            helper.assertTrue(device.network() == network, "network formed");
            helper.assertTrue(ledger(helper, core).bound() == network, "ledger listening");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, core, 50_000);
            NetworkState network = NetworkGameTests.state(helper, core);
            helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 64), 64L, "insert");
            helper.assertValueEqual(extract(network, COBBLE, 10), 10L, "extract");
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(network.insert(COBBLE, 20, transaction, alice), 20L, "alice stores");
                transaction.commit();
            }
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(network.extract(COBBLE, 7, transaction, bob), 7L, "bob takes");
                transaction.commit();
            }
            try (Transaction transaction = Transaction.openRoot()) {
                network.extract(COBBLE, 30, transaction, bob);
            }
            TestStorage.TestDevice other = NetworkGameTests.device(helper, far);
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(other.provider.insert(DIRT, 500, transaction), 500L, "dirt preloaded");
                transaction.commit();
            }
        }).thenIdle(2).thenExecute(() -> {
            long[] cobble = today(helper, ledger(helper, module), COBBLE);
            helper.assertValueEqual(cobble[0], 64L, "cobblestone in");
            helper.assertValueEqual(cobble[1], 10L, "cobblestone out");
            LedgerRecorder recorder = ledger(helper, module);
            helper.assertValueEqual(today(helper, recorder, alice.getUUID(), COBBLE)[0], 20L, "alice's row");
            helper.assertValueEqual(today(helper, recorder, bob.getUUID(), COBBLE)[1], 7L,
                    "bob's row, the rolled-back take left out");
            helper.assertValueEqual(today(helper, recorder, alice.getUUID(), COBBLE)[1], 0L, "alice took nothing");
            ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(gap),
                    CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
            try (Transaction transaction = Transaction.openRoot()) {
                NetworkGameTests.state(helper, core).extract(COBBLE, 3, transaction, bob);
                transaction.commit();
            }
        }).thenWaitUntil(() -> helper.assertValueEqual(NetworkGameTests.state(helper, core).contents().amount(DIRT),
                500L, "dirt joined")).thenIdle(2).thenExecute(() -> {
                    LedgerRecorder recorder = ledger(helper, module);
                    helper.assertValueEqual(today(helper, recorder, DIRT)[0], 0L, "a joining device is no move");
                    helper.assertValueEqual(today(helper, recorder, bob.getUUID(), COBBLE)[1], 10L,
                            "a take in the tick a device joined counts");
                    NetworkGameTests.power(helper, core, 50_000);
                    helper.assertValueEqual(extract(NetworkGameTests.state(helper, core), DIRT, 5), 5L, "take dirt");
                }).thenIdle(2).thenExecute(() -> {
                    LedgerRecorder recorder = ledger(helper, module);
                    helper.assertValueEqual(today(helper, recorder, DIRT)[1], 5L, "dirt out");
                    helper.assertValueEqual(recorder.totals(10, null).get(0).key(), COBBLE, "busiest first");
                    List<RecordsView.LedgerView> views = RecordsView
                            .build(NetworkGameTests.controllerEntity(helper, core), alice, RecordsView.LEDGER).ledger()
                            .views();
                    helper.assertValueEqual(views.size(), 4, "everyone, automation, alice, bob");
                    helper.assertTrue(views.get(0).player() == null, "everyone first");
                    helper.assertValueEqual(views.get(0).rows().get(0).in(), 84L, "everyone's cobblestone in");
                    helper.assertValueEqual(views.get(0).rows().get(0).out(), 20L, "everyone's cobblestone out");
                    helper.assertTrue(views.get(1).automation(), "automation is busiest");
                    helper.assertValueEqual(views.get(2).name(), "alice", "alice named");
                    helper.assertValueEqual(views.get(3).rows().size(), 1, "bob moved cobblestone only");
                    ControllerBlockEntity entity = NetworkGameTests.controllerEntity(helper, core);
                    var saved = entity.saveWithoutMetadata(helper.getLevel().registryAccess());
                    ControllerBlockEntity copy = new ControllerBlockEntity(entity.getBlockPos(),
                            entity.getBlockState());
                    copy.loadCustomOnly(net.minecraft.world.level.storage.TagValueInput.create(
                            net.minecraft.util.ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
                    LedgerBook<StackKey> book = copy.recorders().ledger().saved();
                    long[] cobble = today(helper, book, LedgerBook.AUTOMATION, COBBLE);
                    helper.assertValueEqual(cobble[0], 64L, "in after a reload");
                    helper.assertValueEqual(cobble[1], 10L, "out after a reload");
                    helper.assertValueEqual(today(helper, book, bob.getUUID(), COBBLE)[1], 10L,
                            "bob's row after a reload");
                }).thenSucceed();
    }

    /** A core with a test device holding storage and a mail port beside it. */
    private static BlockPos mailNetwork(GameTestHelper helper, BlockPos core) {
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        NetworkGameTests.device(helper, core.north());
        BlockPos port = core.east();
        helper.setBlock(port, MailContent.MAIL_PORT.get());
        return port;
    }

    /**
     * Two stacks sent cost two stacks' FE and land in the recipient's inbox, the outbox empties; the recipient collects
     * them into the port's network and their inbox is empty again.
     */
    private static void mail(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        BlockPos port = mailNetwork(helper, core);
        ServerPlayer sender = NetworkGameTests.player(helper.getLevel(), "msender");
        ServerPlayer recipient = NetworkGameTests.player(helper.getLevel(),
                "mto_" + helper.getLevel().getRandom().nextInt(100_000));
        helper.startSequence().thenWaitUntil(() -> helper
                .assertTrue(service(helper, port).network() == NetworkGameTests.state(helper, core), "port joined"))
                .thenExecute(() -> {
                    MailService.remember(recipient);
                    NetworkGameTests.power(helper, core, 50_000);
                    NetworkState network = NetworkGameTests.state(helper, core);
                    FeBuffer buffer = network.energyBuffer();
                    if (buffer == null) {
                        throw helper.assertionException(Component.literal("no energy buffer"));
                    }
                    long before = buffer.stored();
                    SimpleContainer outbox = new SimpleContainer(9);
                    outbox.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
                    outbox.setItem(4, new ItemStack(Items.DIRT, 3));
                    String name = recipient.getGameProfile().name();
                    helper.assertValueEqual(
                            MailService.send(sender, network, name.toUpperCase(java.util.Locale.ROOT), outbox),
                            MailService.Result.SENT, "sent, any case");
                    helper.assertTrue(outbox.isEmpty(), "outbox emptied");
                    helper.assertValueEqual(before - buffer.stored(),
                            2 * StorageServerConfig.MAIL_MU_PER_STACK.getAsLong(), "FE per stack");
                    MailData data = MailData.get(helper.getLevel().getServer());
                    helper.assertValueEqual(data.size(recipient.getUUID()), 2, "two stacks waiting");
                    helper.assertValueEqual(MailService.collect(recipient, network), 2, "collected");
                    helper.assertValueEqual(data.size(recipient.getUUID()), 0, "inbox empty");
                    helper.assertValueEqual(network.contents().amount(COBBLE), 64L, "cobblestone in the network");
                    helper.assertValueEqual(network.contents().amount(DIRT), 3L, "dirt in the network");
                }).thenSucceed();
    }

    /**
     * Refusals keep the outbox: an unknown name, the sender's own name, an empty FE buffer and a full inbox; a send
     * that fills the inbox midway sends what fits and keeps the rest.
     */
    private static void mailRefused(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        BlockPos port = mailNetwork(helper, core);
        int suffix = helper.getLevel().getRandom().nextInt(100_000);
        ServerPlayer sender = NetworkGameTests.player(helper.getLevel(), "mfrom_" + suffix);
        ServerPlayer recipient = NetworkGameTests.player(helper.getLevel(), "mfull_" + suffix);
        helper.startSequence().thenWaitUntil(() -> helper
                .assertTrue(service(helper, port).network() == NetworkGameTests.state(helper, core), "port joined"))
                .thenExecute(() -> {
                    MailService.remember(sender);
                    MailService.remember(recipient);
                    NetworkState network = NetworkGameTests.state(helper, core);
                    SimpleContainer outbox = new SimpleContainer(9);
                    outbox.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
                    outbox.setItem(1, new ItemStack(Items.COBBLESTONE, 64));
                    String name = recipient.getGameProfile().name();
                    helper.assertValueEqual(MailService.send(sender, network, "nob_" + suffix, outbox),
                            MailService.Result.UNKNOWN, "unknown name");
                    helper.assertValueEqual(MailService.send(sender, network, sender.getGameProfile().name(), outbox),
                            MailService.Result.UNKNOWN, "own name");
                    helper.assertValueEqual(MailService.send(sender, network, name, outbox),
                            MailService.Result.NO_POWER, "no FE");
                    helper.assertValueEqual(outbox.getItem(0).getCount() + outbox.getItem(1).getCount(), 128,
                            "outbox kept");
                    NetworkGameTests.power(helper, core, 50_000);
                    MailData data = MailData.get(helper.getLevel().getServer());
                    int capacity = StorageServerConfig.MAIL_INBOX_STACKS.getAsInt();
                    while (data.room(recipient.getUUID(), capacity) > 1) {
                        data.deliver(recipient.getUUID(), new ItemStack(Items.STONE), capacity);
                    }
                    helper.assertValueEqual(MailService.send(sender, network, name, outbox), MailService.Result.PARTLY,
                            "one stack fits");
                    helper.assertValueEqual(outbox.getItem(1).getCount(), 64, "second stack kept");
                    helper.assertValueEqual(MailService.send(sender, network, name, outbox), MailService.Result.FULL,
                            "full inbox");
                    helper.assertValueEqual(outbox.getItem(1).getCount(), 64, "still kept");
                    helper.assertValueEqual(data.size(recipient.getUUID()), capacity, "inbox at capacity");
                }).thenSucceed();
    }

    /**
     * A poster asks for 100 cobblestone; the network holds 70: Fulfil mails 70 (two stacks) and leaves 30 wanted, the
     * menu lists the request as the poster's own; 40 more stored, a second Fulfil completes it and it leaves the board.
     * A stranger cannot take a new request down; its poster can.
     */
    private static void board(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        TestStorage.TestDevice device = NetworkGameTests.device(helper, core.north());
        BlockPos boardPos = core.east();
        helper.setBlock(boardPos, BoardContent.REQUEST_BOARD.get());
        int suffix = helper.getLevel().getRandom().nextInt(100_000);
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "bown_" + suffix);
        ServerPlayer poster = NetworkGameTests.player(helper.getLevel(), "bpost_" + suffix);
        ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "bstr_" + suffix);
        // The owner trusts the poster: both have full access, the stranger none. Owned before the network forms.
        OwnerRecord record = OwnerRecord.of(owner).withTrusted(poster.getUUID());
        service(helper, boardPos).setOwner(record);
        NetworkGameTests.controllerEntity(helper, core).setOwner(record);
        device.setOwner(record);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        service(helper, boardPos).network() == NetworkGameTests.state(helper, core), "board joined"))
                .thenExecute(() -> {
                    ServiceBlockEntity board = service(helper, boardPos);
                    NetworkGameTests.power(helper, core, 50_000);
                    NetworkState network = NetworkGameTests.state(helper, core);
                    helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 70), 70L, "stored");
                    poster.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE, 3));
                    helper.assertValueEqual(BoardService.post(poster, board, 100), BoardService.POSTED_KEY, "posted");
                    BoardData data = BoardData.get(helper.getLevel().getServer());
                    List<BoardData.Request> requests = data
                            .requests(BoardService.team(helper.getLevel().getServer(), board));
                    helper.assertValueEqual(requests.size(), 1, "one request");
                    int id = requests.get(0).id();
                    BoardMenu menu = BoardMenu.server(3, poster, board);
                    menu.broadcastChanges();
                    List<BoardView.Entry> entries = menu.entries();
                    helper.assertValueEqual(entries.size(), 1, "listed");
                    helper.assertTrue(entries.get(0).mine(), "the poster's own");
                    helper.assertTrue(menu.clickMenuButton(poster, BoardMenu.FULFIL + id), "fulfil clicked");
                    helper.assertValueEqual(network.contents().amount(COBBLE), 0L, "all 70 taken");
                    MailData mail = MailData.get(helper.getLevel().getServer());
                    helper.assertValueEqual(mail.size(poster.getUUID()), 2, "70 as two stacks");
                    BoardData.Request left = data.request(BoardService.team(helper.getLevel().getServer(), board), id);
                    helper.assertTrue(left != null && left.remaining() == 30, "30 still wanted");
                    menu.broadcastChanges();
                    helper.assertValueEqual(menu.entries().get(0).delivered(), 70, "the list followed");
                    NetworkGameTests.insert(network, COBBLE, 40);
                    helper.assertValueEqual(BoardService.fulfil(owner, board, id), 30, "the rest");
                    helper.assertValueEqual(network.contents().amount(COBBLE), 10L, "10 left over");
                    helper.assertTrue(data.request(BoardService.team(helper.getLevel().getServer(), board), id) == null,
                            "complete requests leave");
                    helper.assertValueEqual(BoardService.post(poster, board, 1), BoardService.POSTED_KEY, "again");
                    int second = data.requests(BoardService.team(helper.getLevel().getServer(), board)).get(0).id();
                    helper.assertFalse(BoardService.cancel(stranger, board, second), "a stranger cannot");
                    helper.assertValueEqual(BoardService.fulfil(stranger, board, second), 0, "nor fulfil");
                    helper.assertTrue(BoardService.cancel(poster, board, second), "the poster can");
                    poster.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    helper.assertValueEqual(BoardService.post(poster, board, 1), BoardService.NOTHING_HELD_KEY,
                            "empty hand");
                }).thenSucceed();
    }
}
