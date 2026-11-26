package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.energy.FeBuffer;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.board.BoardContent;
import dev.silencebound.maxtech.storage.board.BoardData;
import dev.silencebound.maxtech.storage.board.BoardService;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.controller.module.DimensionBridge;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.ServiceDeviceBlockEntity;
import dev.silencebound.maxtech.storage.controller.module.ModuleKind;
import dev.silencebound.maxtech.storage.mail.MailContent;
import dev.silencebound.maxtech.storage.mail.MailData;
import dev.silencebound.maxtech.storage.mail.MailService;
import dev.silencebound.maxtech.storage.mail.ServiceBlockEntity;
import dev.silencebound.maxtech.storage.network.NetworkService;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import dev.silencebound.maxtech.storage.signal.SignalContent;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalSource;
import dev.silencebound.maxtech.storage.tool.belt.BeltContent;
import dev.silencebound.maxtech.storage.tool.belt.BeltMarks;
import dev.silencebound.maxtech.storage.tool.belt.BeltRestock;
import dev.silencebound.maxtech.storage.tool.uplink.PickupOverflow;
import dev.silencebound.maxtech.storage.tool.uplink.UplinkContent;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * M6f area C, the dimension bridge: it drains FE from its network while active; a wireless terminal, pocket uplink and
 * restock belt reach their network from another dimension only while it has a bridge, each crossing operation paying
 * the surcharge; mail and request board deliveries to a player in another dimension need the bridge and pay it too.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
