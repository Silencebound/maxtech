package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.stock.BackgroundCrafting;
import dev.silencebound.maxtech.storage.crafting.stock.BackgroundCrafts;
import dev.silencebound.maxtech.storage.logic.ForEach;
import dev.silencebound.maxtech.storage.logic.GraphError;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.Parameters;
import dev.silencebound.maxtech.storage.logic.SettingValue;
import dev.silencebound.maxtech.storage.logic.controller.CardPayloads;
import dev.silencebound.maxtech.storage.logic.controller.EditorPayloads;
import dev.silencebound.maxtech.storage.logic.controller.EditorTexts;
import dev.silencebound.maxtech.storage.logic.controller.GraphSlot;
import dev.silencebound.maxtech.storage.logic.controller.LogicAction;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerMenu;
import dev.silencebound.maxtech.storage.logic.controller.LogicEditing;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.logic.controller.LogicWorker;
import dev.silencebound.maxtech.storage.logic.template.BuiltInTemplates;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Rules with parameters (M7L2 row 19) in a real controller against the stand-in job queue: one "keep X above N"
 * template copied for two items with their own amounts asks for each; editing the template updates both copies (a value
 * a copy does not set follows the template); a For each runs a rule with parameters once per item with that item; a
 * copy survives a save and load storing only its link and values; a simulating copy only records; renaming a parameter
 * pauses the copies and callers naming it, and removing the template keeps its copies as rules of their own.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LogicParameterGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey ANVIL = ItemKey.of(Items.ANVIL);
    private static final StackKey STICK = ItemKey.of(Items.STICK);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        BackgroundCrafts.install(StockKeepingGameTests.QUEUE);
        register("logic_param_copies_keep_their_items", LogicParameterGameTests::copies);
        register("logic_param_template_edit_updates_copies", LogicParameterGameTests::templateEdit);
        register("logic_param_call_from_for_each", LogicParameterGameTests::callFromForEach);
        register("logic_param_save_load", LogicParameterGameTests::saveLoad);
        register("logic_param_simulate", LogicParameterGameTests::simulate);
        register("logic_param_renamed_and_removed", LogicParameterGameTests::renamedAndRemoved);
        register("logic_param_link_follows_the_rule", LogicParameterGameTests::linkFollowsTheRule);
        register("logic_param_copy_save_after_template_change", LogicParameterGameTests::copySaveAfterChange);
    }

    private LogicParameterGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("logic_param"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 300);
        }
    }

    // ---- helpers

    private static NetworkState network(GameTestHelper helper, LogicControllerBlockEntity entity) {
        return LogicControllerGameTests.network(helper, entity);
    }

    /** The crafts the stand-in queue was asked for on the controller's network, as "item amount". */
    private static List<String> requested(GameTestHelper helper, LogicControllerBlockEntity entity) {
        List<String> asked = new ArrayList<>();
        for (StockKeepingGameTests.Call call : StockKeepingGameTests.QUEUE.calls(network(helper, entity))) {
            asked.add(name(call.request().key()) + " " + call.request().amount());
        }
        return asked;
    }

    private static String name(StackKey key) {
        return key.equals(COBBLE) ? "cobble" : key.equals(ANVIL) ? "anvil" : key.equals(STICK) ? "stick" : "other";
    }

    private static long extract(NetworkState network, StackKey key, long amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            long moved = network.extract(key, amount, transaction);
            transaction.commit();
            return moved;
        }
    }

    private static SettingValue<LogicKey> item(StackKey key) {
        return new SettingValue.Item<>(LogicKey.of(key));
    }

    private static SettingValue<LogicKey> number(long value) {
        return new SettingValue.Num<>(value);
    }

    /** The value parameter {@code key} has in {@code graph}. */
    private static Optional<SettingValue<LogicKey>> value(LogicGraph<LogicKey> graph, String key) {
        return Parameters.find(graph, key).flatMap(Parameters.Param::value);
    }

    /**
     * Row 0: the "keep X above N" template (sticks above 64, batches of 32), switched off; row 1 a copy keeping
     * cobblestone above 100; row 2 a copy keeping anvils above 30 in batches of 7. Both copies are switched on.
     */
    private static void templateAndCopies(LogicWorker worker) {
        worker.setGraph(0, "Keep", BuiltInTemplates.keepAbove(LogicKey.of(STICK)));
        worker.setEnabled(0, false);
        int first = worker.addCopy(0);
        int second = worker.addCopy(0);
        worker.setCopyValue(first, "item", item(COBBLE));
        worker.setCopyValue(first, "amount", number(100));
        worker.setCopyValue(second, "item", item(ANVIL));
        worker.setCopyValue(second, "amount", number(30));
        worker.setCopyValue(second, "batch", number(7));
        worker.setEnabled(first, true);
        worker.setEnabled(second, true);
    }

    /** "When another rule runs this: if the item's count is below the amount, craft 5 of it." */
    private static LogicGraph<LogicKey> callable(String amountName) {
        return new LogicControllerGameTests.Graph().node(1, NodeTypes.RUN_BY_RULE, s -> s)
                .node(2, NodeTypes.PARAMETER,
                        s -> s.withText("name", "item").withText("kind", "item").withItem("item", LogicKey.of(STICK)))
                .node(3, NodeTypes.PARAMETER,
                        s -> s.withText("name", amountName).withText("kind", "number").withNumber("value", 100))
                .node(4, NodeTypes.ITEM_COUNT, s -> s).node(5, NodeTypes.COMPARE, s -> s.withText("mode", "below"))
                .node(6, NodeTypes.IF, s -> s).node(7, NodeTypes.REQUEST_CRAFT, s -> s.withNumber("amount", 5))
                .wire(2, "value", 4, "item").wire(4, "count", 5, "value").wire(3, "value", 5, "threshold")
                .wire(1, "ran", 6, "when").wire(5, "result", 6, "condition").wire(6, "then", 7, "do")
                .wire(2, "value", 7, "item").build();
    }

    /** "When the graph starts, for each of cobblestone and anvils: run rule "keep" with it and the amount 100." */
    private static LogicGraph<LogicKey> caller() {
        return new LogicControllerGameTests.Graph().node(1, NodeTypes.GRAPH_START, s -> s)
                .node(2, NodeTypes.FOR_EACH, s -> s.withText("source", "list").withNumber("items", 2)
                        .withItem(ForEach.item(1), LogicKey.of(COBBLE)).withItem(ForEach.item(2), LogicKey.of(ANVIL)))
                .node(3, NodeTypes.CONSTANT, s -> s.withNumber("value", 100))
                .node(4, NodeTypes.RUN_RULE,
                        s -> s.withText("rule", "keep").withText("item_param", "item").withText("amount_param",
                                "amount"))
                .wire(1, "started", 2, "do").wire(2, "each", 4, "do").wire(2, "item", 4, "item")
                .wire(3, "value", 4, "amount").build();
    }

    private static ServerPlayer near(GameTestHelper helper, String name) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), name);
        player.setPos(helper.absolutePos(NetworkGameTests.at(3)).getCenter());
        return player;
    }

    private static CardPayloads.Edit set(GameTestHelper helper, int id, int index, int node, String setting,
            SettingValue<LogicKey> value) {
        return new CardPayloads.Edit(id, index, CardPayloads.OP_SET, node, "", setting,
                LogicEditing.encodeValue(helper.getLevel().registryAccess(), value), "");
    }

    private static CardPayloads.Edited edit(GameTestHelper helper, ServerPlayer player, CardPayloads.Edit edit) {
        CardPayloads.Edited answer = LogicEditing.cardEdit(player, edit);
        if (answer == null) {
            throw helper.assertionException(Component.literal("expected an answer to " + edit));
        }
        return answer;
    }

    // ---- tests

    /**
     * One template, two copies with their own items and amounts, both made through the controller screen's button and
     * filled through the card: each asks for its own item in its own batch; the template (off) asks for nothing; a card
     * edit of anything but a value on a copy is refused, and a value edit changes only that copy.
     */
    private static void copies(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lp_copies");
        ServerPlayer owner = near(helper, "lp_copies");
        entity.setOwner(OwnerRecord.of(owner));
        LogicWorker worker = entity.worker();
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, entity), COBBLE, 50);
            NetworkGameTests.insert(network(helper, entity), ANVIL, 20);
            worker.setGraph(0, "Keep", BuiltInTemplates.keepAbove(LogicKey.of(STICK)));
            worker.setEnabled(0, false);
            LogicControllerMenu menu = LogicControllerMenu.forController(5, entity);
            owner.containerMenu = menu;
            helper.assertTrue(menu.clickMenuButton(owner, LogicControllerMenu.BUTTON_COPY), "copy with values");
            helper.assertTrue(menu.clickMenuButton(owner, LogicControllerMenu.BUTTON_COPY), "a second copy");
            helper.assertValueEqual(worker.size(), 3, "two copies added");
            helper.assertValueEqual(worker.instance(1).map(link -> link.template()), Optional.of(0), "linked");
            helper.assertValueEqual(worker.status(1), LogicWorker.Status.DISABLED, "added switched off");
            helper.assertValueEqual(worker.name(1), "Keep 2", "named after the template");
            helper.assertValueEqual(worker.history(1).size(), 1, "made as a copy, in the history");
            CardPayloads.Edited refused = edit(helper, owner,
                    set(helper, 5, 1, 5, "mode", new SettingValue.Text<>("above")));
            helper.assertTrue(!refused.done() && refused.refusal().equals(EditorTexts.REFUSED_COPY),
                    "only values change on a copy's card");
        }).thenIdle(LogicEditing.SAVE_INTERVAL + 1).thenExecute(() -> {
            CardPayloads.Edited byCard = edit(helper, owner, set(helper, 5, 1, 1, "item", item(COBBLE)));
            helper.assertTrue(byCard.done(), "the item set on the card: " + byCard.refusal());
        }).thenIdle(LogicEditing.SAVE_INTERVAL + 1).thenExecute(() -> {
            CardPayloads.Edited byCard = edit(helper, owner, set(helper, 5, 1, 2, "value", number(100)));
            helper.assertTrue(byCard.done(), "the amount set on the card: " + byCard.refusal());
            worker.setCopyValue(2, "item", item(ANVIL));
            worker.setCopyValue(2, "amount", number(30));
            worker.setCopyValue(2, "batch", number(7));
            helper.assertValueEqual(value(worker.graph(0), "item"), Optional.of(item(STICK)),
                    "the template keeps its own item");
            worker.setEnabled(1, true);
            worker.setEnabled(2, true);
        }).thenWaitUntil(() -> helper.assertValueEqual(new HashSet<>(requested(helper, entity)),
                Set.of("cobble 32", "anvil 7"), "each copy asks for its own item")).thenIdle(20)
                .thenExecute(() -> helper.assertValueEqual(requested(helper, entity).size(), 2,
                        "once each, and nothing for the template"))
                .thenSucceed();
    }

    /**
     * Editing the template reaches both copies: a new default batch of 5 replaces 32 in the copy that sets no batch,
     * the copy with its own batch keeps 7, and the cobblestone copy (its check's memory kept) asks again with the new
     * batch once its count drops below its amount again.
     */
    private static void templateEdit(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lp_edit");
        LogicWorker worker = entity.worker();
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, entity), COBBLE, 50);
            NetworkGameTests.insert(network(helper, entity), ANVIL, 20);
            templateAndCopies(worker);
        }).thenWaitUntil(() -> helper.assertValueEqual(requested(helper, entity).size(), 2, "both asked"))
                .thenExecute(() -> {
                    // The first jobs finish, so a new request is not skipped as already running.
                    for (StockKeepingGameTests.Call call : StockKeepingGameTests.QUEUE.calls(network(helper, entity))) {
                        call.done().accept(BackgroundCrafting.Outcome.DONE);
                    }
                    LogicGraph<LogicKey> edited = Parameters
                            .apply(BuiltInTemplates.keepAbove(LogicKey.of(STICK)), java.util.Map.of("batch", number(5)))
                            .graph();
                    worker.setGraph(0, "Keep", edited);
                    helper.assertValueEqual(value(worker.graph(1), "batch"), Optional.of(number(5)),
                            "the copy without a batch follows the template");
                    helper.assertValueEqual(value(worker.graph(2), "batch"), Optional.of(number(7)),
                            "the copy with its own batch keeps it");
                    helper.assertValueEqual(value(worker.graph(1), "item"), Optional.of(item(COBBLE)),
                            "and its own item");
                    NetworkGameTests.insert(network(helper, entity), COBBLE, 100);
                }).thenIdle(5).thenExecute(() -> extract(network(helper, entity), COBBLE, 120))
                .thenWaitUntil(() -> helper.assertValueEqual(requested(helper, entity).getLast(), "cobble 5",
                        "asked again with the template's new batch"))
                .thenSucceed();
    }

    /**
     * A For each over cobblestone and anvils runs rule "keep" with each item and the amount 100: one pass per call, so
     * only cobblestone (50 stored) is asked for, not anvils (200) and not the parameter's own sticks.
     */
    private static void callFromForEach(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lp_call");
        LogicWorker worker = entity.worker();
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, entity), COBBLE, 50);
            NetworkGameTests.insert(network(helper, entity), ANVIL, 200);
            worker.setGraph(0, "Keep", callable("amount"));
            worker.setGraph(1, "Caller", caller());
            helper.assertTrue(worker.errors(1).isEmpty(), "the call names parameters the rule has");
        }).thenWaitUntil(() -> helper.assertValueEqual(requested(helper, entity), List.of("cobble 5"),
                "the rule ran per item with its amount")).thenIdle(20).thenExecute(() -> {
                    helper.assertValueEqual(requested(helper, entity).size(), 1, "each call ran once");
                    helper.assertValueEqual(worker.evaluator(0).orElseThrow().callsWaiting(), 0, "no call left");
                }).thenSucceed();
    }

    /**
     * Saved with the controller, a copy stores its link and values and an empty graph; loaded into a new controller it
     * is a copy again with the same graph, and acts when its count drops below its own amount.
     */
    private static void saveLoad(GameTestHelper helper) {
        LogicControllerBlockEntity first = LogicControllerGameTests.setUp(helper, "lp_save");
        BlockPos pos = NetworkGameTests.at(3);
        CompoundTag[] saved = new CompoundTag[1];
        Object[] before = new Object[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, first)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, first), COBBLE, 500);
            NetworkGameTests.insert(network(helper, first), ANVIL, 500);
            templateAndCopies(first.worker());
        }).thenIdle(5).thenExecute(() -> {
            helper.assertValueEqual(requested(helper, first).size(), 0, "all above their amounts");
            before[0] = first.worker().graph(1);
            saved[0] = first.saveWithoutMetadata(helper.getLevel().registryAccess());
            ListTag graphs = saved[0].getListOrEmpty("graphs");
            CompoundTag copy = graphs.getCompoundOrEmpty(1);
            helper.assertTrue(copy.getCompoundOrEmpty("graph").getListOrEmpty("nodes").isEmpty(),
                    "a copy saves no graph of its own: " + copy);
            helper.assertTrue(copy.contains("instance"), "but its link and values");
            helper.setBlock(pos, Blocks.AIR);
            helper.setBlock(pos, LogicContent.CONTROLLER.get());
            LogicControllerGameTests.entity(helper, pos).loadCustomOnly(
                    TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved[0]));
        }).thenWaitUntil(() -> {
            network(helper, LogicControllerGameTests.entity(helper, pos));
            LogicWorker loaded = LogicControllerGameTests.entity(helper, pos).worker();
            helper.assertValueEqual(loaded.size(), 3, "three rules");
            helper.assertValueEqual(loaded.instance(1).map(link -> link.template()), Optional.of(0), "still a copy");
            helper.assertValueEqual(loaded.graph(1), before[0], "the same graph, made from the template");
            helper.assertValueEqual(loaded.status(1), LogicWorker.Status.RUNNING, "running");
        }).thenExecute(() -> extract(network(helper, LogicControllerGameTests.entity(helper, pos)), COBBLE, 450))
                .thenWaitUntil(
                        () -> helper.assertValueEqual(requested(helper, LogicControllerGameTests.entity(helper, pos)),
                                List.of("cobble 32"), "the loaded copy keeps cobblestone above its own amount"))
                .thenSucceed();
    }

    /** A copy in simulate mode records a "would" craft of its own item and asks for nothing. */
    private static void simulate(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lp_sim");
        LogicWorker worker = entity.worker();
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, entity), COBBLE, 50);
            NetworkGameTests.insert(network(helper, entity), ANVIL, 500);
            templateAndCopies(worker);
            worker.setSimulate(1, true);
        }).thenWaitUntil(() -> {
            boolean would = false;
            for (LogicAction action : worker.actions()) {
                would |= action.kind() == LogicAction.Kind.REQUEST_CRAFT && action.simulated()
                        && action.item().equals(Optional.of(LogicKey.of(COBBLE))) && action.value() == 32;
            }
            helper.assertTrue(would, "a would line for the copy's own item: " + worker.actions());
        }).thenExecute(() -> helper.assertValueEqual(requested(helper, entity).size(), 0, "nothing asked for"))
                .thenSucceed();
    }

    /**
     * A copy follows its template by the rule's id, not its row: loaded with another rule on the template's old row and
     * the template moved down, it still makes its graph from the template; loaded without the template, it is paused
     * with an error and keeps the graph it had rather than taking the rule now on that row.
     */
    private static void linkFollowsTheRule(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lp_link");
        LogicWorker worker = entity.worker();
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            templateAndCopies(worker);
            List<GraphSlot> slots = worker.slots();
            helper.assertTrue(slots.get(0).id() != 0 && slots.get(0).id() != slots.get(1).id(), "rules have ids");
            LogicGraph<LogicKey> made = worker.graph(1);
            GraphSlot other = new GraphSlot("Other", false, callable("amount"),
                    dev.silencebound.maxtech.storage.logic.LogicMemory.EMPTY);

            // Another rule on row 0, the template moved to row 3.
            worker.load(List.of(other, slots.get(1), slots.get(2), slots.get(0)));
            helper.assertTrue(worker.errors(1).isEmpty(), "the copy found its template: " + worker.errors(1));
            helper.assertValueEqual(worker.graph(1), made, "made from the template, not the rule on row 0");
            helper.assertValueEqual(worker.instance(1).map(link -> link.template()), Optional.of(3),
                    "the link moved with the template");

            // The template gone, another rule on its row.
            worker.load(List.of(other, slots.get(1)));
            helper.assertValueEqual(worker.errors(1),
                    List.of(new GraphError(GraphError.GRAPH, "", LogicLang.INSTANCE_NO_TEMPLATE, "")),
                    "the copy says its template is gone");
            helper.assertValueEqual(worker.status(1), LogicWorker.Status.PAUSED, "and does not run");
        }).thenSucceed();
    }

    /**
     * The editor open on a copy while its template changes: saving the old graph is refused with the message that the
     * template changed and to open the copy again; after reopening, a rename saves.
     */
    private static void copySaveAfterChange(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lp_editor");
        ServerPlayer owner = near(helper, "lp_editor");
        entity.setOwner(OwnerRecord.of(owner));
        LogicWorker worker = entity.worker();
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            templateAndCopies(worker);
            owner.containerMenu = LogicControllerMenu.forController(6, entity);
            EditorPayloads.Graph opened = LogicEditing.open(owner, 6, 1);
            helper.assertTrue(opened != null && !opened.readOnly(), "the copy opens in the editor");
            LogicGraph<LogicKey> shown = worker.graph(1);
            worker.setGraph(0, "Keep",
                    Parameters
                            .apply(BuiltInTemplates.keepAbove(LogicKey.of(STICK)), java.util.Map.of("batch", number(5)))
                            .graph());
            EditorPayloads.Saved refused = LogicEditing.save(owner, new EditorPayloads.Save(6, 1, "Renamed",
                    LogicEditing.encode(helper.getLevel().registryAccess(), shown)));
            helper.assertTrue(
                    refused != null && !refused.saved() && refused.refusal().equals(EditorTexts.REFUSED_COPY_CHANGED),
                    "refused, saying why");
            helper.assertValueEqual(worker.name(1), "Keep 2", "nothing saved");
        }).thenIdle(LogicEditing.SAVE_INTERVAL + 1).thenExecute(() -> {
            EditorPayloads.Graph reopened = LogicEditing.open(owner, 6, 1);
            helper.assertTrue(reopened != null && !reopened.readOnly(), "opened again");
            EditorPayloads.Saved saved = LogicEditing.save(owner, new EditorPayloads.Save(6, 1, "Renamed",
                    LogicEditing.encode(helper.getLevel().registryAccess(), worker.graph(1))));
            helper.assertTrue(saved != null && saved.saved(), "the rename saves after reopening");
            helper.assertValueEqual(worker.name(1), "Renamed", "renamed");
            helper.assertTrue(worker.instance(1).isPresent(), "still a copy");
        }).thenSucceed();
    }

    /**
     * Renaming the template's parameter "amount" to "limit" pauses the copy that sets "amount" and the rule calling
     * with it, each with an error naming "amount"; removing the template then detaches the copy, which keeps its graph
     * and runs again, with a line in its history.
     */
    private static void renamedAndRemoved(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lp_rename");
        LogicWorker worker = entity.worker();
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            worker.setGraph(0, "Keep", callable("amount"));
            int copy = worker.addCopy(0);
            worker.setCopyValue(copy, "amount", number(10));
            worker.setGraph(2, "Caller", caller());
            helper.assertValueEqual(worker.status(1), LogicWorker.Status.DISABLED, "the copy compiles");
            helper.assertTrue(worker.errors(2).isEmpty(), "the caller compiles");

            worker.setGraph(0, "Keep", callable("limit"));
            helper.assertValueEqual(worker.errors(1),
                    List.of(new GraphError(GraphError.GRAPH, "", LogicLang.PARAM_UNKNOWN, "amount")),
                    "the copy names the missing parameter");
            helper.assertValueEqual(worker.errors(2),
                    List.of(new GraphError(4, "amount_param", LogicLang.PARAM_UNKNOWN, "amount")),
                    "the caller names it too");
            helper.assertValueEqual(worker.status(2), LogicWorker.Status.PAUSED, "the caller is paused");

            LogicGraph<LogicKey> kept = worker.graph(1);
            worker.removeGraph(0);
            helper.assertTrue(worker.instance(0).isEmpty(), "the copy is its own rule now");
            helper.assertValueEqual(worker.graph(0), kept, "with the graph it had");
            helper.assertTrue(worker.errors(0).isEmpty(), "and runs: its old value is gone with the link");
            helper.assertValueEqual(worker.history(0).getLast().changes().getFirst().key(),
                    LogicLang.HISTORY_TEMPLATE_GONE, "the history says why");
        }).thenSucceed();
    }
}
