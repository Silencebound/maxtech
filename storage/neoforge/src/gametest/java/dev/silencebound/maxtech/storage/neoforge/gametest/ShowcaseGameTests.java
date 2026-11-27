package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.conduit.ConduitData;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.bulk.BulkContents;
import dev.silencebound.maxtech.storage.bulk.BulkDriveBlockEntity;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.dev.ShowcaseBridges;
import static dev.silencebound.maxtech.storage.dev.ShowcaseSteps.stores;
import dev.silencebound.maxtech.storage.hub.HubContent;
import dev.silencebound.maxtech.storage.network.ChannelShortage;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.condition.ConditionSwitchBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerBlock;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.controller.ControllerStatus;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuCoreBlockEntity;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuPart;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJobs;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.storage.dev.LogicExamples;
import dev.silencebound.maxtech.storage.dev.Showcase;
import dev.silencebound.maxtech.storage.dev.ShowcaseBoards;
import dev.silencebound.maxtech.storage.dev.ShowcaseStation;
import dev.silencebound.maxtech.storage.dev.ShowcaseSteps;
import dev.silencebound.maxtech.storage.display.DisplayContent;
import dev.silencebound.maxtech.storage.energy.EnergyContent;
import dev.silencebound.maxtech.storage.display.DisplayPanelBlockEntity;
import dev.silencebound.maxtech.storage.display.DisplaySettings;
import dev.silencebound.maxtech.storage.display.DisplaySourceKind;
import dev.silencebound.maxtech.storage.display.DisplayText;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicWorker;
import dev.silencebound.maxtech.storage.machine.MachineContent;
import dev.silencebound.maxtech.storage.machine.StorageMachineBlockEntity;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import dev.silencebound.maxtech.storage.research.ResearchTree;
import dev.silencebound.maxtech.storage.subnet.SubnetBlockEntity;
import dev.silencebound.maxtech.storage.subnet.SubnetContent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The dev showcase builder: every station is built on a fresh floor, its key blocks are where the builder put them,
 * each station's network (the machines station has a generator instead) is online and powered, the stock arrives,
 * research is done for the player's team and clearing takes it all away again.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class ShowcaseGameTests {
    private static final String NAME = "showcase_builds_every_station";
    private static final String WORKS = "showcase_subnet_and_crafting_work";
    private static final String LOGIC = "showcase_logic_works";
    private static final String CHANNELS = "showcase_channels_and_priority_work";
    private static final String CLEAR = "showcase_clear_removes_every_build";
    private static final String STEPS = "showcase_steps_happen_without_a_player";
    /**
     * Steps with a detector that wait for a player (taking items, breaking blocks, flipping levers) and that
     * {@link #stepsHappenWithoutAPlayer} does not drive; every other step with a detector must happen on its own or
     * once that test drove it ({@link #DRIVEN_STEPS}). The logic station's player steps are checked by
     * {@link #logicWorks}, the priority and channels stations' behaviour by {@link #channelsAndPriorityWork}.
     */
    private static final Map<ShowcaseStation, Set<Integer>> PLAYER_STEPS = Map.of(ShowcaseStation.CORE, Set.of(1, 2),
            ShowcaseStation.PRIORITY, Set.of(3, 4), ShowcaseStation.CHANNELS, Set.of(2, 5), ShowcaseStation.TOOLS,
            Set.of(6), ShowcaseStation.LOGIC, Set.of(0, 1, 2, 3, 4));
    /** Player steps {@link #stepsHappenWithoutAPlayer} drives itself, after the others happened. */
    private static final Map<ShowcaseStation, Set<Integer>> DRIVEN_STEPS = Map.of(ShowcaseStation.CORE, Set.of(3),
            ShowcaseStation.SUBNET, Set.of(3), ShowcaseStation.TOOLS, Set.of(1), ShowcaseStation.CABLES, Set.of(4, 5));

    static {
        StorageTestEnvironment.init();
        StorageTests.function(NAME, StorageTestEnvironment.clearing(ShowcaseGameTests::buildsEveryStation));
        StorageTests.function(WORKS, StorageTestEnvironment.clearing(ShowcaseGameTests::subnetAndCraftingWork));
        StorageTests.function(LOGIC, StorageTestEnvironment.clearing(ShowcaseGameTests::logicWorks));
        StorageTests.function(CHANNELS, StorageTestEnvironment.clearing(ShowcaseGameTests::channelsAndPriorityWork));
        StorageTests.function(CLEAR, StorageTestEnvironment.clearing(ShowcaseGameTests::clearRemovesEveryBuild));
        StorageTests.function(STEPS, StorageTestEnvironment.clearing(ShowcaseGameTests::stepsHappenWithoutAPlayer));
    }

    private ShowcaseGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id("showcase"), StorageTestEnvironment.plain()), NAME,
                MaxtechStorage.id("empty_100x2x101"), 400);
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id("showcase_works"), StorageTestEnvironment.plain()), WORKS,
                MaxtechStorage.id("empty_100x2x101"), 1_200);
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id("showcase_logic"), StorageTestEnvironment.plain()), LOGIC,
                MaxtechStorage.id("empty_42x4x27"), 1_200);
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id("showcase_channels"), StorageTestEnvironment.plain()),
                CHANNELS, MaxtechStorage.id("empty_42x4x27"), 600);
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id("showcase_steps"), StorageTestEnvironment.plain()), STEPS,
                MaxtechStorage.id("empty_100x2x101"), 2_400);
        // Its own batch: clearing every build in the level must not meet another test's showcase.
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id("showcase_clear"), StorageTestEnvironment.plain()), CLEAR,
                MaxtechStorage.id("empty_42x4x27"), 200);
    }

    /**
     * The channels station: the two terminals that joined last on the full tier-1 cable are offline and name it, 8 of
     * 8; the three hubs have their tiers' budgets and count the terminals on them (and the hub behind them); the
     * controller placed a tick later is on standby and takes over when the first is broken; the network without a
     * controller holds 8 channels and turns three devices away. The priority station: the import port moves the chest
     * into the drive at the highest priority only; with that drive's cell pulled, new cobblestone goes to the bulk
     * drive (0) rather than the drive at -100, and dirt, which the bulk drive cannot hold, to the drive at -100.
     */
    private static void channelsAndPriorityWork(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = NetworkGameTests.player(level, "showcase_channels");
        Showcase.Build build = Showcase.build(level, helper.absolutePos(new BlockPos(1, 1, 1)),
                List.of(ShowcaseStation.CHANNELS, ShowcaseStation.PRIORITY), player);
        helper.assertTrue(build.warnings().isEmpty(), "builder warnings: " + build.warnings());
        BlockPos pad = Objects.requireNonNull(build.pads().get(ShowcaseStation.CHANNELS));
        BlockPos hub = Objects.requireNonNull(build.hubs().get(ShowcaseStation.CHANNELS));
        BlockPos standby = pad.offset(2, 0, 2);
        BlockPos priority = Objects.requireNonNull(build.pads().get(ShowcaseStation.PRIORITY));
        DriveBlockEntity first = entity(helper, priority.offset(3, 0, 5), DriveBlockEntity.class);
        BulkDriveBlockEntity bulk = entity(helper, priority.offset(4, 0, 5), BulkDriveBlockEntity.class);
        DriveBlockEntity last = entity(helper, priority.offset(5, 0, 5), DriveBlockEntity.class);
        ChestBlockEntity chest = entity(helper, priority.offset(3, 0, 2), ChestBlockEntity.class);
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertFalse(Showcase.stocking(), "the standby controller is not placed yet");
            NetworkState network = network(helper, hub);
            helper.assertValueEqual(network.status(), StorageNetwork.Status.ONLINE, "channels network");
            ControllerBlockEntity second = entity(helper, standby, ControllerBlockEntity.class);
            helper.assertTrue(network.controllerDevice() == entity(helper, hub, ControllerBlockEntity.class),
                    "the first controller is not in charge");
            helper.assertTrue(network.isStandby(second), "the second controller is not on standby");
            helper.assertValueEqual(network.offlineDevices().size(), 2, "terminals offline on the full cable");
            for (NetworkDevice device : network.offlineDevices()) {
                ChannelShortage shortage = device.deviceLink().channelShortage().orElseThrow();
                helper.assertValueEqual(shortage.kind(), ChannelShortage.Kind.CABLE, "what is full");
                helper.assertValueEqual(shortage.pos(), pad.offset(3, 0, 3).asLong(), "the full cable");
                helper.assertValueEqual(shortage.used(), 8, "channels used");
                helper.assertValueEqual(shortage.capacity(), 8, "channels of a tier-1 cable");
                helper.assertValueEqual(BlockPos.of(device.nodePos()).getX(), pad.getX() + 7,
                        "an offline terminal that did not join last");
            }
            int[] budgets = {64, 32, 16};
            int[] counted = {4, 4, 3};
            for (int i = 0; i < 3; i++) {
                long at = pad.offset(3 + 2 * i, 0, 5).asLong();
                helper.assertValueEqual(network.hubCapacity(at), budgets[i], "hub " + i + " budget");
                helper.assertValueEqual(network.hubChannels(at), counted[i], "hub " + i + " count");
            }
            NetworkState adHoc = network(helper, pad.offset(2, 0, 7));
            helper.assertTrue(adHoc.isAdHoc(), "the back row has a controller");
            helper.assertValueEqual(adHoc.channelsHeld(), 8, "channels without a controller");
            helper.assertValueEqual(adHoc.offlineDevices().size(), 2, "devices turned away without a controller");
            for (NetworkDevice device : adHoc.offlineDevices()) {
                helper.assertValueEqual(device.deviceLink().channelShortage().orElseThrow().kind(),
                        ChannelShortage.Kind.AD_HOC, "why the back row is offline");
            }
            // Priority: everything in the drive at the highest priority.
            helper.assertTrue(chest.isEmpty(), "the import chest is not empty");
            helper.assertTrue(stores(first), "nothing in the drive at the highest priority");
            helper.assertTrue(bulk.contents() == null, "the bulk drive took something");
            helper.assertFalse(stores(last), "the drive at -100 took something");
        }).thenExecute(() -> {
            helper.assertValueEqual(first.priority(), Integer.MAX_VALUE, "the first drive's priority");
            helper.assertValueEqual(last.priority(), -100, "the last drive's priority");
            level.setBlockAndUpdate(hub, Blocks.AIR.defaultBlockState());
            helper.assertFalse(first.removeCell().isEmpty(), "no cell in the first drive");
            chest.setItem(0, new ItemStack(Items.COBBLESTONE, 16));
            chest.setChanged();
        }).thenWaitUntil(() -> {
            NetworkState network = network(helper, pad.offset(2, 0, 3));
            helper.assertTrue(network.controllerDevice() == entity(helper, standby, ControllerBlockEntity.class),
                    "the standby controller did not take over");
            helper.assertValueEqual(network.status(), StorageNetwork.Status.ONLINE, "after the takeover");
            BulkContents held = bulk.contents();
            helper.assertTrue(held != null && held.count() == 16, "the bulk drive holds " + held);
            helper.assertFalse(stores(last), "cobblestone went to the drive at -100");
        }).thenExecute(() -> {
            chest.setItem(0, new ItemStack(Items.DIRT, 8));
            chest.setChanged();
        }).thenWaitUntil(() -> helper.assertTrue(stores(last), "dirt did not go to the drive at -100"))
                .thenExecute(() -> Showcase.clear(level, build.bounds())).thenSucceed();
    }

    /** {@code showcase clear} removes a row and a single station built elsewhere, not only the last build. */
    private static void clearRemovesEveryBuild(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Showcase.Build row = Showcase.build(level, helper.absolutePos(new BlockPos(1, 1, 1)),
                List.of(ShowcaseStation.CABLES), null);
        Showcase.Build single = Showcase.build(level, helper.absolutePos(new BlockPos(1 + Showcase.STEP, 1, 1)),
                List.of(ShowcaseStation.CABLES), null);
        BlockPos[] controllers = {Objects.requireNonNull(row.hubs().get(ShowcaseStation.CABLES)),
                Objects.requireNonNull(single.hubs().get(ShowcaseStation.CABLES))};
        helper.startSequence().thenExecute(() -> {
            helper.assertTrue(Showcase.clear(level) >= 2, "builds cleared");
            for (BlockPos controller : controllers) {
                helper.assertTrue(level.getBlockState(controller).isAir(), "a controller is still at " + controller);
            }
            helper.assertValueEqual(Showcase.clear(level), 0, "builds left after clearing");
        }).thenSucceed();
    }

    /**
     * The logic station's eight graphs all run once its stock is in, and each does what the station's summary says: the
     * intake port starts off, the sticks graph crafts sticks and the finished job posts a notice for the display wall;
     * the lever on the controller turns the intake on (its chest empties into the network), lights the lamp once
     * cobblestone is over 100 and sets the drive's priority to 15; the button on the outbox port moves 16 cobblestone
     * into its chest; items added to the sensed chest post a notice; three flips of the lever over the cable light the
     * latch lamp.
     */
    private static void logicWorks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = NetworkGameTests.player(level, "showcase_logic");
        Showcase.Build build = Showcase.build(level, helper.absolutePos(new BlockPos(1, 1, 1)),
                List.of(ShowcaseStation.LOGIC), player);
        helper.assertTrue(build.warnings().isEmpty(), "builder warnings: " + build.warnings());
        BlockPos pad = Objects.requireNonNull(build.pads().get(ShowcaseStation.LOGIC));
        BlockPos hub = Objects.requireNonNull(build.hubs().get(ShowcaseStation.LOGIC));
        LogicControllerBlockEntity logic = entity(helper, pad.offset(2, 0, 5), LogicControllerBlockEntity.class);
        PortBlockEntity intake = entity(helper, pad.offset(3, 0, 3), PortBlockEntity.class);
        ChestBlockEntity intakeChest = entity(helper, pad.offset(3, 0, 2), ChestBlockEntity.class);
        ChestBlockEntity outboxChest = entity(helper, pad.offset(2, 0, 2), ChestBlockEntity.class);
        ChestBlockEntity sensedChest = entity(helper, pad.offset(4, 0, 2), ChestBlockEntity.class);
        DriveBlockEntity bank = entity(helper, pad.offset(3, 0, 5), DriveBlockEntity.class);
        ConditionSwitchBlockEntity lamp = entity(helper, pad.offset(5, 1, 4), ConditionSwitchBlockEntity.class);
        ConditionSwitchBlockEntity latch = entity(helper, pad.offset(6, 1, 4), ConditionSwitchBlockEntity.class);
        BlockPos leverOnCable = pad.offset(1, 1, 4);
        // Step 7 (the editor) is ticked by hand, step 1 (the lever) only by its detector.
        helper.assertTrue(ShowcaseBoards.toggle(level, pad.offset(6, 1, 0), 7).isPresent(), "editor step not ticked");
        helper.assertTrue(ShowcaseBoards.done(level, pad, 6), "editor step not done after its tick");
        helper.assertTrue(ShowcaseBoards.toggle(level, pad.offset(6, 1, 0), 1).isEmpty(), "lever step ticked by hand");
        // Background crafts go to a fake queue in GameTests; this station's go to the real one.
        StockKeepingGameTests.QUEUE.realIn(helper.getBounds());
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertFalse(Showcase.stocking(), "stock or graphs still waiting for the network");
            helper.assertValueEqual(logic.worker().size(), LogicExamples.NAMES.size(), "graphs");
            for (int i = 0; i < logic.worker().size(); i++) {
                helper.assertTrue(logic.worker().errors(i).isEmpty(),
                        LogicExamples.NAMES.get(i) + " has errors: " + logic.worker().errors(i));
                helper.assertTrue(logic.worker().status(i) != LogicWorker.Status.PAUSED,
                        LogicExamples.NAMES.get(i) + " is paused");
            }
            NetworkState network = network(helper, hub);
            for (String label : List.of("intake", "outbox", "bank", "lamp", "latch lamp", "chest sensor",
                    "lever sensor")) {
                helper.assertTrue(network.labels().has(label), "no device labelled " + label);
            }
            helper.assertTrue(intake.switchedOff(), "the intake port is on while the lever is off");
            helper.assertTrue(network.contents().amount(ItemKey.of(Items.STICK)) >= 16,
                    "sticks: " + network.contents().amount(ItemKey.of(Items.STICK)) + ", planks "
                            + network.contents().amount(ItemKey.of(Items.OAK_PLANKS)));
            helper.assertTrue(noticed(network.displayNotices(), LogicExamples.STICKS_READY), "no sticks notice");
        }).thenExecute(() -> {
            helper.assertValueEqual(lamp.worker().strength(), 0, "lamp before the lever");
            helper.assertValueEqual(intakeChest.getItem(0).getCount(), 64, "intake chest before the lever");
            helper.assertFalse(ShowcaseBoards.done(level, pad, 0), "lever step done before the lever");
            pull(helper, pad.offset(2, 1, 5));
        }).thenWaitUntil(() -> {
            helper.assertFalse(intake.switchedOff(), "the intake port is still off");
            helper.assertTrue(intakeChest.isEmpty(), "the intake chest is not empty");
            helper.assertValueEqual(network(helper, hub).contents().amount(ItemKey.of(Items.COBBLESTONE)), 114L,
                    "cobblestone");
            helper.assertValueEqual(lamp.worker().strength(), 15, "lamp over 100 cobblestone");
            helper.assertValueEqual(bank.priority(), 15, "the drive's priority");
            helper.assertTrue(ShowcaseBoards.done(level, pad, 0), "lever step not done");
            helper.assertTrue(wallText(helper, pad).contains("<green>\u2714 Flip the"),
                    "the wall shows no green lever step");
        }).thenExecute(() -> {
            BlockState button = level.getBlockState(pad.offset(2, 1, 3));
            ((ButtonBlock) button.getBlock()).press(button, level, pad.offset(2, 1, 3), null);
        }).thenWaitUntil(() -> {
            helper.assertValueEqual(outboxChest.getItem(0).getCount(), 16, "moved into the outbox chest");
            helper.assertValueEqual(network(helper, hub).contents().amount(ItemKey.of(Items.COBBLESTONE)), 98L,
                    "cobblestone after the move");
        }).thenExecute(() -> {
            helper.assertValueEqual(lamp.worker().strength(), 15, "the lamp switches back only at 64");
            helper.assertFalse(noticed(network(helper, hub).displayNotices(), LogicExamples.CHEST_FULL),
                    "chest notice too early");
            sensedChest.setItem(1, new ItemStack(Items.DIRT, 20));
            sensedChest.setChanged();
        }).thenWaitUntil(() -> helper.assertTrue(
                noticed(network(helper, hub).displayNotices(), LogicExamples.CHEST_FULL), "no chest notice"))
                .thenExecute(() -> {
                    helper.assertValueEqual(latch.worker().strength(), 0, "latch lamp before the flips");
                    pull(helper, leverOnCable);
                }).thenIdle(5).thenExecute(() -> pull(helper, leverOnCable)).thenIdle(5)
                .thenExecute(() -> pull(helper, leverOnCable)).thenIdle(5).thenExecute(() -> {
                    helper.assertValueEqual(latch.worker().strength(), 0, "latch lamp after two flips");
                    pull(helper, leverOnCable);
                }).thenIdle(5).thenExecute(() -> pull(helper, leverOnCable))
                .thenWaitUntil(() -> helper.assertValueEqual(latch.worker().strength(), 15, "latch lamp"))
                .thenWaitUntil(() -> {
                    // Every step with a detector on the wall is green: the lamps light, not only their switches.
                    List<ShowcaseSteps.Step> steps = ShowcaseSteps.steps(ShowcaseStation.LOGIC);
                    for (int i = 0; i < steps.size(); i++) {
                        helper.assertTrue(steps.get(i).manual() || ShowcaseBoards.done(level, pad, i),
                                "logic step " + (i + 1) + " is open: " + steps.get(i).expected());
                    }
                }).thenExecute(() -> {
                    StockKeepingGameTests.QUEUE.forget(helper.getBounds());
                    Showcase.clear(level, build.bounds());
                }).thenSucceed();
    }

    /**
     * Every station is built, and each step with a detector that needs no player turns green on its step wall on its
     * own: the examples, redstone ones included, do what their walls promise. Then the test does what a player would
     * for the steps that are easy to drive (pulling a cell, taking from a chest, breaking and placing a cable) and
     * those turn green too. The rest are in {@link #PLAYER_STEPS}.
     */
    private static void stepsHappenWithoutAPlayer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = NetworkGameTests.player(level, "showcase_steps");
        List<ShowcaseStation> all = List.of(ShowcaseStation.values());
        int half = (all.size() + 1) / 2;
        Showcase.Build front = Showcase.build(level, helper.absolutePos(new BlockPos(1, 1, 1)), all.subList(0, half),
                player);
        Showcase.Build back = Showcase.build(level, helper.absolutePos(new BlockPos(1, 1, 1 + Showcase.STEP)),
                all.subList(half, all.size()), player);
        Map<ShowcaseStation, BlockPos> pads = new EnumMap<>(ShowcaseStation.class);
        for (Showcase.Build build : List.of(front, back)) {
            helper.assertTrue(build.warnings().isEmpty(), "builder warnings: " + build.warnings());
            pads.putAll(build.pads());
        }
        for (Map<ShowcaseStation, Set<Integer>> listed : List.of(PLAYER_STEPS, DRIVEN_STEPS)) {
            for (Map.Entry<ShowcaseStation, Set<Integer>> station : listed.entrySet()) {
                List<ShowcaseSteps.Step> steps = ShowcaseSteps.steps(station.getKey());
                for (int i : station.getValue()) {
                    helper.assertTrue(i < steps.size() && !steps.get(i).manual(),
                            station.getKey() + " step " + (i + 1) + " is listed as a player step but has no detector");
                }
            }
        }
        BlockPos core = Objects.requireNonNull(pads.get(ShowcaseStation.CORE));
        BlockPos subnet = Objects.requireNonNull(pads.get(ShowcaseStation.SUBNET));
        BlockPos tools = Objects.requireNonNull(pads.get(ShowcaseStation.TOOLS));
        BlockPos cables = Objects.requireNonNull(pads.get(ShowcaseStation.CABLES));
        // Background crafts (the stock keeper, the crafting card, the logic sticks graph) go to the real job queue. By
        // the builds' bounds: the structure is too low to hold every node a network may name first.
        StockKeepingGameTests.QUEUE.realIn(front.bounds());
        StockKeepingGameTests.QUEUE.realIn(back.bounds());
        helper.startSequence().thenWaitUntil(() -> {
            List<String> open = new ArrayList<>();
            for (Map.Entry<ShowcaseStation, BlockPos> pad : pads.entrySet()) {
                List<ShowcaseSteps.Step> steps = ShowcaseSteps.steps(pad.getKey());
                Set<Integer> waiting = PLAYER_STEPS.getOrDefault(pad.getKey(), Set.of());
                Set<Integer> driven = DRIVEN_STEPS.getOrDefault(pad.getKey(), Set.of());
                for (int i = 0; i < steps.size(); i++) {
                    if (!steps.get(i).manual() && !waiting.contains(i) && !driven.contains(i)
                            && !ShowcaseBoards.done(level, pad.getValue(), i)) {
                        open.add(pad.getKey() + " step " + (i + 1) + " (" + steps.get(i).expected() + ")");
                    }
                }
            }
            helper.assertTrue(open.isEmpty(), "steps that never happened: " + open);
        }).thenExecute(() -> {
            helper.assertFalse(entity(helper, core.offset(3, 0, 5), DriveBlockEntity.class).removeCell().isEmpty(),
                    "no cell in the core's west drive");
            entity(helper, subnet.offset(3, 0, 2), ChestBlockEntity.class).clearContent();
            ChestBlockEntity wrench = entity(helper, tools.offset(3, 0, 2), ChestBlockEntity.class);
            wrench.setItem(0, ItemStack.EMPTY);
            wrench.setChanged();
            level.setBlockAndUpdate(cables.offset(2, 0, 4), Blocks.AIR.defaultBlockState());
        }).thenWaitUntil(() -> {
            helper.assertTrue(ShowcaseBoards.done(level, core, 3), "core: the pulled cell's step is open");
            helper.assertTrue(ShowcaseBoards.done(level, subnet, 3), "subnet: the wireless terminal's step is open");
            helper.assertTrue(ShowcaseBoards.done(level, tools, 1), "tools: the wrench's step is open");
            helper.assertTrue(ShowcaseBoards.done(level, cables, 4), "cables: the broken cable's step is open");
        }).thenExecute(() -> helper.assertTrue(ConduitBlock.placeConduit(level, cables.offset(2, 0, 4),
                CableContent.STORAGE_CABLE_TIER_1.type().get(), null), "the cable is not placed back"))
                .thenWaitUntil(() -> helper.assertTrue(ShowcaseBoards.done(level, cables, 5),
                        "cables: the step for the cable put back is open"))
                .thenExecute(() -> {
                    StockKeepingGameTests.QUEUE.forget(front.bounds());
                    StockKeepingGameTests.QUEUE.forget(back.bounds());
                    Showcase.clear(level, front.bounds());
                    Showcase.clear(level, back.bounds());
                }).thenSucceed();
    }

    private static void pull(GameTestHelper helper, BlockPos pos) {
        BlockState lever = helper.getLevel().getBlockState(pos);
        ((LeverBlock) lever.getBlock()).pull(lever, helper.getLevel(), pos, null);
    }

    private static boolean noticed(NetworkNotices notices, String text) {
        for (NetworkNotices.Notice notice : notices.lines()) {
            if (notice.text().equals(text)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The subnet station's controller has the main network in front of it, whose cable shows a stub towards it, and is
     * a network of its own; the crafting station crafts sticks on the assembler and iron ingots in the furnace from
     * what the builder stocked.
     */
    private static void subnetAndCraftingWork(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = NetworkGameTests.player(level, "showcase");
        Showcase.Build build = Showcase.build(level, helper.absolutePos(new BlockPos(1, 1, 1)),
                List.of(ShowcaseStation.SUBNET, ShowcaseStation.CRAFTING, ShowcaseStation.PORTS), player);
        BlockPos subnetPad = Objects.requireNonNull(build.pads().get(ShowcaseStation.SUBNET));
        BlockPos portsPad = Objects.requireNonNull(build.pads().get(ShowcaseStation.PORTS));
        BlockPos crafting = Objects.requireNonNull(build.hubs().get(ShowcaseStation.CRAFTING));
        UUID[] ids = new UUID[2];
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertFalse(Showcase.stocking(), "stock still waiting for a network");
            SubnetBlockEntity subnet = entity(helper, subnetPad.offset(5, 0, 4), SubnetBlockEntity.class);
            NetworkState parent = subnet.parent();
            helper.assertTrue(parent != null, "subnet has no parent");
            helper.assertTrue(parent != subnet.network(), "subnet merged into its parent");
            helper.assertTrue(subnet.network() != null && subnet.network().isPowered(), "subnet not powered");
            ConduitData cable = entity(helper, subnetPad.offset(4, 0, 4), ConduitBlockEntity.class)
                    .findNetwork(StorageNetworks.ID);
            helper.assertTrue(cable != null && ConduitConnections.isConnected(cable.blockFaces(), Direction.EAST),
                    "the parent's cable shows no stub towards the subnet controller");
        }).thenExecute(() -> {
            // The stock keeper crafts sticks and the export port's crafting card iron ingots too: the jobs still get
            // their own.
            NetworkState network = network(helper, crafting);
            ids[0] = submit(helper, network, ItemKey.of(Items.STICK), 4);
            ids[1] = submit(helper, network, ItemKey.of(Items.IRON_INGOT), 2);
        }).thenWaitUntil(() -> {
            NetworkState network = network(helper, crafting);
            helper.assertTrue(network.contents().amount(ItemKey.of(Items.STICK)) >= 4,
                    "sticks: " + state(helper, ids[0]) + ", planks "
                            + network.contents().amount(ItemKey.of(Items.OAK_PLANKS)) + ", sticks "
                            + network.contents().amount(ItemKey.of(Items.STICK)));
            // The export port sends ingots on to its chest, so the job finishing is what counts.
            helper.assertTrue(CraftingJobs.get(helper.getLevel()).get(ids[1]) == null,
                    "ingots: " + state(helper, ids[1]));
            // The hopper feeds the item P2P's input; logs come out at the output's chest.
            ChestBlockEntity logs = entity(helper, portsPad.offset(6, 0, 2), ChestBlockEntity.class);
            helper.assertTrue(logs.countItem(Items.OAK_LOG) > 0, "no logs through the item P2P");
        }).thenExecute(() -> Showcase.clear(level, build.bounds())).thenSucceed();
    }

    private static UUID submit(GameTestHelper helper, NetworkState network, StackKey key, long amount) {
        CraftingPlanner.Result plan = CraftingPlanner.planNow(network, key, amount);
        JobQueue.Submission submission = JobQueue.submit(network, network.nodes().iterator().nextLong(), plan,
                JobQueue.Request.player(UUID.randomUUID()));
        return submission.job().map(CraftingJob::id).orElseThrow(
                () -> helper.assertionException(Component.literal(key + " refused: " + submission.refusal())));
    }

    private static Object state(GameTestHelper helper, UUID id) {
        CraftingJob job = CraftingJobs.get(helper.getLevel()).get(id);
        var run = JobQueue.progress(helper.getLevel(), id);
        String detail = "";
        if (job != null && run != null) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < job.steps().size(); i++) {
                out.append(" [").append(job.steps().get(i)).append(" sent ").append(run.sent(i)).append(" recv ")
                        .append(run.received(i)).append(" infl ").append(run.inflight(i)).append(']');
            }
            detail = out + " buffer " + run.buffer() + " missing " + run.missing();
        }
        return job == null ? "gone" : job.state() + detail;
    }

    private static void buildsEveryStation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = NetworkGameTests.player(level, "showcase");
        // Two rows, as all stations in one row are wider than the structure.
        List<ShowcaseStation> all = List.of(ShowcaseStation.values());
        int half = (all.size() + 1) / 2;
        Showcase.Build front = Showcase.build(level, helper.absolutePos(new BlockPos(1, 1, 1)), all.subList(0, half),
                player);
        Showcase.Build back = Showcase.build(level, helper.absolutePos(new BlockPos(1, 1, 1 + Showcase.STEP)),
                all.subList(half, all.size()), player);
        Map<ShowcaseStation, BlockPos> pads = new EnumMap<>(ShowcaseStation.class);
        Map<ShowcaseStation, BlockPos> hubs = new EnumMap<>(ShowcaseStation.class);
        for (Showcase.Build build : List.of(front, back)) {
            helper.assertTrue(build.warnings().isEmpty(), "builder warnings: " + build.warnings());
            pads.putAll(build.pads());
            hubs.putAll(build.hubs());
        }
        helper.assertValueEqual(hubs.size(), ShowcaseStation.values().length, "stations built");
        ResearchTeam team = Research.team(player, false);
        for (ResearchTree.Entry entry : Research.tree(level.getServer()).entries()) {
            helper.assertTrue(Research.isComplete(team, entry), "research not done: " + entry.id());
        }
        helper.startSequence().thenWaitUntil(() -> {
            for (Map.Entry<ShowcaseStation, BlockPos> hub : hubs.entrySet()) {
                check(helper, hub.getKey(), Objects.requireNonNull(pads.get(hub.getKey())), hub.getValue());
            }
            helper.assertFalse(Showcase.stocking(), "stock still waiting for a network");
            for (Map.Entry<ShowcaseStation, BlockPos> pad : pads.entrySet()) {
                // Every station but machines and logic starts with "Walk in: online".
                if (pad.getKey() != ShowcaseStation.MACHINES && pad.getKey() != ShowcaseStation.LOGIC) {
                    helper.assertTrue(ShowcaseBoards.done(level, pad.getValue(), 0),
                            pad.getKey() + ": the online step is not done");
                }
            }
            NetworkState core = network(helper, Objects.requireNonNull(hubs.get(ShowcaseStation.CORE)));
            helper.assertTrue(core.contents().amount(ItemKey.of(Items.COBBLESTONE)) >= 2_000, "core stock missing");
        }).thenExecute(() -> {
            BlockPos controller = Objects.requireNonNull(hubs.get(ShowcaseStation.CORE));
            helper.assertValueEqual(
                    ShowcaseBoards.count(level, front.bounds()) + ShowcaseBoards.count(level, back.bounds()),
                    ShowcaseStation.values().length, "step walls watched");
            for (Showcase.Build build : List.of(front, back)) {
                // By bounds, not every build in the level: repeated runs build side by side in one level.
                Showcase.clear(level, build.bounds());
                helper.assertValueEqual(ShowcaseBoards.count(level, build.bounds()), 0, "step walls still watched");
                helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class, build.bounds().inflate(1)).isEmpty(),
                        "clearing dropped items");
            }
            helper.assertTrue(level.getBlockState(controller).isAir(), "the core controller is still there");
        }).thenSucceed();
    }

    private static void check(GameTestHelper helper, ShowcaseStation station, BlockPos pad, BlockPos hub) {
        for (int x = 5; x <= 8; x++) {
            for (int y = 0; y <= 2; y++) {
                expect(helper, pad.offset(x, y, 0), DisplayContent.PANEL.get(), station);
            }
        }
        DisplaySettings wall = shown(helper, pad.offset(5, 0, 0));
        helper.assertValueEqual(wall.source(), DisplaySourceKind.TEXT, station + " step wall source");
        String text = wall.text();
        helper.assertTrue(text.contains(station.english()), station + " step wall has no heading: " + text);
        // What a player could save: within the length, every icon an item; all steps still open, the longest form.
        helper.assertTrue(DisplayText.problem(text).isEmpty(), station + " step wall: "
                + DisplayText.problem(text).map(Component::getString).orElse("") + " (" + text.length() + " chars)");
        if (station == ShowcaseStation.LOGIC) {
            helper.assertValueEqual(shown(helper, pad.offset(5, 0, 3)).source(), DisplaySourceKind.LOGIC_NOTICES,
                    "the notice wall's source");
        }
        if (station == ShowcaseStation.MACHINES) {
            expect(helper, hub, MachineContent.FUEL_GENERATOR.get(), station);
            StorageMachineBlockEntity lithograph = entity(helper, pad.offset(3, 0, 4), StorageMachineBlockEntity.class);
            helper.assertTrue(lithograph.energy().stored() > 0, "the lithograph has no energy");
            return;
        }
        expect(helper, hub, ControllerContent.CONTROLLER.get(), station);
        helper.assertValueEqual(helper.getLevel().getBlockState(hub).getValue(ControllerBlock.STATUS),
                ControllerStatus.ONLINE, station + " controller");
        helper.assertTrue(network(helper, hub).isPowered(), station + " network not powered");
        switch (station) {
            case CORE -> {
                // The final controller design (M7R): two blocks, the cards in their slots.
                ControllerBlockEntity primary = Objects
                        .requireNonNull(entity(helper, hub, ControllerBlockEntity.class).primary());
                helper.assertValueEqual(primary.blockCount(), 2, "core controller blocks");
                helper.assertValueEqual(primary.cards().installed(), Showcase.CORE_CARDS.size(),
                        "core controller cards");
                helper.assertValueEqual(entity(helper, pad.offset(3, 0, 5), DriveBlockEntity.class).cellCount(), 4,
                        "cells in drive 1");
                helper.assertValueEqual(entity(helper, pad.offset(4, 0, 5), DriveBlockEntity.class).cellCount(), 2,
                        "cells in drive 2");
            }
            case PORTS -> expect(helper, pad.offset(6, 1, 5), DisplayContent.PANEL.get(), station);
            case SUBNET -> expect(helper, pad.offset(5, 0, 4), SubnetContent.SUBNET.get(), station);
            case CRAFTING -> {
                expect(helper, pad.offset(2, 0, 3), ProviderContent.PROVIDER.get(), station);
                expect(helper, pad.offset(2, 0, 2), Blocks.FURNACE, station);
                for (int x : new int[]{3, 5, 7}) {
                    CpuCoreBlockEntity cpu = entity(helper, pad.offset(x, 0, 5), CpuCoreBlockEntity.class);
                    helper.assertTrue(cpu.count(CpuPart.STORAGE) >= 1, "CPU at x " + x + " has no storage");
                }
                helper.assertValueEqual(entity(helper, pad.offset(7, 0, 5), CpuCoreBlockEntity.class).tier(), 3,
                        "third CPU tier");
                expect(helper, pad.offset(7, 0, 5), CpuContent.CORE_3.get(), station);
            }
            case PRIORITY -> {
                helper.assertValueEqual(entity(helper, pad.offset(3, 0, 5), DriveBlockEntity.class).priority(),
                        Integer.MAX_VALUE, "the first drive's priority");
                helper.assertValueEqual(entity(helper, pad.offset(4, 0, 5), BulkDriveBlockEntity.class).priority(), 0,
                        "the bulk drive's priority");
                helper.assertValueEqual(entity(helper, pad.offset(5, 0, 5), DriveBlockEntity.class).priority(), -100,
                        "the last drive's priority");
            }
            case CHANNELS -> {
                expect(helper, pad.offset(3, 0, 5), HubContent.TIER_3.get(), station);
                expect(helper, pad.offset(5, 0, 5), HubContent.TIER_2.get(), station);
                expect(helper, pad.offset(7, 0, 5), HubContent.TIER_1.get(), station);
                expect(helper, pad.offset(2, 0, 2), ControllerContent.CONTROLLER.get(), station);
            }
            case BRIDGES -> {
                // In CI neither AE2 nor Refined Storage is loaded: a sign stands where each network would be.
                int[] xs = {3, 6};
                ShowcaseBridges.Foreign[] mods = ShowcaseBridges.Foreign.values();
                for (int i = 0; i < mods.length; i++) {
                    if (ShowcaseBridges.builder(mods[i]).isEmpty()) {
                        expect(helper, pad.offset(xs[i], 0, 6), Blocks.OAK_SIGN, station);
                    }
                }
            }
            case ENERGY -> {
                // No creative source here: the network is powered only through the generator's supply.
                expect(helper, pad.offset(3, 0, 3), MachineContent.FUEL_GENERATOR.get(), station);
                expect(helper, pad.offset(5, 0, 5), EnergyContent.TIER_3.get(), station);
                helper.assertTrue(
                        network(helper, hub).energyNetwork().map(energy -> energy.readout().supply() > 0).orElse(false),
                        "energy station: the generator supplies no MU");
            }
            case TOOLS -> expect(helper, pad.offset(3, 0, 2), Blocks.CHEST, station);
            case LOGIC -> expect(helper, pad.offset(2, 0, 5), LogicContent.CONTROLLER.get(), station);
            default -> {
            }
        }
    }

    private static String wallText(GameTestHelper helper, BlockPos pad) {
        return shown(helper, pad.offset(5, 0, 0)).text();
    }

    /**
     * What the wall of the panel at {@code pos} shows: its anchor's settings, which only the anchor draws, as a client
     * reads them from the anchor's update tag; they must match the server's.
     */
    static DisplaySettings shown(GameTestHelper helper, BlockPos pos) {
        DisplayPanelBlockEntity panel = entity(helper, pos, DisplayPanelBlockEntity.class);
        DisplayPanelBlockEntity anchor = entity(helper, panel.anchor(), DisplayPanelBlockEntity.class);
        DisplayPanelBlockEntity client = new DisplayPanelBlockEntity(anchor.getBlockPos(), anchor.getBlockState());
        client.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(),
                anchor.getUpdateTag(helper.getLevel().registryAccess())));
        helper.assertValueEqual(client.settings(), anchor.settings(), "the synced settings of the wall at " + pos);
        return client.settings();
    }

    private static void expect(GameTestHelper helper, BlockPos pos, Block block, ShowcaseStation station) {
        helper.assertTrue(helper.getLevel().getBlockState(pos).is(block),
                station + ": no " + block.getName().getString() + " at " + pos.toShortString());
    }

    private static <T> T entity(GameTestHelper helper, BlockPos pos, Class<T> type) {
        Object entity = helper.getLevel().getBlockEntity(pos);
        if (!type.isInstance(entity)) {
            throw helper.assertionException(Component.literal("no " + type.getSimpleName() + " at " + pos));
        }
        return type.cast(entity);
    }

    private static NetworkState network(GameTestHelper helper, BlockPos pos) {
        NetworkState network = StorageNetworks.network(helper.getLevel(), StorageNetworks.ID, pos);
        if (network == null) {
            throw helper.assertionException(Component.literal("no network at " + pos));
        }
        return network;
    }
}
