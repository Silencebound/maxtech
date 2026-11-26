package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.SecuritySettings;
import dev.silencebound.maxtech.storage.logic.CardEdits;
import dev.silencebound.maxtech.storage.logic.GraphChange;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.LogicNode;
import dev.silencebound.maxtech.storage.logic.NodeSettings;
import dev.silencebound.maxtech.storage.logic.NodeType;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.RuleCard;
import dev.silencebound.maxtech.storage.logic.SettingValue;
import dev.silencebound.maxtech.storage.logic.Wire;
import dev.silencebound.maxtech.storage.logic.controller.CardPayloads;
import dev.silencebound.maxtech.storage.logic.controller.EditorTexts;
import dev.silencebound.maxtech.storage.logic.controller.HistoryEntry;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerMenu;
import dev.silencebound.maxtech.storage.logic.controller.LogicEditing;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.logic.controller.LogicWorker;
import dev.silencebound.maxtech.storage.network.AccessLevel;
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
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.jspecify.annotations.Nullable;

/**
 * Editing on the controller screen's card view (M7L2 chunk 11): each card edit is saved like an editor save and goes
 * into the rule's history with a short summary, the card goes out again, a rule started as a card stays switched off
 * until it has a trigger and an action, an edit is refused while another player has the rule open in the editor (with
 * their name), and an edit that would pass {@code logic.maxNodes} is refused.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LogicCardViewGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("logic_card_edit_saves_with_history", LogicCardViewGameTests::savesWithHistory);
        register("logic_card_edit_refused_while_editing", LogicCardViewGameTests::refusedWhileEditing);
        register("logic_card_edit_node_limit", LogicCardViewGameTests::nodeLimit);
        register("logic_card_edit_switched_off_stays_off", LogicCardViewGameTests::switchedOffStaysOff);
        register("logic_card_edit_forged_refused", LogicCardViewGameTests::forgedEditsRefused);
    }

    private LogicCardViewGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("logic_card_view"), StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 300);
        }
    }

    // ---- set-up

    private static ServerPlayer near(GameTestHelper helper, String name) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), name);
        player.setPos(helper.absolutePos(NetworkGameTests.at(3)).getCenter());
        return player;
    }

    private static LogicControllerMenu menu(ServerPlayer player, int id, LogicControllerBlockEntity entity) {
        LogicControllerMenu menu = LogicControllerMenu.forController(id, entity);
        player.containerMenu = menu;
        return menu;
    }

    private static CardPayloads.Edited edit(GameTestHelper helper, ServerPlayer player, CardPayloads.Edit edit) {
        CardPayloads.Edited answer = LogicEditing.cardEdit(player, edit);
        if (answer == null) {
            throw helper.assertionException(Component.literal("expected an answer to " + edit));
        }
        return answer;
    }

    private static CardPayloads.Edit set(GameTestHelper helper, int id, int index, int node, String setting,
            SettingValue<LogicKey> value) {
        return new CardPayloads.Edit(id, index, CardPayloads.OP_SET, node, "", setting,
                LogicEditing.encodeValue(helper.getLevel().registryAccess(), value), "");
    }

    private static CardPayloads.Edit add(int id, int index, CardEdits.Add add, NodeType type) {
        return new CardPayloads.Edit(id, index, CardPayloads.OP_ADD, 0, add.name(), "", new CompoundTag(), type.id());
    }

    private static CardPayloads.Edit remove(int id, int index, RuleCard.Section section, int node) {
        return new CardPayloads.Edit(id, index, CardPayloads.OP_REMOVE, node, section.name(), "", new CompoundTag(),
                "");
    }

    private static void assertDone(GameTestHelper helper, CardPayloads.Edited answer, String what) {
        helper.assertTrue(answer.done(), what + ": " + answer.refusal() + " " + answer.args());
    }

    private static void assertNewest(GameTestHelper helper, LogicWorker worker, int index, ServerPlayer player,
            String key, String what) {
        HistoryEntry entry = worker.history(index).getLast();
        helper.assertValueEqual(entry.player(), Optional.of(player.getUUID()), what + ": the player");
        helper.assertValueEqual(entry.changes().getFirst().key(), key, what + ": " + entry.changes());
    }

    private static int id(LogicGraph<LogicKey> graph, NodeType type) {
        for (LogicNode<LogicKey> node : graph.nodes()) {
            if (node.type() == type) {
                return node.id();
            }
        }
        return -1;
    }

    private static long threshold(LogicWorker worker) {
        LogicNode<LogicKey> node = worker.graph(0).node(1);
        return node == null ? -1 : node.settings().number("threshold").orElse(-1);
    }

    // ---- tests

    /**
     * A changed setting, an added condition and a removed line are each saved, recorded in the history with what
     * changed and sent to the card again; a rule started as a card is off until it has a trigger and an action, and
     * then switched on.
     */
    private static void savesWithHistory(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lcv_owner");
        ServerPlayer owner = near(helper, "lcv_owner");
        entity.setOwner(OwnerRecord.of(owner));
        LogicControllerMenu[] screen = new LogicControllerMenu[1];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            entity.worker().setGraph(0, "Rule", LogicControllerGameTests.crossesNotify(COBBLE, 10, "x"));
            screen[0] = menu(owner, 60, entity);
            assertDone(helper, edit(helper, owner, set(helper, 60, 0, 1, "threshold", new SettingValue.Num<>(50))),
                    "set the limit");
            LogicWorker worker = entity.worker();
            helper.assertValueEqual(threshold(worker), 50L, "saved");
            assertNewest(helper, worker, 0, owner, LogicLang.HISTORY_SETTING, "the setting");
            helper.assertValueEqual(worker.history(0).getLast().changes().getFirst().args().subList(2, 4),
                    List.of(GraphChange.Arg.text("10"), GraphChange.Arg.text("50")), "10 -> 50");
            helper.assertTrue(screen[0].cardUpdate(helper.getLevel().getGameTime() + 100) != null,
                    "the card goes out again");
            // Too soon after the last: refused, nothing changed.
            CardPayloads.Edited fast = edit(helper, owner,
                    set(helper, 60, 0, 1, "threshold", new SettingValue.Num<>(7)));
            helper.assertTrue(!fast.done() && fast.refusal().equals(EditorTexts.REFUSED_TOO_FAST), "rate limited");
            helper.assertValueEqual(threshold(worker), 50L, "unchanged");
        }).thenIdle(LogicEditing.SAVE_INTERVAL).thenExecute(() -> {
            LogicWorker worker = entity.worker();
            assertDone(helper, edit(helper, owner, add(60, 0, CardEdits.Add.CONDITION, NodeTypes.DAYTIME)),
                    "add a condition");
            helper.assertTrue(id(worker.graph(0), NodeTypes.IF) > 0, "an If joins the chain");
            assertNewest(helper, worker, 0, owner, CardEdits.HISTORY_CONDITION, "the condition");
            helper.assertValueEqual(worker.history(0).getLast().changes().getFirst().args(),
                    List.of(GraphChange.Arg.key(NodeTypes.DAYTIME.nameKey())), "names the node");
            helper.assertTrue(worker.errors(0).isEmpty(), "it runs: " + worker.errors(0));
        }).thenIdle(LogicEditing.SAVE_INTERVAL).thenExecute(() -> {
            LogicWorker worker = entity.worker();
            int daytime = id(worker.graph(0), NodeTypes.DAYTIME);
            assertDone(helper, edit(helper, owner, remove(60, 0, RuleCard.Section.IF, daytime)), "remove the check");
            helper.assertValueEqual(worker.graph(0).nodes().size(), 2, "back to two nodes");
            assertNewest(helper, worker, 0, owner, CardEdits.HISTORY_REMOVED, "the removal");
        }).thenIdle(LogicEditing.SAVE_INTERVAL).thenExecute(() -> {
            LogicWorker worker = entity.worker();
            assertDone(helper,
                    edit(helper, owner,
                            new CardPayloads.Edit(60, 1, CardPayloads.OP_NEW, 0, "", "", new CompoundTag(), "")),
                    "new rule");
            helper.assertValueEqual(worker.size(), 2, "a second rule");
            helper.assertValueEqual(worker.status(1), LogicWorker.Status.DISABLED, "off while blank");
            assertNewest(helper, worker, 1, owner, CardEdits.HISTORY_NEW, "started");
        }).thenIdle(LogicEditing.SAVE_INTERVAL).thenExecute(() -> {
            assertDone(helper, edit(helper, owner, add(60, 1, CardEdits.Add.TRIGGER, NodeTypes.TIMER)), "a trigger");
            helper.assertValueEqual(entity.worker().status(1), LogicWorker.Status.DISABLED, "still off");
        }).thenIdle(LogicEditing.SAVE_INTERVAL).thenExecute(() -> {
            LogicWorker worker = entity.worker();
            assertDone(helper, edit(helper, owner, add(60, 1, CardEdits.Add.ACTION, NodeTypes.RUN_RULE)), "an action");
            helper.assertTrue(worker.enabled(1), "switched on with a trigger and an action");
            assertNewest(helper, worker, 1, owner, LogicLang.HISTORY_SWITCHED_ON, "switched on");
        }).thenSucceed();
    }

    /** While the owner has the rule open in the editor, the logician's card edits are refused with the owner's name. */
    private static void refusedWhileEditing(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lcv_first");
        ServerPlayer owner = near(helper, "lcv_first");
        ServerPlayer logician = near(helper, "lcv_second");
        entity.setOwner(OwnerRecord.of(owner));
        BlockPos core = NetworkGameTests.at(1);
        NetworkGameTests.controllerEntity(helper, core).setOwner(OwnerRecord.of(owner));
        ControllerClusterGameTests.expansion(helper, core.above(), ExpansionContent.SECURITY_MODULE);
        NetworkGameTests.controllerEntity(helper, core).setSecurity(SecuritySettings.DEFAULT
                .withPlayer(logician.getUUID(), logician.getGameProfile().name(), AccessLevel.LOGIC));
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            entity.worker().setGraph(0, "Shared", LogicControllerGameTests.crossesNotify(COBBLE, 10, "a"));
            menu(owner, 70, entity);
            menu(logician, 71, entity);
            helper.assertTrue(present(helper, LogicEditing.open(owner, 70, 0)).index() == 0, "the owner edits");
            CardPayloads.Edited refused = edit(helper, logician,
                    set(helper, 71, 0, 1, "threshold", new SettingValue.Num<>(99)));
            helper.assertTrue(!refused.done(), "refused while the owner edits");
            helper.assertValueEqual(refused.refusal(), EditorTexts.REFUSED_LOCKED, "the lock");
            helper.assertValueEqual(refused.args(), List.of("lcv_first"), "with the editor's name");
            helper.assertValueEqual(threshold(entity.worker()), 10L, "unchanged");
            LogicEditing.close(owner, 70);
        }).thenIdle(LogicEditing.SAVE_INTERVAL).thenExecute(() -> {
            assertDone(helper, edit(helper, logician, set(helper, 71, 0, 1, "threshold", new SettingValue.Num<>(99))),
                    "free once the editor closed");
            helper.assertValueEqual(threshold(entity.worker()), 99L, "saved");
            assertNewest(helper, entity.worker(), 0, logician, LogicLang.HISTORY_SETTING, "the logician's edit");
        }).thenIdle(LogicEditing.SAVE_INTERVAL).thenExecute(() -> {
            // The owner opens the editor on a new rule: the logician cannot start one as a card in that row.
            present(helper, LogicEditing.open(owner, 70, 1));
            CardPayloads.Edited fresh = edit(helper, logician,
                    new CardPayloads.Edit(71, 1, CardPayloads.OP_NEW, 0, "", "", new CompoundTag(), ""));
            helper.assertTrue(!fresh.done() && fresh.refusal().equals(EditorTexts.REFUSED_LOCKED),
                    "a new rule waits for the editor too: " + fresh.refusal());
            helper.assertValueEqual(fresh.args(), List.of("lcv_first"), "with the editor's name");
            helper.assertValueEqual(entity.worker().size(), 1, "no rule added");
        }).thenSucceed();
    }

    /**
     * A rule started as a blank card and then switched by hand no longer waits: it stays off when card edits give it a
     * trigger and an action.
     */
    private static void switchedOffStaysOff(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lcv_switch");
        ServerPlayer owner = near(helper, "lcv_switch");
        entity.setOwner(OwnerRecord.of(owner));
        LogicControllerMenu[] screen = new LogicControllerMenu[1];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            screen[0] = menu(owner, 85, entity);
            assertDone(helper,
                    edit(helper, owner,
                            new CardPayloads.Edit(85, 0, CardPayloads.OP_NEW, 0, "", "", new CompoundTag(), "")),
                    "new rule");
            LogicWorker worker = entity.worker();
            helper.assertTrue(worker.waiting(0), "waits to be filled");
            helper.assertTrue(screen[0].clickMenuButton(owner, 0), "switched on by hand");
            helper.assertTrue(screen[0].clickMenuButton(owner, 0), "and off again");
            helper.assertTrue(!worker.waiting(0) && !worker.enabled(0), "off and no longer waiting");
        }).thenIdle(LogicEditing.SAVE_INTERVAL).thenExecute(() -> {
            assertDone(helper, edit(helper, owner, add(85, 0, CardEdits.Add.TRIGGER, NodeTypes.TIMER)), "a trigger");
        }).thenIdle(LogicEditing.SAVE_INTERVAL).thenExecute(() -> {
            assertDone(helper, edit(helper, owner, add(85, 0, CardEdits.Add.ACTION, NodeTypes.RUN_RULE)), "an action");
            helper.assertTrue(!entity.worker().enabled(0), "the player switched it off, so it stays off");
        }).thenSucceed();
    }

    /**
     * Forged card edits are refused and change nothing: a node that is not there, a value a wire sets, a choice the
     * setting lacks, a value of the wrong kind, a value that does not decode, a type the button does not offer or that
     * does not exist, a row past the rules, and an edit for a controller whose menu the player does not have open (no
     * answer at all).
     */
    private static void forgedEditsRefused(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lcv_forger");
        ServerPlayer owner = near(helper, "lcv_forger");
        entity.setOwner(OwnerRecord.of(owner));
        BlockPos otherPos = NetworkGameTests.at(5);
        // Rule 0: the redstone sets a priority, through a wire.
        LogicGraph<LogicKey> wired = new LogicGraph<>(
                List.of(new LogicNode<>(1, NodeTypes.REDSTONE_CHANGES, 0, 0, NodeSettings.empty()),
                        new LogicNode<>(2, NodeTypes.SET_PRIORITY, 180, 0,
                                NodeSettings.<LogicKey>empty().withText("label", "bank"))),
                List.of(new Wire(1, "changed", 2, "do"), new Wire(1, "strength", 2, "priority")));
        CompoundTag garbage = new CompoundTag();
        garbage.putString("kind", "nonsense");
        List<CardPayloads.Edit> forged = List.of(set(helper, 90, 0, 99, "label", new SettingValue.Text<>("x")),
                set(helper, 90, 0, 2, "priority", new SettingValue.Num<>(5)),
                set(helper, 90, 0, 1, "label", new SettingValue.Num<>(5)),
                set(helper, 90, 1, 1, "mode", new SettingValue.Text<>("sideways")),
                new CardPayloads.Edit(90, 0, CardPayloads.OP_SET, 2, "", "label", garbage, ""),
                add(90, 0, CardEdits.Add.TRIGGER, NodeTypes.NOTIFY),
                new CardPayloads.Edit(90, 0, CardPayloads.OP_ADD, 0, "TRIGGER", "", new CompoundTag(), "nonsense"),
                new CardPayloads.Edit(90, 0, CardPayloads.OP_ADD, 0, "SIDEWAYS", "", new CompoundTag(), "timer"),
                remove(90, 0, RuleCard.Section.REMEMBER, 1),
                set(helper, 90, 7, 1, "label", new SettingValue.Text<>("x")));
        List<String> expected = List.of(CardEdits.REFUSED_GONE, CardEdits.REFUSED_WIRED, CardEdits.REFUSED_VALUE,
                CardEdits.REFUSED_VALUE, EditorTexts.REFUSED_UNREADABLE, CardEdits.REFUSED_TYPE, CardEdits.REFUSED_TYPE,
                CardEdits.REFUSED_TYPE, CardEdits.REFUSED_REMOVE, EditorTexts.REFUSED_NO_SLOT);
        List<LogicGraph<LogicKey>> before = new ArrayList<>();
        var sequence = helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity))
                .thenExecute(() -> {
                    entity.worker().setGraph(0, "Wired", wired);
                    entity.worker().setGraph(1, "Rule", LogicControllerGameTests.crossesNotify(COBBLE, 10, "x"));
                    before.add(entity.worker().graph(0));
                    before.add(entity.worker().graph(1));
                    menu(owner, 90, entity);
                });
        for (int i = 0; i < forged.size(); i++) {
            CardPayloads.Edit edit = forged.get(i);
            String key = expected.get(i);
            sequence = sequence.thenIdle(LogicEditing.SAVE_INTERVAL).thenExecute(() -> {
                CardPayloads.Edited answer = edit(helper, owner, edit);
                helper.assertTrue(!answer.done(), "refused: " + edit);
                helper.assertValueEqual(answer.refusal(), key, "why: " + edit);
                helper.assertValueEqual(entity.worker().graph(0), before.get(0), "rule 0 unchanged");
                helper.assertValueEqual(entity.worker().graph(1), before.get(1), "rule 1 unchanged");
                helper.assertValueEqual(entity.worker().size(), 2, "no rule added");
            });
        }
        sequence.thenIdle(LogicEditing.SAVE_INTERVAL).thenExecute(() -> {
            // Another controller's menu: the edit is not answered and touches nothing.
            helper.setBlock(otherPos, LogicContent.CONTROLLER.get());
            LogicControllerBlockEntity other = LogicControllerGameTests.entity(helper, otherPos);
            other.worker().setGraph(0, "Theirs", LogicControllerGameTests.crossesNotify(COBBLE, 10, "y"));
            LogicGraph<LogicKey> theirs = other.worker().graph(0);
            helper.assertTrue(
                    LogicEditing.cardEdit(owner, set(helper, 91, 0, 1, "threshold", new SettingValue.Num<>(3))) == null,
                    "no answer for a menu the player does not have open");
            helper.assertValueEqual(other.worker().graph(0), theirs, "the other controller unchanged");
            helper.assertValueEqual(entity.worker().graph(1), before.get(1), "nor this one");
        }).thenSucceed();
    }

    /**
     * At one node under {@code logic.maxNodes}, adding a condition (a check and an If) is refused; a setting is not.
     */
    private static void nodeLimit(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lcv_limit");
        ServerPlayer owner = near(helper, "lcv_limit");
        entity.setOwner(OwnerRecord.of(owner));
        int max = StorageServerConfig.LOGIC_MAX_NODES.getAsInt();
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            LogicGraph<LogicKey> rule = LogicControllerGameTests.crossesNotify(COBBLE, 10, "x");
            List<LogicNode<LogicKey>> nodes = new ArrayList<>(rule.nodes());
            for (int id = 3; nodes.size() < max - 1; id++) {
                nodes.add(new LogicNode<>(id, NodeTypes.CONSTANT, 0, id * 20, NodeSettings.empty()));
            }
            entity.worker().setGraph(0, "Full", new LogicGraph<>(nodes, rule.wires()));
            menu(owner, 80, entity);
            CardPayloads.Edited refused = edit(helper, owner, add(80, 0, CardEdits.Add.CONDITION, NodeTypes.DAYTIME));
            helper.assertTrue(!refused.done(), "refused past the limit");
            helper.assertValueEqual(refused.refusal(), EditorTexts.FULL, "the limit");
            helper.assertValueEqual(refused.args(), List.of(String.valueOf(max)), "names it");
            helper.assertValueEqual(entity.worker().graph(0).nodes().size(), max - 1, "unchanged");
        }).thenIdle(LogicEditing.SAVE_INTERVAL).thenExecute(() -> {
            assertDone(helper, edit(helper, owner, set(helper, 80, 0, 1, "threshold", new SettingValue.Num<>(20))),
                    "a setting still changes");
            helper.assertValueEqual(threshold(entity.worker()), 20L, "saved");
        }).thenSucceed();
    }

    private static <T> T present(GameTestHelper helper, @Nullable T value) {
        if (value == null) {
            throw helper.assertionException(Component.literal("expected a value"));
        }
        return value;
    }
}
