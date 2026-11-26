package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.creative.CreativeEnergyContent;
import dev.silencebound.maxtech.storage.display.ChartRecorder;
import dev.silencebound.maxtech.storage.display.ChartSettings;
import dev.silencebound.maxtech.storage.display.DisplayChartSave;
import dev.silencebound.maxtech.storage.display.DisplayHistoryUpdate;
import dev.silencebound.maxtech.storage.display.DisplayMenu;
import dev.silencebound.maxtech.storage.display.DisplayPanelBlockEntity;
import dev.silencebound.maxtech.storage.display.DisplaySettings;
import dev.silencebound.maxtech.storage.display.DisplaySourceKind;
import dev.silencebound.maxtech.storage.display.DisplaySync;
import dev.silencebound.maxtech.storage.display.DisplayTextSave;
import dev.silencebound.maxtech.storage.display.DisplayUpdate;
import dev.silencebound.maxtech.storage.display.DisplayViewers;
import dev.silencebound.maxtech.storage.history.ValueRing;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerMenu;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.logic.controller.PinHistory;
import dev.silencebound.maxtech.storage.logic.controller.PinSparks;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * History charts (M7L2 row 24): a chart screen records its value on the network scheduler at its interval only, keeps
 * the record through a save and load, sends the chart to viewers, stops at the network's series limit; a logic
 * controller records its pinned values and sends their sparklines to an open screen. The tests that swap the display
 * payload sender run in their own batch.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class HistoryChartGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final int FAST = 20;
    private static final String RECORDS = "history_chart_records_at_interval";
    private static final String SAVES = "history_chart_survives_save_and_load";
    private static final String VIEWERS = "history_chart_reaches_viewers";
    private static final String LIMIT = "history_chart_series_limit";
    private static final String PINS = "history_pins_sparklines";
    private static final String FORMER_ANCHOR = "history_chart_former_anchor_cleared";

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(RECORDS, HistoryChartGameTests::recordsAtInterval);
        register(SAVES, HistoryChartGameTests::survivesSaveAndLoad);
        register(VIEWERS, HistoryChartGameTests::reachesViewers);
        register(LIMIT, HistoryChartGameTests::seriesLimit);
        register(PINS, HistoryChartGameTests::pinSparklines);
        register(FORMER_ANCHOR, HistoryChartGameTests::formerAnchorCleared);
    }

    private HistoryChartGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        // Its own batch: the viewer test swaps the payload sender.
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("history"),
                StorageTestEnvironment.plain());
        test(event, RECORDS, plain);
        test(event, SAVES, plain);
        test(event, VIEWERS, plain);
        test(event, FORMER_ANCHOR, plain);
        test(event, LIMIT, event.registerEnvironment(MaxtechStorage.id("history_limit"),
                new StorageTestEnvironment("history.maxSeriesPerNetwork", "1", false)));
        test(event, PINS, event.registerEnvironment(MaxtechStorage.id("history_pins"),
                new StorageTestEnvironment("history.pinIntervalTicks", "" + FAST, false)));
    }

    private static void test(RegisterGameTestsEvent event, String name, Holder<TestEnvironmentDefinition<?>> env) {
        event.registerTest(MaxtechStorage.id(name),
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                        new TestData<>(env, STRUCTURE, 400, 0, true)));
    }

    /** Chart settings recording {@code value} every {@link #FAST} ticks over one hour. */
    static DisplaySettings chart(String value) {
        return DisplaySettings.DEFAULT.withSource(DisplaySourceKind.HISTORY)
                .withChart(new ChartSettings(value, FAST, 1));
    }

    /** A creative-powered network with a storage device at x 2 and a chart panel at x 3 facing north. */
    private static DisplayPanelBlockEntity chartPanel(GameTestHelper helper, String value) {
        helper.setBlock(NetworkGameTests.at(1), CreativeEnergyContent.CREATIVE_ENERGY_SOURCE.get());
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        DisplayPanelBlockEntity panel = DisplayGameTests.panel(helper, NetworkGameTests.at(3), Direction.NORTH);
        panel.applyToWall(chart(value));
        return panel;
    }

    private static ChartRecorder recorder(DisplayPanelBlockEntity panel) {
        return panel.worker().chart();
    }

    private static ValueRing ring(GameTestHelper helper, DisplayPanelBlockEntity panel) {
        ValueRing ring = recorder(panel).ring();
        if (ring == null) {
            throw helper.assertionException(Component.literal("nothing recorded"));
        }
        return ring;
    }

    /**
     * A chart recording every 20 ticks takes five samples in 100 ticks, each 20 ticks after the last, and none while
     * the source is something else; the newest sample is the stored count.
     */
    private static void recordsAtInterval(GameTestHelper helper) {
        DisplayPanelBlockEntity panel = chartPanel(helper, "count:minecraft:cobblestone");
        int[] start = new int[1];
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(recorder(panel).samples() > 0, "first sample"))
                .thenExecute(() -> {
                    NetworkGameTests.insert(panel.network(), COBBLE, 7);
                    start[0] = recorder(panel).samples();
                }).thenIdle(100).thenExecute(() -> {
                    int taken = recorder(panel).samples() - start[0];
                    helper.assertTrue(taken >= 4 && taken <= 6, taken + " samples in 100 ticks at a 20-tick interval");
                    ValueRing ring = ring(helper, panel);
                    helper.assertValueEqual(ring.interval(), FAST, "interval");
                    helper.assertValueEqual(ring.latest(), 7L, "newest sample");
                    for (int i = 1; i < ring.size(); i++) {
                        helper.assertTrue(ring.fineAt(i) != ValueRing.NO_VALUE, "a gap at " + i);
                    }
                    helper.assertValueEqual(recorder(panel).state(), ChartRecorder.RECORDING, "state");
                    panel.applyToWall(panel.settings().withSource(DisplaySourceKind.POWER_STORED));
                    start[0] = recorder(panel).samples();
                }).thenIdle(60).thenExecute(() -> {
                    helper.assertValueEqual(recorder(panel).samples(), start[0], "samples of another source");
                    NetworkState network = panel.network();
                    helper.assertValueEqual(network == null ? -1 : network.historyClaims().total(), 0,
                            "series claimed by a screen showing something else");
                }).thenSucceed();
    }

    /** The anchor saves its record with the reference; a loaded copy has the same samples and keeps recording. */
    private static void survivesSaveAndLoad(GameTestHelper helper) {
        DisplayPanelBlockEntity panel = chartPanel(helper, "energy");
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(recorder(panel).samples() >= 3, "three samples"))
                .thenExecute(() -> {
                    ValueRing before = ring(helper, panel);
                    CompoundTag saved = panel.saveWithoutMetadata(helper.getLevel().registryAccess());
                    DisplayPanelBlockEntity copy = new DisplayPanelBlockEntity(panel.getBlockPos(),
                            panel.getBlockState());
                    copy.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING,
                            helper.getLevel().registryAccess(), saved));
                    ValueRing after = ring(helper, copy);
                    helper.assertValueEqual(after.size(), before.size(), "samples after a reload");
                    helper.assertValueEqual(after.lastTime(), before.lastTime(), "newest sample time");
                    helper.assertValueEqual(after.latest(), before.latest(), "newest sample");
                    helper.assertValueEqual(copy.settings().chart(), panel.settings().chart(), "chart settings");
                }).thenSucceed();
    }

    private static final Map<UUID, List<CustomPacketPayload>> SENT = new ConcurrentHashMap<>();

    private static List<CustomPacketPayload> collect(ServerPlayer viewer) {
        List<CustomPacketPayload> sent = new CopyOnWriteArrayList<>();
        SENT.put(viewer.getUUID(), sent);
        DisplaySync.setSender((player, payload) -> {
            List<CustomPacketPayload> target = SENT.get(player.getUUID());
            if (target != null) {
                target.add(payload);
            }
        });
        return sent;
    }

    private static List<DisplayHistoryUpdate> charts(List<CustomPacketPayload> sent) {
        List<DisplayHistoryUpdate> out = new ArrayList<>();
        for (CustomPacketPayload payload : sent) {
            if (payload instanceof DisplayHistoryUpdate update) {
                out.add(update);
            }
        }
        return out;
    }

    /**
     * A viewer gets the chart: its points end with the stored count (one hour at 20 ticks comes from the coarse ring,
     * so after six samples), and a new chart after later samples; the value update carries the recording state. A value
     * typed in the settings GUI is checked on the server.
     */
    private static void reachesViewers(GameTestHelper helper) {
        DisplayPanelBlockEntity panel = chartPanel(helper, "count:minecraft:cobblestone");
        // Recording starts once the count is in, so every point is the same.
        panel.applyToWall(DisplaySettings.DEFAULT);
        ServerPlayer viewer = NetworkGameTests.player(helper.getLevel(), "chart_viewer");
        ServerPlayer[] typist = {NetworkGameTests.player(helper.getLevel(), "chart_typist")};
        typist[0].setPos(Vec3.atCenterOf(helper.absolutePos(NetworkGameTests.at(3))));
        typist[0].containerMenu = DisplayMenu.forDevice(5, typist[0].getInventory(), panel);
        List<CustomPacketPayload> sent = collect(viewer);
        long chunk = ChunkPos.pack(helper.absolutePos(NetworkGameTests.at(3)));
        int[] before = new int[1];
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(panel.network() != null, "in a network"))
                .thenExecute(() -> {
                    NetworkGameTests.insert(panel.network(), COBBLE, 9);
                    panel.applyToWall(chart("count:minecraft:cobblestone"));
                    DisplayViewers.watch(helper.getLevel(), viewer, chunk);
                }).thenWaitUntil(() -> {
                    List<DisplayHistoryUpdate> charts = charts(sent);
                    helper.assertTrue(!charts.isEmpty(), "no chart");
                    DisplayHistoryUpdate last = charts.getLast();
                    helper.assertValueEqual(last.samples().size(), 60, "points of a one-hour chart");
                    helper.assertValueEqual(last.samples().getLong(59), 9L, "newest point");
                    helper.assertValueEqual(last.ticksPerSample(), 1200, "ticks per point");
                }).thenExecute(() -> {
                    boolean recording = false;
                    for (CustomPacketPayload payload : sent) {
                        if (payload instanceof DisplayUpdate update && update.max() == ChartRecorder.RECORDING
                                && update.value() == 9) {
                            recording = true;
                        }
                    }
                    helper.assertTrue(recording, "no value update with the recording state");
                    before[0] = charts(sent).size();
                }).thenIdle(3 * FAST).thenExecute(() -> {
                    helper.assertTrue(charts(sent).size() > before[0], "no chart after later samples");
                    DisplayViewers.unwatch(helper.getLevel(), viewer, chunk);
                    ServerPlayer player = typist[0];
                    helper.assertValueEqual(DisplayChartSave.apply(player, 5, "count:nothing:here"),
                            DisplayTextSave.Result.INVALID, "an unknown item");
                }).thenIdle(1).thenExecute(() -> {
                    ServerPlayer player = typist[0];
                    helper.assertValueEqual(DisplayChartSave.apply(player, 5, "variable:main:batch"),
                            DisplayTextSave.Result.SAVED, "a variable");
                    helper.assertValueEqual(panel.settings().chart().value(), "variable:main:batch", "saved value");
                }).thenSucceed();
    }

    /**
     * A panel placed beside a recording screen so that it becomes the wall's anchor takes over the chart; the former
     * anchor drops its record and claim, and the new anchor records.
     */
    private static void formerAnchorCleared(GameTestHelper helper) {
        DisplayPanelBlockEntity panel = chartPanel(helper, "energy");
        DisplayPanelBlockEntity[] next = new DisplayPanelBlockEntity[1];
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(recorder(panel).samples() > 0, "first sample"))
                .thenExecute(() -> next[0] = DisplayGameTests.panel(helper, NetworkGameTests.at(4), Direction.NORTH))
                .thenWaitUntil(() -> {
                    helper.assertFalse(panel.isAnchor(), "the old panel is still the anchor");
                    helper.assertTrue(recorder(next[0]).samples() > 0, "the new anchor does not record");
                }).thenExecute(() -> {
                    helper.assertTrue(recorder(panel).ring() == null, "the former anchor keeps its ring");
                    NetworkState network = panel.network();
                    helper.assertValueEqual(network == null ? -1 : network.historyClaims().total(), 1,
                            "series claimed on the network");
                }).thenSucceed();
    }

    /** With a limit of one series, the second chart on the network records nothing and says so. */
    private static void seriesLimit(GameTestHelper helper) {
        DisplayPanelBlockEntity first = chartPanel(helper, "energy");
        DisplayPanelBlockEntity second = DisplayGameTests.panel(helper, NetworkGameTests.at(4), Direction.SOUTH);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(recorder(first).samples() > 0, "first sample"))
                .thenExecute(() -> second.applyToWall(chart("items"))).thenIdle(3 * FAST).thenExecute(() -> {
                    helper.assertValueEqual(recorder(second).state(), ChartRecorder.LIMIT, "state past the limit");
                    helper.assertValueEqual(recorder(second).samples(), 0, "samples past the limit");
                    helper.assertValueEqual(recorder(first).state(), ChartRecorder.RECORDING,
                            "the first still records");
                    first.applyToWall(first.settings().withSource(DisplaySourceKind.POWER_STORED));
                }).thenWaitUntil(() -> helper.assertTrue(recorder(second).samples() > 0, "freed series used"))
                .thenSucceed();
    }

    /**
     * A pinned value is recorded at the pin interval, its sparkline goes to an open controller screen only when the
     * record changed, and the record survives a save and load of the controller.
     */
    private static void pinSparklines(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "hist_pins");
        LogicGraph<LogicKey> graph = LogicControllerGameTests.crossesNotify(COBBLE, 1000, "full");
        LogicControllerMenu menu = LogicControllerMenu.forController(3, entity);
        int[] start = new int[1];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            entity.worker().setGraph(0, "Pins", graph);
            entity.worker().setPins(0, List.of(1));
            NetworkGameTests.insert(LogicControllerGameTests.network(helper, entity), COBBLE, 5);
        }).thenWaitUntil(() -> helper.assertValueEqual(entity.worker().pinValue(0, 0), 5L, "pinned value"))
                .thenWaitUntil(() -> {
                    long[] points = new long[PinHistory.SPARK_POINTS];
                    int count = entity.pinHistory().sparkline(0, 0, points);
                    helper.assertTrue(count > 0 && points[count - 1] == 5, "a sparkline ending at the pinned value");
                }).thenExecute(() -> {
                    helper.assertValueEqual(entity.pinHistory().seriesCount(), 1, "series");
                    PinSparks sparks = menu.sparkUpdate();
                    if (sparks == null || sparks.sparks().isEmpty()) {
                        throw helper.assertionException(Component.literal("no sparkline"));
                    }
                    PinSparks.Spark spark = sparks.sparks().getFirst();
                    helper.assertValueEqual(spark.graph(), 0, "spark rule");
                    helper.assertValueEqual(spark.pin(), 0, "spark pin");
                    helper.assertValueEqual(spark.points().getLong(spark.points().size() - 1), 5L, "newest point");
                    helper.assertTrue(menu.sparkUpdate() == null, "sent again without a sample");
                    start[0] = entity.pinHistory().samples();
                }).thenIdle(5 * FAST).thenExecute(() -> {
                    int taken = entity.pinHistory().samples() - start[0];
                    helper.assertTrue(taken >= 4 && taken <= 6, taken + " pin samples in 100 ticks");
                    helper.assertTrue(menu.sparkUpdate() != null, "no sparkline after new samples");
                    CompoundTag saved = entity.saveWithoutMetadata(helper.getLevel().registryAccess());
                    LogicControllerBlockEntity copy = new LogicControllerBlockEntity(entity.getBlockPos(),
                            entity.getBlockState());
                    copy.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING,
                            helper.getLevel().registryAccess(), saved));
                    helper.assertValueEqual(copy.pinHistory().seriesCount(), 1, "series after a reload");
                    long[] points = new long[PinHistory.SPARK_POINTS];
                    helper.assertTrue(copy.pinHistory().sparkline(0, 0, points) > 0, "sparkline after a reload");
                }).thenSucceed();
    }

}
