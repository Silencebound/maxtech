package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.logic.LogicEvaluator;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.controller.LogicAction;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.logic.controller.LogicWorker;
import dev.silencebound.maxtech.storage.logic.controller.TeamBudget;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The team's two logic budgets (M7L2 chunk 5): with its action points used up, a rule that moves items waits (status
 * waiting, for action points) while a rule without actions still runs on the checks left; with its checks used up, both
 * wait (for checks) though action points are left. Either way the move happens in a later second.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LogicBudgetGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("logic_budget_moves_wait_for_action_points", helper -> waits(helper, "lb_points", true));
        register("logic_budget_moves_wait_for_checks", helper -> waits(helper, "lb_checks", false));
    }

    private LogicBudgetGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("logic_budget"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 300);
        }
    }

    /** "Cobblestone above 10": move one to the port "box". */
    private static LogicGraph<LogicKey> move() {
        return new LogicControllerGameTests.Graph()
                .node(1, NodeTypes.COUNT_CROSSES,
                        s -> s.withItem("item", LogicKey.of(COBBLE)).withNumber("threshold", 10))
                .node(2, NodeTypes.MOVE_ITEMS,
                        s -> s.withItem("item", LogicKey.of(COBBLE)).withText("to", "box").withNumber("amount", 1))
                .wire(1, "crossed", 2, "do").build();
    }

    /** "Cobblestone above 10": count up. No action, so it needs checks only. */
    private static LogicGraph<LogicKey> count() {
        return new LogicControllerGameTests.Graph()
                .node(1, NodeTypes.COUNT_CROSSES,
                        s -> s.withItem("item", LogicKey.of(COBBLE)).withNumber("threshold", 10))
                .node(2, NodeTypes.COUNTER, s -> s).wire(1, "crossed", 2, "up").build();
    }

    private static LogicEvaluator<LogicKey> evaluator(GameTestHelper helper, LogicControllerBlockEntity entity,
            int index) {
        return entity.worker().evaluator(index)
                .orElseThrow(() -> helper.assertionException(Component.literal("graph " + index + " is not running")));
    }

    private static int cobble(ChestBlockEntity chest) {
        int total = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            if (chest.getItem(i).is(Items.COBBLESTONE)) {
                total += chest.getItem(i).getCount();
            }
        }
        return total;
    }

    private static long moves(LogicControllerBlockEntity entity) {
        return entity.worker().actions().stream().filter(action -> action.kind() == LogicAction.Kind.MOVE_ITEMS)
                .count();
    }

    /**
     * Uses up the team's action points ({@code points}) or its checks for this second, then makes cobblestone cross:
     * the move rule waits for the budget that is used up, and the counting rule runs only when checks are left. In a
     * later second the move goes through.
     */
    private static void waits(GameTestHelper helper, String owner, boolean points) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, owner);
        helper.setBlock(NetworkGameTests.at(4), PortContent.PORT.get().defaultBlockState()
                .setValue(PortBlock.FACING, Direction.EAST).setValue(PortBlock.MODE, PortMode.EXPORT));
        helper.getBlockEntity(NetworkGameTests.at(4), PortBlockEntity.class).setLabel(DeviceLabel.of("box", null));
        helper.setBlock(NetworkGameTests.at(5), Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(NetworkGameTests.at(5), ChestBlockEntity.class);
        long[] spentIn = new long[1];
        long[] countRuns = new long[1];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            entity.worker().setGraph(0, "Move", move());
            entity.worker().setGraph(1, "Count", count());
        }).thenWaitUntil(() -> {
            helper.assertTrue(evaluator(helper, entity, 0).runs() > 0 && evaluator(helper, entity, 1).runs() > 0,
                    "the rules have not run yet");
            // Start early in a second, so the checks below run before it ends.
            helper.assertTrue(helper.getLevel().getGameTime() % TeamBudget.SECOND <= 2, "not early in a second");
        }).thenExecute(() -> {
            long time = helper.getLevel().getGameTime();
            UUID team = TeamBudget.team(helper.getLevel(), entity);
            spentIn[0] = time / TeamBudget.SECOND;
            countRuns[0] = evaluator(helper, entity, 1).runs();
            TeamBudget.setUsed(team, points ? 0 : TeamBudget.limit(), points ? TeamBudget.actionLimit() : 0, time);
            NetworkGameTests.insert(LogicControllerGameTests.network(helper, entity), COBBLE, 20);
        }).thenIdle(4).thenExecute(() -> {
            LogicWorker worker = entity.worker();
            helper.assertValueEqual(moves(entity), 0L, "moves over budget");
            helper.assertValueEqual(cobble(chest), 0, "in the chest over budget");
            helper.assertValueEqual(worker.status(0), LogicWorker.Status.WAITING, "the move rule's status");
            helper.assertValueEqual(worker.waitingFor(0),
                    points ? LogicWorker.Budget.ACTIONS : LogicWorker.Budget.CHECKS, "the budget it waits for");
            if (points) {
                helper.assertTrue(evaluator(helper, entity, 1).runs() > countRuns[0],
                        "the rule without actions should run on the checks left");
                helper.assertValueEqual(worker.status(1), LogicWorker.Status.RUNNING, "the counting rule's status");
            } else {
                helper.assertValueEqual(evaluator(helper, entity, 1).runs(), countRuns[0],
                        "no rule runs without checks");
                helper.assertValueEqual(worker.status(1), LogicWorker.Status.WAITING, "the counting rule's status");
            }
        }).thenWaitUntil(() -> helper.assertValueEqual(cobble(chest), 1, "the move in a later second"))
                .thenExecute(() -> {
                    helper.assertTrue(helper.getLevel().getGameTime() / TeamBudget.SECOND > spentIn[0],
                            "moved in a later second");
                    helper.assertValueEqual(entity.worker().status(0), LogicWorker.Status.RUNNING, "status after");
                    helper.assertValueEqual(entity.worker().lastActionPoints(0), NodeTypes.MOVE_ITEMS.actionPoints(),
                            "the move's points");
                }).thenSucceed();
    }
}
