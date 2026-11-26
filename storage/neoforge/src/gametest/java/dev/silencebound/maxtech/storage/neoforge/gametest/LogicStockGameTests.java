package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.stock.BackgroundCrafting;
import dev.silencebound.maxtech.storage.crafting.stock.BackgroundCrafts;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperBlockEntity;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperContent;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperMenu;
import dev.silencebound.maxtech.storage.crafting.stock.StockRule;
import dev.silencebound.maxtech.storage.logic.LogicEvaluator;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.RunTrace;
import dev.silencebound.maxtech.storage.logic.controller.ControllerLog;
import dev.silencebound.maxtech.storage.logic.controller.LogicAction;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerMenu;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Logic controls the Stock Keeper (M7L2 row 15) against the stand-in job queue: a stock status node reads what the
 * keeper holds and runs only when it changes; switching a rule off stops its requests and its own screen shows it; a
 * set amount is clamped as the keeper clamps it and saved; the short event fires once per change; a keeper of another
 * player is left alone; a simulating rule changes nothing.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LogicStockGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final String LABEL = "keeper";

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        // The stand-in job queue: loading the stock keeping tests installs it.
        BackgroundCrafts.install(StockKeepingGameTests.QUEUE);
        register("logic_stock_status_matches_keeper", LogicStockGameTests::statusMatches);
        register("logic_stock_switch_off_stops_crafting", LogicStockGameTests::switchOffStops);
        register("logic_stock_set_clamps_and_persists", LogicStockGameTests::setClampsAndPersists);
        register("logic_stock_short_fires_once", LogicStockGameTests::shortFiresOnce);
        register("logic_stock_access_denied", LogicStockGameTests::accessDenied);
        register("logic_stock_simulate_changes_nothing", LogicStockGameTests::simulate);
        register("logic_stock_keeper_button_switches_rule", LogicStockGameTests::keeperButton);
        register("logic_stock_switch_off_while_job_runs", LogicStockGameTests::switchOffWhileJobRuns);
    }

    private LogicStockGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("logic_stock"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 900);
        }
    }

    // ---- helpers

    /** The logic controller's set-up (powered controller, drive, logic controller at x 3) and a keeper at x 4. */
    private static StockKeeperBlockEntity keeper(GameTestHelper helper) {
        helper.setBlock(NetworkGameTests.at(4), StockKeeperContent.KEEPER.get());
        StockKeeperBlockEntity keeper = helper.getBlockEntity(NetworkGameTests.at(4), StockKeeperBlockEntity.class);
        keeper.setLabel(DeviceLabel.of(LABEL, null));
        return keeper;
    }

    private static StockRule rule(long keep, long restart, long batch) {
        return StockRule.EMPTY.withKey(Optional.of(COBBLE)).withKeep(keep).withRestart(restart).withBatch(batch);
    }

    private static NetworkState network(GameTestHelper helper, LogicControllerBlockEntity entity) {
        return LogicControllerGameTests.network(helper, entity);
    }

    private static LogicEvaluator<LogicKey> evaluator(GameTestHelper helper, LogicControllerBlockEntity entity) {
        return entity.worker().evaluator(0)
                .orElseThrow(() -> helper.assertionException(Component.literal("graph 0 is not running")));
    }

    private static long out(GameTestHelper helper, LogicControllerBlockEntity entity, int node, String output) {
        LogicEvaluator<LogicKey> evaluator = evaluator(helper, entity);
        return evaluator.value(evaluator.graph().slot(node, output));
    }

    private static String why(GameTestHelper helper, LogicControllerBlockEntity entity, int node) {
        RunTrace trace = evaluator(helper, entity).trace();
        int i = trace.find(node);
        return i < 0 ? "" : trace.reason(i);
    }

    private static long extract(NetworkState network, StackKey key, long amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            long moved = network.extract(key, amount, transaction);
            transaction.commit();
            return moved;
        }
    }

    private static long notices(LogicControllerBlockEntity entity, String text) {
        return entity.worker().actions().stream()
                .filter(action -> action.kind() == LogicAction.Kind.NOTIFY && action.text().equals(text)).count();
    }

    private static LogicGraph<LogicKey> status() {
        return new LogicControllerGameTests.Graph()
                .node(1, NodeTypes.STOCK_STATUS, s -> s.withItem("item", LogicKey.of(COBBLE)).withText("label", LABEL))
                .build();
    }

    /** "Switch the stock rule for cobblestone on while the controller gets redstone". */
    private static LogicGraph<LogicKey> switchOnRedstone() {
        return new LogicControllerGameTests.Graph().node(1, NodeTypes.REDSTONE_STRENGTH, s -> s)
                .node(2, NodeTypes.STOCK_RULE_SWITCH,
                        s -> s.withText("label", LABEL).withItem("item", LogicKey.of(COBBLE)))
                .wire(1, "powered", 2, "on").build();
    }

    /** "When the graph starts, set {@code field} of the stock rule for cobblestone to {@code amount}". */
    private static LogicGraph<LogicKey> setOnStart(String field, long amount) {
        return new LogicControllerGameTests.Graph()
                .node(1, NodeTypes.GRAPH_START,
                        s -> s)
                .node(2, NodeTypes.STOCK_RULE_SET, s -> s.withText("label", LABEL).withItem("item", LogicKey.of(COBBLE))
                        .withText("field", field).withNumber("amount", amount))
                .wire(1, "started", 2, "do").build();
    }

    // ---- tests

    /**
     * The status node shows the keeper's rule, the count and its state: stocked at 50, then below restart with a job
     * out after taking 20, and a keep amount changed at the keeper shows at once. Idle, the graph does not run.
     */
    private static void statusMatches(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lst_status");
        StockKeeperBlockEntity keeper = keeper(helper);
        long[] runs = new long[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, entity), COBBLE, 50);
            keeper.worker().setRule(0, rule(100, 40, 32));
            entity.worker().setGraph(0, "Status", status());
        }).thenWaitUntil(() -> {
            helper.assertValueEqual(out(helper, entity, 1, "keep"), 100L, "keep");
            helper.assertValueEqual(out(helper, entity, 1, "restart"), 40L, "restart");
            helper.assertValueEqual(out(helper, entity, 1, "batch"), 32L, "batch");
            helper.assertValueEqual(out(helper, entity, 1, "count"), 50L, "count");
            helper.assertValueEqual(out(helper, entity, 1, "below"), 0L, "50 is not below 40");
            helper.assertValueEqual(out(helper, entity, 1, "crafting"), 0L, "no job out");
            helper.assertValueEqual(out(helper, entity, 1, "on"), 1L, "switched on");
        }).thenExecute(() -> extract(network(helper, entity), COBBLE, 20)).thenWaitUntil(() -> {
            helper.assertValueEqual(out(helper, entity, 1, "count"), 30L, "count after taking 20");
            helper.assertValueEqual(out(helper, entity, 1, "below"), 1L, "30 is below 40");
            helper.assertValueEqual(out(helper, entity, 1, "crafting"), 1L, "the keeper's job is out");
            helper.assertTrue(keeper.worker().outstanding(0), "the keeper agrees");
        }).thenExecute(() -> keeper.worker().setRule(0, keeper.worker().rule(0).withKeep(200)))
                .thenWaitUntil(() -> helper.assertValueEqual(out(helper, entity, 1, "keep"), 200L, "keep changed"))
                .thenIdle(5).thenExecute(() -> runs[0] = entity.worker().runs()).thenIdle(40)
                .thenExecute(() -> helper.assertValueEqual(entity.worker().runs(), runs[0], "runs while idle"))
                .thenSucceed();
    }

    /**
     * Switched off by logic, a rule asks for nothing even when its stock falls short, and the keeper's screen shows it
     * off; switched on again, it requests at once.
     */
    private static void switchOffStops(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lst_switch");
        StockKeeperBlockEntity keeper = keeper(helper);
        ServerPlayer viewer = NetworkGameTests.player(helper.getLevel(), "lst_switch_viewer");
        StockKeeperMenu menu = StockKeeperMenu.forKeeper(1, viewer.getInventory(), keeper);
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, entity), COBBLE, 20);
            keeper.worker().setRule(0, rule(10, 0, 0));
            entity.worker().setGraph(0, "Switch", switchOnRedstone());
        }).thenWaitUntil(() -> helper.assertTrue(keeper.worker().rule(0).switchedOff(), "switched off by logic"))
                .thenExecute(() -> {
                    helper.assertTrue(menu.switchedOff(), "the keeper's screen shows it off");
                    extract(network(helper, entity), COBBLE, 15);
                }).thenIdle(20).thenExecute(() -> {
                    helper.assertValueEqual(StockKeepingGameTests.QUEUE.calls(network(helper, entity)).size(), 0,
                            "requests while switched off");
                    helper.assertTrue(keeper.worker().filling(0), "it still knows it is short of stock");
                    helper.setBlock(NetworkGameTests.at(3).above(), Blocks.REDSTONE_BLOCK);
                }).thenWaitUntil(() -> {
                    helper.assertFalse(keeper.worker().rule(0).switchedOff(), "switched on by logic");
                    helper.assertValueEqual(StockKeepingGameTests.QUEUE.calls(network(helper, entity)).size(), 1,
                            "requests once switched on");
                    helper.assertFalse(menu.switchedOff(), "the keeper's screen shows it on");
                }).thenSucceed();
    }

    /**
     * A restart threshold set above keep is clamped to keep, as the keeper's screen clamps it; a new keep amount takes
     * the threshold along; both are saved with the keeper and kept in its item.
     */
    private static void setClampsAndPersists(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lst_set");
        StockKeeperBlockEntity keeper = keeper(helper);
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, entity), COBBLE, 500);
            keeper.worker().setRule(0, rule(100, 40, 32));
            entity.worker().setGraph(0, "Restart", setOnStart("restart_below", 500));
        }).thenWaitUntil(
                () -> helper.assertValueEqual(keeper.worker().rule(0).restart(), 100L, "restart clamped to keep"))
                .thenExecute(() -> {
                    helper.assertValueEqual(keeper.worker().rule(0).keep(), 100L, "keep untouched");
                    entity.worker().setGraph(0, "Keep", setOnStart("keep_amount", 250));
                }).thenWaitUntil(() -> helper.assertValueEqual(keeper.worker().rule(0).keep(), 250L, "keep set"))
                .thenExecute(() -> {
                    helper.assertValueEqual(keeper.worker().rule(0).restart(), 250L, "restart at keep follows it");
                    helper.assertValueEqual(keeper.worker().rule(0).batch(), 32L, "batch untouched");
                    CompoundTag saved = keeper.saveWithoutMetadata(helper.getLevel().registryAccess());
                    helper.setBlock(NetworkGameTests.at(8), StockKeeperContent.KEEPER.get());
                    StockKeeperBlockEntity loaded = helper.getBlockEntity(NetworkGameTests.at(8),
                            StockKeeperBlockEntity.class);
                    loaded.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING,
                            helper.getLevel().registryAccess(), saved));
                    helper.assertValueEqual(loaded.worker().rule(0), keeper.worker().rule(0), "rule after a load");
                    ItemStack item = new ItemStack(StockKeeperContent.KEEPER.item().get());
                    item.applyComponents(keeper.collectComponents());
                    helper.assertValueEqual(item.getOrDefault(ModDataComponents.STOCK_RULES.get(), List.of()),
                            List.of(keeper.worker().rule(0)), "rule in the item");
                }).thenSucceed();
    }

    /**
     * A refused request makes the rule short: "went short" fires once, and not again when the retry is refused too;
     * once a job is taken and ends done, "recovered" fires once.
     */
    private static void shortFiresOnce(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lst_short");
        StockKeeperBlockEntity keeper = keeper(helper);
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph()
                .node(1, NodeTypes.STOCK_SHORT, s -> s.withText("label", LABEL))
                .node(2, NodeTypes.NOTIFY, s -> s.withText("text", "short")).wire(1, "went_short", 2, "do")
                .node(3, NodeTypes.NOTIFY, s -> s.withText("text", "back")).wire(1, "recovered", 3, "do").build();
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            entity.worker().setGraph(0, "Short", graph);
        }).thenWaitUntil(() -> helper.assertTrue(evaluator(helper, entity).runs() > 0, "ran")).thenExecute(() -> {
            StockKeepingGameTests.QUEUE.refuse(network(helper, entity), true);
            keeper.worker().setRule(0, rule(10, 0, 0));
        }).thenWaitUntil(() -> helper.assertValueEqual(notices(entity, "short"), 1L, "went short")).thenExecute(() -> {
            helper.assertTrue(keeper.worker().isShort(0), "the keeper says short");
            CompoundTag saved = keeper.saveWithoutMetadata(helper.getLevel().registryAccess());
            helper.setBlock(NetworkGameTests.at(8), StockKeeperContent.KEEPER.get());
            StockKeeperBlockEntity loaded = helper.getBlockEntity(NetworkGameTests.at(8), StockKeeperBlockEntity.class);
            loaded.loadCustomOnly(
                    TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
            helper.assertTrue(loaded.worker().isShort(0), "short after a load");
            helper.assertFalse(loaded.worker().isShort(1), "only the short rule");
        }).thenWaitUntil(() -> helper
                .assertValueEqual(StockKeepingGameTests.QUEUE.calls(network(helper, entity)).size(), 2, "retried"))
                .thenIdle(5).thenExecute(() -> {
                    helper.assertValueEqual(notices(entity, "short"), 1L, "still short: no second event");
                    helper.assertValueEqual(notices(entity, "back"), 0L, "not back yet");
                    StockKeepingGameTests.QUEUE.refuse(network(helper, entity), false);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(
                        StockKeepingGameTests.QUEUE.calls(network(helper, entity)).size(), 3, "taken"))
                .thenExecute(() -> {
                    List<StockKeepingGameTests.Call> calls = StockKeepingGameTests.QUEUE.calls(network(helper, entity));
                    StockKeepingGameTests.Call call = calls.get(calls.size() - 1);
                    helper.assertTrue(call.taken(), "the third try is taken");
                    NetworkGameTests.insert(network(helper, entity), call.request().key(), call.request().amount());
                    call.done().accept(BackgroundCrafting.Outcome.DONE);
                }).thenWaitUntil(() -> helper.assertValueEqual(notices(entity, "back"), 1L, "recovered")).thenIdle(5)
                .thenExecute(() -> {
                    helper.assertValueEqual(notices(entity, "short"), 1L, "short once in all");
                    helper.assertValueEqual(notices(entity, "back"), 1L, "recovered once");
                }).thenSucceed();
    }

    /** A keeper owned by another player keeps its rule: both actions are refused and say so. */
    private static void accessDenied(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lst_access");
        StockKeeperBlockEntity keeper = keeper(helper);
        keeper.setOwner(OwnerRecord.of(NetworkGameTests.player(helper.getLevel(), "lst_access_stranger")));
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph().node(1, NodeTypes.REDSTONE_STRENGTH, s -> s)
                .node(2, NodeTypes.STOCK_RULE_SWITCH,
                        s -> s.withText("label", LABEL).withItem("item", LogicKey.of(COBBLE)))
                .wire(1, "powered", 2, "on").node(3, NodeTypes.GRAPH_START, s -> s)
                .node(4, NodeTypes.STOCK_RULE_SET,
                        s -> s.withText("label", LABEL).withItem("item", LogicKey.of(COBBLE)).withNumber("amount", 7))
                .wire(3, "started", 4, "do").build();
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, entity), COBBLE, 500);
            keeper.worker().setRule(0, rule(100, 40, 32));
            entity.worker().setGraph(0, "Access", graph);
        }).thenWaitUntil(() -> helper.assertTrue(evaluator(helper, entity).runs() > 0, "ran")).thenIdle(3)
                .thenExecute(() -> {
                    helper.assertValueEqual(keeper.worker().rule(0), rule(100, 40, 32), "the stranger's rule");
                    helper.assertValueEqual(why(helper, entity, 2), LogicLang.WHY_DENIED, "why for the switch");
                    helper.assertValueEqual(why(helper, entity, 4), LogicLang.WHY_DENIED, "why for the amount");
                }).thenSucceed();
    }

    /** A simulating rule records "would" lines for both actions and leaves the keeper's rule alone. */
    private static void simulate(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lst_sim");
        StockKeeperBlockEntity keeper = keeper(helper);
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph().node(1, NodeTypes.REDSTONE_STRENGTH, s -> s)
                .node(2, NodeTypes.STOCK_RULE_SWITCH,
                        s -> s.withText("label", LABEL).withItem("item", LogicKey.of(COBBLE)))
                .wire(1, "powered", 2, "on").node(3, NodeTypes.GRAPH_START, s -> s)
                .node(4, NodeTypes.STOCK_RULE_SET,
                        s -> s.withText("label", LABEL).withItem("item", LogicKey.of(COBBLE)).withNumber("amount", 7))
                .wire(3, "started", 4, "do").build();
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, entity), COBBLE, 500);
            keeper.worker().setRule(0, rule(100, 40, 32));
            entity.worker().setGraph(0, "Sim", graph);
            entity.worker().setSimulate(0, true);
        }).thenWaitUntil(() -> {
            List<LogicAction> actions = entity.worker().actions();
            helper.assertTrue(
                    actions.stream().anyMatch(a -> a.kind() == LogicAction.Kind.STOCK_SWITCH && a.simulated()),
                    "a simulated switch: " + actions);
            helper.assertTrue(actions.stream().anyMatch(a -> a.kind() == LogicAction.Kind.STOCK_SET && a.simulated()),
                    "a simulated amount: " + actions);
        }).thenIdle(5).thenExecute(() -> {
            helper.assertTrue(entity.worker().actions().stream().noneMatch(
                    a -> (a.kind() == LogicAction.Kind.STOCK_SWITCH || a.kind() == LogicAction.Kind.STOCK_SET)
                            && !a.simulated()),
                    "a real stock action");
            helper.assertValueEqual(keeper.worker().rule(0), rule(100, 40, 32), "the rule is unchanged");
            helper.assertValueEqual(why(helper, entity, 4), LogicLang.WHY_SIMULATED, "why");
            ControllerLog.Update log = LogicControllerMenu.forController(1, entity)
                    .logUpdate(helper.getLevel().getGameTime());
            if (log == null) {
                throw helper.assertionException(Component.literal("no log"));
            }
            helper.assertTrue(
                    log.rules().getFirst().lines().stream().anyMatch(line -> line.key().endsWith("stock_keep.would")),
                    "no Would line in " + log.rules().getFirst().lines());
        }).thenSucceed();
    }

    /** The keeper's own on/off button switches the shown rule, and the switched-off rule travels in the item. */
    private static void keeperButton(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lst_button");
        StockKeeperBlockEntity keeper = keeper(helper);
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "lst_button_player");
        StockKeeperMenu menu = StockKeeperMenu.forKeeper(1, player.getInventory(), keeper);
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            keeper.worker().setRule(0, rule(100, 40, 32));
            helper.assertTrue(menu.clickMenuButton(player, StockKeeperMenu.TOGGLE_ON), "the button works");
            helper.assertTrue(keeper.worker().rule(0).switchedOff(), "switched off at the keeper");
            ItemStack item = new ItemStack(StockKeeperContent.KEEPER.item().get());
            item.applyComponents(keeper.collectComponents());
            helper.assertValueEqual(item.getOrDefault(ModDataComponents.STOCK_RULES.get(), List.of()),
                    List.of(rule(100, 40, 32).withSwitchedOff(true)), "switched off in the item");
            CompoundTag saved = keeper.saveWithoutMetadata(helper.getLevel().registryAccess());
            helper.setBlock(NetworkGameTests.at(8), StockKeeperContent.KEEPER.get());
            StockKeeperBlockEntity loaded = helper.getBlockEntity(NetworkGameTests.at(8), StockKeeperBlockEntity.class);
            loaded.loadCustomOnly(
                    TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
            helper.assertTrue(loaded.worker().rule(0).switchedOff(), "switched off after a load");
            helper.assertTrue(menu.clickMenuButton(player, StockKeeperMenu.TOGGLE_ON), "and back");
            helper.assertFalse(keeper.worker().rule(0).switchedOff(), "switched on again");
        }).thenSucceed();
    }

    /**
     * Two rules with a job out each are switched off: the jobs keep running and end (one done, one failed), neither
     * rule asks again nor turns short, and nothing is left out.
     */
    private static void switchOffWhileJobRuns(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lst_running");
        StockKeeperBlockEntity keeper = keeper(helper);
        StackKey dirt = ItemKey.of(Items.DIRT);
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            keeper.worker().setRule(0, rule(10, 0, 0));
            keeper.worker().setRule(1, rule(10, 0, 0).withKey(Optional.of(dirt)));
        }).thenWaitUntil(() -> {
            helper.assertValueEqual(StockKeepingGameTests.QUEUE.calls(network(helper, entity)).size(), 2, "requests");
            helper.assertTrue(keeper.worker().outstanding(0) && keeper.worker().outstanding(1), "both jobs out");
        }).thenExecute(() -> {
            keeper.worker().setRule(0, keeper.worker().rule(0).withSwitchedOff(true));
            keeper.worker().setRule(1, keeper.worker().rule(1).withSwitchedOff(true));
            helper.assertTrue(keeper.worker().outstanding(0), "the job still counts for the rule");
            for (StockKeepingGameTests.Call call : StockKeepingGameTests.QUEUE.calls(network(helper, entity))) {
                if (call.request().key().equals(COBBLE)) {
                    NetworkGameTests.insert(network(helper, entity), COBBLE, call.request().amount());
                    call.done().accept(BackgroundCrafting.Outcome.DONE);
                } else {
                    call.done().accept(BackgroundCrafting.Outcome.FAILED);
                }
            }
        }).thenIdle(20).thenExecute(() -> {
            helper.assertValueEqual(StockKeepingGameTests.QUEUE.calls(network(helper, entity)).size(), 2,
                    "requests after the jobs ended");
            helper.assertValueEqual(network(helper, entity).contents().amount(COBBLE), 10L, "the done job delivered");
            helper.assertFalse(keeper.worker().outstanding(0) || keeper.worker().outstanding(1), "no job out");
            helper.assertFalse(keeper.worker().isShort(0), "done: not short");
            helper.assertFalse(keeper.worker().isShort(1), "failed after the switch-off: not short");
        }).thenSucceed();
    }
}
