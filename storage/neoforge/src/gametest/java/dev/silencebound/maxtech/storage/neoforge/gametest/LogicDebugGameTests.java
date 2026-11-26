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
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.SecuritySettings;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.display.DisplayPanelBlockEntity;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.logic.LogicEvaluator;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.NodeSettings;
import dev.silencebound.maxtech.storage.logic.NodeType;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.RunTrace;
import dev.silencebound.maxtech.storage.logic.controller.ControllerLog;
import dev.silencebound.maxtech.storage.logic.controller.ControllerTexts;
import dev.silencebound.maxtech.storage.logic.controller.EditorPayloads;
import dev.silencebound.maxtech.storage.logic.controller.LogicAction;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerMenu;
import dev.silencebound.maxtech.storage.logic.controller.LogicEditing;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.logic.controller.LogicWorker;
import dev.silencebound.maxtech.storage.logic.controller.SharedVariable;
import dev.silencebound.maxtech.storage.network.AccessLevel;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Debugging tools for logic rules (M7L2 chunk 8). Simulate mode: for every action type a simulating rule changes
 * nothing in the world (device on/off, priority, redstone out, pulse, moves, craft request and cancel, notices, display
 * text, port filter, shared variables, running another rule), records the action as simulated with a why line and a
 * "Would ..." line in the controller's log; memory comes back as it was when simulating began, also through a save, and
 * a pulse running at that moment ends. Revert: a bad save keeps the last working version, Revert puts it back switched
 * off and it survives saving and loading. Pinned values reach an open controller screen, also when a watched count
 * changes without a run.
 *
 * <p>
 * Set-up as {@link LogicControllerGameTests#setUp}: a powered controller at x 1, a test drive at x 2, the logic
 * controller at x 3; the action's target at x 4 (and a chest at x 5). Most graphs start from "redstone into the
 * controller changes", toggled with a redstone block on top of it.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LogicDebugGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey PLANKS = ItemKey.of(Items.OAK_PLANKS);
    private static final StackKey STICK = ItemKey.of(Items.STICK);
    private static final BlockPos LOGIC = NetworkGameTests.at(3);
    private static final BlockPos TARGET = NetworkGameTests.at(4);
    private static final BlockPos CHEST = NetworkGameTests.at(5);
    /** Runs with {@code logic.maxVariables} at 2. */
    private static final String SHADOW_LIMIT = "logic_sim_shadow_variable_limit";

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("logic_sim_switch", LogicDebugGameTests::simSwitch);
        register("logic_sim_priority", LogicDebugGameTests::simPriority);
        register("logic_sim_redstone_out", LogicDebugGameTests::simRedstone);
        register("logic_sim_pulse", LogicDebugGameTests::simPulse);
        register("logic_sim_move", LogicDebugGameTests::simMove);
        register("logic_sim_craft_request", LogicDebugGameTests::simCraft);
        register("logic_sim_craft_cancel", LogicDebugGameTests::simCancel);
        register("logic_sim_notify", LogicDebugGameTests::simNotify);
        register("logic_sim_write_display", LogicDebugGameTests::simDisplay);
        register("logic_sim_set_filter", LogicDebugGameTests::simFilter);
        register("logic_sim_shared_variable_shadow", LogicDebugGameTests::simVariable);
        register("logic_sim_run_rule", LogicDebugGameTests::simRunRule);
        register("logic_sim_memory_comes_back", LogicDebugGameTests::simMemory);
        register("logic_sim_ends_running_pulse", LogicDebugGameTests::simEndsPulse);
        register("logic_revert_after_bad_save", LogicDebugGameTests::revertAfterBadSave);
        register("logic_revert_survives_save_and_load", LogicDebugGameTests::revertSaved);
        register("logic_pins_reach_open_screen", LogicDebugGameTests::pinsReachScreen);
        StorageTests.function(SHADOW_LIMIT, StorageTestEnvironment.clearing(LogicDebugGameTests::simVariableLimit));
    }

    private LogicDebugGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("logic_debug"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 400);
        }
        StorageTests.test(event, event.registerEnvironment(MaxtechStorage.id(SHADOW_LIMIT),
                new StorageTestEnvironment("logic.maxVariables", "2", false)), SHADOW_LIMIT, STRUCTURE, 200);
    }

    // ---- helpers

    /** Nothing to prepare. */
    private static void nothing() {
    }

    private static LogicEvaluator<LogicKey> evaluator(GameTestHelper helper, LogicControllerBlockEntity entity,
            int graph) {
        if (entity.worker().size() <= graph) {
            throw helper.assertionException(Component.literal("no graph " + graph + " yet"));
        }
        return entity.worker().evaluator(graph)
                .orElseThrow(() -> helper.assertionException(Component.literal("graph " + graph + " is paused")));
    }

    private static void ran(GameTestHelper helper, LogicControllerBlockEntity entity) {
        helper.assertTrue(evaluator(helper, entity, 0).runs() > 0, "graph 0 has not run yet");
    }

    /** Toggles a redstone block on top of the logic controller at {@code logic}: "redstone changes" fires once. */
    private static void toggle(GameTestHelper helper, BlockPos logic) {
        BlockPos top = logic.above();
        helper.setBlock(top, helper.getBlockState(top).is(Blocks.REDSTONE_BLOCK) ? Blocks.AIR : Blocks.REDSTONE_BLOCK);
    }

    /** "Redstone into the controller changes" does {@code action} (node 2), configured by {@code settings}. */
    private static LogicGraph<LogicKey> onRedstone(NodeType action, UnaryOperator<NodeSettings<LogicKey>> settings) {
        return new LogicControllerGameTests.Graph().node(1, NodeTypes.REDSTONE_CHANGES, s -> s)
                .node(2, action, settings).wire(1, "changed", 2, "do").build();
    }

    /**
     * Saves {@code graph} as graph 0 in simulate mode, fires "redstone changes" when {@code trigger}, waits until the
     * graph recorded a simulated action of {@code kind}, then checks: no action of that kind was carried out, node 2's
     * why line says it was simulated, the controller's log reads "Would ...", and {@code unchanged} (the world as
     * before) holds a while later. {@code before} runs once the network has formed, before the graph is saved.
     */
    private static void simulated(GameTestHelper helper, LogicControllerBlockEntity entity, BlockPos logic,
            LogicGraph<LogicKey> graph, LogicAction.Kind kind, boolean trigger, Runnable before, Runnable unchanged) {
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            before.run();
            entity.worker().setGraph(0, "Sim", graph);
            entity.worker().setSimulate(0, true);
        }).thenWaitUntil(() -> ran(helper, entity)).thenIdle(2).thenExecute(() -> {
            if (trigger) {
                toggle(helper, logic);
            }
        }).thenWaitUntil(() -> helper.assertTrue(
                entity.worker().actions().stream().anyMatch(a -> a.kind() == kind && a.simulated()),
                "no simulated " + kind)).thenExecute(() -> {
                    helper.assertTrue(
                            entity.worker().actions().stream().noneMatch(a -> a.kind() == kind && !a.simulated()),
                            "a real " + kind);
                    RunTrace trace = evaluator(helper, entity, 0).trace();
                    int at = trace.find(2);
                    helper.assertTrue(at >= 0, "no why line for the action");
                    helper.assertValueEqual(trace.reason(at), LogicLang.WHY_SIMULATED, "why");
                    ControllerLog.Update log = LogicControllerMenu.forController(1, entity)
                            .logUpdate(helper.getLevel().getGameTime());
                    if (log == null) {
                        throw helper.assertionException(Component.literal("no log"));
                    }
                    helper.assertTrue(
                            log.rules().getFirst().lines().stream().anyMatch(line -> line.key().endsWith(".would")),
                            "no Would line in " + log.rules().getFirst().lines());
                }).thenIdle(10).thenExecute(unchanged).thenSucceed();
    }

    private static PortBlockEntity port(GameTestHelper helper, BlockPos pos, PortMode mode, String label) {
        helper.setBlock(pos, PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, Direction.EAST)
                .setValue(PortBlock.MODE, mode));
        PortBlockEntity port = helper.getBlockEntity(pos, PortBlockEntity.class);
        port.setLabel(DeviceLabel.of(label, null));
        return port;
    }

    private static ChestBlockEntity chest(GameTestHelper helper) {
        helper.setBlock(CHEST, Blocks.CHEST);
        return helper.getBlockEntity(CHEST, ChestBlockEntity.class);
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

    /** A condition switch in logic mode labelled {@code label}, owned by {@code owner}, whose team has the research. */
    private static ConditionSwitchBlockEntity lamp(GameTestHelper helper, BlockPos pos, ServerPlayer owner,
            String label) {
        ResearchTeam team = Research.team(owner, true);
        Research.addPoints(helper.getLevel().getServer(), team, 1000);
        Research.spendPoints(helper.getLevel().getServer(), team, MaxtechStorage.id("condition_switch"));
        helper.setBlock(pos, ConditionContent.SWITCH.get());
        ConditionSwitchBlockEntity entity = helper.getBlockEntity(pos, ConditionSwitchBlockEntity.class);
        entity.setOwner(OwnerRecord.of(owner));
        entity.worker().setSettings(ConditionSettings.DEFAULT.withType(ConditionType.LOGIC));
        entity.setLabel(DeviceLabel.of(label, null));
        return entity;
    }

    /** A controller set up as usual whose owner also owns {@code lamp}-style devices; returns it with its owner. */
    private static LogicControllerBlockEntity owned(GameTestHelper helper, String name, ServerPlayer[] owner) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, name);
        owner[0] = NetworkGameTests.player(helper.getLevel(), name + "_owner");
        entity.setOwner(OwnerRecord.of(owner[0]));
        return entity;
    }

    private static ServerPlayer near(GameTestHelper helper, String name) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), name);
        player.setPos(helper.absolutePos(LOGIC).getCenter());
        return player;
    }

    /** A notice without a trigger: a graph that does not compile. */
    private static LogicGraph<LogicKey> broken() {
        return new LogicControllerGameTests.Graph().node(1, NodeTypes.NOTIFY, s -> s.withText("text", "lonely"))
                .build();
    }

    // ---- simulate: one test per action type

    /** A simulating "turn intake off" leaves the port on. */
    private static void simSwitch(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "ld_switch");
        PortBlockEntity port = port(helper, TARGET, PortMode.IMPORT, "intake");
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph()
                .node(1, NodeTypes.CONSTANT, s -> s.withText("kind", "yes_no").withFlag("yes", false))
                .node(2, NodeTypes.DEVICE_SWITCH, s -> s.withText("label", "intake")).wire(1, "value", 2, "on").build();
        simulated(helper, entity, LOGIC, graph, LogicAction.Kind.SWITCH_DEVICE, false, LogicDebugGameTests::nothing,
                () -> helper.assertTrue(!port.switchedOff(), "the port was turned off"));
    }

    /** A simulating priority change leaves the drive's priority at 0. */
    private static void simPriority(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "ld_priority");
        DriveBlockEntity drive = CellGameTests.drive(helper, TARGET);
        drive.setLabel(DeviceLabel.of("bank", null));
        simulated(helper, entity, LOGIC,
                onRedstone(NodeTypes.SET_PRIORITY, s -> s.withText("label", "bank").withNumber("priority", 42)),
                LogicAction.Kind.SET_PRIORITY, true, LogicDebugGameTests::nothing,
                () -> helper.assertValueEqual(drive.priority(), 0, "priority"));
    }

    /** A simulating "redstone 7 to lamp" leaves the switch at 0. */
    private static void simRedstone(GameTestHelper helper) {
        ServerPlayer[] owner = new ServerPlayer[1];
        LogicControllerBlockEntity entity = owned(helper, "ld_redstone", owner);
        ConditionSwitchBlockEntity lamp = lamp(helper, TARGET, owner[0], "lamp");
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph()
                .node(1, NodeTypes.CONSTANT, s -> s.withNumber("value", 7))
                .node(2, NodeTypes.REDSTONE_OUT, s -> s.withText("label", "lamp")).wire(1, "value", 2, "strength")
                .build();
        simulated(helper, entity, LOGIC, graph, LogicAction.Kind.REDSTONE, false, LogicDebugGameTests::nothing, () -> {
            helper.assertValueEqual(lamp.worker().logicStrength(), 0, "logic strength");
            helper.assertValueEqual(lamp.worker().strength(), 0, "output");
        });
    }

    /** A simulating pulse never turns the switch on. */
    private static void simPulse(GameTestHelper helper) {
        ServerPlayer[] owner = new ServerPlayer[1];
        LogicControllerBlockEntity entity = owned(helper, "ld_pulse", owner);
        ConditionSwitchBlockEntity lamp = lamp(helper, TARGET, owner[0], "lamp");
        simulated(helper, entity, LOGIC,
                onRedstone(NodeTypes.PULSE_REDSTONE, s -> s.withText("label", "lamp").withNumber("ticks", 40)),
                LogicAction.Kind.REDSTONE, true, LogicDebugGameTests::nothing,
                () -> helper.assertValueEqual(lamp.worker().strength(), 0, "pulse output"));
    }

    /** A simulating move of 10 cobblestone to "box" moves nothing. */
    private static void simMove(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "ld_move");
        port(helper, TARGET, PortMode.EXPORT, "box");
        ChestBlockEntity chest = chest(helper);
        simulated(helper, entity, LOGIC,
                onRedstone(NodeTypes.MOVE_ITEMS,
                        s -> s.withItem("item", LogicKey.of(COBBLE)).withText("to", "box").withNumber("amount", 10)),
                LogicAction.Kind.MOVE_ITEMS, true,
                () -> NetworkGameTests.insert(LogicControllerGameTests.network(helper, entity), COBBLE, 100), () -> {
                    helper.assertValueEqual(count(chest, Items.COBBLESTONE), 0, "in the chest");
                    helper.assertValueEqual(LogicControllerGameTests.network(helper, entity).contents().amount(COBBLE),
                            100L, "stored");
                    helper.assertValueEqual(entity.worker().lastMoved(), 0L, "moved");
                });
    }

    /** A simulating craft request asks the queue for nothing. */
    private static void simCraft(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "ld_craft");
        simulated(helper, entity, LOGIC,
                onRedstone(NodeTypes.REQUEST_CRAFT,
                        s -> s.withItem("item", LogicKey.of(COBBLE)).withNumber("amount", 5)),
                LogicAction.Kind.REQUEST_CRAFT, true, LogicDebugGameTests::nothing,
                () -> helper.assertValueEqual(
                        StockKeepingGameTests.QUEUE.calls(LogicControllerGameTests.network(helper, entity)).size(), 0,
                        "requests"));
    }

    /** A simulating cancel leaves a waiting stick job alone. */
    private static void simCancel(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(3), CraftingTestContent.HOST.get());
        helper.getBlockEntity(NetworkGameTests.at(3), CraftingTestContent.Host.class).setPatterns(List.of(sticks()));
        helper.setBlock(TARGET, LogicContent.CONTROLLER.get());
        LogicControllerBlockEntity entity = LogicControllerGameTests.entity(helper, TARGET);
        entity.setOwner(OwnerRecord.of(NetworkGameTests.player(helper.getLevel(), "ld_cancel")));
        UUID[] job = new UUID[1];
        simulated(helper, entity, TARGET,
                onRedstone(NodeTypes.CANCEL_CRAFT, s -> s.withItem("item", LogicKey.of(STICK))),
                LogicAction.Kind.CANCEL_CRAFT, true, () -> {
                    NetworkState network = LogicControllerGameTests.network(helper, entity);
                    NetworkGameTests.insert(network, PLANKS, 8);
                    CraftingPlanner.Result plan = CraftingPlanner.planNow(network, STICK, 8);
                    Optional<CraftingJob> submitted = JobQueue.submit(network, network.nodes().iterator().nextLong(),
                            plan, JobQueue.Request.player(UUID.randomUUID())).job();
                    job[0] = submitted.orElseThrow(() -> helper.assertionException(Component.literal("job refused")))
                            .id();
                },
                () -> helper.assertTrue(JobQueue.progress(helper.getLevel(), job[0]) != null, "the job was cancelled"));
    }

    private static CraftingPattern sticks() {
        return new CraftingPattern(CraftingPattern.Kind.CRAFTING,
                List.of(new PatternInput(new PatternIngredient.Exact(PLANKS), 2, Optional.empty(), false)),
                List.of(new PatternOutput(STICK, 4, 1f)), Optional.empty(), Optional.empty());
    }

    /** A simulating notice posts nothing to the log or the displays. */
    private static void simNotify(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "ld_notify");
        simulated(helper, entity, LOGIC,
                onRedstone(NodeTypes.NOTIFY, s -> s.withText("text", "hello").withFlag("displays", true)),
                LogicAction.Kind.NOTIFY, true, LogicDebugGameTests::nothing, () -> {
                    NetworkState network = LogicControllerGameTests.network(helper, entity);
                    helper.assertValueEqual(network.notices().posted(), 0L, "log notices");
                    helper.assertValueEqual(network.displayNotices().posted(), 0L, "display notices");
                });
    }

    /** A simulating display text leaves the board as it was. */
    private static void simDisplay(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "ld_display");
        DisplayPanelBlockEntity board = DisplayGameTests.panel(helper, TARGET, Direction.NORTH);
        board.setLabel(DeviceLabel.of("board", null));
        String before = board.settings().text();
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph()
                .node(1, NodeTypes.ITEM_COUNT, s -> s.withItem("item", LogicKey.of(COBBLE)))
                .node(2, NodeTypes.WRITE_DISPLAY, s -> s.withText("label", "board").withText("text", "Cobble: {a}"))
                .wire(1, "count", 2, "a").build();
        simulated(helper, entity, LOGIC, graph, LogicAction.Kind.WRITE_DISPLAY, false, LogicDebugGameTests::nothing,
                () -> helper.assertValueEqual(board.settings().text(), before, "display text"));
    }

    /** A simulating filter change leaves the port's filter empty. */
    private static void simFilter(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "ld_filter");
        PortBlockEntity port = port(helper, TARGET, PortMode.EXPORT, "out");
        simulated(helper, entity, LOGIC, onRedstone(NodeTypes.SET_FILTER,
                s -> s.withText("label", "out").withItem("item", LogicKey.of(COBBLE)).withText("port_mode", "import")),
                LogicAction.Kind.SET_FILTER, true, LogicDebugGameTests::nothing, () -> {
                    helper.assertTrue(port.filter().getItem(0).isEmpty(), "filter slot 1 set");
                    helper.assertValueEqual(port.mode(), PortMode.EXPORT, "mode");
                });
    }

    /**
     * A simulating graph sets "level" to 7: only its shadow copy changes, which it reads itself; the controller's
     * variables and another graph see nothing. Once simulating ends the graph starts afresh, sets it for real and the
     * other graph sees 7.
     */
    private static void simVariable(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "ld_variable");
        LogicGraph<LogicKey> setter = new LogicControllerGameTests.Graph().node(1, NodeTypes.GRAPH_START, s -> s)
                .node(2, NodeTypes.SET_SHARED_VARIABLE, s -> s.withText("name", "level").withNumber("value", 7))
                .node(3, NodeTypes.SHARED_VARIABLE, s -> s.withText("name", "level")).wire(1, "started", 2, "do")
                .build();
        LogicGraph<LogicKey> reader = new LogicControllerGameTests.Graph()
                .node(1, NodeTypes.SHARED_VARIABLE, s -> s.withText("name", "level")).build();
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            entity.worker().setGraph(0, "Setter", setter);
            entity.worker().setSimulate(0, true);
            entity.worker().setGraph(1, "Reader", reader);
        }).thenWaitUntil(() -> helper.assertValueEqual(entity.worker().variable(0, "level"), 7L, "shadow")).thenIdle(5)
                .thenExecute(() -> {
                    helper.assertTrue(entity.worker().variables().isEmpty(), "a real variable was set");
                    helper.assertValueEqual(entity.worker().variable("level"), 0L, "real value");
                    LogicEvaluator<LogicKey> own = evaluator(helper, entity, 0);
                    helper.assertValueEqual(own.value(own.graph().slot(3, "value")), 7L, "the graph reads its shadow");
                    LogicEvaluator<LogicKey> other = evaluator(helper, entity, 1);
                    helper.assertValueEqual(other.value(other.graph().slot(1, "value")), 0L, "the other graph");
                    entity.worker().setSimulate(0, false);
                    helper.assertValueEqual(entity.worker().variable(0, "level"), 0L, "shadow dropped");
                }).thenWaitUntil(() -> helper.assertValueEqual(entity.worker().variable("level"), 7L, "set for real"))
                .thenWaitUntil(() -> {
                    LogicEvaluator<LogicKey> other = evaluator(helper, entity, 1);
                    helper.assertValueEqual(other.value(other.graph().slot(1, "value")), 7L, "the other graph");
                }).thenSucceed();
    }

    /**
     * With {@code logic.maxVariables} at 2 and the real variable "a" set, a simulating graph sets "a" (in its shadow),
     * then the new name "b": "a" counts once, so "b" fits.
     */
    private static void simVariableLimit(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "ld_shadow_limit");
        LogicGraph<LogicKey> setter = new LogicControllerGameTests.Graph().node(1, NodeTypes.GRAPH_START, s -> s)
                .node(2, NodeTypes.SET_SHARED_VARIABLE, s -> s.withText("name", "a").withNumber("value", 5))
                .node(3, NodeTypes.REDSTONE_CHANGES, s -> s)
                .node(4, NodeTypes.SET_SHARED_VARIABLE, s -> s.withText("name", "b").withNumber("value", 6))
                .wire(1, "started", 2, "do").wire(3, "changed", 4, "do").build();
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            entity.worker().loadVariables(List.of(new SharedVariable("a", 1, false)));
            entity.worker().setGraph(0, "Setter", setter);
            entity.worker().setSimulate(0, true);
        }).thenWaitUntil(() -> helper.assertValueEqual(entity.worker().variable(0, "a"), 5L, "a in the shadow"))
                .thenExecute(() -> toggle(helper, LOGIC))
                .thenWaitUntil(() -> helper.assertValueEqual(entity.worker().variable(0, "b"), 6L, "b in the shadow"))
                .thenExecute(() -> {
                    helper.assertValueEqual(entity.worker().variables(), List.of(new SharedVariable("a", 1, false)),
                            "real variables");
                    RunTrace trace = evaluator(helper, entity, 0).trace();
                    int at = trace.find(4);
                    helper.assertTrue(at < 0 || !trace.reason(at).equals(LogicLang.WHY_VARIABLES_FULL), "b refused");
                }).thenSucceed();
    }

    /** A simulating "run Target" does not run graph 1, whose counter stays 0. */
    private static void simRunRule(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "ld_rule");
        LogicGraph<LogicKey> target = new LogicControllerGameTests.Graph().node(1, NodeTypes.RUN_BY_RULE, s -> s)
                .node(2, NodeTypes.COUNTER, s -> s).wire(1, "ran", 2, "up").build();
        simulated(helper, entity, LOGIC, onRedstone(NodeTypes.RUN_RULE, s -> s.withText("rule", "Target")),
                LogicAction.Kind.RUN_RULE, true, () -> {
                    // Graph 0 is replaced by the simulating graph; graph 1 is the target.
                    entity.worker().setGraph(0, "First", target);
                    entity.worker().setGraph(1, "Target", target);
                }, () -> {
                    LogicEvaluator<LogicKey> other = evaluator(helper, entity, 1);
                    helper.assertValueEqual(other.value(other.graph().slot(2, "count")), 0L, "the target ran");
                });
    }

    // ---- simulate: memory and pulses

    /**
     * A counter counts 2 for real, then 2 more while simulating; a save and load while simulating keeps simulate mode
     * and gives the simulation the real memory again (2), from which it counts on; when simulating ends the counter is
     * back at 2. Redstone is off at each switch, so no change event fires on the first look.
     */
    private static void simMemory(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "ld_memory");
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph().node(1, NodeTypes.REDSTONE_CHANGES, s -> s)
                .node(2, NodeTypes.COUNTER, s -> s).wire(1, "changed", 2, "up").build();
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Count", graph)).thenWaitUntil(() -> ran(helper, entity))
                .thenExecute(() -> toggle(helper, LOGIC)).thenWaitUntil(() -> count(helper, entity, 1))
                .thenExecute(() -> toggle(helper, LOGIC)).thenWaitUntil(() -> count(helper, entity, 2))
                .thenExecute(() -> entity.worker().setSimulate(0, true)).thenWaitUntil(() -> ran(helper, entity))
                .thenExecute(() -> toggle(helper, LOGIC)).thenWaitUntil(() -> count(helper, entity, 3))
                .thenExecute(() -> toggle(helper, LOGIC)).thenWaitUntil(() -> count(helper, entity, 4))
                .thenExecute(() -> {
                    helper.assertValueEqual(
                            entity.worker().slots().getFirst().memory().nodes().stream()
                                    .filter(entry -> entry.node() == 2).findFirst().orElseThrow().values().getFirst(),
                            2L, "saved memory while simulating");
                    CompoundTag saved = entity.saveWithoutMetadata(helper.getLevel().registryAccess());
                    entity.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING,
                            helper.getLevel().registryAccess(), saved));
                    helper.assertTrue(entity.worker().simulating(0), "still simulating after a load");
                }).thenWaitUntil(() -> count(helper, entity, 2)).thenExecute(() -> toggle(helper, LOGIC))
                .thenWaitUntil(() -> count(helper, entity, 3)).thenExecute(() -> toggle(helper, LOGIC))
                .thenWaitUntil(() -> count(helper, entity, 4)).thenExecute(() -> {
                    entity.worker().setSimulate(0, false);
                    helper.assertTrue(!entity.worker().simulating(0), "simulating");
                }).thenWaitUntil(() -> count(helper, entity, 2)).thenSucceed();
    }

    private static void count(GameTestHelper helper, LogicControllerBlockEntity entity, long expected) {
        LogicEvaluator<LogicKey> evaluator = evaluator(helper, entity, 0);
        helper.assertValueEqual(evaluator.value(evaluator.graph().slot(2, "count")), expected, "count");
    }

    /** A pulse running when simulating begins ends at once: a simulated end could not turn it off. */
    private static void simEndsPulse(GameTestHelper helper) {
        ServerPlayer[] owner = new ServerPlayer[1];
        LogicControllerBlockEntity entity = owned(helper, "ld_endpulse", owner);
        ConditionSwitchBlockEntity lamp = lamp(helper, TARGET, owner[0], "lamp");
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Pulse",
                        onRedstone(NodeTypes.PULSE_REDSTONE,
                                s -> s.withText("label", "lamp").withNumber("ticks", 1000).withNumber("strength", 9))))
                .thenWaitUntil(() -> ran(helper, entity)).thenExecute(() -> toggle(helper, LOGIC))
                .thenWaitUntil(() -> helper.assertValueEqual(lamp.worker().strength(), 9, "on"))
                .thenExecute(() -> entity.worker().setSimulate(0, true))
                .thenWaitUntil(() -> helper.assertValueEqual(lamp.worker().strength(), 0, "ended"))
                .thenExecute(() -> helper.assertTrue(entity.worker().pulsedSwitches().isEmpty(), "still held"))
                .thenSucceed();
    }

    // ---- revert

    /**
     * A working graph, then a save with errors: the working one is kept. A second bad save keeps it too. Revert (a menu
     * click) is refused while another player has the rule open in the editor, then puts it back switched off, with
     * nothing left to revert to; a revert of a working version swaps the two.
     */
    private static void revertAfterBadSave(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "ld_revert");
        ServerPlayer owner = near(helper, "ld_revert");
        entity.setOwner(OwnerRecord.of(owner));
        // A second player with logic access through a security module, who opens the rule in the editor.
        ServerPlayer editor = near(helper, "ld_revert_editor");
        BlockPos core = NetworkGameTests.at(1);
        NetworkGameTests.controllerEntity(helper, core).setOwner(OwnerRecord.of(owner));
        ControllerClusterGameTests.expansion(helper, core.above(), ExpansionContent.SECURITY_MODULE);
        NetworkGameTests.controllerEntity(helper, core).setSecurity(SecuritySettings.DEFAULT
                .withPlayer(editor.getUUID(), editor.getGameProfile().name(), AccessLevel.LOGIC));
        LogicGraph<LogicKey> good = LogicControllerGameTests.crossesNotify(COBBLE, 10, "good");
        LogicGraph<LogicKey> other = LogicControllerGameTests.crossesNotify(COBBLE, 20, "other");
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            LogicWorker worker = entity.worker();
            worker.setGraph(0, "Rule", good);
            helper.assertTrue(!worker.hasPrevious(0), "a new graph has nothing to revert to");
            worker.setGraph(0, "Rule", broken());
            helper.assertValueEqual(worker.status(0), LogicWorker.Status.PAUSED, "the bad save is paused");
            helper.assertValueEqual(worker.previous(0), Optional.of(good), "kept after a bad save");
            worker.setGraph(0, "Rule", broken().with(new dev.silencebound.maxtech.storage.logic.LogicNode<>(5,
                    NodeTypes.DELAY, 0, 0, NodeSettings.empty())));
            helper.assertValueEqual(worker.previous(0), Optional.of(good), "a second bad save keeps it");

            LogicControllerMenu menu = LogicControllerMenu.forController(3, entity);
            owner.containerMenu = menu;
            // Another player has the rule open in the editor: refused, their unsaved edits stay theirs.
            LogicControllerMenu editorMenu = LogicControllerMenu.forController(4, entity);
            editor.containerMenu = editorMenu;
            EditorPayloads.Graph opened = LogicEditing.open(editor, 4, 0);
            helper.assertTrue(opened != null && !opened.readOnly(), "the other player edits");
            helper.assertTrue(!menu.clickMenuButton(owner, LogicControllerMenu.BUTTON_REVERT), "refused while edited");
            helper.assertTrue(worker.hasPrevious(0), "still kept");
            helper.assertTrue(worker.history(0).isEmpty(), "a refused revert is not in the history");
            LogicEditing.close(editor, 4);
            helper.assertTrue(menu.clickMenuButton(owner, LogicControllerMenu.BUTTON_REVERT), "revert");
            helper.assertValueEqual(worker.history(0).size(), 1, "the revert is in the history");
            helper.assertValueEqual(worker.graph(0), good, "reverted");
            helper.assertValueEqual(worker.status(0), LogicWorker.Status.DISABLED, "switched off");
            helper.assertTrue(!worker.hasPrevious(0), "a broken version is not kept");
            helper.assertTrue(!menu.clickMenuButton(owner, LogicControllerMenu.BUTTON_REVERT), "nothing to revert");

            worker.setGraph(0, "Rule", other);
            helper.assertValueEqual(worker.previous(0), Optional.of(good), "the working one before");
            helper.assertTrue(worker.revert(0), "revert");
            helper.assertValueEqual(worker.graph(0), good, "back");
            helper.assertValueEqual(worker.previous(0), Optional.of(other), "swapped");
            helper.assertTrue(!menu.clickMenuButton(owner, LogicControllerMenu.BUTTON_REVERT + 5), "no graph 5");
        }).thenSucceed();
    }

    /** The last working version and simulate mode survive a save and load and the dropped item. */
    private static void revertSaved(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "ld_revert_saved");
        LogicGraph<LogicKey> good = LogicControllerGameTests.crossesNotify(COBBLE, 10, "good");
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            entity.worker().setGraph(0, "Rule", good);
            entity.worker().setGraph(0, "Rule", broken());
            entity.worker().setSimulate(0, true);
            CompoundTag saved = entity.saveWithoutMetadata(helper.getLevel().registryAccess());
            BlockPos other = NetworkGameTests.at(8);
            helper.setBlock(other, LogicContent.CONTROLLER.get());
            LogicControllerBlockEntity loaded = LogicControllerGameTests.entity(helper, other);
            loaded.loadCustomOnly(
                    TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
            LogicWorker worker = loaded.worker();
            helper.assertValueEqual(worker.previous(0), Optional.of(good), "kept through a load");
            helper.assertTrue(worker.simulating(0), "simulating through a load");
            helper.assertTrue(worker.revert(0), "revert after the load");
            helper.assertValueEqual(worker.graph(0), good, "reverted");
            helper.assertTrue(worker.simulating(0), "a revert keeps simulate mode");
        }).thenSucceed();
    }

    // ---- pinned values

    /**
     * Two pinned nodes (an unknown id is dropped) reach the open controller screen's log; a watched count that changes
     * without crossing its limit costs no run but still reaches the screen. An editor save keeps the pins it sends.
     */
    private static void pinsReachScreen(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "ld_pins");
        ServerPlayer owner = near(helper, "ld_pins");
        entity.setOwner(OwnerRecord.of(owner));
        LogicGraph<LogicKey> graph = LogicControllerGameTests.crossesNotify(COBBLE, 1000, "full");
        LogicControllerMenu menu = LogicControllerMenu.forController(2, entity);
        long[] runs = new long[1];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            entity.worker().setGraph(0, "Pins", graph);
            entity.worker().setPins(0, List.of(1, 99));
            helper.assertValueEqual(entity.worker().pins(0), List.of(1), "an unknown node is dropped");
        }).thenWaitUntil(() -> ran(helper, entity)).thenIdle(ControllerLog.INTERVAL).thenExecute(() -> {
            ControllerLog.Update log = menu.logUpdate(helper.getLevel().getGameTime());
            if (log == null) {
                throw helper.assertionException(Component.literal("no first log"));
            }
            List<ControllerLog.Pin> pins = log.rules().getFirst().pins();
            helper.assertValueEqual(pins.size(), 1, "pins");
            helper.assertValueEqual(pins.getFirst().node(), NodeTypes.COUNT_CROSSES.nameKey(), "node");
            helper.assertValueEqual(pins.getFirst().value(), 0L, "value");
            helper.assertValueEqual(pins.getFirst().limit(), 1000L, "limit");
            runs[0] = evaluator(helper, entity, 0).runs();
            NetworkGameTests.insert(LogicControllerGameTests.network(helper, entity), COBBLE, 5);
        }).thenIdle(ControllerLog.INTERVAL + 2).thenExecute(() -> {
            helper.assertValueEqual(evaluator(helper, entity, 0).runs(), runs[0], "a count under the limit ran it");
            ControllerLog.Update log = menu.logUpdate(helper.getLevel().getGameTime());
            if (log == null) {
                throw helper.assertionException(Component.literal("the new value was not sent"));
            }
            helper.assertValueEqual(log.rules().getFirst().pins().getFirst().value(), 5L, "new value");
            helper.assertTrue(menu.logUpdate(helper.getLevel().getGameTime() + ControllerLog.INTERVAL) == null,
                    "sent again without a change");

            owner.containerMenu = menu;
            if (LogicEditing.open(owner, 2, 0) == null) {
                throw helper.assertionException(Component.literal("the editor did not open"));
            }
            // The editor sends the count node and the notice: a notice has no value to pin and is dropped.
            LogicGraph<LogicKey> counted = graph.with(new dev.silencebound.maxtech.storage.logic.LogicNode<>(3,
                    NodeTypes.ITEM_COUNT, 0, 40, NodeSettings.<LogicKey>empty().withItem("item", LogicKey.of(COBBLE))));
            EditorPayloads.Saved saved = LogicEditing.save(owner, new EditorPayloads.Save(2, 0, "Pins",
                    LogicEditing.encode(helper.getLevel().registryAccess(), counted), List.of(3, 2)));
            helper.assertTrue(saved != null && saved.saved(), "saved");
            helper.assertValueEqual(entity.worker().pins(0), List.of(3), "pins from the editor");
            helper.assertValueEqual(entity.worker().slots().getFirst().pins(), List.of(3), "saved with the slot");
        }).thenSucceed();
    }
}