// Networks are compared by identity.
@SuppressWarnings("ReferenceEquality")
public final class DimensionBridgeGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final BlockPos CORE = new BlockPos(5, 1, 5);
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final String DRAIN = "bridge_drains_while_active";
    private static final String WIRELESS = "bridge_wireless_across_dimensions";
    private static final String MAIL = "bridge_mail_and_board_across_dimensions";

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(DRAIN, DimensionBridgeGameTests::drain);
        register(WIRELESS, DimensionBridgeGameTests::wireless);
        register(MAIL, DimensionBridgeGameTests::mail);
    }

    private DimensionBridgeGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("dimension_bridge"),
                StorageTestEnvironment.plain());
        StorageTests.test(event, plain, DRAIN, STRUCTURE, 900);
        StorageTests.test(event, plain, WIRELESS, STRUCTURE, 200);
        StorageTests.test(event, plain, MAIL, STRUCTURE, 200);
    }

    /**
     * An active bridge takes {@code drainPerTick} FE per tick of game time from its network, paid every
     * {@link DimensionBridge#DRAIN_INTERVAL_TICKS}; a network that runs dry has its last FE taken and the bridge
     * reports the shortfall.
     */
    private static void drain(GameTestHelper helper) {
        helper.setBlock(CORE, ControllerContent.CONTROLLER.get());
        BlockPos bridge = CORE.east();
        helper.setBlock(bridge, ExpansionContent.DIMENSION_BRIDGE.get());
        long perTick = DimensionBridge.drainPerTick();
        long interval = DimensionBridge.DRAIN_INTERVAL_TICKS;
        long[] start = new long[2];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(
                        NetworkGameTests.controllerEntity(helper, CORE).services(NetworkService.DIMENSION_BRIDGE), 1,
                        "bridge active"))
                .thenExecute(() -> {
                    NetworkGameTests.power(helper, CORE, 100_000);
                    start[0] = NetworkGameTests.controllerEntity(helper, CORE).buffer().stored();
                    start[1] = drained(helper, bridge);
                }).thenIdle((int) interval * 2 + 5).thenExecute(() -> {
                    long drained = drained(helper, bridge) - start[1];
                    long taken = start[0] - NetworkGameTests.controllerEntity(helper, CORE).buffer().stored();
                    helper.assertTrue(perTick > 0, "drain configured");
                    // At least one full interval was paid, at most the ticks that passed plus the one before them.
                    helper.assertTrue(drained >= interval * perTick && drained <= (2 * interval + 5) * perTick,
                            "drained " + drained + " FE in " + (2 * interval + 5) + " ticks at " + perTick + " FE/t");
                    helper.assertValueEqual(drained % perTick, 0L, "whole ticks");
                    helper.assertValueEqual(taken, drained, "taken from the network's buffer");
                    NetworkGameTests.controllerEntity(helper, CORE).buffer().load(perTick);
                }).thenIdle((int) interval + 5).thenExecute(() -> {
                    helper.assertValueEqual(NetworkGameTests.controllerEntity(helper, CORE).buffer().stored(), 0L,
                            "a short network gives what it has");
                }).thenSucceed();
    }

    private static long drained(GameTestHelper helper, BlockPos bridge) {
        return helper.getBlockEntity(bridge, ServiceDeviceBlockEntity.class).bridgeDrained();
    }

    /**
     * A wireless terminal, uplink and belt bound to a network in the overworld, carried in the nether: refused without
     * a bridge, working with one, each crossing costing the surcharge on top of what the same operation costs at home;
     * a network that can pay the operation but not the surcharge refuses it.
     */
    private static void wireless(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        if (nether == null) {
            throw helper.assertionException(Component.literal("no nether"));
        }
        helper.setBlock(CORE, ControllerContent.CONTROLLER.get());
        helper.setBlock(CORE.east(), ExpansionContent.WIRELESS_ACCESS_MOUNT.get());
        ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(CORE.south()),
                CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
        TestStorage.TestDevice drive = NetworkGameTests.device(helper, CORE.south(2));
        ServerPlayer home = researched(helper, helper.getLevel(), "bhome");
        ServerPlayer away = researched(helper, nether, "baway");
        GlobalPos link = GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(CORE));
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(drive.network() == NetworkGameTests.state(helper, CORE), "drive joined");
            helper.assertValueEqual(
                    NetworkGameTests.controllerEntity(helper, CORE).services(NetworkService.WIRELESS_ACCESS), 1,
                    "mount active");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, CORE, 1_000_000);
            NetworkState network = NetworkGameTests.state(helper, CORE);
            NetworkGameTests.insert(network, COBBLE, 200);
            terminal(home, link);
            terminal(away, link);
            helper.assertValueEqual(WirelessTerminalItem.reach(home, home.getMainHandItem()),
                    WirelessTerminalItem.Reach.OK, "at home");
            helper.assertValueEqual(WirelessTerminalItem.reach(away, away.getMainHandItem()),
                    WirelessTerminalItem.Reach.OTHER_DIMENSION, "away without a bridge");
            helper.assertTrue(WirelessTerminalItem.source(away, InteractionHand.MAIN_HAND) == null, "no source away");
            ItemStack uplink = new ItemStack(UplinkContent.POCKET_UPLINK.get());
            uplink.set(ModDataComponents.WIRELESS_LINK.get(), link);
            away.getInventory().setItem(1, uplink);
            fill(away.getInventory());
            helper.assertValueEqual(PickupOverflow.overflow(away, item(nether, away, 4)), 0, "uplink without a bridge");
            away.getInventory().setItem(9, ItemStack.EMPTY);
            helper.assertValueEqual(BeltRestock.restock(away, belt(link)), 0, "belt without a bridge");
            helper.assertTrue(away.getInventory().getItem(9).isEmpty(), "slot 9 still empty");
            helper.setBlock(CORE.west(), ExpansionContent.DIMENSION_BRIDGE.get());
        }).thenWaitUntil(() -> helper.assertValueEqual(
                NetworkGameTests.controllerEntity(helper, CORE).services(NetworkService.DIMENSION_BRIDGE), 1,
                "bridge active")).thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, CORE);
                    FeBuffer buffer = NetworkGameTests.controllerEntity(helper, CORE).buffer();
                    long surcharge = DimensionBridge.surcharge();
                    helper.assertTrue(surcharge > 0, "surcharge configured");
                    helper.assertValueEqual(WirelessTerminalItem.reach(away, away.getMainHandItem()),
                            WirelessTerminalItem.Reach.OK, "away with a bridge");
                    WirelessTerminalSource atHome = source(helper, home);
                    WirelessTerminalSource fromAway = source(helper, away);
                    long before = buffer.stored();
                    helper.assertValueEqual(extract(atHome, 5), 5L, "taken at home");
                    long homeCost = before - buffer.stored();
                    before = buffer.stored();
                    helper.assertValueEqual(extract(fromAway, 5), 5L, "taken from the nether");
                    helper.assertValueEqual(before - buffer.stored(), homeCost + surcharge, "the crossing's surcharge");

                    away.getInventory().setItem(9, new ItemStack(Items.STONE, 64));
                    before = buffer.stored();
                    helper.assertValueEqual(PickupOverflow.overflow(away, item(nether, away, 4)), 4, "uplink stored");
                    helper.assertTrue(before - buffer.stored() >= surcharge, "uplink paid the surcharge");
                    ItemStack belt = belt(link);
                    away.getInventory().setItem(9, ItemStack.EMPTY);
                    before = buffer.stored();
                    helper.assertValueEqual(BeltRestock.restock(away, belt), 8, "belt refilled");
                    helper.assertTrue(before - buffer.stored() >= surcharge, "belt paid the surcharge");

                    // Enough for the operation at home, not for the surcharge on top.
                    buffer.load(homeCost + surcharge - 1);
                    helper.assertValueEqual(extract(fromAway, 5), 0L, "refused without the surcharge");
                    helper.assertValueEqual(buffer.stored(), homeCost + surcharge - 1, "nothing paid");
                    helper.assertValueEqual(extract(atHome, 5), 5L, "home still works");
                    helper.assertValueEqual(network.contents().amount(COBBLE), 200L - 5 - 5 + 4 - 8 - 5,
                            "network contents");
                }).thenSucceed();
    }

    /**
     * Mail to a player whose home is the nether, from a port in the overworld: refused without a bridge (outbox kept),
     * sent with one at the stack cost plus the surcharge. A request board delivering to a poster in the nether: nothing
     * without the bridge and the bridge message, delivered with it.
     */
    private static void mail(GameTestHelper helper) {
        helper.setBlock(CORE, ControllerContent.CONTROLLER.get());
        NetworkGameTests.device(helper, CORE.north());
        // Beside the device, not the core: a lone core has two root links (M7N: a device without a route is offline).
        BlockPos port = CORE.north().east();
        helper.setBlock(port, MailContent.MAIL_PORT.get());
        BlockPos boardPos = CORE.north().west();
        helper.setBlock(boardPos, BoardContent.REQUEST_BOARD.get());
        int suffix = helper.getLevel().getRandom().nextInt(100_000);
        ServerPlayer sender = NetworkGameTests.player(helper.getLevel(), "xfrom_" + suffix);
        ServerPlayer recipient = NetworkGameTests.player(helper.getLevel(), "xto_" + suffix);
        MailData data = MailData.get(helper.getLevel().getServer());
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(service(helper, port).network() == NetworkGameTests.state(helper, CORE), "port joined");
            helper.assertTrue(service(helper, boardPos).network() == NetworkGameTests.state(helper, CORE),
                    "board joined");
        }).thenExecute(() -> {
            data.remember(recipient.getUUID(), recipient.getGameProfile().name(), Level.NETHER);
            NetworkGameTests.power(helper, CORE, 100_000);
            NetworkState network = NetworkGameTests.state(helper, CORE);
            NetworkGameTests.insert(network, COBBLE, 64);
            SimpleContainer outbox = new SimpleContainer(9);
            outbox.setItem(0, new ItemStack(Items.DIRT, 5));
            String name = recipient.getGameProfile().name();
            helper.assertValueEqual(MailService.send(sender, network, name, outbox), MailService.Result.NO_BRIDGE,
                    "no bridge");
            helper.assertValueEqual(outbox.getItem(0).getCount(), 5, "outbox kept");

            ServiceBlockEntity board = service(helper, boardPos);
            recipient.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE));
            helper.assertValueEqual(BoardService.post(recipient, board, 10), BoardService.POSTED_KEY, "posted");
            int id = BoardData.get(helper.getLevel().getServer())
                    .requests(BoardService.team(helper.getLevel().getServer(), board)).get(0).id();
            helper.assertValueEqual(BoardService.fulfil(sender, board, id), 0, "board without a bridge");
            helper.assertValueEqual(BoardService.nothingMoved(helper.getLevel().getServer(), board, id),
                    BoardService.NO_BRIDGE_KEY, "board message");
            helper.setBlock(CORE.west(), ExpansionContent.DIMENSION_BRIDGE.get());
        }).thenWaitUntil(() -> helper.assertValueEqual(
                NetworkGameTests.controllerEntity(helper, CORE).services(NetworkService.DIMENSION_BRIDGE), 1,
                "bridge active")).thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, CORE);
                    FeBuffer buffer = NetworkGameTests.controllerEntity(helper, CORE).buffer();
                    SimpleContainer outbox = new SimpleContainer(9);
                    outbox.setItem(0, new ItemStack(Items.DIRT, 5));
                    long before = buffer.stored();
                    helper.assertValueEqual(
                            MailService.send(sender, network, recipient.getGameProfile().name(), outbox),
                            MailService.Result.SENT, "sent across");
                    helper.assertValueEqual(before - buffer.stored(),
                            StorageServerConfig.MAIL_MU_PER_STACK.getAsLong() + DimensionBridge.surcharge(),
                            "stack cost and surcharge");
                    helper.assertValueEqual(data.size(recipient.getUUID()), 1, "in the inbox");

                    ServiceBlockEntity board = service(helper, boardPos);
                    int id = BoardData.get(helper.getLevel().getServer())
                            .requests(BoardService.team(helper.getLevel().getServer(), board)).get(0).id();
                    helper.assertValueEqual(BoardService.fulfil(sender, board, id), 10, "board across");
                    helper.assertValueEqual(network.contents().amount(COBBLE), 54L, "taken from the network");
                    helper.assertValueEqual(data.size(recipient.getUUID()), 2, "delivered to the inbox");
                    helper.assertTrue(Level.NETHER.equals(data.home(recipient.getUUID())), "home kept");
                    RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, helper.getLevel().registryAccess());
                    MailData loaded = MailData.CODEC.parse(ops, MailData.CODEC.encodeStart(ops, data).getOrThrow())
                            .getOrThrow();
                    helper.assertTrue(Level.NETHER.equals(loaded.home(recipient.getUUID())), "home saved and loaded");
                }).thenSucceed();
    }

    private static ServiceBlockEntity service(GameTestHelper helper, BlockPos pos) {
        return helper.getBlockEntity(pos, ServiceBlockEntity.class);
    }

    /** Puts a charged wireless terminal bound to {@code link} in the player's main hand. */
    private static void terminal(ServerPlayer player, GlobalPos link) {
        ItemStack terminal = new ItemStack(SignalContent.WIRELESS_TERMINAL.get());
        terminal.set(ModDataComponents.WIRELESS_LINK.get(), link);
        terminal.set(ModDataComponents.WIRELESS_ENERGY.get(), WirelessTerminalItem.capacity());
        player.setItemInHand(InteractionHand.MAIN_HAND, terminal);
    }

    private static WirelessTerminalSource source(GameTestHelper helper, ServerPlayer player) {
        WirelessTerminalSource source = WirelessTerminalItem.source(player, InteractionHand.MAIN_HAND);
        if (source == null) {
            throw helper
                    .assertionException(Component.literal("no terminal source for " + player.getGameProfile().name()));
        }
        return source;
    }

    private static long extract(WirelessTerminalSource source, long amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            long moved = source.extract(COBBLE, amount, transaction);
            transaction.commit();
            return moved;
        }
    }

    /** A belt bound to {@code link} that keeps 8 cobblestone in slot 9. */
    private static ItemStack belt(GlobalPos link) {
        ItemStack belt = new ItemStack(BeltContent.RESTOCK_BELT.get());
        belt.set(ModDataComponents.WIRELESS_LINK.get(), link);
        belt.set(BeltContent.MARKS.get(), BeltMarks.EMPTY.with(9, ItemKey.of(Items.COBBLESTONE), 8, 36));
        return belt;
    }

    /** Fills every empty main inventory slot with a full stack of stone. */
    private static void fill(Inventory inventory) {
        for (int slot = 0; slot < 36; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                inventory.setItem(slot, new ItemStack(Items.STONE, 64));
            }
        }
    }

    private static ItemEntity item(ServerLevel level, ServerPlayer player, int count) {
        ItemEntity entity = new ItemEntity(level, player.getX(), player.getY(), player.getZ(),
                new ItemStack(Items.COBBLESTONE, count));
        entity.setNoPickUpDelay();
        return entity;
    }

    /** A player in {@code level} whose team completed Wireless, at the core's coordinates. */
    private static ServerPlayer researched(GameTestHelper helper, ServerLevel level, String name) {
        ServerPlayer player = NetworkGameTests.player(level,
                name + "_" + helper.getLevel().getRandom().nextInt(100_000));
        ResearchTeam team = Research.team(player, true);
        Research.addPoints(level.getServer(), team, 2000);
        for (String node : List.of("port_tier_2", "antennas", "wireless")) {
            helper.assertValueEqual(Research.spendPoints(level.getServer(), team, MaxtechStorage.id(node)),
                    Research.Result.DONE, node);
        }
        player.setPos(Vec3.atCenterOf(helper.absolutePos(CORE)));
        return player;
    }
}
