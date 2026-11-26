package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.condition.ConditionContent;
import dev.silencebound.maxtech.storage.condition.ConditionSettings;
import dev.silencebound.maxtech.storage.condition.ConditionSwitchBlockEntity;
import dev.silencebound.maxtech.storage.condition.ConditionType;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.display.DisplayPanelBlockEntity;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.display.DisplaySettings;
import dev.silencebound.maxtech.storage.display.DisplaySourceKind;
import dev.silencebound.maxtech.storage.logic.LogicEvaluator;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.NodeType;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.RunTrace;
import dev.silencebound.maxtech.storage.logic.controller.LogicAction;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The action node types of batch 3 in a real controller: cancelling crafts gives back what the job held and frees the
 * CPU, and leaves other jobs alone when only the graph's own are cancelled or when the owner may not cancel them; a
 * pulse lasts its ticks and starts again or is ignored; a display shows the text with its values, at most once per
 * {@code logic.displayWriteTicks}; a port filter set by logic exports and is saved with the port; devices of another
 * owner are left alone.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LogicActionNodeGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey PLANKS = ItemKey.of(Items.OAK_PLANKS);
    private static final StackKey STICK = ItemKey.of(Items.STICK);
    /** {@code logic.displayWriteTicks} in both presets. */
    private static final int DISPLAY_TICKS = 20;

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("logic_act_cancel_returns_items", LogicActionNodeGameTests::cancelReturns);
        register("logic_act_cancel_own_only", LogicActionNodeGameTests::cancelOwnOnly);
        register("logic_act_cancel_access", LogicActionNodeGameTests::cancelAccess);
        register("logic_act_pulse_length", LogicActionNodeGameTests::pulseLength);
        register("logic_act_pulse_restart_or_ignore", LogicActionNodeGameTests::pulseAgain);
        register("logic_act_display_value_and_rate", LogicActionNodeGameTests::displayRate);
        register("logic_act_set_filter_exports_and_saves", LogicActionNodeGameTests::setFilter);
        register("logic_act_devices_of_others_left_alone", LogicActionNodeGameTests::othersLeftAlone);
        register("logic_act_older_actions_need_access", LogicActionNodeGameTests::olderActionsNeedAccess);
        register("logic_act_display_wait_checks_again", LogicActionNodeGameTests::displayWaitChecksAgain);
        register("logic_act_pulse_ends_without_access", LogicActionNodeGameTests::pulseEndsWithoutAccess);
    }

    private LogicActionNodeGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("logic_act"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 400);
        }
    }

    // ---- helpers

    private static LogicEvaluator<LogicKey> evaluator(GameTestHelper helper, LogicControllerBlockEntity entity) {
        if (entity.worker().size() == 0) {
            throw helper.assertionException(Component.literal("no graph yet"));
        }
        return entity.worker().evaluator(0)
                .orElseThrow(() -> helper.assertionException(Component.literal("graph 0 is paused")));
    }

    private static void ran(GameTestHelper helper, LogicControllerBlockEntity entity) {
        helper.assertTrue(evaluator(helper, entity).runs() > 0, "graph 0 has not run yet");
    }

    /** The why reason the last run gave for node {@code node}; empty for none. */
    private static String why(GameTestHelper helper, LogicControllerBlockEntity entity, int node) {
        RunTrace trace = evaluator(helper, entity).trace();
        int i = trace.find(node);
        return i < 0 ? "" : trace.reason(i);
    }

    /** Toggles a redstone block on top of {@code controller}: "redstone changes" fires once. */
    private static void toggle(GameTestHelper helper, BlockPos controller) {
        BlockPos top = controller.above();
        helper.setBlock(top, helper.getBlockState(top).is(Blocks.REDSTONE_BLOCK) ? Blocks.AIR : Blocks.REDSTONE_BLOCK);
    }

    /** "Redstone into the controller changes" does {@code action} (node 2). */
    private static LogicGraph<LogicKey> onRedstone(NodeType action,
            UnaryOperator<dev.silencebound.maxtech.storage.logic.NodeSettings<LogicKey>> settings) {
        return new LogicControllerGameTests.Graph().node(1, NodeTypes.REDSTONE_CHANGES, s -> s)
                .node(2, action, settings).wire(1, "changed", 2, "do").build();
    }

    private static LogicControllerBlockEntity controller(GameTestHelper helper, BlockPos pos, String owner) {
        helper.setBlock(pos, LogicContent.CONTROLLER.get());
        LogicControllerBlockEntity entity = LogicControllerGameTests.entity(helper, pos);
        entity.setOwner(OwnerRecord.of(NetworkGameTests.player(helper.getLevel(), owner)));
        return entity;
    }

    /** A powered controller at x 1, a test drive at x 2 and a pattern host for sticks at x 3. */
    private static CraftingTestContent.Host crafting(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(3), CraftingTestContent.HOST.get());
        CraftingTestContent.Host host = helper.getBlockEntity(NetworkGameTests.at(3), CraftingTestContent.Host.class);
        host.setPatterns(List.of(sticks()));
        return host;
    }

    private static CraftingPattern sticks() {
        return new CraftingPattern(CraftingPattern.Kind.CRAFTING,
                List.of(new PatternInput(new PatternIngredient.Exact(ItemKey.of(Items.OAK_PLANKS)), 2, Optional.empty(),
                        false)),
                List.of(new PatternOutput(STICK, 4, 1f)), Optional.empty(), Optional.empty());
    }

    /** Plans {@code amount} sticks now and queues them for player {@code requester}; fails the test on a refusal. */
    private static UUID submitSticks(GameTestHelper helper, NetworkState network, long amount, UUID requester) {
        CraftingPlanner.Result plan = CraftingPlanner.planNow(network, STICK, amount);
        Optional<CraftingJob> submitted = JobQueue
                .submit(network, network.nodes().iterator().nextLong(), plan, JobQueue.Request.player(requester)).job();
        return submitted.orElseThrow(() -> helper.assertionException(Component.literal("job refused"))).id();
    }

    private static List<CraftingJob> stickJobs(NetworkState network) {
        List<CraftingJob> jobs = new ArrayList<>();
        for (CraftingJob job : JobQueue.jobsOn(network)) {
            if (job.key().equals(STICK)) {
                jobs.add(job);
            }
        }
        return jobs;
    }

    private static ConditionSwitchBlockEntity lamp(GameTestHelper helper, BlockPos pos, ServerPlayer owner,
            String label) {
        helper.setBlock(pos, ConditionContent.SWITCH.get());
        ConditionSwitchBlockEntity entity = helper.getBlockEntity(pos, ConditionSwitchBlockEntity.class);
        entity.setOwner(OwnerRecord.of(owner));
        entity.worker().setSettings(ConditionSettings.DEFAULT.withType(ConditionType.LOGIC));
        entity.setLabel(DeviceLabel.of(label, null));
        return entity;
    }

    /** Gives {@code owner}'s team the condition switch research, which its output needs. */
    private static void research(GameTestHelper helper, ServerPlayer owner) {
        ResearchTeam team = Research.team(owner, true);
        Research.addPoints(helper.getLevel().getServer(), team, 1000);
        Research.spendPoints(helper.getLevel().getServer(), team, MaxtechStorage.id("condition_switch"));
    }

    private static PortBlockEntity port(GameTestHelper helper, BlockPos pos, PortMode mode, String label) {
        return port(helper, pos, Direction.EAST, mode, label);
    }

    /** A port facing {@code facing}; it joins the network through its other sides. */
    private static PortBlockEntity port(GameTestHelper helper, BlockPos pos, Direction facing, PortMode mode,
            String label) {
        helper.setBlock(pos, PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, facing)
                .setValue(PortBlock.MODE, mode));
        PortBlockEntity port = helper.getBlockEntity(pos, PortBlockEntity.class);
        port.setLabel(DeviceLabel.of(label, null));
        return port;
    }

    private static int count(ChestBlockEntity chest, Item item) {
        int total = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack stack = chest.getItem(i);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    // ---- cancel craft

    /**
     * A running job for 64 sticks (32 planks) is cancelled by a graph: the planks the CPU still held go back to
     * storage, the job is gone and the CPU is free again. Planks in storage plus those sent to the machine make 32.
     */
    private static void cancelReturns(GameTestHelper helper) {
        CraftingTestContent.Host host = crafting(helper);
        helper.setBlock(NetworkGameTests.at(4), CpuContent.CORE_1.get());
        BlockPos logic = NetworkGameTests.at(5);
        LogicControllerBlockEntity entity = controller(helper, logic, "lan_cancel");
        UUID[] job = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            entity.worker().setGraph(0, "Cancel",
                    onRedstone(NodeTypes.CANCEL_CRAFT, s -> s.withItem("item", LogicKey.of(STICK))));
        }).thenWaitUntil(() -> ran(helper, entity)).thenExecute(() -> {
            NetworkState network = LogicControllerGameTests.network(helper, entity);
            NetworkGameTests.insert(network, PLANKS, 32);
            job[0] = submitSticks(helper, network, 64, UUID.randomUUID());
        }).thenWaitUntil(() -> helper.assertTrue(host.pushed >= 1, "a craft sent")).thenExecute(() -> {
            helper.assertValueEqual(JobQueue.freeCpus(LogicControllerGameTests.network(helper, entity)), 0,
                    "the CPU is busy");
            toggle(helper, logic);
        }).thenWaitUntil(() -> helper.assertTrue(JobQueue.progress(helper.getLevel(), job[0]) == null, "job gone"))
                .thenExecute(() -> {
                    NetworkState network = LogicControllerGameTests.network(helper, entity);
                    long planks = network.contents().amount(PLANKS);
                    helper.assertValueEqual(planks + 2L * host.pushed, 32L, "planks back plus planks sent");
                    helper.assertTrue(planks > 0, "the CPU held planks that came back");
                    helper.assertValueEqual(JobQueue.freeCpus(network), 1, "the CPU is free");
                    helper.assertValueEqual(stickJobs(network).size(), 0, "no stick job left");
                }).thenSucceed();
    }

    /**
     * A graph requests 8 sticks (through the real job queue) and a player queues 8 more; "only this graph's" cancels
     * the graph's job and leaves the player's waiting.
     */
    private static void cancelOwnOnly(GameTestHelper helper) {
        crafting(helper);
        BlockPos logic = NetworkGameTests.at(4);
        LogicControllerBlockEntity entity = controller(helper, logic, "lan_own");
        UUID player = UUID.randomUUID();
        UUID[] jobs = new UUID[2];
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph().node(1, NodeTypes.GRAPH_START, s -> s)
                .node(2, NodeTypes.REQUEST_CRAFT, s -> s.withItem("item", LogicKey.of(STICK)).withNumber("amount", 8))
                .node(3, NodeTypes.REDSTONE_CHANGES, s -> s)
                .node(4, NodeTypes.CANCEL_CRAFT, s -> s.withItem("item", LogicKey.of(STICK)).withText("scope", "own"))
                .wire(1, "started", 2, "do").wire(3, "changed", 4, "do").build();
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            NetworkState network = LogicControllerGameTests.network(helper, entity);
            NetworkGameTests.insert(network, PLANKS, 32);
            StockKeepingGameTests.QUEUE.real(network);
            entity.worker().setGraph(0, "Own", graph);
        }).thenWaitUntil(() -> helper.assertValueEqual(
                stickJobs(LogicControllerGameTests.network(helper, entity)).size(), 1, "the graph's job is queued"))
                .thenExecute(() -> {
                    NetworkState network = LogicControllerGameTests.network(helper, entity);
                    CraftingJob own = stickJobs(network).getFirst();
                    helper.assertTrue(own.requester().isEmpty(), "a background job");
                    jobs[0] = own.id();
                    jobs[1] = submitSticks(helper, network, 8, player);
                    helper.assertValueEqual(stickJobs(network).size(), 2, "and the player's");
                    entity.worker().clearActions();
                    toggle(helper, logic);
                }).thenWaitUntil(() -> helper.assertTrue(JobQueue.progress(helper.getLevel(), jobs[0]) == null,
                        "the graph's job is cancelled"))
                .thenIdle(3).thenExecute(() -> {
                    helper.assertTrue(JobQueue.progress(helper.getLevel(), jobs[1]) != null, "the player's job stays");
                    helper.assertValueEqual(stickJobs(LogicControllerGameTests.network(helper, entity)).size(), 1,
                            "one job left");
                    helper.assertTrue(entity.worker().actions().stream()
                            .anyMatch(action -> action.kind() == LogicAction.Kind.CANCEL_CRAFT), "cancelled by logic");
                }).thenSucceed();
    }

    /**
     * On a network owned by another player, the controller's owner may not cancel a third player's job: it stays and
     * the why line says so. Once the network's owner owns the controller too, the same graph cancels it.
     */
    private static void cancelAccess(GameTestHelper helper) {
        crafting(helper);
        ServerPlayer alice = NetworkGameTests.player(helper.getLevel(), "lan_alice");
        NetworkGameTests.controllerEntity(helper, NetworkGameTests.at(1)).setOwner(OwnerRecord.of(alice));
        BlockPos logic = NetworkGameTests.at(4);
        LogicControllerBlockEntity entity = controller(helper, logic, "lan_bob");
        UUID[] job = new UUID[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        LogicControllerGameTests.network(helper, entity).owner().isOwner(alice.getUUID()),
                        "alice owns the network"))
                .thenExecute(() -> {
                    NetworkState network = LogicControllerGameTests.network(helper, entity);
                    NetworkGameTests.insert(network, PLANKS, 8);
                    job[0] = submitSticks(helper, network, 8, UUID.randomUUID());
                    entity.worker().setGraph(0, "Cancel",
                            onRedstone(NodeTypes.CANCEL_CRAFT, s -> s.withItem("item", LogicKey.of(STICK))));
                }).thenWaitUntil(() -> ran(helper, entity)).thenExecute(() -> toggle(helper, logic)).thenIdle(5)
                .thenExecute(() -> {
                    helper.assertTrue(JobQueue.progress(helper.getLevel(), job[0]) != null, "the job stays");
                    helper.assertValueEqual(why(helper, entity, 2), LogicLang.WHY_CANCEL_DENIED, "why");
                    entity.setOwner(OwnerRecord.of(alice));
                    toggle(helper, logic);
                })
                .thenWaitUntil(
                        () -> helper.assertTrue(JobQueue.progress(helper.getLevel(), job[0]) == null, "cancelled"))
                .thenSucceed();
    }

    // ---- pulse redstone

    /**
     * A 30-tick pulse turns the labelled switch in logic mode on at strength 9 and off 30 ticks later, through one
     * scheduled wake: the task runs no more than a few times in between.
     */
    private static void pulseLength(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lan_pulse");
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "lan_pulse_owner");
        // Logic changes only devices its owner may configure: the switch's owner owns the controller too.
        entity.setOwner(OwnerRecord.of(owner));
        research(helper, owner);
        ConditionSwitchBlockEntity lamp = lamp(helper, NetworkGameTests.at(2).north(), owner, "lamp");
        BlockPos logic = NetworkGameTests.at(3);
        long[] on = new long[2];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Pulse",
                        onRedstone(NodeTypes.PULSE_REDSTONE,
                                s -> s.withText("label", "lamp").withNumber("ticks", 30).withNumber("strength", 9))))
                .thenWaitUntil(() -> ran(helper, entity)).thenExecute(() -> toggle(helper, logic))
                .thenWaitUntil(() -> helper.assertValueEqual(lamp.worker().strength(), 9, "on")).thenExecute(() -> {
                    LogicEvaluator<LogicKey> evaluator = evaluator(helper, entity);
                    long due = evaluator.due(evaluator.graph().position(2));
                    long now = helper.getLevel().getGameTime();
                    // Seen on or one tick after the run that turned it on.
                    helper.assertTrue(due - now == 30 || due - now == 29, "off 30 ticks after on: " + (due - now));
                    on[0] = due;
                    on[1] = entity.worker().taskRuns();
                }).thenWaitUntil(() -> helper.assertValueEqual(lamp.worker().strength(), 0, "off")).thenExecute(() -> {
                    long now = helper.getLevel().getGameTime();
                    helper.assertTrue(now >= on[0] && now <= on[0] + 1, "off at its tick, not " + (now - on[0]));
                    helper.assertTrue(entity.worker().taskRuns() - on[1] <= 3,
                            "task runs while on: " + (entity.worker().taskRuns() - on[1]));
                }).thenSucceed();
    }

    /**
     * Two pulses 10 ticks apart: the switch set to start again stays on 40 ticks after the second, the one set to
     * ignore goes off 40 ticks after the first.
     */
    private static void pulseAgain(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lan_again");
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "lan_again_owner");
        entity.setOwner(OwnerRecord.of(owner));
        research(helper, owner);
        ConditionSwitchBlockEntity restart = lamp(helper, NetworkGameTests.at(2).north(), owner, "restart");
        ConditionSwitchBlockEntity ignore = lamp(helper, NetworkGameTests.at(2).south(), owner, "ignore");
        BlockPos logic = NetworkGameTests.at(3);
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph().node(1, NodeTypes.REDSTONE_CHANGES, s -> s)
                .node(2, NodeTypes.PULSE_REDSTONE, s -> s.withText("label", "restart").withNumber("ticks", 40))
                .node(3, NodeTypes.PULSE_REDSTONE,
                        s -> s.withText("label", "ignore").withNumber("ticks", 40).withText("again", "ignore"))
                .wire(1, "changed", 2, "do").wire(1, "changed", 3, "do").build();
        long[] first = new long[1];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Again", graph)).thenWaitUntil(() -> ran(helper, entity))
                .thenExecute(() -> toggle(helper, logic)).thenWaitUntil(() -> {
                    helper.assertValueEqual(restart.worker().strength(), 15, "restart on");
                    helper.assertValueEqual(ignore.worker().strength(), 15, "ignore on");
                }).thenExecute(() -> first[0] = helper.getLevel().getGameTime()).thenIdle(10)
                .thenExecute(() -> toggle(helper, logic)).thenIdle(3)
                .thenExecute(() -> helper.assertValueEqual(why(helper, entity, 3), LogicLang.WHY_PULSE_ON,
                        "the ignoring pulse says why"))
                .thenWaitUntil(() -> helper.assertValueEqual(ignore.worker().strength(), 0, "ignore off"))
                .thenExecute(() -> {
                    long now = helper.getLevel().getGameTime();
                    helper.assertTrue(now - first[0] <= 41, "ignore off 40 ticks after the first: " + (now - first[0]));
                    helper.assertValueEqual(restart.worker().strength(), 15, "restart still on");
                }).thenWaitUntil(() -> helper.assertValueEqual(restart.worker().strength(), 0, "restart off"))
                .thenExecute(() -> {
                    long now = helper.getLevel().getGameTime();
                    helper.assertTrue(now - first[0] >= 49,
                            "restart off 40 ticks after the second: " + (now - first[0]));
                }).thenSucceed();
    }

    /**
     * Access lost while a pulse is on does not leave the switch on: the end only undoes the controller's own pulse, so
     * it needs no fresh access, and it comes at its tick.
     */
    private static void pulseEndsWithoutAccess(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lan_pulse_lost");
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "lan_pulse_lost_owner");
        ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "lan_pulse_lost_stranger");
        entity.setOwner(OwnerRecord.of(owner));
        research(helper, owner);
        research(helper, stranger);
        ConditionSwitchBlockEntity lamp = lamp(helper, NetworkGameTests.at(2).north(), owner, "lamp");
        BlockPos logic = NetworkGameTests.at(3);
        long[] due = new long[1];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Pulse",
                        onRedstone(NodeTypes.PULSE_REDSTONE, s -> s.withText("label", "lamp").withNumber("ticks", 30))))
                .thenWaitUntil(() -> ran(helper, entity)).thenExecute(() -> toggle(helper, logic))
                .thenWaitUntil(() -> helper.assertValueEqual(lamp.worker().logicStrength(), 15, "on"))
                .thenExecute(() -> {
                    LogicEvaluator<LogicKey> evaluator = evaluator(helper, entity);
                    due[0] = evaluator.due(evaluator.graph().position(2));
                    lamp.setOwner(OwnerRecord.of(stranger));
                }).thenWaitUntil(() -> helper.assertValueEqual(lamp.worker().logicStrength(), 0, "off without access"))
                .thenExecute(() -> {
                    long now = helper.getLevel().getGameTime();
                    helper.assertTrue(now >= due[0] && now <= due[0] + 1, "off at its tick, not " + (now - due[0]));
                }).thenSucceed();
    }

    // ---- write to display

    /**
     * A display labelled "board" shows the cobblestone count through "Cobble: {a}"; a change right after a write waits
     * for {@code logic.displayWriteTicks} and then shows, with no write in between.
     */
    private static void displayRate(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lan_display");
        DisplayPanelBlockEntity board = DisplayGameTests.panel(helper, NetworkGameTests.at(4), Direction.NORTH);
        board.setLabel(DeviceLabel.of("board", null));
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph()
                .node(1, NodeTypes.ITEM_COUNT, s -> s.withItem("item", LogicKey.of(COBBLE)))
                .node(2, NodeTypes.WRITE_DISPLAY, s -> s.withText("label", "board").withText("text", "Cobble: {a}"))
                .wire(1, "count", 2, "a").build();
        long[] written = new long[1];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Board", graph)).thenWaitUntil(() -> {
                    DisplaySettings settings = board.settings();
                    helper.assertValueEqual(settings.source(), DisplaySourceKind.TEXT, "source");
                    helper.assertValueEqual(settings.text(), "Cobble: 0", "text");
                }).thenExecute(() -> {
                    written[0] = helper.getLevel().getGameTime();
                    NetworkGameTests.insert(LogicControllerGameTests.network(helper, entity), COBBLE, 5);
                }).thenIdle(DISPLAY_TICKS / 2).thenExecute(() -> {
                    helper.assertValueEqual(board.settings().text(), "Cobble: 0", "still the old text");
                    helper.assertValueEqual(why(helper, entity, 2), LogicLang.WHY_DISPLAY_WAIT, "why");
                    NetworkGameTests.insert(LogicControllerGameTests.network(helper, entity), COBBLE, 2);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(board.settings().text(), "Cobble: 7", "the newest value"))
                .thenExecute(() -> {
                    long waited = helper.getLevel().getGameTime() - written[0];
                    helper.assertTrue(waited >= DISPLAY_TICKS - 1 && waited <= DISPLAY_TICKS + 2,
                            "shown when the time was up: " + waited);
                }).thenSucceed();
    }

    /**
     * A text waiting for the display's next write is checked again when its time comes: a display that lost the label
     * meanwhile, or now belongs to someone the controller's owner may not change things for, keeps what it showed.
     */
    private static void displayWaitChecksAgain(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lan_display_again");
        DisplayPanelBlockEntity board = DisplayGameTests.panel(helper, NetworkGameTests.at(4), Direction.NORTH);
        board.setLabel(DeviceLabel.of("board", null));
        DisplayPanelBlockEntity owned = DisplayGameTests.panel(helper, NetworkGameTests.at(3).south(), Direction.SOUTH);
        owned.setLabel(DeviceLabel.of("board", null));
        ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "lan_display_stranger");
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph()
                .node(1, NodeTypes.ITEM_COUNT, s -> s.withItem("item", LogicKey.of(COBBLE)))
                .node(2, NodeTypes.WRITE_DISPLAY, s -> s.withText("label", "board").withText("text", "Cobble: {a}"))
                .wire(1, "count", 2, "a").build();
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Board", graph)).thenWaitUntil(() -> {
                    helper.assertValueEqual(board.settings().text(), "Cobble: 0", "first display");
                    helper.assertValueEqual(owned.settings().text(), "Cobble: 0", "second display");
                }).thenExecute(() -> {
                    NetworkGameTests.insert(LogicControllerGameTests.network(helper, entity), COBBLE, 5);
                }).thenIdle(3).thenExecute(() -> {
                    helper.assertValueEqual(board.settings().text(), "Cobble: 0", "waiting");
                    board.setLabel(DeviceLabel.of("elsewhere", null));
                    owned.setOwner(OwnerRecord.of(stranger));
                }).thenIdle(DISPLAY_TICKS + 5).thenExecute(() -> {
                    helper.assertValueEqual(board.settings().text(), "Cobble: 0", "relabelled: not written");
                    helper.assertValueEqual(owned.settings().text(), "Cobble: 0", "another owner's: not written");
                }).thenSucceed();
    }

    // ---- set port filter

    /**
     * An export port "out" with an empty filter moves nothing; a graph sets filter slot 1 to cobblestone and the port
     * exports it into the chest. The filter stays through a save and load and in the dropped item, and the mode is
     * kept.
     */
    private static void setFilter(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lan_filter");
        BlockPos at = NetworkGameTests.at(4);
        PortBlockEntity port = port(helper, at, PortMode.EXPORT, "out");
        helper.setBlock(NetworkGameTests.at(5), Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(NetworkGameTests.at(5), ChestBlockEntity.class);
        BlockPos logic = NetworkGameTests.at(3);
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            NetworkGameTests.insert(LogicControllerGameTests.network(helper, entity), COBBLE, 100);
            entity.worker().setGraph(0, "Filter", onRedstone(NodeTypes.SET_FILTER,
                    s -> s.withText("label", "out").withItem("item", LogicKey.of(COBBLE))));
        }).thenWaitUntil(() -> ran(helper, entity)).thenIdle(20).thenExecute(() -> {
            helper.assertValueEqual(count(chest, Items.COBBLESTONE), 0, "nothing exported with an empty filter");
            toggle(helper, logic);
        }).thenWaitUntil(() -> helper.assertTrue(port.filter().getItem(0).is(Items.COBBLESTONE), "slot 1 set"))
                .thenWaitUntil(() -> helper.assertTrue(count(chest, Items.COBBLESTONE) > 0, "exported"))
                .thenExecute(() -> {
                    helper.assertValueEqual(port.mode(), PortMode.EXPORT, "mode kept");
                    CompoundTag saved = port.saveWithoutMetadata(helper.getLevel().registryAccess());
                    List<ItemStack> drops = Block.getDrops(helper.getBlockState(at), helper.getLevel(),
                            helper.absolutePos(at), port);
                    helper.assertTrue(drops.getFirst().has(net.minecraft.core.component.DataComponents.CONTAINER),
                            "the filter in the dropped item");
                    BlockPos other = NetworkGameTests.at(7);
                    PortBlockEntity loaded = port(helper, other, PortMode.EXPORT, "copy");
                    loaded.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING,
                            helper.getLevel().registryAccess(), saved));
                    helper.assertTrue(loaded.filter().getItem(0).is(Items.COBBLESTONE), "slot 1 after a load");
                    helper.assertTrue(loaded.filter().getItem(1).isEmpty(), "slot 2 untouched");
                }).thenSucceed();
    }

    // ---- access

    /**
     * A port and a display owned by another player are left alone by a controller whose owner may not change them: the
     * filter stays empty, the display keeps its source, and the why line says so. Owned by the controller's owner, the
     * same graph changes the port.
     */
    private static void othersLeftAlone(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lan_access");
        ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "lan_stranger");
        PortBlockEntity port = port(helper, NetworkGameTests.at(4), PortMode.EXPORT, "out");
        port.setOwner(OwnerRecord.of(stranger));
        DisplayPanelBlockEntity board = DisplayGameTests.panel(helper, NetworkGameTests.at(3).south(), Direction.SOUTH);
        board.setLabel(DeviceLabel.of("board", null));
        board.setOwner(OwnerRecord.of(stranger));
        BlockPos logic = NetworkGameTests.at(3);
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph().node(1, NodeTypes.REDSTONE_CHANGES, s -> s)
                .node(2, NodeTypes.SET_FILTER,
                        s -> s.withText("label", "out").withItem("item", LogicKey.of(COBBLE)).withText("port_mode",
                                "import"))
                .node(3, NodeTypes.WRITE_DISPLAY, s -> s.withText("label", "board").withText("text", "Hello"))
                .wire(1, "changed", 2, "do").wire(1, "changed", 3, "do").build();
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Access", graph))
                .thenWaitUntil(() -> ran(helper, entity)).thenExecute(() -> toggle(helper, logic)).thenIdle(5)
                .thenExecute(() -> {
                    helper.assertTrue(port.filter().getItem(0).isEmpty(), "the stranger's filter is untouched");
                    helper.assertValueEqual(port.mode(), PortMode.EXPORT, "the stranger's port mode is untouched");
                    helper.assertTrue(board.settings().source() != DisplaySourceKind.TEXT, "the display is untouched");
                    helper.assertValueEqual(why(helper, entity, 2), LogicLang.WHY_DENIED, "why for the port");
                    helper.assertValueEqual(why(helper, entity, 3), LogicLang.WHY_DENIED, "why for the display");
                    port.setOwner(entity.ownerRecord());
                    toggle(helper, logic);
                }).thenWaitUntil(() -> {
                    helper.assertTrue(port.filter().getItem(0).is(Items.COBBLESTONE), "the owner's port is changed");
                    helper.assertValueEqual(port.mode(), PortMode.IMPORT, "and switched to import");
                }).thenSucceed();
    }

    /**
     * Switch device, move items, redstone out and set priority (owner decision 2026-10-07: every action) leave devices
     * of another player alone: the port stays on, nothing moves into the chest, the switch gives no signal, and the
     * network's log says so once per device even when the graphs run again. Owned by the controller's owner, the same
     * graphs act.
     */
    private static void olderActionsNeedAccess(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lan_older");
        ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "lan_older_stranger");
        PortBlockEntity intake = port(helper, NetworkGameTests.at(4), PortMode.IMPORT, "intake");
        PortBlockEntity box = port(helper, NetworkGameTests.at(3).north(), Direction.NORTH, PortMode.EXPORT, "box");
        helper.setBlock(NetworkGameTests.at(3).north().north(), Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(NetworkGameTests.at(3).north().north(), ChestBlockEntity.class);
        ConditionSwitchBlockEntity lamp = lamp(helper, NetworkGameTests.at(3).south(), stranger, "lamp");
        intake.setOwner(OwnerRecord.of(stranger));
        box.setOwner(OwnerRecord.of(stranger));
        DriveBlockEntity bank = CellGameTests.drive(helper, NetworkGameTests.at(2).south());
        bank.setLabel(DeviceLabel.of("bank", null));
        bank.setOwner(OwnerRecord.of(stranger));
        LogicGraph<LogicKey> priority = new LogicControllerGameTests.Graph().node(1, NodeTypes.GRAPH_START, s -> s)
                .node(2, NodeTypes.SET_PRIORITY, s -> s.withText("label", "bank").withNumber("priority", 7))
                .wire(1, "started", 2, "do").build();
        LogicGraph<LogicKey> off = new LogicControllerGameTests.Graph()
                .node(1, NodeTypes.CONSTANT, s -> s.withText("kind", "yes_no").withFlag("yes", false))
                .node(2, NodeTypes.DEVICE_SWITCH, s -> s.withText("label", "intake")).wire(1, "value", 2, "on").build();
        LogicGraph<LogicKey> move = new LogicControllerGameTests.Graph().node(1, NodeTypes.GRAPH_START, s -> s)
                .node(2, NodeTypes.MOVE_ITEMS,
                        s -> s.withItem("item", LogicKey.of(COBBLE)).withText("to", "box").withNumber("amount", 10))
                .wire(1, "started", 2, "do").build();
        LogicGraph<LogicKey> redstone = new LogicControllerGameTests.Graph()
                .node(1, NodeTypes.CONSTANT, s -> s.withNumber("value", 7))
                .node(2, NodeTypes.REDSTONE_OUT, s -> s.withText("label", "lamp")).wire(1, "value", 2, "strength")
                .build();
        Runnable save = () -> {
            entity.worker().setGraph(0, "Off", off);
            entity.worker().setGraph(1, "Move", move);
            entity.worker().setGraph(2, "Lamp", redstone);
            entity.worker().setGraph(3, "Priority", priority);
        };
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = LogicControllerGameTests.network(helper, entity);
            helper.assertTrue(intake.network() == network, "the intake port has joined the network");
            helper.assertTrue(box.network() == network, "the box port has joined the network");
            helper.assertTrue(lamp.network() == network, "the switch has joined the network");
            helper.assertTrue(bank.network() == network, "the drive has joined the network");
        }).thenExecute(() -> {
            NetworkGameTests.insert(LogicControllerGameTests.network(helper, entity), COBBLE, 100);
            save.run();
        }).thenWaitUntil(() -> {
            for (int g = 0; g < 4; g++) {
                helper.assertTrue(entity.worker().evaluator(g).orElseThrow().runs() > 0, "graph " + g + " ran");
            }
        }).thenIdle(3).thenExecute(() -> {
            helper.assertTrue(!intake.switchedOff(), "the stranger's port stays on");
            helper.assertValueEqual(count(chest, Items.COBBLESTONE), 0, "nothing moved through the stranger's port");
            helper.assertValueEqual(lamp.worker().logicStrength(), 0, "the stranger's switch gives no signal");
            helper.assertValueEqual(bank.priority(), 0, "the stranger's drive keeps its priority");
            for (int g = 0; g < 4; g++) {
                RunTrace trace = entity.worker().evaluator(g).orElseThrow().trace();
                int i = trace.find(2);
                helper.assertValueEqual(i < 0 ? "" : trace.reason(i), LogicLang.WHY_DENIED, "why in graph " + g);
            }
            // Running again while still refused adds nothing to the log.
            save.run();
        }).thenIdle(5).thenExecute(() -> {
            long logged = LogicControllerGameTests.network(helper, entity).notices().lines().stream()
                    .filter(notice -> notice.key().equals(LogicLang.LOG_DENIED)).count();
            helper.assertValueEqual(logged, 4L, "one log line per refused device");
            intake.setOwner(entity.ownerRecord());
            box.setOwner(entity.ownerRecord());
            lamp.setOwner(entity.ownerRecord());
            bank.setOwner(entity.ownerRecord());
            save.run();
        }).thenWaitUntil(() -> {
            helper.assertTrue(intake.switchedOff(), "the owner's port is turned off");
            helper.assertValueEqual(count(chest, Items.COBBLESTONE) > 0, true, "moved through the owner's port");
            helper.assertValueEqual(lamp.worker().logicStrength(), 7, "the owner's switch");
            helper.assertValueEqual(bank.priority(), 7, "the owner's drive");
        }).thenSucceed();
    }
}
