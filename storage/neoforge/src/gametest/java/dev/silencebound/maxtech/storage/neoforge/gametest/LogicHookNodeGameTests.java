package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.logic.GraphError;
import dev.silencebound.maxtech.storage.logic.LogicEvaluator;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.RunTrace;
import dev.silencebound.maxtech.storage.logic.SettingSpec;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.logic.controller.LogicAction;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.logic.controller.LogicWorker;
import dev.silencebound.maxtech.storage.logic.controller.SharedVariable;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import dev.silencebound.maxtech.storage.terminal.TerminalBlockEntity;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The hook node types of batch 4 in a real controller: item moves fire once per committed transfer through the labelled
 * device and the network records moves only while a graph listens; a crafting job that stops on a missing item fires
 * "crafting job failed"; players coming near a labelled device and opening a terminal fire their events; shared
 * variables pass values between graphs and survive a save; graphs run each other, circles are paused before they run
 * and calls nest only so deep; a wait-until node keeps its held events through a save.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LogicHookNodeGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey PLANKS = ItemKey.of(Items.OAK_PLANKS);
    private static final StackKey STICK = ItemKey.of(Items.STICK);
    private static final String VARIABLES = "logic_hook_variable_limit";
    private static final String DEPTH = "logic_hook_rule_depth";

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("logic_hook_item_moves_once_per_transfer", LogicHookNodeGameTests::itemMoves);
        register("logic_hook_craft_failed", LogicHookNodeGameTests::craftFailed);
        register("logic_hook_craft_failed_missing_item", LogicHookNodeGameTests::craftFailedMissingItem);
        register("logic_hook_player_nearby", LogicHookNodeGameTests::playerNearby);
        register("logic_hook_terminal_opened", LogicHookNodeGameTests::terminalOpened);
        register("logic_hook_shared_variable_save_load", LogicHookNodeGameTests::sharedVariable);
        register("logic_hook_run_rule_and_loops", LogicHookNodeGameTests::runRule);
        register("logic_hook_wait_until_save_load", LogicHookNodeGameTests::waitUntil);
        register("logic_hook_variables_from_item_capped", LogicHookNodeGameTests::variablesFromItem);
        register("logic_hook_variables_drop_round_trip", LogicHookNodeGameTests::variablesDropRoundTrip);
        StorageTests.function(VARIABLES, StorageTestEnvironment.clearing(LogicHookNodeGameTests::variableLimit));
        StorageTests.function(DEPTH, StorageTestEnvironment.clearing(LogicHookNodeGameTests::ruleDepth));
    }

    private LogicHookNodeGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("logic_hook"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 400);
        }
        StorageTests.test(event, event.registerEnvironment(MaxtechStorage.id(VARIABLES),
                new StorageTestEnvironment("logic.maxVariables", "1", false)), VARIABLES, STRUCTURE, 200);
        StorageTests.test(event, event.registerEnvironment(MaxtechStorage.id(DEPTH),
                new StorageTestEnvironment("logic.maxRuleDepth", "1", false)), DEPTH, STRUCTURE, 200);
    }

    // ---- helpers

    private static LogicEvaluator<LogicKey> evaluator(GameTestHelper helper, LogicControllerBlockEntity entity,
            int graph) {
        if (entity.worker().size() <= graph) {
            throw helper.assertionException(Component.literal("no graph " + graph + " yet"));
        }
        return entity.worker().evaluator(graph)
                .orElseThrow(() -> helper.assertionException(Component.literal("graph " + graph + " is paused")));
    }

    private static void ran(GameTestHelper helper, LogicControllerBlockEntity entity, int graph) {
        helper.assertTrue(evaluator(helper, entity, graph).runs() > 0, "graph " + graph + " has not run yet");
    }

    /** Output {@code output} of node {@code node} of graph {@code graph}. */
    private static long value(GameTestHelper helper, LogicControllerBlockEntity entity, int graph, int node,
            String output) {
        LogicEvaluator<LogicKey> evaluator = evaluator(helper, entity, graph);
        return evaluator.value(evaluator.graph().slot(node, output));
    }

    /** The why reason the last run of graph {@code graph} gave for node {@code node}; empty for none. */
    private static String why(GameTestHelper helper, LogicControllerBlockEntity entity, int graph, int node) {
        RunTrace trace = evaluator(helper, entity, graph).trace();
        int i = trace.find(node);
        return i < 0 ? "" : trace.reason(i);
    }

    /** Toggles a redstone block on top of {@code controller}: "redstone changes" fires once. */
    private static void toggle(GameTestHelper helper, BlockPos controller) {
        BlockPos top = controller.above();
        helper.setBlock(top, helper.getBlockState(top).is(Blocks.REDSTONE_BLOCK) ? Blocks.AIR : Blocks.REDSTONE_BLOCK);
    }

    private static LogicControllerGameTests.Graph graph() {
        return new LogicControllerGameTests.Graph();
    }

    /** One operation of {@code device} through its own transfer, committed or rolled back. */
    private static long deviceMove(GameTestHelper helper, TestStorage.TestDevice device, StackKey key, long amount,
            boolean insert, boolean commit) {
        StorageTransfer transfer = device.deviceLink().transfer();
        if (transfer == null) {
            throw helper.assertionException(Component.literal("the device is in no network"));
        }
        try (Transaction transaction = Transaction.openRoot()) {
            long moved = insert
                    ? transfer.insert(key, amount, transaction)
                    : transfer.extract(key, amount, transaction);
            if (commit) {
                transaction.commit();
            }
            return moved;
        }
    }

    /** Saves the controller at {@code pos}, places a new one and loads the save into it. */
    private static void reload(GameTestHelper helper, LogicControllerBlockEntity entity, BlockPos pos) {
        CompoundTag saved = entity.saveWithoutMetadata(helper.getLevel().registryAccess());
        helper.setBlock(pos, Blocks.AIR);
        helper.setBlock(pos, LogicContent.CONTROLLER.get());
        LogicControllerGameTests.entity(helper, pos).loadCustomOnly(
                TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
    }

    // ---- item moves

    /**
     * One committed insert of 32 cobblestone through the labelled device fires the labelled "into" node once with 32,
     * and the any-item node once; a rolled-back insert fires nothing; a player's insert fires only the unlabelled node;
     * an extract through the device fires the both-ways node but not the "into" one. The network records moves only
     * while the graph listens.
     */
    private static void itemMoves(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lh_moves");
        TestStorage.TestDevice intake = NetworkGameTests.device(helper, NetworkGameTests.at(4));
        intake.setLabel(DeviceLabel.of("Intake", null));
        LogicGraph<LogicKey> graph = graph()
                .node(1, NodeTypes.ITEM_MOVES,
                        s -> s.withItem("item", LogicKey.of(COBBLE)).withText("label", "intake").withText("direction",
                                "into"))
                .node(2, NodeTypes.COUNTER, s -> s).node(3, NodeTypes.ITEM_MOVES, s -> s)
                .node(4, NodeTypes.COUNTER, s -> s).wire(1, "moved", 2, "up").wire(3, "moved", 4, "up").build();
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            helper.assertFalse(LogicControllerGameTests.network(helper, entity).hearsItemMoves(),
                    "nothing listens before the graph");
            entity.worker().setGraph(0, "Moves", graph);
        }).thenWaitUntil(() -> ran(helper, entity, 0)).thenExecute(() -> {
            helper.assertTrue(LogicControllerGameTests.network(helper, entity).hearsItemMoves(), "the graph listens");
            helper.assertValueEqual(deviceMove(helper, intake, COBBLE, 32, true, true), 32L, "inserted");
        }).thenWaitUntil(() -> helper.assertValueEqual(value(helper, entity, 0, 2, "count"), 1L, "labelled, into"))
                .thenExecute(() -> {
                    helper.assertValueEqual(value(helper, entity, 0, 1, "amount"), 32L, "the amount");
                    helper.assertValueEqual(value(helper, entity, 0, 4, "count"), 1L, "any item, any device");
                    helper.assertValueEqual(deviceMove(helper, intake, COBBLE, 8, true, false), 8L, "simulated");
                }).thenIdle(3).thenExecute(() -> {
                    helper.assertValueEqual(value(helper, entity, 0, 2, "count"), 1L, "a rollback is no move");
                    helper.assertValueEqual(value(helper, entity, 0, 4, "count"), 1L, "a rollback is no move");
                    NetworkGameTests.insert(LogicControllerGameTests.network(helper, entity), COBBLE, 10);
                })
                .thenWaitUntil(
                        () -> helper.assertValueEqual(value(helper, entity, 0, 4, "count"), 2L, "a player's insert"))
                .thenExecute(() -> {
                    helper.assertValueEqual(value(helper, entity, 0, 2, "count"), 1L, "a player is no labelled device");
                    helper.assertValueEqual(deviceMove(helper, intake, COBBLE, 5, false, true), 5L, "extracted");
                }).thenWaitUntil(() -> helper.assertValueEqual(value(helper, entity, 0, 4, "count"), 3L, "an extract"))
                .thenExecute(() -> {
                    helper.assertValueEqual(value(helper, entity, 0, 2, "count"), 1L, "\"into\" ignores extracts");
                    entity.worker().removeGraph(0);
                    helper.assertFalse(LogicControllerGameTests.network(helper, entity).hearsItemMoves(),
                            "nothing listens once the graph is gone");
                }).thenSucceed();
    }

    // ---- craft failed

    private static CraftingPattern sticks() {
        return new CraftingPattern(CraftingPattern.Kind.CRAFTING,
                List.of(new PatternInput(new PatternIngredient.Exact(PLANKS), 2, Optional.empty(), false)),
                List.of(new PatternOutput(STICK, 4, 1f)), Optional.empty(), Optional.empty());
    }

    /**
     * A player's job for 8 sticks with no planks in storage stops partial: "crafting job failed" for sticks fires once
     * with the planks it lacked, the node for planks does not.
     */
    private static void craftFailed(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(3), CraftingTestContent.HOST.get());
        helper.getBlockEntity(NetworkGameTests.at(3), CraftingTestContent.Host.class).setPatterns(List.of(sticks()));
        helper.setBlock(NetworkGameTests.at(4), CpuContent.CORE_1.get());
        helper.setBlock(NetworkGameTests.at(5), LogicContent.CONTROLLER.get());
        LogicControllerBlockEntity entity = LogicControllerGameTests.entity(helper, NetworkGameTests.at(5));
        entity.setOwner(OwnerRecord.of(NetworkGameTests.player(helper.getLevel(), "lh_failed")));
        LogicGraph<LogicKey> graph = graph()
                .node(1, NodeTypes.CRAFT_FAILED, s -> s.withItem("item", LogicKey.of(STICK)))
                .node(2, NodeTypes.COUNTER, s -> s)
                .node(3, NodeTypes.CRAFT_FAILED, s -> s.withItem("item", LogicKey.of(PLANKS)))
                .node(4, NodeTypes.COUNTER, s -> s).wire(1, "failed", 2, "up").wire(3, "failed", 4, "up")
                .node(5, NodeTypes.CRAFT_FAILED, s -> s.withItem("item", LogicKey.tag(Tags.Items.RODS_WOODEN)))
                .node(6, NodeTypes.COUNTER, s -> s).wire(5, "failed", 6, "up").build();
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Failed", graph))
                .thenWaitUntil(() -> ran(helper, entity, 0)).thenExecute(() -> {
                    NetworkState network = LogicControllerGameTests.network(helper, entity);
                    CraftingPlanner.Result plan = CraftingPlanner.planNow(network, STICK, 8);
                    helper.assertTrue(JobQueue.submit(network, network.nodes().iterator().nextLong(), plan,
                            JobQueue.Request.player(UUID.randomUUID())).job().isPresent(), "the job is queued");
                })
                .thenWaitUntil(
                        () -> helper.assertValueEqual(value(helper, entity, 0, 2, "count"), 1L, "the stick job failed"))
                .thenIdle(5).thenExecute(() -> {
                    helper.assertValueEqual(value(helper, entity, 0, 2, "count"), 1L, "once");
                    helper.assertValueEqual(value(helper, entity, 0, 1, "missing"), 4L, "4 planks were missing");
                    helper.assertValueEqual(value(helper, entity, 0, 4, "count"), 0L, "no planks job");
                    helper.assertValueEqual(value(helper, entity, 0, 6, "count"), 1L, "sticks are wooden rods");
                }).thenSucceed();
    }

    /**
     * "Missing item" (owner decision 2026-10-09): a job for 8 torches with neither coal nor sticks stored stops short
     * of both. The failed node's why line lists both, and its first missing item flows into a request craft, which asks
     * for exactly that item (refused here, since nothing makes it, so the log names it).
     */
    private static void craftFailedMissingItem(GameTestHelper helper) {
        StackKey torch = ItemKey.of(Items.TORCH);
        StackKey coal = ItemKey.of(Items.COAL);
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(3), CraftingTestContent.HOST.get());
        helper.getBlockEntity(NetworkGameTests.at(3), CraftingTestContent.Host.class)
                .setPatterns(List.of(new CraftingPattern(CraftingPattern.Kind.CRAFTING,
                        List.of(new PatternInput(new PatternIngredient.Exact(coal), 1, Optional.empty(), false),
                                new PatternInput(new PatternIngredient.Exact(STICK), 1, Optional.empty(), false)),
                        List.of(new PatternOutput(torch, 4, 1f)), Optional.empty(), Optional.empty())));
        helper.setBlock(NetworkGameTests.at(4), CpuContent.CORE_1.get());
        helper.setBlock(NetworkGameTests.at(5), LogicContent.CONTROLLER.get());
        LogicControllerBlockEntity entity = LogicControllerGameTests.entity(helper, NetworkGameTests.at(5));
        entity.setOwner(OwnerRecord.of(NetworkGameTests.player(helper.getLevel(), "lh_missing")));
        LogicGraph<LogicKey> graph = graph()
                .node(1, NodeTypes.CRAFT_FAILED, s -> s.withItem("item", LogicKey.of(torch)))
                .node(2, NodeTypes.REQUEST_CRAFT, s -> s.withNumber("amount", 16)).wire(1, "failed", 2, "do")
                .wire(1, "missing_item", 2, "item").build();
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Refill", graph))
                .thenWaitUntil(() -> ran(helper, entity, 0)).thenExecute(() -> {
                    NetworkState network = LogicControllerGameTests.network(helper, entity);
                    CraftingPlanner.Result plan = CraftingPlanner.planNow(network, torch, 8);
                    helper.assertTrue(JobQueue.submit(network, network.nodes().iterator().nextLong(), plan,
                            JobQueue.Request.player(UUID.randomUUID())).job().isPresent(), "the job is queued");
                }).thenWaitUntil(() -> helper.assertValueEqual(why(helper, entity, 0, 1), LogicLang.WHY_MISSING,
                        "the failed node's why line"))
                .thenExecute(() -> {
                    RunTrace trace = evaluator(helper, entity, 0).trace();
                    String listed = trace.text(trace.find(1));
                    helper.assertTrue(listed.contains("Coal") && listed.contains("Stick"), "both listed: " + listed);
                    String first = listed.substring(listed.indexOf(' ') + 1, listed.indexOf(','));
                    StackKey asked = ItemKey.of(first.equals("Coal") ? Items.COAL : Items.STICK);
                    List<LogicAction> actions = entity.worker().actions();
                    helper.assertValueEqual(actions.size(), 1, "one request: " + actions);
                    LogicAction request = actions.getFirst();
                    helper.assertValueEqual(request.kind(), LogicAction.Kind.REQUEST_CRAFT, "a craft request");
                    helper.assertValueEqual(request.item(), Optional.of(LogicKey.of(asked)),
                            "the request asks for the first missing item, " + first);
                    helper.assertValueEqual(request.value(), 16L, "16 of it");
                }).thenSucceed();
    }

    // ---- players

    /**
     * A player walking up to the labelled device fires "a player came", walking away "a player left". While nobody is
     * near, the controller only looks every 20 ticks and the graph does not run.
     */
    @SuppressWarnings("removal") // makeMockServerPlayerInLevel is the way to put a player into the level
    private static void playerNearby(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lh_near");
        TestStorage.TestDevice door = NetworkGameTests.device(helper, NetworkGameTests.at(4));
        door.setLabel(DeviceLabel.of("door", null));
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 far = helper.absoluteVec(new Vec3(30.5, 1, 20.5));
        Vec3 near = helper.absoluteVec(Vec3.atBottomCenterOf(NetworkGameTests.at(4).south(2)));
        player.setPos(far);
        LogicGraph<LogicKey> graph = graph()
                .node(1, NodeTypes.PLAYER_NEARBY, s -> s.withText("label", "door").withNumber("radius", 4))
                .node(2, NodeTypes.COUNTER, s -> s).node(3, NodeTypes.COUNTER, s -> s).wire(1, "came", 2, "up")
                .wire(1, "left", 3, "up").build();
        long[] start = new long[2];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Near", graph))
                .thenWaitUntil(() -> ran(helper, entity, 0)).thenIdle(2).thenExecute(() -> {
                    start[0] = entity.worker().taskRuns();
                    start[1] = entity.worker().runs();
                }).thenIdle(100).thenExecute(() -> {
                    long looks = entity.worker().taskRuns() - start[0];
                    helper.assertTrue(looks >= 4 && looks <= 7, "looks every 20 ticks, not " + looks + " in 100");
                    helper.assertValueEqual(entity.worker().runs(), start[1], "graph runs while nobody came");
                    player.setPos(near);
                }).thenWaitUntil(() -> helper.assertValueEqual(value(helper, entity, 0, 2, "count"), 1L, "came"))
                .thenExecute(() -> {
                    helper.assertValueEqual(value(helper, entity, 0, 1, "near"), 1L, "near");
                    helper.assertValueEqual(value(helper, entity, 0, 1, "players"), 1L, "one player");
                    player.setPos(far);
                }).thenWaitUntil(() -> helper.assertValueEqual(value(helper, entity, 0, 3, "count"), 1L, "left"))
                .thenExecute(() -> {
                    helper.assertValueEqual(value(helper, entity, 0, 2, "count"), 1L, "came once");
                    helper.getLevel().getServer().getPlayerList().remove(player);
                }).thenSucceed();
    }

    /**
     * Opening the labelled terminal fires the labelled node and the any-terminal node; opening the other terminal fires
     * only the any-terminal node; a player whose open menu is no terminal's fires nothing. GameTest players cannot
     * receive the open-screen packet, so the test sets the player's menu and calls what {@link TerminalMenu#open} calls
     * once the menu is open.
     */
    private static void terminalOpened(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lh_terminal");
        BlockPos deskPos = NetworkGameTests.at(4);
        BlockPos otherPos = NetworkGameTests.at(5);
        helper.setBlock(deskPos, TerminalContent.TERMINAL.get());
        helper.setBlock(otherPos, TerminalContent.TERMINAL.get());
        TerminalBlockEntity desk = helper.getBlockEntity(deskPos, TerminalBlockEntity.class);
        TerminalBlockEntity other = helper.getBlockEntity(otherPos, TerminalBlockEntity.class);
        desk.setLabel(DeviceLabel.of("Desk", null));
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "lh_terminal_user");
        LogicGraph<LogicKey> graph = graph().node(1, NodeTypes.PLAYER_USES_TERMINAL, s -> s.withText("label", "desk"))
                .node(2, NodeTypes.COUNTER, s -> s).node(3, NodeTypes.PLAYER_USES_TERMINAL, s -> s)
                .node(4, NodeTypes.COUNTER, s -> s).wire(1, "opened", 2, "up").wire(3, "opened", 4, "up").build();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        other.network() == LogicControllerGameTests.network(helper, entity), "terminals join"))
                .thenExecute(() -> entity.worker().setGraph(0, "Terminal", graph))
                .thenWaitUntil(() -> ran(helper, entity, 0))
                // Not in a terminal's menu: nothing to tell.
                .thenExecute(() -> TerminalMenu.opened(player, desk)).thenIdle(3).thenExecute(() -> {
                    helper.assertValueEqual(value(helper, entity, 0, 4, "count"), 0L, "no terminal menu open");
                    player.containerMenu = TerminalMenu.server(1, player.getInventory(), desk, update -> {
                    });
                    TerminalMenu.opened(player, desk);
                }).thenWaitUntil(() -> {
                    helper.assertValueEqual(value(helper, entity, 0, 2, "count"), 1L, "the desk");
                    helper.assertValueEqual(value(helper, entity, 0, 4, "count"), 1L, "any terminal");
                }).thenExecute(() -> {
                    player.containerMenu = TerminalMenu.server(2, player.getInventory(), other, update -> {
                    });
                    TerminalMenu.opened(player, other);
                }).thenWaitUntil(() -> helper.assertValueEqual(value(helper, entity, 0, 4, "count"), 2L, "the other"))
                .thenIdle(3).thenExecute(() -> {
                    helper.assertValueEqual(value(helper, entity, 0, 2, "count"), 1L, "not the desk");
                    helper.assertValueEqual(value(helper, entity, 0, 4, "count"), 2L, "once each");
                }).thenSucceed();
    }

    // ---- shared variables

    /**
     * One graph sets "Level" to the redstone strength into the controller, another reads "level". With the writer gone
     * the value is still there after a save and load, and the reader reads it.
     */
    private static void sharedVariable(GameTestHelper helper) {
        LogicControllerBlockEntity first = LogicControllerGameTests.setUp(helper, "lh_variable");
        BlockPos pos = NetworkGameTests.at(3);
        LogicGraph<LogicKey> writer = graph().node(1, NodeTypes.REDSTONE_CHANGES, s -> s)
                .node(2, NodeTypes.SET_SHARED_VARIABLE, s -> s.withText("name", "Level"))
                .wire(1, "strength", 2, "value").build();
        LogicGraph<LogicKey> reader = graph().node(1, NodeTypes.SHARED_VARIABLE, s -> s.withText("name", "level"))
                .build();
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, first)).thenExecute(() -> {
            first.worker().setGraph(0, "Writer", writer);
            first.worker().setGraph(1, "Reader", reader);
        }).thenWaitUntil(() -> {
            ran(helper, first, 0);
            ran(helper, first, 1);
        }).thenExecute(() -> toggle(helper, pos))
                .thenWaitUntil(
                        () -> helper.assertValueEqual(value(helper, first, 1, 1, "value"), 15L, "the reader sees 15"))
                .thenExecute(() -> {
                    helper.assertValueEqual(first.worker().variable("level"), 15L, "kept by the controller");
                    helper.assertValueEqual(value(helper, first, 1, 1, "yes"), 1L, "15 is yes");
                    first.worker().removeGraph(0);
                    helper.assertValueEqual(first.worker().variables(), List.of(new SharedVariable("level", 15, false)),
                            "one number variable");
                    reload(helper, first, pos);
                }).thenWaitUntil(() -> ran(helper, LogicControllerGameTests.entity(helper, pos), 0)).thenExecute(() -> {
                    LogicControllerBlockEntity loaded = LogicControllerGameTests.entity(helper, pos);
                    helper.assertValueEqual(loaded.worker().variable("level"), 15L, "saved");
                    helper.assertValueEqual(value(helper, loaded, 0, 1, "value"), 15L, "read after the load");
                }).thenSucceed();
    }

    /**
     * A controller item whose variables component was written by hand (the component is untrusted): names are keyed as
     * graphs key them, a blank name and a repeated one are dropped, a yes/no holds 1, a number is kept within range,
     * and only {@code logic.maxVariables} (32) are kept.
     */
    private static void variablesFromItem(GameTestHelper helper) {
        BlockPos pos = NetworkGameTests.at(3);
        helper.setBlock(pos, LogicContent.CONTROLLER.get());
        LogicControllerBlockEntity entity = LogicControllerGameTests.entity(helper, pos);
        List<SharedVariable> written = new ArrayList<>();
        written.add(new SharedVariable("  Mode ", 7, true));
        written.add(new SharedVariable("MODE", 9, false));
        written.add(new SharedVariable("   ", 3, false));
        written.add(new SharedVariable("Big", Long.MAX_VALUE, false));
        for (int i = 0; i < 40; i++) {
            written.add(new SharedVariable("v" + i, i, false));
        }
        ItemStack item = new ItemStack(LogicContent.CONTROLLER.get());
        item.set(ModDataComponents.LOGIC_VARIABLES.get(), written);
        entity.applyComponentsFromItemStack(item);
        LogicWorker worker = entity.worker();
        helper.assertValueEqual(worker.variables().size(), 32, "kept up to logic.maxVariables");
        helper.assertValueEqual(worker.variables().getFirst(), new SharedVariable("mode", 1, true), "keyed, yes is 1");
        helper.assertValueEqual(worker.variable("big"), SettingSpec.MAX_NUMBER, "kept within the number range");
        helper.assertValueEqual(worker.variable("v29"), 29L, "the 32nd");
        helper.assertTrue(worker.variables().stream().noneMatch(v -> v.name().equals("v30")), "the 33rd is dropped");
        helper.assertTrue(worker.variables().stream().noneMatch(v -> v.name().isBlank()), "no blank name");
        helper.succeed();
    }

    /** Variables travel with the controller: broken, dropped and placed again, it has them all. */
    private static void variablesDropRoundTrip(GameTestHelper helper) {
        BlockPos pos = NetworkGameTests.at(3);
        helper.setBlock(pos, LogicContent.CONTROLLER.get());
        LogicControllerBlockEntity entity = LogicControllerGameTests.entity(helper, pos);
        List<SharedVariable> variables = List.of(new SharedVariable("level", 15, false),
                new SharedVariable("open", 1, true));
        entity.worker().loadVariables(variables);
        List<ItemStack> drops = Block.getDrops(helper.getBlockState(pos), helper.getLevel(), helper.absolutePos(pos),
                entity);
        helper.assertValueEqual(drops.size(), 1, "one drop");
        helper.assertValueEqual(drops.getFirst().getOrDefault(ModDataComponents.LOGIC_VARIABLES.get(), List.of()),
                variables, "the item carries the variables");
        helper.setBlock(pos, Blocks.AIR);
        BlockPos placed = NetworkGameTests.at(5);
        helper.setBlock(placed, LogicContent.CONTROLLER.get());
        LogicControllerBlockEntity fresh = LogicControllerGameTests.entity(helper, placed);
        fresh.applyComponentsFromItemStack(drops.getFirst());
        helper.assertValueEqual(fresh.worker().variables(), variables, "placed again with its variables");
        helper.succeed();
    }

    /** With {@code logic.maxVariables} 1, a second name is refused with a why line; the first still changes. */
    private static void variableLimit(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lh_variable_limit");
        BlockPos pos = NetworkGameTests.at(3);
        LogicGraph<LogicKey> graph = graph().node(1, NodeTypes.REDSTONE_CHANGES, s -> s)
                .node(2, NodeTypes.SET_SHARED_VARIABLE, s -> s.withText("name", "a"))
                .node(3, NodeTypes.SET_SHARED_VARIABLE, s -> s.withText("name", "b").withText("kind", "yes_no"))
                .wire(1, "strength", 2, "value").wire(1, "changed", 3, "do").build();
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Limit", graph))
                .thenWaitUntil(() -> ran(helper, entity, 0)).thenExecute(() -> toggle(helper, pos))
                .thenWaitUntil(() -> helper.assertValueEqual(entity.worker().variable("a"), 15L, "a is set"))
                .thenExecute(() -> {
                    helper.assertValueEqual(entity.worker().variables().size(), 1, "b is refused");
                    helper.assertValueEqual(why(helper, entity, 0, 3), LogicLang.WHY_VARIABLES_FULL, "why");
                    toggle(helper, pos);
                }).thenWaitUntil(() -> helper.assertValueEqual(entity.worker().variable("a"), 0L, "a still changes"))
                .thenSucceed();
    }

    // ---- run another rule

    private static LogicGraph<LogicKey> calls(String rule) {
        return graph().node(1, NodeTypes.RUN_BY_RULE, s -> s).node(2, NodeTypes.RUN_RULE, s -> s.withText("rule", rule))
                .node(3, NodeTypes.COUNTER, s -> s).wire(1, "ran", 2, "do").wire(1, "ran", 3, "up").build();
    }

    /**
     * "Caller" runs "callee" on a redstone change, and the callee's counter steps once. "Loop A" and "Loop B" run each
     * other: both are paused with the loop error before they run, and run again once B runs the callee instead.
     */
    private static void runRule(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lh_rules");
        BlockPos pos = NetworkGameTests.at(3);
        LogicWorker worker = entity.worker();
        LogicGraph<LogicKey> caller = graph().node(1, NodeTypes.REDSTONE_CHANGES, s -> s)
                .node(2, NodeTypes.RUN_RULE, s -> s.withText("rule", "callee")).wire(1, "changed", 2, "do").build();
        LogicGraph<LogicKey> callee = graph().node(1, NodeTypes.RUN_BY_RULE, s -> s).node(3, NodeTypes.COUNTER, s -> s)
                .wire(1, "ran", 3, "up").build();
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            worker.setGraph(0, "Caller", caller);
            worker.setGraph(1, "Callee", callee);
            worker.setGraph(2, "Loop A", calls("Loop B"));
            helper.assertTrue(worker.setGraph(3, "Loop B", calls("3")).orElseThrow().paused(),
                    "the save that closes the circle is paused");
            helper.assertValueEqual(worker.status(2), LogicWorker.Status.PAUSED, "A paused");
            helper.assertValueEqual(worker.status(3), LogicWorker.Status.PAUSED, "B paused");
            List<GraphError> errors = worker.errors(2);
            helper.assertValueEqual(errors.size(), 1, "one error on A");
            helper.assertValueEqual(errors.getFirst().key(), LogicLang.RULE_LOOP, "the loop error");
            helper.assertValueEqual(errors.getFirst().node(), 2, "on the run-rule node");
        }).thenWaitUntil(() -> {
            ran(helper, entity, 0);
            ran(helper, entity, 1);
        }).thenExecute(() -> toggle(helper, pos))
                .thenWaitUntil(
                        () -> helper.assertValueEqual(value(helper, entity, 1, 3, "count"), 1L, "the callee ran"))
                .thenIdle(3).thenExecute(() -> {
                    helper.assertValueEqual(value(helper, entity, 1, 3, "count"), 1L, "once");
                    worker.setGraph(3, "Loop B", calls("callee"));
                    helper.assertValueEqual(worker.status(2), LogicWorker.Status.RUNNING, "A runs again");
                    helper.assertValueEqual(worker.status(3), LogicWorker.Status.RUNNING, "B runs again");
                    helper.assertValueEqual(worker.errors(2), List.of(), "no error left");
                }).thenSucceed();
    }

    /** With {@code logic.maxRuleDepth} 1: A runs B, but B's call of C is one too deep and the why line says so. */
    private static void ruleDepth(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lh_depth");
        BlockPos pos = NetworkGameTests.at(3);
        LogicGraph<LogicKey> first = graph().node(1, NodeTypes.REDSTONE_CHANGES, s -> s)
                .node(2, NodeTypes.RUN_RULE, s -> s.withText("rule", "2")).wire(1, "changed", 2, "do").build();
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            entity.worker().setGraph(0, "A", first);
            entity.worker().setGraph(1, "B", calls("C"));
            entity.worker().setGraph(2, "C", calls("Nobody"));
        }).thenWaitUntil(() -> ran(helper, entity, 2)).thenExecute(() -> toggle(helper, pos))
                .thenWaitUntil(() -> helper.assertValueEqual(value(helper, entity, 1, 3, "count"), 1L, "B ran"))
                .thenIdle(3).thenExecute(() -> {
                    helper.assertValueEqual(value(helper, entity, 2, 3, "count"), 0L, "C did not");
                    helper.assertValueEqual(why(helper, entity, 1, 2), LogicLang.WHY_RULE_DEPTH, "why");
                }).thenSucceed();
    }

    // ---- wait until

    /**
     * Two redstone changes are held while the network has no cobblestone; after a save and load both are still held,
     * and cobblestone coming in lets both through.
     */
    private static void waitUntil(GameTestHelper helper) {
        LogicControllerBlockEntity first = LogicControllerGameTests.setUp(helper, "lh_wait");
        BlockPos pos = NetworkGameTests.at(3);
        LogicGraph<LogicKey> graph = graph().node(1, NodeTypes.REDSTONE_CHANGES, s -> s)
                .node(2, NodeTypes.ITEM_COUNT, s -> s.withItem("item", LogicKey.of(COBBLE)))
                .node(4, NodeTypes.COMPARE, s -> s.withNumber("threshold", 0))
                .node(3, NodeTypes.WAIT_UNTIL, s -> s.withNumber("timeout", 0)).node(5, NodeTypes.COUNTER, s -> s)
                .wire(1, "changed", 3, "in").wire(2, "count", 4, "value").wire(4, "result", 3, "condition")
                .wire(3, "passed", 5, "up").build();
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, first))
                .thenExecute(() -> first.worker().setGraph(0, "Wait", graph)).thenWaitUntil(() -> ran(helper, first, 0))
                .thenExecute(() -> toggle(helper, pos))
                .thenWaitUntil(() -> helper.assertValueEqual(value(helper, first, 0, 3, "held"), 1L, "one held"))
                .thenExecute(() -> toggle(helper, pos))
                .thenWaitUntil(() -> helper.assertValueEqual(value(helper, first, 0, 3, "held"), 2L, "two held"))
                .thenExecute(() -> reload(helper, first, pos))
                .thenWaitUntil(() -> ran(helper, LogicControllerGameTests.entity(helper, pos), 0)).thenIdle(2)
                .thenExecute(() -> {
                    LogicControllerBlockEntity loaded = LogicControllerGameTests.entity(helper, pos);
                    helper.assertValueEqual(value(helper, loaded, 0, 3, "held"), 2L, "still held after the load");
                    helper.assertValueEqual(value(helper, loaded, 0, 5, "count"), 0L, "none let through");
                    NetworkGameTests.insert(LogicControllerGameTests.network(helper, loaded), COBBLE, 1);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(
                        value(helper, LogicControllerGameTests.entity(helper, pos), 0, 5, "count"), 2L,
                        "both let through"))
                .thenSucceed();
    }
}
