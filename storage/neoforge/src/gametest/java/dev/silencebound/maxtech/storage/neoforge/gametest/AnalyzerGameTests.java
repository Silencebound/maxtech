package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.authlib.GameProfile;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.highlight.HighlightRequest;
import dev.silencebound.maxtech.lib.highlight.Highlights;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.analyzer.AnalyzerReport;
import dev.silencebound.maxtech.storage.analyzer.AnalyzerTexts;
import dev.silencebound.maxtech.storage.analyzer.NetworkOverview;
import dev.silencebound.maxtech.storage.analyzer.RouteTrace;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerMenu;
import dev.silencebound.maxtech.storage.controller.NetworkTabPayload;
import dev.silencebound.maxtech.storage.network.NetworkContent;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import dev.silencebound.maxtech.storage.subnet.SubnetBlockEntity;
import dev.silencebound.maxtech.storage.subnet.SubnetMode;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
// PLATFORM-LEAK: the load comes from device operations in NeoForge transactions, as in the network.
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The full analyzer and the controller's Network tab (M6d): the route report with each segment's kind, rate and load,
 * the route highlight and the sneak overlay; the tab's overview payload, sent on open and again only on change, with
 * its locate buttons; and a subnet seen from both sides (mode, parent id and the parent's FE).
 *
 * <p>
 * Layout on one row: a controller at x = 1, tier-1 cables 2 and 3, a device at x = 4, and three more devices around
 * cable 3 whose channels (M7N) fill half of the tier-1 cables' eight.
 */
// Networks are compared by identity.
@SuppressWarnings("ReferenceEquality")
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class AnalyzerGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    /** The devices around cable 3. */
    private static final List<BlockPos> MORE = List.of(new BlockPos(3, 1, 0), new BlockPos(3, 1, 2),
            new BlockPos(3, 2, 1));
    private static final String REPORT = "analyzer_route_report";
    private static final String TAB = "controller_network_tab_sync";
    private static final String SUBNET = "analyzer_subnet_both_sides";

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        FUNCTIONS.register(REPORT, () -> StorageTestEnvironment.clearing(AnalyzerGameTests::routeReport));
        FUNCTIONS.register(TAB, () -> StorageTestEnvironment.clearing(AnalyzerGameTests::networkTab));
        FUNCTIONS.register(SUBNET, () -> StorageTestEnvironment.clearing(AnalyzerGameTests::subnetBothSides));
    }

    private AnalyzerGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("analyzer"),
                StorageTestEnvironment.plain());
        test(event, REPORT, plain);
        test(event, TAB, plain);
        test(event, SUBNET, plain);
    }

    private static void test(RegisterGameTestsEvent event, String name, Holder<TestEnvironmentDefinition<?>> env) {
        event.registerTest(MaxtechStorage.id(name),
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                        new TestData<>(env, STRUCTURE, 300, 0, true)));
    }

    /**
     * The layout from the class comment, the devices around cable 3 only with {@code more}; returns the device at x 4.
     */
    private static TestStorage.TestDevice layout(GameTestHelper helper, boolean more) {
        NetworkGameTests.cables(helper, 2, 3, CableContent.STORAGE_CABLE_TIER_1.type().get());
        if (more) {
            more(helper);
        }
        return NetworkGameTests.device(helper, NetworkGameTests.at(4));
    }

    private static void more(GameTestHelper helper) {
        for (BlockPos pos : MORE) {
            NetworkGameTests.device(helper, pos);
        }
    }

    /**
     * The device's route has three segments (device, cable, root link into the controller), each carrying 8 channels;
     * the device's own holds one, the cable and the root link four (50 %); the report names the network, route, fullest
     * segment, root link, segment load and zone; the route highlight draws the segments in their load colour. The
     * analyzer used on the device sends that report and highlight; sneak-used, it sends the overlay with a line per
     * route segment near the player.
     */
    private static void routeReport(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        TestStorage.TestDevice device = layout(helper, true);
        List<Component> chat = new ArrayList<>();
        List<HighlightRequest> highlights = new ArrayList<>();
        ServerPlayer player = listening(helper.getLevel(), "analyzer_report", chat);
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.net(helper, device)).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, 10_000);
        }).thenWaitUntil(
                () -> helper.assertValueEqual(NetworkGameTests.net(helper, device).channelsHeld(), 4, "channels held"))
                .thenExecute(() -> {
                    NetworkState network = NetworkGameTests.net(helper, device);
                    long node = device.nodePos();
                    RouteTrace trace = RouteTrace.of(network, node);
                    helper.assertValueEqual(trace.segments().size(), 3, "segments to the root");
                    helper.assertValueEqual(trace.segments().get(0).kind(), RouteTrace.Kind.DEVICE, "first kind");
                    helper.assertValueEqual(trace.segments().get(1).kind(), RouteTrace.Kind.CABLE, "second kind");
                    helper.assertValueEqual(trace.segments().get(2).kind(), RouteTrace.Kind.ROOT_LINK, "last kind");
                    helper.assertValueEqual(trace.segments().get(2).parent(), helper.absolutePos(controller).asLong(),
                            "the route ends at the controller");
                    for (RouteTrace.Segment segment : trace.segments()) {
                        helper.assertValueEqual(segment.rate(), 8L, "tier-1 channels");
                    }
                    helper.assertValueEqual(trace.segments().get(0).load(), 12, "the device's own segment");
                    helper.assertValueEqual(trace.segments().get(1).load(), 50, "the cable's segment");
                    helper.assertValueEqual(trace.segments().get(2).load(), 50, "the root link");
                    List<String> keys = keys(AnalyzerReport.lines(network, node));
                    for (String key : List.of(AnalyzerTexts.NETWORK_ID, AnalyzerTexts.ROUTE, AnalyzerTexts.BUSIEST,
                            AnalyzerTexts.ROOT_LINK, AnalyzerTexts.SEGMENT_LOAD, AnalyzerTexts.ZONE_SIZE)) {
                        helper.assertTrue(keys.contains(key), "report lacks " + key + ": " + keys);
                    }
                    HighlightRequest route = AnalyzerReport.route(trace, node);
                    helper.assertValueEqual(route.lineCount(), 3, "route lines");
                    helper.assertValueEqual(route.lineColour(0),
                            AnalyzerReport.loadColour(trace.segments().get(0).load()), "busy colour");
                    helper.assertTrue(route.lineColour(0) != AnalyzerReport.loadColour(0), "not the idle colour");

                    Highlights.setObserver((to, request) -> {
                        if (to == player) {
                            highlights.add(request);
                        }
                    });
                    try {
                        ItemStack analyzer = new ItemStack(NetworkContent.NETWORK_ANALYZER.get());
                        player.setItemInHand(InteractionHand.MAIN_HAND, analyzer);
                        BlockPos at = helper.absolutePos(NetworkGameTests.at(4));
                        player.setPos(Vec3.atCenterOf(at.above()));
                        helper.assertValueEqual(use(analyzer, player, at), InteractionResult.SUCCESS, "used");
                        List<String> said = keys(chat);
                        helper.assertTrue(said.contains(AnalyzerTexts.ROUTE), "chat lacks the route: " + said);
                        helper.assertValueEqual(highlights.size(), 1, "one route highlight");
                        helper.assertValueEqual(highlights.get(0).channel(), AnalyzerReport.ROUTE_CHANNEL,
                                "route channel");
                        player.getCooldowns().removeCooldown(player.getCooldowns().getCooldownGroup(analyzer));
                        player.setShiftKeyDown(true);
                        helper.assertValueEqual(use(analyzer, player, at), InteractionResult.SUCCESS, "sneak-used");
                        helper.assertValueEqual(highlights.size(), 2, "the overlay");
                        HighlightRequest overlay = highlights.get(1);
                        helper.assertValueEqual(overlay.channel(), AnalyzerReport.OVERLAY_CHANNEL, "overlay channel");
                        helper.assertTrue(overlay.lineCount() >= 3, overlay.lineCount() + " overlay lines");
                    } finally {
                        Highlights.setObserver(null);
                    }
                }).thenSucceed();
    }

    private static InteractionResult use(ItemStack analyzer, ServerPlayer player, BlockPos at) {
        return analyzer.onItemUseFirst(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false)));
    }

    /**
     * The tab's menu sends an overview when it first refreshes (unpowered: a "no power" warning, no busy segment), then
     * nothing while nothing changed, then a new one after power and three more devices: the fullest segments listed,
     * the warning gone. Its locate button lights the busiest segment for the viewer only and refuses indices it did not
     * send; the payload survives its codec; a stranger may not open an owned core's screen.
     */
    private static void networkTab(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        TestStorage.TestDevice device = layout(helper, false);
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "tab_owner");
        ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "tab_stranger");
        ControllerBlockEntity core = NetworkGameTests.controllerEntity(helper, controller);
        core.setOwner(OwnerRecord.of(owner));
        List<NetworkTabPayload> sent = new ArrayList<>();
        List<HighlightRequest> located = new ArrayList<>();
        ControllerMenu menu = ControllerMenu.server(7, core, owner, sent::add);
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.net(helper, device)).thenExecute(() -> {
            helper.assertTrue(ControllerMenu.mayOpen(owner, core), "owner opens");
            helper.assertFalse(ControllerMenu.mayOpen(stranger, core), "stranger refused");
            menu.broadcastChanges();
            helper.assertValueEqual(sent.size(), 1, "sent on open");
            NetworkOverview first = sent.get(0).overview();
            NetworkState network = NetworkGameTests.net(helper, device);
            helper.assertValueEqual(sent.get(0).containerId(), 7, "container id");
            helper.assertValueEqual(first.summary().networkId(), network.id(), "network id");
            helper.assertValueEqual(first.summary().devices(), network.deviceCount(), "devices");
            helper.assertValueEqual(first.summary().nodes(), 4, "nodes");
            helper.assertValueEqual(first.hotspots().get(0).load(), 12, "one device's channel");
            helper.assertTrue(hasWarning(first, NetworkOverview.WarningKind.NO_POWER), "unpowered warning");
            menu.broadcastChanges();
            helper.assertValueEqual(sent.size(), 1, "not again within the cadence");
        }).thenIdle(ControllerMenu.REFRESH_TICKS + 1).thenExecute(() -> {
            menu.broadcastChanges();
            helper.assertValueEqual(sent.size(), 1, "nothing changed, nothing sent");
            NetworkGameTests.power(helper, controller, 10_000);
            more(helper);
        }).thenWaitUntil(
                () -> helper.assertValueEqual(NetworkGameTests.net(helper, device).channelsHeld(), 4, "channels held"))
                .thenIdle(ControllerMenu.REFRESH_TICKS).thenExecute(() -> {
                    menu.broadcastChanges();
                    helper.assertValueEqual(sent.size(), 2, "sent after the change");
                    NetworkOverview second = sent.get(1).overview();
                    helper.assertFalse(hasWarning(second, NetworkOverview.WarningKind.NO_POWER), "powered now");
                    helper.assertFalse(second.hotspots().isEmpty(), "busiest segments");
                    NetworkOverview.Hotspot top = second.hotspots().get(0);
                    helper.assertValueEqual(top.load(), 50, "hotspot load");
                    helper.assertValueEqual(top.rate(), 8L, "hotspot channels");
                    helper.assertFalse(second.zones().isEmpty(), "zones listed");
                    helper.assertTrue(second.zones().get(0).peakLoad() > 0, "zone peak load");

                    Highlights.setObserver((to, request) -> located.add(request));
                    try {
                        helper.assertTrue(menu.clickMenuButton(owner, ControllerMenu.HOTSPOT_BUTTON),
                                "locate the busiest segment");
                        helper.assertFalse(
                                menu.clickMenuButton(owner,
                                        ControllerMenu.HOTSPOT_BUTTON + NetworkOverview.MAX_HOTSPOTS),
                                "an index never sent");
                    } finally {
                        Highlights.setObserver(null);
                    }
                    helper.assertValueEqual(located.size(), 1, "one locate");
                    helper.assertValueEqual(located.get(0).channel(), Highlights.LOCATE, "locate channel");
                    HighlightRequest box = located.get(0);
                    // The box is a little larger than the block; its centre is the block's.
                    helper.assertValueEqual(
                            BlockPos.containing((box.box(0, 0) + box.box(0, 3)) / 2,
                                    (box.box(0, 1) + box.box(0, 4)) / 2, (box.box(0, 2) + box.box(0, 5)) / 2),
                            BlockPos.of(top.pos()), "located the hotspot");

                    FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
                    NetworkTabPayload.STREAM_CODEC.encode(buffer, sent.get(1));
                    helper.assertValueEqual(NetworkTabPayload.STREAM_CODEC.decode(buffer), sent.get(1),
                            "payload round trip");
                }).thenSucceed();
    }

    /**
     * A merged subnet below the controller network: the parent's overview lists it with the subnet's id and mode; the
     * subnet's overview names its mode, the parent's id and the parent's stored FE; the analyzer's report on the
     * subnet's device says it is a subnet and shows the parent's power.
     */
    private static void subnetBothSides(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 4, CableContent.STORAGE_CABLE_TIER_1.type().get());
        SubnetBlockEntity subnet = SubnetGameTests.subnet(helper, NetworkGameTests.at(5), Direction.WEST);
        NetworkGameTests.cables(helper, 6, 7, CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice inner = NetworkGameTests.device(helper, NetworkGameTests.at(8));
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState parent = NetworkGameTests.state(helper, controller);
            helper.assertTrue(subnet.parent() == parent, "uplink not in the parent yet");
            helper.assertTrue(NetworkGameTests.net(helper, inner) != parent, "the subnet is its own network");
        }).thenExecute(() -> NetworkGameTests.power(helper, controller, 5_000)).thenExecute(() -> {
            NetworkState parent = NetworkGameTests.state(helper, controller);
            NetworkState child = NetworkGameTests.net(helper, inner);
            NetworkOverview above = NetworkOverview.of(parent, NetworkOverview.Cluster.NO_CLUSTER);
            helper.assertValueEqual(above.subnets().size(), 1, "one subnet below");
            helper.assertValueEqual(above.subnets().get(0).networkId(), child.id(), "subnet id");
            helper.assertValueEqual(above.subnets().get(0).mode(), SubnetMode.MERGED.id(), "subnet mode");
            helper.assertValueEqual(above.subnets().get(0).pos(), helper.absolutePos(NetworkGameTests.at(5)).asLong(),
                    "subnet block");
            NetworkOverview below = NetworkOverview.of(child, NetworkOverview.Cluster.NO_CLUSTER);
            helper.assertValueEqual(below.summary().subnetMode(), SubnetMode.MERGED.id(), "mode seen below");
            helper.assertValueEqual(below.summary().parentId(), parent.id(), "parent id");
            helper.assertValueEqual(below.summary().parentStoredFe(), parent.storedEnergy(), "parent FE");
            helper.assertTrue(below.summary().parentStoredFe() >= 5_000, "the parent's power is there");
            List<String> keys = keys(AnalyzerReport.lines(child, inner.nodePos()));
            helper.assertTrue(keys.contains(AnalyzerTexts.SUBNET_OF), "subnet line: " + keys);
            helper.assertTrue(keys.contains(AnalyzerTexts.PARENT_POWER), "parent power line: " + keys);
            helper.assertTrue(keys(AnalyzerReport.lines(parent, helper.absolutePos(controller).asLong()))
                    .contains(AnalyzerTexts.SUBNETS_BELOW), "the parent names its subnets");
        }).thenSucceed();
    }

    private static boolean hasWarning(NetworkOverview overview, NetworkOverview.WarningKind kind) {
        for (NetworkOverview.Warning warning : overview.warnings()) {
            if (warning.kind() == kind) {
                return true;
            }
        }
        return false;
    }

    private static List<String> keys(List<Component> lines) {
        List<String> keys = new ArrayList<>();
        for (Component line : lines) {
            keys.add(line.getContents() instanceof TranslatableContents translatable
                    ? translatable.getKey()
                    : line.getString());
        }
        return keys;
    }

    /** A player without a connection, with plain cooldowns, whose chat lines land in {@code chat}. */
    private static ServerPlayer listening(ServerLevel level, String name, List<Component> chat) {
        return new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), name),
                ClientInformation.createDefault()) {
            @Override
            public void sendSystemMessage(Component message) {
                chat.add(message);
            }

            // Server cooldowns send a packet, and this player has no connection.
            @Override
            protected ItemCooldowns createItemCooldowns() {
                return new ItemCooldowns();
            }
        };
    }
}
