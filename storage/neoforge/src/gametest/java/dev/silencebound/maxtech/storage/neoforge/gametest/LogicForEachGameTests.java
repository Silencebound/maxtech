package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.stock.BackgroundCrafts;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperBlockEntity;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperContent;
import dev.silencebound.maxtech.storage.crafting.stock.StockRule;
import dev.silencebound.maxtech.storage.logic.ForEach;
import dev.silencebound.maxtech.storage.logic.GraphText;
import dev.silencebound.maxtech.storage.logic.LogicEvaluator;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.NodeSettings;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.RunTrace;
import dev.silencebound.maxtech.storage.logic.controller.LogicAction;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.logic.controller.TeamBudget;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkState;
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
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * For each (M7L2 row 18) in a real controller against the stand-in job queue: over a tag it asks for crafts of only the
 * items under the limit; a list (with a tag in it) and a stock keeper's rules give their items; the run's item limit
 * stops it and the why line says so; simulating records a "would" line per item and asks for nothing; the graph, with
 * its item wires, survives the text round trip and a save and load of the controller.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LogicForEachGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    private static final StackKey ANVIL = ItemKey.of(Items.ANVIL);
    private static final StackKey CHIPPED = ItemKey.of(Items.CHIPPED_ANVIL);
    private static final StackKey DAMAGED = ItemKey.of(Items.DAMAGED_ANVIL);
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey STICK = ItemKey.of(Items.STICK);
    private static final String CAP = "logic_each_cap_stops_and_says_so";

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        // The stand-in job queue: loading the stock keeping tests installs it.
        BackgroundCrafts.install(StockKeepingGameTests.QUEUE);
        register("logic_each_tag_crafts_under_limit", LogicForEachGameTests::tag);
        register("logic_each_list", LogicForEachGameTests::list);
        register("logic_each_stock_keeper", LogicForEachGameTests::stockKeeper);
        register("logic_each_stock_status_per_item", LogicForEachGameTests::stockStatus);
        register("logic_each_budget_stops_cleanly", LogicForEachGameTests::budget);
        register("logic_each_simulate", LogicForEachGameTests::simulate);
        register("logic_each_save_load_and_text", LogicForEachGameTests::saveLoad);
        StorageTests.function(CAP, StorageTestEnvironment.clearing(LogicForEachGameTests::cap));
    }

    private LogicForEachGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("logic_each"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 300);
        }
        StorageTests.test(event, event.registerEnvironment(MaxtechStorage.id(CAP),
                new StorageTestEnvironment("logic.forEachMaxItems", "2", false)), CAP, STRUCTURE, 300);
    }

    // ---- helpers

    /** "When the graph starts, for each item of the source: if its count is below 1,000, craft 64 of it." */
    private static LogicGraph<LogicKey> keepStocked(UnaryOperator<NodeSettings<LogicKey>> source) {
        return new LogicControllerGameTests.Graph().node(1, NodeTypes.GRAPH_START, s -> s)
                .node(2, NodeTypes.FOR_EACH, source).node(3, NodeTypes.ITEM_COUNT, s -> s)
                .node(4, NodeTypes.COMPARE, s -> s.withText("mode", "below").withNumber("threshold", 1_000))
                .node(5, NodeTypes.IF, s -> s).node(6, NodeTypes.REQUEST_CRAFT, s -> s.withNumber("amount", 64))
                .wire(1, "started", 2, "do").wire(2, "item", 3, "item").wire(3, "count", 4, "value")
                .wire(2, "each", 5, "when").wire(4, "result", 5, "condition").wire(5, "then", 6, "do")
                .wire(2, "item", 6, "item").build();
    }

    private static LogicGraph<LogicKey> anvils() {
        return keepStocked(s -> s.withText("source", "tag").withItem("tag", LogicKey.tag(ItemTags.ANVIL)));
    }

    private static NetworkState network(GameTestHelper helper, LogicControllerBlockEntity entity) {
        return LogicControllerGameTests.network(helper, entity);
    }

    /** The items the stand-in queue was asked to craft for the controller's network, in order. */
    private static List<StackKey> requested(GameTestHelper helper, LogicControllerBlockEntity entity) {
        List<StackKey> keys = new ArrayList<>();
        for (StockKeepingGameTests.Call call : StockKeepingGameTests.QUEUE.calls(network(helper, entity))) {
            keys.add(call.request().key());
        }
        return keys;
    }

    private static LogicEvaluator<LogicKey> evaluator(GameTestHelper helper, LogicControllerBlockEntity entity) {
        return entity.worker().evaluator(0)
                .orElseThrow(() -> helper.assertionException(Component.literal("graph 0 is not running")));
    }

    private static long checked(GameTestHelper helper, LogicControllerBlockEntity entity) {
        LogicEvaluator<LogicKey> evaluator = evaluator(helper, entity);
        return evaluator.value(evaluator.graph().slot(2, ForEach.CHECKED));
    }

    // ---- tests

    /** Over the anvil tag, with 2,000 anvils stored, only the chipped and damaged anvils are asked for, 64 each. */
    private static void tag(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "le_tag");
        long[] runs = new long[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, entity), ANVIL, 2_000);
            entity.worker().setGraph(0, "Anvils", anvils());
        }).thenWaitUntil(
                () -> helper.assertValueEqual(requested(helper, entity), List.of(CHIPPED, DAMAGED), "crafts asked for"))
                .thenExecute(() -> {
                    helper.assertValueEqual(checked(helper, entity), 3L, "items checked");
                    for (StockKeepingGameTests.Call call : StockKeepingGameTests.QUEUE.calls(network(helper, entity))) {
                        helper.assertValueEqual(call.request().amount(), 64L, "amount");
                    }
                    helper.assertTrue(evaluator(helper, entity).graph().watches().isEmpty(),
                            "the branch watches nothing");
                    runs[0] = entity.worker().runs();
                }).thenIdle(40).thenExecute(() -> {
                    helper.assertValueEqual(entity.worker().runs(), runs[0], "no runs without the trigger");
                    helper.assertValueEqual(requested(helper, entity).size(), 2, "asked once");
                }).thenSucceed();
    }

    /** A list of cobblestone, sticks and the anvil tag: each item once, all but the cobblestone stored. */
    private static void list(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "le_list");
        LogicGraph<LogicKey> graph = keepStocked(s -> s.withText("source", "list").withNumber("items", 4)
                .withItem(ForEach.item(1), LogicKey.of(COBBLE)).withItem(ForEach.item(2), LogicKey.of(STICK))
                .withItem(ForEach.item(3), LogicKey.tag(ItemTags.ANVIL)).withItem(ForEach.item(4), LogicKey.of(STICK)));
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, entity), COBBLE, 2_000);
            entity.worker().setGraph(0, "List", graph);
        }).thenWaitUntil(() -> helper.assertValueEqual(requested(helper, entity),
                List.of(STICK, ANVIL, CHIPPED, DAMAGED), "crafts asked for"))
                .thenExecute(() -> helper.assertValueEqual(checked(helper, entity), 5L, "sticks once")).thenSucceed();
    }

    /** The items of a labelled stock keeper's rules: cobblestone is stored, sticks are not. */
    private static void stockKeeper(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "le_keeper");
        helper.setBlock(NetworkGameTests.at(4), StockKeeperContent.KEEPER.get());
        StockKeeperBlockEntity keeper = helper.getBlockEntity(NetworkGameTests.at(4), StockKeeperBlockEntity.class);
        keeper.setLabel(DeviceLabel.of("keeper", null));
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, entity), COBBLE, 2_000);
            // Keep 0: the keeper itself asks for nothing.
            keeper.worker().setRule(0, StockRule.EMPTY.withKey(Optional.of(COBBLE)));
            keeper.worker().setRule(1, StockRule.EMPTY.withKey(Optional.of(STICK)));
            // Switched off: the keeper does not run it, so the For each leaves it out.
            keeper.worker().setRule(2, StockRule.EMPTY.withKey(Optional.of(ANVIL)).withSwitchedOff(true));
            entity.worker().setGraph(0, "Keeper",
                    keepStocked(s -> s.withText("source", "stock_keeper").withText("label", "keeper")));
        }).thenWaitUntil(() -> helper.assertValueEqual(requested(helper, entity), List.of(STICK), "crafts asked for"))
                .thenExecute(() -> helper.assertValueEqual(checked(helper, entity), 2L, "items checked")).thenSucceed();
    }

    /**
     * Stock rule status per item through a real keeper: cobblestone (50 of a keep of 100) is below its rule, sticks (50
     * of 10) are not, so only cobblestone is asked for.
     */
    private static void stockStatus(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "le_status");
        helper.setBlock(NetworkGameTests.at(4), StockKeeperContent.KEEPER.get());
        StockKeeperBlockEntity keeper = helper.getBlockEntity(NetworkGameTests.at(4), StockKeeperBlockEntity.class);
        keeper.setLabel(DeviceLabel.of("keeper", null));
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph().node(1, NodeTypes.GRAPH_START, s -> s)
                .node(2, NodeTypes.FOR_EACH, s -> s.withText("source", "stock_keeper").withText("label", "keeper"))
                .node(3, NodeTypes.STOCK_STATUS, s -> s.withText("label", "keeper")).node(4, NodeTypes.IF, s -> s)
                .node(5, NodeTypes.REQUEST_CRAFT, s -> s.withNumber("amount", 7)).wire(1, "started", 2, "do")
                .wire(2, "item", 3, "item").wire(2, "each", 4, "when").wire(3, "below", 4, "condition")
                .wire(4, "then", 5, "do").wire(2, "item", 5, "item").build();
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, entity), COBBLE, 50);
            NetworkGameTests.insert(network(helper, entity), STICK, 50);
            keeper.worker().setRule(0, StockRule.EMPTY.withKey(Optional.of(COBBLE)).withKeep(100));
            keeper.worker().setRule(1, StockRule.EMPTY.withKey(Optional.of(STICK)).withKeep(10));
            entity.worker().setGraph(0, "Status", graph);
        }).thenWaitUntil(() -> helper.assertValueEqual(checked(helper, entity), 2L, "items checked"))
                .thenExecute(() -> {
                    // The keeper asks for its own crafts too; logic's are the ones of 7.
                    List<StackKey> logic = new ArrayList<>();
                    for (StockKeepingGameTests.Call call : StockKeepingGameTests.QUEUE.calls(network(helper, entity))) {
                        if (call.request().amount() == 7) {
                            logic.add(call.request().key());
                        }
                    }
                    helper.assertValueEqual(logic, List.of(COBBLE), "only cobblestone is below its rule");
                }).thenSucceed();
    }

    /**
     * The team's checks run out in the middle of a list of four: the run stops between items, each item it went through
     * got its craft (no half pass), the why line says the budget stopped it, and nothing runs on its own later.
     */
    private static void budget(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "le_budget");
        LogicGraph<LogicKey> graph = keepStocked(s -> s.withText("source", "list").withNumber("items", 4)
                .withItem(ForEach.item(1), LogicKey.of(STICK)).withItem(ForEach.item(2), LogicKey.of(ANVIL))
                .withItem(ForEach.item(3), LogicKey.of(CHIPPED)).withItem(ForEach.item(4), LogicKey.of(DAMAGED)));
        helper.startSequence().thenWaitUntil(() -> network(helper, entity))
                // Early in a budget second, so the run below falls in the same second as the spending.
                .thenWaitUntil(() -> helper.assertTrue(helper.getLevel().getGameTime() % TeamBudget.SECOND < 5,
                        "early in a budget second"))
                .thenExecute(() -> {
                    long now = helper.getLevel().getGameTime();
                    UUID team = TeamBudget.team(helper.getLevel(), entity);
                    long used = TeamBudget.used(team, now);
                    // Leave 12 checks: the run's start and one item's pass fit, a second pass starts, a third not.
                    TeamBudget.spend(team, (int) Math.max(0, TeamBudget.limit() - 12 - used), now);
                    entity.worker().setGraph(0, "Budget", graph);
                })
                .thenWaitUntil(() -> helper
                        .assertTrue(entity.worker().evaluator(0).map(LogicEvaluator::runs).orElse(0L) > 0, "ran"))
                .thenExecute(() -> {
                    long checked = checked(helper, entity);
                    helper.assertTrue(checked >= 1 && checked < 4, "stopped between items, after " + checked);
                    helper.assertValueEqual((long) requested(helper, entity).size(), checked,
                            "a craft for every item gone through, none for the rest");
                    RunTrace trace = evaluator(helper, entity).trace();
                    boolean said = false;
                    for (int i = 0; i < trace.size(); i++) {
                        said |= trace.reason(i).equals(LogicLang.WHY_EACH_BUDGET) && trace.first(i) == checked;
                    }
                    helper.assertTrue(said, "the why line says the budget stopped it");
                }).thenIdle(40).thenExecute(() -> helper.assertValueEqual((long) requested(helper, entity).size(),
                        checked(helper, entity), "nothing more without a trigger"))
                .thenSucceed();
    }

    /** With {@code logic.forEachMaxItems} 2, a list of four stops after two, and the why line says so. */
    private static void cap(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "le_cap");
        LogicGraph<LogicKey> graph = keepStocked(s -> s.withText("source", "list").withNumber("items", 4)
                .withItem(ForEach.item(1), LogicKey.of(STICK)).withItem(ForEach.item(2), LogicKey.of(ANVIL))
                .withItem(ForEach.item(3), LogicKey.of(CHIPPED)).withItem(ForEach.item(4), LogicKey.of(DAMAGED)));
        helper.startSequence().thenWaitUntil(() -> network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Capped", graph))
                .thenWaitUntil(
                        () -> helper.assertValueEqual(requested(helper, entity), List.of(STICK, ANVIL), "first two"))
                .thenExecute(() -> {
                    helper.assertValueEqual(checked(helper, entity), 2L, "items checked");
                    RunTrace trace = evaluator(helper, entity).trace();
                    boolean said = false;
                    for (int i = 0; i < trace.size(); i++) {
                        said |= trace.reason(i).equals(LogicLang.WHY_EACH_CAP) && trace.first(i) == 2
                                && trace.second(i) == 2;
                    }
                    helper.assertTrue(said, "the why line names the limit");
                }).thenIdle(20)
                .thenExecute(() -> helper.assertValueEqual(requested(helper, entity).size(), 2, "no more later"))
                .thenSucceed();
    }

    /** Simulating, each item's craft is a "would" line and the queue is asked for nothing. */
    private static void simulate(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "le_sim");
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, entity), ANVIL, 2_000);
            entity.worker().setGraph(0, "Sim", anvils());
            entity.worker().setSimulate(0, true);
        }).thenWaitUntil(() -> {
            List<Optional<LogicKey>> would = new ArrayList<>();
            for (LogicAction action : entity.worker().actions()) {
                if (action.kind() == LogicAction.Kind.REQUEST_CRAFT && action.simulated()) {
                    would.add(action.item());
                }
            }
            helper.assertValueEqual(would,
                    List.of(Optional.of(LogicKey.of(CHIPPED)), Optional.of(LogicKey.of(DAMAGED))),
                    "a would line per item");
        }).thenExecute(() -> {
            helper.assertValueEqual(requested(helper, entity).size(), 0, "nothing asked for");
            helper.assertTrue(entity.worker().actions().stream().allMatch(LogicAction::simulated), "all simulated");
        }).thenSucceed();
    }

    /**
     * The graph goes through the text format and back unchanged; saved with the controller and loaded into a new one,
     * its item wires still carry each item, so the loaded graph (started by the load) asks for the same crafts.
     */
    private static void saveLoad(GameTestHelper helper) {
        LogicControllerBlockEntity first = LogicControllerGameTests.setUp(helper, "le_save");
        BlockPos pos = NetworkGameTests.at(3);
        LogicGraph<LogicKey> graph = anvils();
        CompoundTag[] saved = new CompoundTag[1];
        NetworkState[] before = new NetworkState[1];
        int[] askedBefore = new int[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, first)).thenExecute(() -> {
            NbtOps ops = NbtOps.INSTANCE;
            String text = GraphText.write(LogicKey.GRAPH_CODEC,
                    helper.getLevel().registryAccess().createSerializationContext(ops), "Anvils", graph).orElseThrow();
            GraphText.Read<LogicKey> read = GraphText.read(text, GraphText.HARD_MAX_LENGTH, 64, LogicKey.GRAPH_CODEC,
                    helper.getLevel().registryAccess().createSerializationContext(ops));
            helper.assertTrue(read.ok(), "the text reads");
            helper.assertValueEqual(read.graph().orElseThrow(), graph, "the graph after the text round trip");
            NetworkGameTests.insert(network(helper, first), ANVIL, 2_000);
            first.worker().setGraph(0, "Anvils", read.graph().orElseThrow());
        }).thenWaitUntil(() -> helper.assertValueEqual(requested(helper, first).size(), 2, "asked before the save"))
                .thenIdle(5).thenExecute(() -> {
                    before[0] = network(helper, first);
                    askedBefore[0] = requested(helper, first).size();
                    saved[0] = first.saveWithoutMetadata(helper.getLevel().registryAccess());
                    helper.setBlock(pos, Blocks.AIR);
                    helper.setBlock(pos, LogicContent.CONTROLLER.get());
                    LogicControllerGameTests.entity(helper, pos).loadCustomOnly(TagValueInput
                            .create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved[0]));
                }).thenWaitUntil(() -> {
                    LogicControllerBlockEntity loaded = LogicControllerGameTests.entity(helper, pos);
                    helper.assertValueEqual(loaded.worker().graph(0), graph, "the graph after a save and load");
                    helper.assertValueEqual(checked(helper, loaded), 3L, "the loaded graph went through the tag");
                    // The network may be a new one after the controller was replaced; then all its requests are new.
                    List<StackKey> asked = requested(helper, loaded);
                    int base = network(helper, loaded) == before[0] ? askedBefore[0] : 0;
                    helper.assertValueEqual(asked.size(), base + 2, "the loaded graph asked twice more");
                    helper.assertValueEqual(asked.subList(base, asked.size()), List.of(CHIPPED, DAMAGED),
                            "the same crafts");
                }).thenSucceed();
    }
}
