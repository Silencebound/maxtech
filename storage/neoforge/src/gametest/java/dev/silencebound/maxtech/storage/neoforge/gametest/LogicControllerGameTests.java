package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.SecuritySettings;
import dev.silencebound.maxtech.storage.logic.LogicEvaluator;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicNode;
import dev.silencebound.maxtech.storage.logic.NodeSettings;
import dev.silencebound.maxtech.storage.logic.NodeType;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.Wire;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.controller.CardPayloads;
import dev.silencebound.maxtech.storage.logic.controller.ControllerLog;
import dev.silencebound.maxtech.storage.logic.controller.EditorPayloads;
import dev.silencebound.maxtech.storage.logic.controller.LogicEditing;
import dev.silencebound.maxtech.storage.logic.controller.ControllerTexts;
import dev.silencebound.maxtech.storage.logic.controller.LogicAction;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlock;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerMenu;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.logic.controller.LogicWorker;
import dev.silencebound.maxtech.storage.logic.controller.TeamBudget;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.sensor.SensorBlock;
import dev.silencebound.maxtech.storage.sensor.SensorBlockEntity;
import dev.silencebound.maxtech.storage.sensor.SensorContent;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The Logic Controller (M7L area B): graphs and their memory survive saving, loading and the dropped item and act the
 * same afterwards; a graph with errors never acts and stops its previous version; events wake graphs only on crossings;
 * triggers within a tick are one run; the team budget makes graphs wait and resume; a network that is not online
 * evaluates nothing and every graph rechecks once when it is back; opening needs logic access.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LogicControllerGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("logic_controller_saved_and_loaded", LogicControllerGameTests::savedAndLoaded);
        register("logic_controller_paused_never_acts", LogicControllerGameTests::pausedNeverActs);
        register("logic_controller_triggers_on_crossings", LogicControllerGameTests::crossings);
        register("logic_controller_budget_waits_and_resumes", LogicControllerGameTests::budget);
        register("logic_controller_coalesces_a_tick", LogicControllerGameTests::coalesces);
        register("logic_controller_offline_network", LogicControllerGameTests::offline);
        register("logic_controller_needs_logic_access", LogicControllerGameTests::access);
        register("logic_controller_sensor_wakes_graph", LogicControllerGameTests::sensor);
        register("logic_controller_unowned_waits_at_limit", LogicControllerGameTests::unownedWaits);
        register("logic_controller_unowned_charges_network_owner",
                LogicControllerGameTests::unownedChargesNetworkOwner);
        register("logic_controller_log_has_why_chain", LogicControllerGameTests::logWhyChain);
        register("logic_controller_card_syncs_once", LogicControllerGameTests::cardSyncsOnce);
    }

    private LogicControllerGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("logic_controller"), StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 300);
        }
    }

    // ---- graphs

    /** Builds a graph with {@link LogicKey} items. */
    static final class Graph {
        private final List<LogicNode<LogicKey>> nodes = new ArrayList<>();
        private final List<Wire> wires = new ArrayList<>();

        Graph node(int id, NodeType type, UnaryOperator<NodeSettings<LogicKey>> settings) {
            nodes.add(new LogicNode<>(id, type, id * 20, 0, settings.apply(NodeSettings.empty())));
            return this;
        }

        Graph wire(int from, String output, int to, String input) {
            wires.add(new Wire(from, output, to, input));
            return this;
        }

        LogicGraph<LogicKey> build() {
            return new LogicGraph<>(nodes, wires);
        }
    }

    /** "Cobblestone above {@code limit}" fires a notice saying {@code text}. */
    static LogicGraph<LogicKey> crossesNotify(StackKey key, long limit, String text) {
        return new Graph()
                .node(1, NodeTypes.COUNT_CROSSES,
                        s -> s.withItem("item", LogicKey.of(key)).withNumber("threshold", limit))
                .node(2, NodeTypes.NOTIFY, s -> s.withText("text", text)).wire(1, "crossed", 2, "do").build();
    }

    // ---- set-up

    /** A powered controller at x 1, a test device at x 2 and a logic controller at x 3, owned by a new player. */
    static LogicControllerBlockEntity setUp(GameTestHelper helper, String owner) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(3), LogicContent.CONTROLLER.get());
        NetworkGameTests.power(helper, core, 1_000_000);
        LogicControllerBlockEntity entity = entity(helper, NetworkGameTests.at(3));
        entity.setOwner(OwnerRecord.of(NetworkGameTests.player(helper.getLevel(), owner)));
        return entity;
    }

    static LogicControllerBlockEntity entity(GameTestHelper helper, BlockPos pos) {
        return helper.getBlockEntity(pos, LogicControllerBlockEntity.class);
    }

    static NetworkState network(GameTestHelper helper, LogicControllerBlockEntity entity) {
        NetworkState network = entity.network();
        if (network == null) {
            throw helper.assertionException(Component.literal("the logic controller is in no network"));
        }
        return network;
    }

    private static long extract(NetworkState network, StackKey key, long amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            long moved = network.extract(key, amount, transaction);
            transaction.commit();
            return moved;
        }
    }

    private static LogicEvaluator<LogicKey> evaluator(GameTestHelper helper, LogicControllerBlockEntity entity,
            int graph) {
        return entity.worker().evaluator(graph)
                .orElseThrow(() -> helper.assertionException(Component.literal("graph " + graph + " is paused")));
    }

    private static List<String> described(List<LogicAction> actions) {
        List<String> out = new ArrayList<>();
        for (LogicAction action : actions) {
            out.add(action.describe());
        }
        return out;
    }

    /** Waits until graph 0 has run at least once since it was set. */
    private static void ran(GameTestHelper helper, LogicControllerBlockEntity entity) {
        helper.assertTrue(evaluator(helper, entity, 0).runs() > 0, "graph 0 has not run yet");
    }

    // ---- tests

    /**
     * Cobblestone above 10 counts once (a counter) and fires a notice; "past the limit" switches the "lamps" on. The
     * block entity is saved, broken and placed again with the saved data, and once from its dropped item: each time the
     * graph reads everything again, keeps its counter and crossing (no second notice), and sends the same switch again.
     */
    private static void savedAndLoaded(GameTestHelper helper) {
        LogicControllerBlockEntity first = setUp(helper, "lc_saver");
        LogicGraph<LogicKey> graph = new Graph()
                .node(1, NodeTypes.COUNT_CROSSES,
                        s -> s.withItem("item", LogicKey.of(COBBLE)).withNumber("threshold", 10))
                .node(2, NodeTypes.NOTIFY, s -> s.withText("text", "full"))
                .node(3, NodeTypes.DEVICE_SWITCH, s -> s.withText("label", "lamps")).node(4, NodeTypes.COUNTER, s -> s)
                .wire(1, "crossed", 2, "do").wire(1, "past", 3, "on").wire(1, "crossed", 4, "up").build();
        BlockPos pos = NetworkGameTests.at(3);
        BlockPos dropPos = NetworkGameTests.at(4);
        CompoundTag[] saved = new CompoundTag[1];
        List<String> before = new ArrayList<>();
        helper.startSequence().thenWaitUntil(() -> network(helper, first)).thenExecute(() -> {
            first.worker().setGraph(0, "Lamps", graph);
            NetworkGameTests.insert(network(helper, first), COBBLE, 20);
        }).thenWaitUntil(() -> helper.assertValueEqual(first.worker().actions().size(), 2, "actions"))
                .thenExecute(() -> {
                    before.addAll(described(first.worker().actions()));
                    helper.assertTrue(before.contains("notify \"full\" 3"), "notice: " + before);
                    helper.assertTrue(before.contains("switch_device lamps 1"), "switch: " + before);
                    saved[0] = first.saveWithoutMetadata(helper.getLevel().registryAccess());
                    List<ItemStack> drops = Block.getDrops(helper.getBlockState(pos), helper.getLevel(),
                            helper.absolutePos(pos), first);
                    helper.assertTrue(drops.getFirst().get(ModDataComponents.LOGIC_GRAPHS.get()) != null,
                            "graphs in the dropped item");
                    helper.setBlock(pos, Blocks.AIR);
                    helper.setBlock(pos, LogicContent.CONTROLLER.get());
                    entity(helper, pos).loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING,
                            helper.getLevel().registryAccess(), saved[0]));
                    helper.setBlock(dropPos, LogicContent.CONTROLLER.get());
                    entity(helper, dropPos).applyComponentsFromItemStack(drops.getFirst());
                }).thenWaitUntil(() -> {
                    ran(helper, entity(helper, pos));
                    ran(helper, entity(helper, dropPos));
                }).thenIdle(5).thenExecute(() -> {
                    for (BlockPos at : List.of(pos, dropPos)) {
                        LogicControllerBlockEntity loaded = entity(helper, at);
                        LogicEvaluator<LogicKey> evaluator = evaluator(helper, loaded, 0);
                        helper.assertValueEqual(loaded.worker().name(0), "Lamps", "name");
                        helper.assertValueEqual(evaluator.value(evaluator.graph().slot(4, "count")), 1L,
                                "the counter kept its count");
                        helper.assertValueEqual(described(loaded.worker().actions()), List.of("switch_device lamps 1"),
                                "actions after the load");
                    }
                }).thenSucceed();
    }

    /**
     * A running graph acts; saving a version with an error (a compare without its input) pauses it: the old version
     * stops, and the broken one's constant switch never acts, whatever happens on the network.
     */
    private static void pausedNeverActs(GameTestHelper helper) {
        LogicControllerBlockEntity entity = setUp(helper, "lc_pauser");
        LogicGraph<LogicKey> broken = new Graph()
                .node(1, NodeTypes.CONSTANT, s -> s.withText("kind", "yes_no").withFlag("yes", true))
                .node(2, NodeTypes.DEVICE_SWITCH, s -> s.withText("label", "lamps"))
                .node(3, NodeTypes.COUNT_CROSSES,
                        s -> s.withItem("item", LogicKey.of(COBBLE)).withNumber("threshold", 10))
                .node(4, NodeTypes.COMPARE, s -> s).node(5, NodeTypes.NOTIFY, s -> s.withText("text", "broken"))
                .wire(1, "value", 2, "on").wire(3, "crossed", 5, "do").build();
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            entity.worker().setGraph(0, "Full", crossesNotify(COBBLE, 10, "full"));
            NetworkGameTests.insert(network(helper, entity), COBBLE, 20);
        }).thenWaitUntil(() -> helper.assertValueEqual(entity.worker().actions().size(), 1, "the good graph acted"))
                .thenExecute(() -> {
                    entity.worker().clearActions();
                    helper.assertTrue(entity.worker().setGraph(0, "Full", broken).orElseThrow().paused(),
                            "the broken graph compiles");
                    helper.assertValueEqual(entity.worker().status(0), LogicWorker.Status.PAUSED, "status");
                    helper.assertTrue(!entity.worker().errors(0).isEmpty(), "errors kept");
                    extract(network(helper, entity), COBBLE, 15);
                }).thenIdle(5).thenExecute(() -> NetworkGameTests.insert(network(helper, entity), COBBLE, 15))
                .thenIdle(20).thenExecute(() -> {
                    helper.assertValueEqual(described(entity.worker().actions()), List.of(), "actions while paused");
                    helper.assertTrue(entity.worker().evaluator(0).isEmpty(), "no evaluator");
                    helper.assertValueEqual(entity.worker().slots().getFirst().graph(), broken, "saved as built");
                }).thenSucceed();
    }

    /**
     * Cobblestone above 10: inserts that stay below, and inserts above once crossed, cost no run; crossing costs one
     * run and one notice; switching back and crossing again fires again.
     */
    private static void crossings(GameTestHelper helper) {
        LogicControllerBlockEntity entity = setUp(helper, "lc_crosser");
        long[] runs = new long[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            entity.worker().setGraph(0, "Full", crossesNotify(COBBLE, 10, "full"));
        }).thenWaitUntil(() -> ran(helper, entity)).thenExecute(() -> {
            runs[0] = entity.worker().runs();
            NetworkGameTests.insert(network(helper, entity), COBBLE, 5);
        }).thenIdle(3).thenExecute(() -> NetworkGameTests.insert(network(helper, entity), COBBLE, 3)).thenIdle(3)
                .thenExecute(() -> {
                    helper.assertValueEqual(entity.worker().runs(), runs[0], "runs below the limit");
                    NetworkGameTests.insert(network(helper, entity), COBBLE, 5);
                }).thenIdle(3).thenExecute(() -> {
                    helper.assertValueEqual(entity.worker().runs(), runs[0] + 1, "runs after crossing");
                    helper.assertValueEqual(described(entity.worker().actions()), List.of("notify \"full\" 3"),
                            "one notice");
                    NetworkGameTests.insert(network(helper, entity), COBBLE, 7);
                }).thenIdle(3).thenExecute(() -> {
                    helper.assertValueEqual(entity.worker().runs(), runs[0] + 1, "runs while above");
                    extract(network(helper, entity), COBBLE, 15);
                }).thenIdle(3).thenExecute(() -> {
                    helper.assertValueEqual(entity.worker().runs(), runs[0] + 2, "runs after switching back");
                    NetworkGameTests.insert(network(helper, entity), COBBLE, 10);
                }).thenIdle(3).thenExecute(() -> {
                    helper.assertValueEqual(entity.worker().actions().size(), 2, "a notice per crossing");
                }).thenSucceed();
    }

    /**
     * The team's budget for this second is used up elsewhere: a crossing makes the graph wait (no run, no action,
     * status waiting), and it runs in the next second.
     */
    private static void budget(GameTestHelper helper) {
        waitsAtLimit(helper, setUp(helper, "lc_budget"));
    }

    /**
     * An unowned controller on an unowned network (placed by a machine, a command or a structure) is not unlimited: it
     * shares the unowned budget and waits at the same limit.
     */
    private static void unownedWaits(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(3), LogicContent.CONTROLLER.get());
        NetworkGameTests.power(helper, core, 1_000_000);
        LogicControllerBlockEntity entity = entity(helper, NetworkGameTests.at(3));
        helper.assertValueEqual(TeamBudget.team(helper.getLevel(), entity), TeamBudget.UNOWNED, "team");
        waitsAtLimit(helper, entity);
    }

    /** An unowned controller on an owned network charges the network owner's team. */
    private static void unownedChargesNetworkOwner(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(3), LogicContent.CONTROLLER.get());
        NetworkGameTests.power(helper, core, 1_000_000);
        OwnerRecord owner = OwnerRecord.of(NetworkGameTests.player(helper.getLevel(), "lc_network_owner"));
        NetworkGameTests.controllerEntity(helper, core).setOwner(owner);
        LogicControllerBlockEntity entity = entity(helper, NetworkGameTests.at(3));
        UUID team = TeamBudget.team(helper.getLevel(), owner);
        long[] before = new long[1];
        helper.startSequence().thenWaitUntil(() -> {
            network(helper, entity);
            helper.assertValueEqual(TeamBudget.team(helper.getLevel(), entity), team, "the network owner's team");
        }).thenExecute(() -> entity.worker().setGraph(0, "Full", crossesNotify(COBBLE, 10, "full")))
                .thenWaitUntil(() -> ran(helper, entity)).thenExecute(() -> {
                    before[0] = TeamBudget.total(team);
                    NetworkGameTests.insert(network(helper, entity), COBBLE, 20);
                }).thenWaitUntil(() -> helper.assertValueEqual(entity.worker().actions().size(), 1, "the notice"))
                .thenExecute(() -> helper.assertTrue(TeamBudget.total(team) > before[0],
                        "the run was not charged to the network owner's team"))
                .thenSucceed();
    }

    /**
     * The team of {@code entity} has its budget for this second used up elsewhere: a crossing makes the graph wait, and
     * it runs in the next second.
     */
    private static void waitsAtLimit(GameTestHelper helper, LogicControllerBlockEntity entity) {
        long[] spentIn = new long[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            entity.worker().setGraph(0, "Full", crossesNotify(COBBLE, 10, "full"));
        }).thenWaitUntil(() -> ran(helper, entity)).thenWaitUntil(() -> {
            // Start early in a second, so the checks below run before it ends.
            long time = helper.getLevel().getGameTime();
            helper.assertTrue(time % TeamBudget.SECOND <= 2, "not early in a second");
        }).thenExecute(() -> {
            long time = helper.getLevel().getGameTime();
            UUID team = TeamBudget.team(helper.getLevel(), entity);
            spentIn[0] = time / TeamBudget.SECOND;
            TeamBudget.spend(team, TeamBudget.limit(), time);
            NetworkGameTests.insert(network(helper, entity), COBBLE, 20);
        }).thenIdle(4).thenExecute(() -> {
            helper.assertValueEqual(entity.worker().actions().size(), 0, "actions over budget");
            helper.assertValueEqual(entity.worker().status(0), LogicWorker.Status.WAITING, "status");
            helper.assertValueEqual(entity.worker().waiting(), 1, "waiting graphs");
        }).thenWaitUntil(() -> helper.assertValueEqual(entity.worker().actions().size(), 1, "the notice"))
                .thenExecute(() -> {
                    helper.assertTrue(helper.getLevel().getGameTime() / TeamBudget.SECOND > spentIn[0],
                            "ran in a later second");
                    helper.assertValueEqual(entity.worker().status(0), LogicWorker.Status.RUNNING, "status after");
                }).thenSucceed();
    }

    /** Three changes that each cross within one tick, then back above: one run of the graph, one notice. */
    private static void coalesces(GameTestHelper helper) {
        LogicControllerBlockEntity entity = setUp(helper, "lc_coalescer");
        long[] runs = new long[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            entity.worker().setGraph(0, "Full", crossesNotify(COBBLE, 10, "full"));
        }).thenWaitUntil(() -> ran(helper, entity)).thenExecute(() -> {
            runs[0] = entity.worker().runs();
            NetworkState network = network(helper, entity);
            NetworkGameTests.insert(network, COBBLE, 20);
            extract(network, COBBLE, 15);
            NetworkGameTests.insert(network, COBBLE, 15);
        }).thenIdle(5).thenExecute(() -> {
            helper.assertValueEqual(entity.worker().runs(), runs[0] + 1, "runs for three crossings in a tick");
            helper.assertValueEqual(entity.worker().actions().size(), 1, "notices");
        }).thenSucceed();
    }

    /**
     * Quarantine stands in for a network that is not online (unloaded parts in pause mode take the same status path):
     * redstone into the controller while it is down runs nothing; when it is back, both graphs run exactly once, and
     * the redstone change is seen then.
     */
    private static void offline(GameTestHelper helper) {
        LogicControllerBlockEntity entity = setUp(helper, "lc_offline");
        LogicGraph<LogicKey> redstone = new Graph().node(1, NodeTypes.REDSTONE_CHANGES, s -> s)
                .node(2, NodeTypes.NOTIFY, s -> s.withText("text", "lever")).wire(1, "changed", 2, "do").build();
        long[] runs = new long[2];
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            entity.worker().setGraph(0, "Lever", redstone);
            entity.worker().setGraph(1, "Full", crossesNotify(COBBLE, 10, "full"));
        }).thenWaitUntil(() -> {
            ran(helper, entity);
            helper.assertTrue(evaluator(helper, entity, 1).runs() > 0, "graph 1 ran");
        }).thenExecute(() -> {
            runs[0] = evaluator(helper, entity, 0).runs();
            runs[1] = evaluator(helper, entity, 1).runs();
            network(helper, entity).setQuarantined(true);
        }).thenIdle(3).thenExecute(() -> helper.setBlock(NetworkGameTests.at(3).above(), Blocks.REDSTONE_BLOCK))
                .thenIdle(10).thenExecute(() -> {
                    helper.assertValueEqual(evaluator(helper, entity, 0).runs(), runs[0], "runs while down");
                    helper.assertValueEqual(entity.worker().actions().size(), 0, "actions while down");
                    network(helper, entity).setQuarantined(false);
                }).thenIdle(10).thenExecute(() -> {
                    helper.assertValueEqual(evaluator(helper, entity, 0).runs(), runs[0] + 1, "graph 0 rechecks");
                    helper.assertValueEqual(evaluator(helper, entity, 1).runs(), runs[1] + 1, "graph 1 rechecks");
                    helper.assertValueEqual(described(entity.worker().actions()), List.of("notify \"lever\" 3"),
                            "the change seen once back");
                }).thenSucceed();
    }

    /**
     * With a security module, a player with craft access may not open the controller or switch its graphs; a player
     * with logic access may.
     */
    private static void access(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "lc_owner");
        ServerPlayer crafter = NetworkGameTests.player(helper.getLevel(), "lc_crafter");
        ServerPlayer logician = NetworkGameTests.player(helper.getLevel(), "lc_logician");
        LogicControllerBlockEntity entity = setUp(helper, "lc_owner");
        entity.setOwner(OwnerRecord.of(owner));
        BlockPos core = NetworkGameTests.at(1);
        NetworkGameTests.controllerEntity(helper, core).setOwner(OwnerRecord.of(owner));
        ControllerClusterGameTests.expansion(helper, core.above(), ExpansionContent.SECURITY_MODULE);
        NetworkGameTests.controllerEntity(helper, core)
                .setSecurity(SecuritySettings.DEFAULT
                        .withPlayer(crafter.getUUID(), crafter.getGameProfile().name(), AccessLevel.CRAFT)
                        .withPlayer(logician.getUUID(), logician.getGameProfile().name(), AccessLevel.LOGIC));
        helper.startSequence().thenWaitUntil(() -> {
            network(helper, entity);
            helper.assertTrue(NetworkGameTests.controllerEntity(helper, core).secured(), "module active");
        }).thenExecute(() -> {
            entity.worker().setGraph(0, "Full", crossesNotify(COBBLE, 10, "full"));
            helper.assertTrue(!LogicControllerBlock.canEdit(crafter, entity), "craft access opens it");
            helper.assertTrue(LogicControllerBlock.canEdit(logician, entity), "logic access may not open it");
            helper.assertTrue(LogicControllerBlock.canEdit(owner, entity), "the owner may not open it");
            LogicControllerMenu menu = LogicControllerMenu.forController(1, entity);
            helper.assertTrue(!menu.clickMenuButton(crafter, 0), "the crafter's click");
            crafter.setPos(helper.absolutePos(NetworkGameTests.at(3)).getCenter());
            logician.setPos(helper.absolutePos(NetworkGameTests.at(3)).getCenter());
            helper.assertTrue(!menu.stillValid(crafter), "the crafter's menu stays open");
            helper.assertTrue(menu.stillValid(logician), "the logician's menu closes");
            helper.assertValueEqual(entity.worker().status(0), LogicWorker.Status.RUNNING, "still on");
            helper.assertTrue(menu.clickMenuButton(logician, 0), "the logician's click");
            helper.assertValueEqual(entity.worker().status(0), LogicWorker.Status.DISABLED, "switched off");
        }).thenSucceed();
    }

    /**
     * A sensor labelled "tank" reads a chest next to it; a graph watching its item total above 10 sleeps while the
     * chest holds 5, and the sensor's read after the chest fills to 20 wakes it: one run, one notice, no polling.
     */
    private static void sensor(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        SensorGameTests.ownNetwork(helper, core);
        helper.setBlock(NetworkGameTests.at(2),
                SensorContent.SENSOR.get().defaultBlockState().setValue(SensorBlock.FACING, Direction.EAST));
        SensorBlockEntity sensor = helper.getBlockEntity(NetworkGameTests.at(2), SensorBlockEntity.class);
        sensor.setLabel(DeviceLabel.of("tank", null));
        helper.setBlock(NetworkGameTests.at(3), Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(NetworkGameTests.at(3), ChestBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 5));
        chest.setChanged();
        helper.setBlock(NetworkGameTests.at(0), LogicContent.CONTROLLER.get());
        LogicControllerBlockEntity entity = entity(helper, NetworkGameTests.at(0));
        entity.setOwner(OwnerRecord.of(NetworkGameTests.player(helper.getLevel(), "lc_sensor")));
        LogicGraph<LogicKey> graph = new Graph()
                .node(1, NodeTypes.SENSOR_CROSSES,
                        s -> s.withText("label", "tank").withText("value", "items.total").withNumber("threshold", 10))
                .node(2, NodeTypes.NOTIFY, s -> s.withText("text", "chest full")).wire(1, "crossed", 2, "do").build();
        long[] runs = new long[1];
        helper.startSequence().thenWaitUntil(() -> {
            network(helper, entity);
            helper.assertValueEqual(sensor.sensor().value("items.total"), 5L, "first read");
        }).thenExecute(() -> entity.worker().setGraph(0, "Tank", graph)).thenWaitUntil(() -> ran(helper, entity))
                .thenIdle(5).thenExecute(() -> {
                    helper.assertValueEqual(entity.worker().actions().size(), 0, "actions at 5");
                    runs[0] = entity.worker().runs();
                    chest.setItem(0, new ItemStack(Items.COBBLESTONE, 20));
                    chest.setChanged();
                })
                .thenWaitUntil(() -> helper.assertValueEqual(described(entity.worker().actions()),
                        List.of("notify \"chest full\" 3"), "the notice"))
                .thenExecute(() -> helper.assertValueEqual(entity.worker().runs(), runs[0] + 1, "runs after the read"))
                .thenSucceed();
    }

    /**
     * The open screen's log (M7L2 chunk 10): after a run whose priority action found no device it carries the rule's
     * name, the action and the why line from the run's trace, on the node that said it; nothing goes out again while
     * nothing changed or within the interval; a broken save sends the rule's errors and forgets the old run.
     */
    private static void logWhyChain(GameTestHelper helper) {
        LogicControllerBlockEntity entity = setUp(helper, "lc_logger");
        LogicControllerMenu[] menu = new LogicControllerMenu[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            entity.worker().setGraph(0, "Priority", new Graph().node(1, NodeTypes.GRAPH_START, s -> s)
                    .node(2, NodeTypes.SET_PRIORITY, s -> s.withText("label", "nobody").withNumber("priority", 7))
                    .wire(1, "started", 2, "do").build());
            menu[0] = LogicControllerMenu.forController(20, entity);
        }).thenWaitUntil(() -> helper.assertTrue(entity.worker().lastRun(0) >= 0, "the graph ran")).thenExecute(() -> {
            long time = helper.getLevel().getGameTime();
            ControllerLog.Update log = menu[0].logUpdate(time);
            if (log == null) {
                throw helper.assertionException(Component.literal("expected the log once the screen opened"));
            }
            helper.assertValueEqual(log.containerId(), 20, "container");
            ControllerLog.Rule rule = log.rules().getFirst();
            helper.assertValueEqual(rule.name(), "Priority", "name");
            helper.assertValueEqual(rule.actions(), 1, "actions of the last run");
            helper.assertTrue(rule.runs() > 0 && rule.lastRun() >= 0, "ran: " + rule);
            List<String> keys = new ArrayList<>();
            for (ControllerLog.Line line : rule.lines()) {
                keys.add(line.key());
            }
            helper.assertValueEqual(keys, List.of(ControllerTexts.ACTION_PRIORITY, LogicLang.WHY_NO_TARGET),
                    "the action, then the why line");
            helper.assertValueEqual(rule.lines().getFirst().a(), "nobody", "the action's label");
            helper.assertValueEqual(rule.lines().getFirst().b(), "7", "the action's priority");
            helper.assertValueEqual(rule.lines().get(1).node(), NodeTypes.SET_PRIORITY.nameKey(),
                    "the why line names its node");
            helper.assertTrue(rule.errors().isEmpty(), "no errors");
            helper.assertTrue(menu[0].logUpdate(time + 1) == null, "not within the interval");
            helper.assertTrue(menu[0].logUpdate(time + ControllerLog.INTERVAL) == null, "nothing changed");
            entity.worker().setGraph(0, "Priority",
                    new Graph().node(1, NodeTypes.NOTIFY, s -> s.withText("text", "lonely")).build());
            ControllerLog.Update broken = menu[0].logUpdate(time + 2L * ControllerLog.INTERVAL);
            if (broken == null) {
                throw helper.assertionException(Component.literal("expected the log after a save"));
            }
            ControllerLog.Rule paused = broken.rules().getFirst();
            helper.assertTrue(!paused.errors().isEmpty(), "the errors: " + paused);
            helper.assertTrue(paused.lines().isEmpty() && paused.lastRun() < 0, "the old run is gone: " + paused);
        }).thenSucceed();
    }

    /**
     * Selecting a rule for the card view sends its graph to the open screen once, with its live values; asking for a
     * row the controller lacks sends nothing, and a save of the shown rule sends its card again.
     */
    private static void cardSyncsOnce(GameTestHelper helper) {
        LogicControllerBlockEntity entity = setUp(helper, "lc_card");
        LogicGraph<LogicKey> second = new Graph().node(1, NodeTypes.GRAPH_START, s -> s)
                .node(2, NodeTypes.SET_PRIORITY, s -> s.withText("label", "nobody"))
                .node(3, NodeTypes.CONSTANT, s -> s.withNumber("value", 5)).wire(1, "started", 2, "do")
                .wire(3, "value", 2, "priority").build();
        LogicControllerMenu[] menu = new LogicControllerMenu[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            entity.worker().setGraph(0, "First", crossesNotify(COBBLE, 10, "first"));
            entity.worker().setGraph(1, "Second", second);
            menu[0] = LogicControllerMenu.forController(22, entity);
        }).thenWaitUntil(() -> helper.assertTrue(entity.worker().lastRun(1) >= 0, "the second graph ran"))
                .thenExecute(() -> {
                    long time = helper.getLevel().getGameTime();
                    LogicControllerMenu screen = menu[0];
                    helper.assertTrue(screen.cardUpdate(time) == null, "no card before a rule is picked");
                    screen.selectCard(LogicControllerMenu.ROWS + 1);
                    helper.assertTrue(screen.cardUpdate(time) == null, "no card for a row the controller lacks");
                    screen.selectCard(1);
                    CardPayloads.Card card = screen.cardUpdate(time);
                    if (card == null) {
                        throw helper.assertionException(Component.literal("expected the card once a rule is picked"));
                    }
                    helper.assertValueEqual(card.containerId(), 22, "container");
                    helper.assertValueEqual(card.index(), 1, "the picked rule");
                    helper.assertValueEqual(LogicEditing.decode(helper.getLevel().registryAccess(), card.graph()),
                            Optional.of(second), "the rule's graph");
                    helper.assertTrue(card.values().contains(new EditorPayloads.LiveValue(3, "value", 5)),
                            "live values come along: " + card.values());
                    for (int later = 1; later <= 2 * CardPayloads.INTERVAL; later++) {
                        helper.assertTrue(screen.cardUpdate(time + later) == null, "sent once, not every tick");
                    }
                    // A save of the shown rule sends its card again with the next log update.
                    entity.worker().setGraph(1, "Second", new Graph().node(1, NodeTypes.GRAPH_START, s -> s).build());
                    long next = time + 4L * ControllerLog.INTERVAL;
                    helper.assertTrue(screen.logUpdate(next) != null, "the save rebuilds the log");
                    CardPayloads.Card again = screen.cardUpdate(next);
                    if (again == null) {
                        throw helper.assertionException(Component.literal("expected the card again after a save"));
                    }
                    helper.assertValueEqual(LogicEditing.decode(helper.getLevel().registryAccess(), again.graph())
                            .map(graph -> graph.nodes().size()).orElse(-1), 1, "the saved graph");
                }).thenSucceed();
    }
}
