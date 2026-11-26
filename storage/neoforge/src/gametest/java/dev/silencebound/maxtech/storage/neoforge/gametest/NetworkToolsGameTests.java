package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.highlight.HighlightRequest;
import dev.silencebound.maxtech.lib.highlight.Highlights;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import dev.silencebound.maxtech.storage.network.ZoneBorders;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.tool.goggles.GogglesContent;
import dev.silencebound.maxtech.storage.tool.goggles.GogglesView;
import dev.silencebound.maxtech.storage.tool.goggles.NetworkGogglesItem;
import dev.silencebound.maxtech.storage.tool.tracepen.TracePenContent;
import dev.silencebound.maxtech.storage.tool.tracepen.TracePenItem;
import dev.silencebound.maxtech.storage.tool.zone.ZonePainterItem;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
// PLATFORM-LEAK: the goggles charge through NeoForge's item energy capability, as chargers would.
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Seeing the network (M6e area B): the zone painter's borders and how routing honours them, the network goggles'
 * readout, overlay and FE use, and the trace pen's route between two blocks.
 *
 * <p>
 * Layout on one row: a controller at x = 1, tier-1 cables from x = 2 to 6, a device at x = 7, and for the trace a
 * second device beside the cable at x = 4.
 */
// Networks are compared by identity.
@SuppressWarnings("ReferenceEquality")
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class NetworkToolsGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final String PAINT = "network_tools_zone_borders";
    private static final String CAP = "network_tools_border_past_cap";
    private static final String GOGGLES = "network_tools_goggles";
    private static final String TRACE = "network_tools_trace_pen";

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function(PAINT, StorageTestEnvironment.clearing(NetworkToolsGameTests::paintBorders));
        StorageTests.function(CAP, StorageTestEnvironment.clearing(NetworkToolsGameTests::borderPastCap));
        StorageTests.function(GOGGLES, StorageTestEnvironment.clearing(NetworkToolsGameTests::goggles));
        StorageTests.function(TRACE, StorageTestEnvironment.clearing(NetworkToolsGameTests::trace));
    }

    private NetworkToolsGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("network_tools"),
                StorageTestEnvironment.plain());
        StorageTests.test(event, plain, PAINT, STRUCTURE, 300);
        StorageTests.test(event, plain, GOGGLES, STRUCTURE, 300);
        StorageTests.test(event, plain, TRACE, STRUCTURE, 300);
        StorageTests.test(event, event.registerEnvironment(MaxtechStorage.id("network_tools_cap"),
                new StorageTestEnvironment("caps.zonesPerTeam", "1", false)), CAP, STRUCTURE, 300);
    }

    /** The row from the class comment; returns the device at x = 7. */
    private static TestStorage.TestDevice row(GameTestHelper helper) {
        NetworkGameTests.cables(helper, 2, 6, CableContent.STORAGE_CABLE_TIER_1.type().get());
        return NetworkGameTests.device(helper, NetworkGameTests.at(7));
    }

    private static long node(GameTestHelper helper, int x) {
        return helper.absolutePos(NetworkGameTests.at(x)).asLong();
    }

    /**
     * Painting the cable at x = 4 makes it start a second zone that the cables and device behind it join; painting it
     * again clears it and the network is one zone again. The mark survives the saved data's codec. A stranger may not
     * paint an owned network's cable, the device is no cable, a cable in no network is refused, and breaking a painted
     * cable drops its mark.
     */
    private static void paintBorders(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        TestStorage.TestDevice device = row(helper);
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "painter");
        ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "painter_stranger");
        own(helper, controller, device, owner);
        BlockPos border = helper.absolutePos(NetworkGameTests.at(4));
        int[] rebuilds = new int[1];
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.net(helper, device)).thenExecute(() -> {
            NetworkState network = NetworkGameTests.net(helper, device);
            helper.assertValueEqual(network.zoneCount(), 1, "one zone before painting");
            helper.assertValueEqual(paint(helper, stranger, border), ZonePainterItem.Outcome.DENIED, "stranger");
            helper.assertValueEqual(paint(helper, owner, helper.absolutePos(NetworkGameTests.at(7))),
                    ZonePainterItem.Outcome.NOT_CABLE, "a device is no cable");
            helper.assertValueEqual(
                    ZonePainterItem.paint(helper.getLevel(), stranger, border, MaxtechStorage.id("no_such_network")),
                    ZonePainterItem.Outcome.NO_NETWORK, "a cable outside any network is refused");
            helper.assertFalse(ZoneBorders.of(helper.getLevel()).isBorder(border.asLong()), "refusals paint nothing");
            rebuilds[0] = network.rebuilds();
            helper.assertValueEqual(paint(helper, owner, border), ZonePainterItem.Outcome.PAINTED, "painted");
            helper.assertTrue(ZoneBorders.of(helper.getLevel()).isBorder(border.asLong()), "marked");
            Tag saved = ZoneBorders.TYPE.codec().encodeStart(NbtOps.INSTANCE, ZoneBorders.of(helper.getLevel()))
                    .getOrThrow();
            helper.assertTrue(
                    ZoneBorders.TYPE.codec().parse(NbtOps.INSTANCE, saved).getOrThrow().isBorder(border.asLong()),
                    "the mark survives saving");
        }).thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.net(helper, device);
            helper.assertTrue(network.rebuilds() > rebuilds[0], "no rebuild after painting");
        }).thenExecute(() -> {
            NetworkState network = NetworkGameTests.net(helper, device);
            helper.assertTrue(network.isZoneBorder(border.asLong()), "the network sees the border");
            helper.assertValueEqual(network.zoneCount(), 2, "two zones");
            int behind = network.zone(border.asLong());
            helper.assertTrue(behind != network.zone(node(helper, 3)), "the border starts a zone");
            helper.assertValueEqual(network.zone(node(helper, 5)), behind, "the next cable joins it");
            helper.assertValueEqual(network.zone(device.nodePos()), behind, "the device joins it");
            helper.assertValueEqual(network.zone(node(helper, 2)), network.zone(node(helper, 3)),
                    "the controller side stays one zone");
            HighlightRequest shown = ZonePainterItem.borders(helper.getLevel(), border);
            helper.assertValueEqual(shown.boxCount(), 1, "the painter shows the border");
            rebuilds[0] = network.rebuilds();
            helper.assertValueEqual(paint(helper, owner, border), ZonePainterItem.Outcome.CLEARED, "cleared");
        }).thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.net(helper, device);
            helper.assertTrue(network.rebuilds() > rebuilds[0], "no rebuild after clearing");
        }).thenExecute(() -> {
            NetworkState network = NetworkGameTests.net(helper, device);
            helper.assertValueEqual(network.zoneCount(), 1, "one zone again");
            helper.assertValueEqual(paint(helper, owner, border), ZonePainterItem.Outcome.PAINTED, "painted again");
            helper.setBlock(NetworkGameTests.at(4), Blocks.AIR);
        }).thenWaitUntil(() -> helper.assertFalse(ZoneBorders.of(helper.getLevel()).isBorder(border.asLong()),
                "a broken cable keeps its mark")).thenSucceed();
    }

    /**
     * With a zone cap of one per team, a painted border on an owned network finds no room for a zone of its own and
     * stays in its parent's zone: the device keeps its route.
     */
    private static void borderPastCap(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        TestStorage.TestDevice device = row(helper);
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "cap_painter");
        own(helper, controller, device, owner);
        BlockPos border = helper.absolutePos(NetworkGameTests.at(4));
        int[] rebuilds = new int[1];
        helper.startSequence().thenWaitUntil(() -> helper
                .assertTrue(NetworkGameTests.net(helper, device).capTeam() != null, "the network has no cap team yet"))
                .thenExecute(() -> {
                    NetworkState network = NetworkGameTests.net(helper, device);
                    rebuilds[0] = network.rebuilds();
                    helper.assertValueEqual(paint(helper, owner, border), ZonePainterItem.Outcome.PAINTED, "painted");
                }).thenWaitUntil(() -> helper.assertTrue(NetworkGameTests.net(helper, device).rebuilds() > rebuilds[0],
                        "no rebuild after painting"))
                .thenExecute(() -> {
                    NetworkState network = NetworkGameTests.net(helper, device);
                    helper.assertValueEqual(network.zoneCount(), 1, "no zone past the cap");
                    helper.assertTrue(network.zone(device.nodePos()) >= 0, "the device kept its zone");
                    helper.assertTrue(network.routeParent(device.nodePos()) != Long.MIN_VALUE,
                            "the device kept its route");
                }).thenSucceed();
    }

    /**
     * Goggles are worn on the head and charge through the item energy capability (refusing extraction). Worn while
     * looking at a cable, one readout pays a second's FE, sends the zone readout and the overlay (with an amber box on
     * a painted border); the next one within the refresh pays again but sends no new overlay. Empty goggles show
     * nothing and spend nothing; looking at the sky costs nothing.
     */
    private static void goggles(GameTestHelper helper) {
        NetworkGameTests.controller(helper, 1);
        TestStorage.TestDevice device = row(helper);
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "goggles_wearer");
        BlockPos border = helper.absolutePos(NetworkGameTests.at(5));
        List<HighlightRequest> sent = new ArrayList<>();
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.net(helper, device)).thenExecute(() -> {
            ZonePainterItem.paint(helper.getLevel(), player, border, StorageNetworks.ID);
        }).thenWaitUntil(() -> helper.assertTrue(NetworkGameTests.net(helper, device).isZoneBorder(border.asLong())
                && NetworkGameTests.net(helper, device).zoneCount() == 2, "border not built yet")).thenExecute(() -> {
                    ItemStack goggles = new ItemStack(GogglesContent.NETWORK_GOGGLES.get());
                    Equippable equippable = goggles.get(DataComponents.EQUIPPABLE);
                    helper.assertTrue(equippable != null && equippable.slot() == EquipmentSlot.HEAD,
                            "worn on the head");
                    player.setItemSlot(EquipmentSlot.HEAD, goggles);
                    EnergyHandler battery = ItemAccess.forPlayerSlot(player, 39)
                            .getCapability(Capabilities.Energy.ITEM);
                    if (battery == null) {
                        throw helper.assertionException(Component.literal("no item energy capability on the goggles"));
                    }
                    int cost = NetworkGogglesItem.energyPerSecond();
                    helper.assertTrue(cost > 0, "the goggles cost FE");
                    BlockPos cable = helper.absolutePos(NetworkGameTests.at(3));
                    look(player, cable);
                    NetworkGogglesItem.forgetShown();
                    Highlights.setObserver((to, request) -> {
                        if (to == player) {
                            sent.add(request);
                        }
                    });
                    try {
                        ItemStack worn = player.getItemBySlot(EquipmentSlot.HEAD);
                        helper.assertFalse(NetworkGogglesItem.show(worn, helper.getLevel(), player), "shown uncharged");
                        helper.assertTrue(sent.isEmpty(), "an overlay without power");
                        try (Transaction transaction = Transaction.openRoot()) {
                            helper.assertValueEqual(battery.insert(5 * cost, transaction), 5 * cost, "charged");
                            helper.assertValueEqual(battery.extract(cost, transaction), 0, "drawn out");
                            transaction.commit();
                        }
                        worn = player.getItemBySlot(EquipmentSlot.HEAD);
                        helper.assertTrue(NetworkGogglesItem.show(worn, helper.getLevel(), player), "shown");
                        helper.assertValueEqual(NetworkGogglesItem.energy(worn), 4 * cost, "one second paid");
                        helper.assertValueEqual(sent.size(), 1, "one overlay");
                        HighlightRequest overlay = sent.get(0);
                        helper.assertValueEqual(overlay.channel(), GogglesView.CHANNEL, "goggles channel");
                        helper.assertTrue(overlay.lineCount() >= 6, overlay.lineCount() + " route lines");
                        helper.assertTrue(hasBox(overlay, 0xFFFFA000, border), "the painted border's amber box");
                        helper.assertTrue(NetworkGogglesItem.show(worn, helper.getLevel(), player), "shown again");
                        helper.assertValueEqual(NetworkGogglesItem.energy(worn), 3 * cost, "another second paid");
                        helper.assertValueEqual(sent.size(), 1, "no new overlay before the refresh");
                        NetworkState network = NetworkGameTests.net(helper, device);
                        Component readout = GogglesView.readout(network, cable.asLong());
                        helper.assertValueEqual(key(readout), "info." + MaxtechStorage.MOD_ID + ".goggles.readout",
                                "readout line");
                        player.setXRot(-90.0F);
                        helper.assertFalse(NetworkGogglesItem.show(worn, helper.getLevel(), player), "the sky");
                        helper.assertValueEqual(NetworkGogglesItem.energy(worn), 3 * cost, "the sky is free");
                    } finally {
                        Highlights.setObserver(null);
                        NetworkGogglesItem.forgetShown();
                    }
                }).thenSucceed();
    }

    /**
     * The pen marks the far device (x = 7) and traces to a device beside the cable at x = 4: up 7, 6, 5 to the shared
     * cable, then down from the second device, four segments, lit as four lines. Air is no storage block, a lone cable
     * is another network, and sneak-use in the air drops the start.
     */
    private static void trace(GameTestHelper helper) {
        NetworkGameTests.controller(helper, 1);
        TestStorage.TestDevice far = row(helper);
        TestStorage.TestDevice near = NetworkGameTests.device(helper, NetworkGameTests.at(4).north());
        NetworkGameTests.cables(helper, 20, 20, CableContent.STORAGE_CABLE_TIER_1.type().get());
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "tracer");
        List<HighlightRequest> sent = new ArrayList<>();
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.net(helper, far);
            helper.assertTrue(NetworkGameTests.net(helper, near) == network, "one network");
            helper.assertTrue(network.routeParent(near.nodePos()) != Long.MIN_VALUE, "routes not built yet");
            NetworkGameTests.state(helper, NetworkGameTests.at(20));
        }).thenExecute(() -> {
            ItemStack pen = new ItemStack(TracePenContent.TRACE_PEN.get());
            List<Component> lines = new ArrayList<>();
            BlockPos start = helper.absolutePos(NetworkGameTests.at(7));
            BlockPos end = helper.absolutePos(NetworkGameTests.at(4).north());
            helper.assertValueEqual(TracePenItem.use(pen, helper.getLevel(), player,
                    helper.absolutePos(NetworkGameTests.at(10)), false, lines), TracePenItem.Outcome.NOT_STORAGE,
                    "air");
            helper.assertValueEqual(TracePenItem.use(pen, helper.getLevel(), player, start, true, lines),
                    TracePenItem.Outcome.STARTED, "start marked");
            GlobalPos marked = pen.get(ModDataComponents.TRACE_START.get());
            helper.assertTrue(marked != null && marked.pos().equals(start), "start stored");
            helper.assertValueEqual(TracePenItem.use(pen, helper.getLevel(), player,
                    helper.absolutePos(NetworkGameTests.at(20)), false, lines), TracePenItem.Outcome.OTHER_NETWORK,
                    "a lone cable");
            Highlights.setObserver((to, request) -> {
                if (to == player) {
                    sent.add(request);
                }
            });
            try {
                helper.assertValueEqual(TracePenItem.use(pen, helper.getLevel(), player, end, false, lines),
                        TracePenItem.Outcome.TRACED, "traced");
            } finally {
                Highlights.setObserver(null);
            }
            helper.assertTrue(!lines.isEmpty() && lines.get(0).getContents() instanceof TranslatableContents first
                    && first.getKey().equals(TracePenContent.TRACE_KEY), "trace line first: " + lines);
            TranslatableContents first = (TranslatableContents) lines.get(0).getContents();
            helper.assertValueEqual(String.valueOf(first.getArgs()[2]), "4", "segments");
            helper.assertValueEqual(sent.size(), 1, "one highlight");
            helper.assertValueEqual(sent.get(0).channel(), TracePenItem.CHANNEL, "trace channel");
            helper.assertValueEqual(sent.get(0).lineCount(), 4, "trace lines");
            helper.assertValueEqual(sent.get(0).boxCount(), 3, "two ends and the meeting cable");
            player.setShiftKeyDown(true);
            player.setItemInHand(InteractionHand.MAIN_HAND, pen);
            pen.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            helper.assertFalse(pen.has(ModDataComponents.TRACE_START.get()), "start dropped");
        }).thenSucceed();
    }

    /** Owns the controller and the device by {@code owner}, before the network forms, as the cap tests do. */
    private static void own(GameTestHelper helper, BlockPos controller, TestStorage.TestDevice device,
            ServerPlayer owner) {
        NetworkGameTests.controllerEntity(helper, controller).setOwner(OwnerRecord.of(owner));
        device.setOwner(OwnerRecord.of(owner));
    }

    private static ZonePainterItem.Outcome paint(GameTestHelper helper, ServerPlayer player, BlockPos pos) {
        return ZonePainterItem.paint(helper.getLevel(), player, pos, StorageNetworks.ID);
    }

    /**
     * Stands {@code player} on the block above {@code target}, looking straight down at it; higher, the ray would meet
     * the test area's ceiling first.
     */
    private static void look(ServerPlayer player, BlockPos target) {
        player.setPos(Vec3.atBottomCenterOf(target.above()));
        player.setXRot(90.0F);
        player.setYRot(0.0F);
    }

    private static boolean hasBox(HighlightRequest request, int colour, BlockPos pos) {
        for (int i = 0; i < request.boxCount(); i++) {
            if (request.boxColour(i) == colour && BlockPos
                    .containing((request.box(i, 0) + request.box(i, 3)) / 2,
                            (request.box(i, 1) + request.box(i, 4)) / 2, (request.box(i, 2) + request.box(i, 5)) / 2)
                    .equals(pos)) {
                return true;
            }
        }
        return false;
    }

    private static String key(Component component) {
        return component.getContents() instanceof TranslatableContents translatable ? translatable.getKey() : "";
    }
}
