package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.analyzer.NetworkOverview;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cable.StorageCableBlock;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.display.ChartRecorder;
import dev.silencebound.maxtech.storage.display.ChartSettings;
import dev.silencebound.maxtech.storage.display.DisplayContent;
import dev.silencebound.maxtech.storage.display.DisplayPanelBlockEntity;
import dev.silencebound.maxtech.storage.display.DisplaySettings;
import dev.silencebound.maxtech.storage.display.DisplaySourceKind;
import dev.silencebound.maxtech.storage.history.ValueRing;
import dev.silencebound.maxtech.storage.label.LabelPayloads;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.LoadShedding;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.PowerPriority;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMenu;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Load shedding (M7L2 row 21): once the network has been charged past the highest resume level (armed), devices of
 * power priority 5, then 4, then 3 pause as its FE falls below the thresholds (25, 15 and 5 % by default), and they
 * resume only at the threshold plus the margin (10 %); essential devices and storage never pause, and a network never
 * charged that far never sheds. The status shows on Jade and the controller's Network tab, the priority survives saving
 * and the dropped item, a steady FE visits no device, a chart keeps recording while its display is shed, both pause
 * reasons hold each other, and logic reads the level and sets priorities.
 *
 * <p>
 * The row: a controller at x 1, a test drive at x 2 with a display panel on top (optional), an import port at x 3
 * (normal) facing a chest at x 4, and a second port on top of the controller. {@code assertValueEqual} takes the
 * expected value first here, so its message reads the right way round.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LoadSheddingGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    private static final BlockPos CORE = NetworkGameTests.at(1);
    private static final BlockPos DRIVE = NetworkGameTests.at(2);
    private static final BlockPos DISPLAY = DRIVE.above();
    private static final BlockPos PORT = NetworkGameTests.at(3);
    private static final BlockPos CHEST = NetworkGameTests.at(4);
    private static final BlockPos TOP_PORT = CORE.above();
    /** The chart interval: history.minIntervalTicks by default. */
    private static final int CHART_TICKS = 20;

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("shedding_pauses_lowest_first_and_resumes", LoadSheddingGameTests::pausesAndResumes);
        register("shedding_cold_start_waits_for_charge", LoadSheddingGameTests::coldStart);
        register("shedding_status_lines", LoadSheddingGameTests::statusLines);
        register("shedding_priority_saved_and_dropped", LoadSheddingGameTests::savedAndDropped);
        register("shedding_steady_power_visits_nothing", LoadSheddingGameTests::steadyVisitsNothing);
        register("shedding_and_switch_hold_each_other", LoadSheddingGameTests::bothReasons);
        register("shedding_chart_keeps_recording", LoadSheddingGameTests::chartKeepsRecording);
        register("shedding_logic_reads_and_sets", LoadSheddingGameTests::logicReadsAndSets);
    }

    private LoadSheddingGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("load_shedding"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 400);
        }
    }

    // ---- helpers

    private record Row(TestStorage.TestDevice drive, DisplayPanelBlockEntity display, PortBlockEntity port,
            ChestBlockEntity chest, PortBlockEntity topPort) {
    }

    private static Row row(GameTestHelper helper) {
        NetworkGameTests.controller(helper, 1);
        TestStorage.TestDevice drive = NetworkGameTests.device(helper, DRIVE);
        helper.setBlock(DISPLAY, DisplayContent.PANEL.get());
        DisplayPanelBlockEntity display = helper.getBlockEntity(DISPLAY, DisplayPanelBlockEntity.class);
        helper.setBlock(PORT, PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, Direction.EAST)
                .setValue(PortBlock.MODE, PortMode.IMPORT));
        PortBlockEntity port = helper.getBlockEntity(PORT, PortBlockEntity.class);
        helper.setBlock(CHEST, Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
        helper.setBlock(TOP_PORT, PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, Direction.UP));
        PortBlockEntity top = helper.getBlockEntity(TOP_PORT, PortBlockEntity.class);
        return new Row(drive, display, port, chest, top);
    }

    /** Fails (to be retried) until every device of the row is in the controller's network. */
    private static NetworkState joined(GameTestHelper helper, Row row) {
        NetworkState network = NetworkGameTests.state(helper, CORE);
        helper.assertTrue(row.drive().network() == network, "the drive should have joined");
        helper.assertTrue(row.display().network() == network, "the display should have joined");
        helper.assertTrue(row.port().network() == network, "the port should have joined");
        helper.assertTrue(row.topPort().network() == network, "the top port should have joined");
        return network;
    }

    /** Sets the controller's FE to {@code percent} of its buffer; the network has no cells, so of its capacity. */
    private static void charge(GameTestHelper helper, int percent) {
        ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, CORE);
        controller.buffer().load(controller.buffer().capacity() * percent / 100);
    }

    private static void level(GameTestHelper helper, int level) {
        helper.assertValueEqual(level, NetworkGameTests.state(helper, CORE).shedding().level(), "shed level");
    }

    /** Fails (to be retried) until the network's tick has seen enough FE to arm load shedding, at level 0. */
    private static void armed(GameTestHelper helper) {
        helper.assertTrue(NetworkGameTests.state(helper, CORE).sheddingArmed(), "the network should be armed");
        level(helper, 0);
    }

    private static int count(ChestBlockEntity chest) {
        int total = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack stack = chest.getItem(i);
            if (stack.is(Items.COBBLESTONE)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static void fill(ChestBlockEntity chest, int amount) {
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, amount));
        chest.setChanged();
    }

    // ---- tests

    /**
     * Full power arms the network and pauses nothing. At 20 % the display (priority 5) pauses and the import port (3)
     * keeps importing; at 2 % the port pauses too and leaves the chest alone, while a port set to essential, the drive
     * and the controller never pause, not even at 0 FE. Back at 20 % priority 3 resumes (at 15 %) but 4 and 5 wait for
     * 25 and 35 %; the port imports what waited. At 40 % the display runs again.
     */
    private static void pausesAndResumes(GameTestHelper helper) {
        Row row = row(helper);
        row.topPort().setPowerPriority(PowerPriority.ESSENTIAL);
        helper.startSequence().thenWaitUntil(() -> joined(helper, row)).thenExecute(() -> {
            helper.assertValueEqual(PowerPriority.OPTIONAL, row.display().powerPriority(), "display default");
            helper.assertValueEqual(PowerPriority.NORMAL, row.port().powerPriority(), "port default");
            helper.assertValueEqual(PowerPriority.ESSENTIAL, row.drive().powerPriority(), "drive priority");
            helper.assertTrue(!row.drive().sheddable(), "storage should not be sheddable");
            charge(helper, 100);
        }).thenWaitUntil(() -> armed(helper)).thenExecute(() -> charge(helper, 20))
                .thenWaitUntil(() -> level(helper, 1)).thenExecute(() -> {
                    helper.assertTrue(row.display().powerPaused(), "the display should be paused at 20 %");
                    helper.assertTrue(!row.port().powerPaused(), "the port should run at 20 %");
                    fill(row.chest(), 10);
                }).thenWaitUntil(() -> helper.assertValueEqual(0, count(row.chest()), "cobblestone left at 20 %"))
                .thenExecute(() -> charge(helper, 2)).thenWaitUntil(() -> level(helper, 3)).thenExecute(() -> {
                    helper.assertTrue(row.port().powerPaused(), "the port should be paused at 2 %");
                    helper.assertTrue(row.port().deviceLink().stopped(), "the port should do no work at 2 %");
                    helper.assertTrue(!row.topPort().powerPaused(), "an essential port should never pause");
                    helper.assertTrue(!row.drive().powerPaused(), "the drive should never pause");
                    fill(row.chest(), 10);
                    charge(helper, 0);
                }).thenIdle(40).thenExecute(() -> {
                    level(helper, 3);
                    helper.assertValueEqual(10, count(row.chest()), "cobblestone left beside a paused port");
                    helper.assertTrue(!row.topPort().powerPaused(), "an essential port should run at 0 FE");
                    helper.assertTrue(!row.drive().powerPaused(), "the drive should run at 0 FE");
                    charge(helper, 20);
                }).thenWaitUntil(() -> level(helper, 2)).thenExecute(() -> {
                    helper.assertTrue(!row.port().powerPaused(), "priority 3 should resume at 20 %");
                    helper.assertTrue(row.display().powerPaused(), "priority 5 should stay paused below 35 %");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(0, count(row.chest()), "cobblestone left after resuming"))
                .thenExecute(() -> charge(helper, 30)).thenWaitUntil(() -> level(helper, 1))
                .thenExecute(
                        () -> helper.assertTrue(row.display().powerPaused(), "priority 5 should stay paused at 30 %"))
                .thenExecute(() -> charge(helper, 40)).thenWaitUntil(() -> level(helper, 0))
                .thenExecute(() -> helper.assertTrue(!row.display().powerPaused(), "priority 5 should run at 40 %"))
                .thenSucceed();
    }

    /**
     * A network that starts empty sheds nothing at 20 % or at 0 FE: it is not armed until it reaches 35 % (the highest
     * resume level), and from then the thresholds apply. The controller saves the arming, so a reloaded controller
     * stays armed.
     */
    private static void coldStart(GameTestHelper helper) {
        Row row = row(helper);
        helper.startSequence().thenWaitUntil(() -> joined(helper, row)).thenExecute(() -> charge(helper, 20))
                .thenIdle(20).thenExecute(() -> {
                    level(helper, 0);
                    helper.assertTrue(!NetworkGameTests.state(helper, CORE).sheddingArmed(),
                            "the network should not be armed below 35 %");
                    helper.assertTrue(!row.display().powerPaused(), "a display should run before arming");
                    charge(helper, 0);
                }).thenIdle(20).thenExecute(() -> {
                    level(helper, 0);
                    helper.assertTrue(!row.port().powerPaused(), "a port should run on a network never charged");
                    charge(helper, 35);
                })
                .thenWaitUntil(() -> helper.assertTrue(NetworkGameTests.state(helper, CORE).sheddingArmed(),
                        "the network should be armed at 35 %"))
                .thenExecute(() -> charge(helper, 20)).thenWaitUntil(() -> level(helper, 1)).thenExecute(() -> {
                    helper.assertTrue(row.display().powerPaused(), "the display should be paused once armed");
                    ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, CORE);
                    CompoundTag tag = controller.saveWithoutMetadata(helper.getLevel().registryAccess());
                    ControllerBlockEntity loaded = new ControllerBlockEntity(controller.getBlockPos(),
                            controller.getBlockState());
                    loaded.loadCustomOnly(
                            TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), tag));
                    helper.assertTrue(loaded.sheddingArmed(), "a reloaded controller should stay armed");
                }).thenSucceed();
    }

    /**
     * While shedding, Jade says "Paused: low power (priority 5)" on the display and names the shed level on the
     * network, the Network tab warns, and nothing of it shows once the power is back. The status tab's packet changes
     * the priority for the port's owner only.
     */
    private static void statusLines(GameTestHelper helper) {
        Row row = row(helper);
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "shed_owner");
        ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "shed_stranger");
        Vec3 near = Vec3.atCenterOf(helper.absolutePos(PORT.above()));
        owner.setPos(near);
        stranger.setPos(near);
        row.port().setOwner(OwnerRecord.of(owner));
        helper.startSequence().thenWaitUntil(() -> joined(helper, row)).thenExecute(() -> charge(helper, 100))
                .thenWaitUntil(() -> armed(helper)).thenExecute(() -> charge(helper, 20))
                .thenWaitUntil(() -> level(helper, 1)).thenExecute(() -> {
                    List<String> display = NetworkGameTests.keys(helper, DISPLAY, BlockInfoRequest.Detail.FULL);
                    helper.assertTrue(display.contains(PowerPriority.PAUSED_KEY),
                            "Jade should say the display is paused: " + display);
                    helper.assertTrue(display.contains(NetworkInfo.SHEDDING_KEY), "Jade should name the shed level");
                    List<String> port = NetworkGameTests.keys(helper, PORT, BlockInfoRequest.Detail.FULL);
                    helper.assertTrue(!port.contains(PowerPriority.PAUSED_KEY),
                            "Jade should not call a running port paused");
                    NetworkOverview overview = NetworkOverview.of(NetworkGameTests.state(helper, CORE),
                            NetworkOverview.Cluster.NO_CLUSTER);
                    helper.assertTrue(
                            overview.warnings()
                                    .contains(new NetworkOverview.Warning(NetworkOverview.WarningKind.LOAD_SHEDDING,
                                            PowerPriority.OPTIONAL, Long.MIN_VALUE)),
                            "the Network tab should warn: " + overview.warnings());

                    owner.containerMenu = PortMenu.forPort(7, owner.getInventory(), row.port());
                    stranger.containerMenu = PortMenu.forPort(8, stranger.getInventory(), row.port());
                    helper.assertTrue(!LabelPayloads.applyPowerPriority(stranger, 8, 5),
                            "a stranger should not change the priority");
                    helper.assertValueEqual(PowerPriority.NORMAL, row.port().powerPriority(),
                            "priority after a stranger");
                    helper.assertTrue(LabelPayloads.applyPowerPriority(owner, 7, 9),
                            "the owner should change the priority");
                    helper.assertValueEqual(PowerPriority.OPTIONAL, row.port().powerPriority(),
                            "priority clamped to 5");
                    helper.assertTrue(row.port().powerPaused(), "priority 5 at 20 % should pause at once");
                }).thenExecute(() -> charge(helper, 100)).thenWaitUntil(() -> level(helper, 0)).thenExecute(() -> {
                    List<String> display = NetworkGameTests.keys(helper, DISPLAY, BlockInfoRequest.Detail.FULL);
                    helper.assertTrue(!display.contains(PowerPriority.PAUSED_KEY), "the display should run again");
                    helper.assertTrue(!display.contains(NetworkInfo.SHEDDING_KEY), "the shed level line should go");
                    helper.assertTrue(!row.port().powerPaused(), "the port should run again");
                }).thenSucceed();
    }

    /**
     * A port's power priority survives saving and loading and travels in its dropped item; a default priority saves
     * nothing. A display part placed from an item with a priority keeps it and gives it back in its item.
     */
    private static void savedAndDropped(GameTestHelper helper) {
        helper.setBlock(PORT, PortContent.PORT.get());
        PortBlockEntity port = helper.getBlockEntity(PORT, PortBlockEntity.class);
        CompoundTag plain = port.saveWithoutMetadata(helper.getLevel().registryAccess());
        helper.assertTrue(!plain.contains("power_priority"), "a default priority should save nothing");
        port.setPowerPriority(PowerPriority.LOW);
        CompoundTag tag = port.saveWithoutMetadata(helper.getLevel().registryAccess());
        PortBlockEntity loaded = new PortBlockEntity(port.getBlockPos(), port.getBlockState());
        loaded.loadCustomOnly(
                TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), tag));
        helper.assertValueEqual(PowerPriority.LOW, loaded.powerPriority(), "priority after loading");

        List<ItemStack> drops = Block.getDrops(helper.getBlockState(PORT), helper.getLevel(), helper.absolutePos(PORT),
                port);
        helper.assertValueEqual(1, drops.size(), "port drops");
        helper.assertValueEqual(PowerPriority.LOW, drops.get(0).getOrDefault(ModDataComponents.POWER_PRIORITY.get(), 0),
                "priority in the item");
        BlockPos again = NetworkGameTests.at(6);
        helper.setBlock(again, PortContent.PORT.get());
        PortBlockEntity placed = helper.getBlockEntity(again, PortBlockEntity.class);
        placed.applyComponentsFromItemStack(drops.get(0));
        helper.assertValueEqual(PowerPriority.LOW, placed.powerPriority(), "priority from the placed item");

        NetworkGameTests.cables(helper, 9, 9, CableContent.STORAGE_CABLE_TIER_1.type().get());
        CableParts parts = StorageCableBlock.parts(helper.getLevel(), helper.absolutePos(NetworkGameTests.at(9)));
        if (parts == null) {
            throw helper.assertionException(Component.literal("no cable parts"));
        }
        ItemStack stack = new ItemStack(DisplayContent.PANEL_PART_ITEM.get());
        stack.set(ModDataComponents.POWER_PRIORITY.get(), PowerPriority.HIGH);
        helper.assertTrue(parts.place(Direction.EAST, DisplayContent.PANEL_PART, null, stack),
                "the display part should be placed");
        CablePart part = parts.part(Direction.EAST);
        if (part == null) {
            throw helper.assertionException(Component.literal("no display part"));
        }
        helper.assertValueEqual(PowerPriority.HIGH, part.powerPriority(), "part priority from the item");
        helper.assertValueEqual(PowerPriority.HIGH,
                part.toItem().getOrDefault(ModDataComponents.POWER_PRIORITY.get(), 0), "part priority in its item");
        helper.succeed();
    }

    /**
     * Once shed, FE moving up and down within the band (18 to 30 %) changes no level, works nothing out again and
     * visits no device, however long: the tick only compares the FE with the band.
     */
    private static void steadyVisitsNothing(GameTestHelper helper) {
        Row row = row(helper);
        long[] visits = {-1};
        long[] updates = {-1};
        int[] step = {0};
        helper.startSequence().thenWaitUntil(() -> joined(helper, row)).thenExecute(() -> charge(helper, 100))
                .thenWaitUntil(() -> armed(helper)).thenExecute(() -> charge(helper, 20))
                .thenWaitUntil(() -> level(helper, 1)).thenExecute(() -> {
                    LoadShedding shedding = NetworkGameTests.state(helper, CORE).shedding();
                    visits[0] = shedding.visits();
                    updates[0] = shedding.updates();
                    helper.assertTrue(visits[0] > 0, "pausing should have visited the display");
                }).thenExecuteFor(40, () -> {
                    step[0]++;
                    charge(helper, 18 + (step[0] * 7) % 13);
                }).thenExecute(() -> {
                    level(helper, 1);
                    LoadShedding shedding = NetworkGameTests.state(helper, CORE).shedding();
                    helper.assertValueEqual(visits[0], shedding.visits(), "devices visited while the level held");
                    helper.assertValueEqual(updates[0], shedding.updates(), "levels worked out while in the band");
                }).thenSucceed();
    }

    /**
     * Turned off by logic and paused for low power at once, the port stays stopped until both are lifted: switched on
     * while still paused it imports nothing, and resumed by the power while still switched off it imports nothing.
     */
    private static void bothReasons(GameTestHelper helper) {
        Row row = row(helper);
        PortBlockEntity port = row.port();
        helper.startSequence().thenWaitUntil(() -> joined(helper, row)).thenExecute(() -> charge(helper, 100))
                .thenWaitUntil(() -> armed(helper)).thenExecute(() -> {
                    port.setSwitchedOff(true);
                    charge(helper, 2);
                }).thenWaitUntil(() -> level(helper, 3)).thenExecute(() -> {
                    helper.assertTrue(port.powerPaused(), "the port should be paused at 2 %");
                    port.setSwitchedOff(false);
                    helper.assertTrue(port.deviceLink().stopped(), "switching on should not lift the power pause");
                    fill(row.chest(), 10);
                }).thenIdle(40).thenExecute(() -> {
                    helper.assertValueEqual(10, count(row.chest()), "cobblestone left while still paused");
                    port.setSwitchedOff(true);
                    charge(helper, 20);
                }).thenWaitUntil(() -> level(helper, 2)).thenExecute(() -> {
                    helper.assertTrue(!port.powerPaused(), "the port should resume from shedding at 20 %");
                    helper.assertTrue(port.deviceLink().stopped(), "the power should not lift the logic switch");
                }).thenIdle(40).thenExecute(() -> {
                    helper.assertValueEqual(10, count(row.chest()), "cobblestone left while still switched off");
                    port.setSwitchedOff(false);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(0, count(row.chest()), "cobblestone left when both lift"))
                .thenSucceed();
    }

    /**
     * A display charting the network's energy keeps recording while load shedding pauses it: a sample every interval
     * and no gap in the ring, though the display itself is paused.
     */
    private static void chartKeepsRecording(GameTestHelper helper) {
        NetworkGameTests.controller(helper, 1);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        DisplayPanelBlockEntity panel = DisplayGameTests.panel(helper, NetworkGameTests.at(3), Direction.NORTH);
        panel.applyToWall(DisplaySettings.DEFAULT.withSource(DisplaySourceKind.HISTORY)
                .withChart(new ChartSettings("energy", CHART_TICKS, 1)));
        int[] start = {-1};
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(panel.network() != null, "panel joined"))
                .thenExecute(() -> charge(helper, 100)).thenWaitUntil(() -> armed(helper))
                .thenExecute(() -> charge(helper, 20)).thenWaitUntil(() -> level(helper, 1)).thenExecute(() -> {
                    helper.assertTrue(panel.powerPaused(), "the display should be paused at 20 %");
                    start[0] = panel.worker().chart().samples();
                }).thenIdle(5 * CHART_TICKS).thenExecute(() -> {
                    ChartRecorder chart = panel.worker().chart();
                    int taken = chart.samples() - start[0];
                    helper.assertTrue(taken >= 4, "a shed display's chart should record, took " + taken);
                    helper.assertValueEqual(ChartRecorder.RECORDING, chart.state(), "chart state");
                    ValueRing ring = chart.ring();
                    if (ring == null) {
                        throw helper.assertionException(Component.literal("nothing recorded"));
                    }
                    for (int i = 1; i < ring.size(); i++) {
                        helper.assertTrue(ring.fineAt(i) != ValueRing.NO_VALUE, "a gap in the chart at " + i);
                    }
                    helper.assertTrue(panel.powerPaused(), "the display should still be paused");
                }).thenSucceed();
    }

    /**
     * A graph "when load shedding changes, set the power priority of pumps to 1" makes the pumps essential as soon as
     * low power pauses them: the owner's port resumes at once, a stranger's port labelled the same stays paused at its
     * priority.
     */
    private static void logicReadsAndSets(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "shed_logic");
        ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "shed_logic_stranger");
        BlockPos pumpPos = NetworkGameTests.at(4);
        helper.setBlock(pumpPos, PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, Direction.EAST));
        PortBlockEntity pump = helper.getBlockEntity(pumpPos, PortBlockEntity.class);
        pump.setLabel(DeviceLabel.of("pumps", null));
        BlockPos otherPos = NetworkGameTests.at(3).above();
        helper.setBlock(otherPos, PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, Direction.UP));
        PortBlockEntity other = helper.getBlockEntity(otherPos, PortBlockEntity.class);
        other.setLabel(DeviceLabel.of("pumps", null));
        other.setOwner(OwnerRecord.of(stranger));
        pump.setOwner(entity.ownerRecord());
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph().node(1, NodeTypes.SHEDDING_CHANGES, s -> s)
                .node(2, NodeTypes.SET_POWER_PRIORITY, s -> s.withText("label", "pumps").withNumber("priority", 1))
                .wire(1, "changed", 2, "do").build();
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = LogicControllerGameTests.network(helper, entity);
            helper.assertTrue(pump.network() == network && other.network() == network, "the ports should have joined");
            helper.assertTrue(network.sheddingArmed(), "the full network should be armed");
        }).thenExecute(() -> entity.worker().setGraph(0, "Keep pumps", graph)).thenIdle(5)
                .thenExecute(() -> charge(helper, 2)).thenWaitUntil(() -> level(helper, 3)).thenWaitUntil(() -> {
                    helper.assertValueEqual(PowerPriority.ESSENTIAL, pump.powerPriority(), "the owner's pump priority");
                    helper.assertTrue(!pump.powerPaused(), "the essential pump should run");
                }).thenExecute(() -> {
                    helper.assertValueEqual(PowerPriority.NORMAL, other.powerPriority(), "a stranger's pump priority");
                    helper.assertTrue(other.powerPaused(), "a stranger's pump should stay paused at 2 %");
                }).thenSucceed();
    }
}
