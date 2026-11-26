package dev.silencebound.maxtech.storage.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Card edits as graph edits (M7L2 chunk 11): every kind of edit, and that adding and removing keep a sound graph. */
class CardEditsTest {
    private static final Map<String, String> ENGLISH = LogicLang.english();
    private static final RuleCards.Lang LANG = key -> ENGLISH.getOrDefault(key, key);

    private static RuleCard card(LogicGraph<String> graph) {
        return RuleCards.build(graph, LANG, item -> item);
    }

    private static List<String> text(LogicGraph<String> graph) {
        return RuleCardsTest.text(card(graph));
    }

    private static LogicGraph<String> applied(CardEdits.Result<String> result) {
        assertEquals("", result.refusal(), "refused");
        LogicGraph<String> graph = result.graph().orElseThrow();
        assertSound(graph);
        return graph;
    }

    private static LogicGraph<String> add(LogicGraph<String> graph, CardEdits.Add add, NodeType type) {
        return applied(CardEdits.add(graph, add, type));
    }

    private static LogicGraph<String> set(LogicGraph<String> graph, int node, String setting,
            SettingValue<String> value) {
        return applied(CardEdits.set(graph, node, setting, value));
    }

    private static LogicNode<String> node(LogicGraph<String> graph, int id) {
        return java.util.Objects.requireNonNull(graph.node(id), "node " + id);
    }

    /** The id of the only node of {@code type}. */
    private static int id(LogicGraph<String> graph, NodeType type) {
        int found = -1;
        for (LogicNode<String> node : graph.nodes()) {
            if (node.type() == type) {
                assertEquals(-1, found, "one " + type);
                found = node.id();
            }
        }
        assertTrue(found >= 0, "a " + type);
        return found;
    }

    /** No wire to a missing node or port, none of the wrong type, at most one into an input, no repeated ids. */
    static void assertSound(LogicGraph<String> graph) {
        Set<String> structural = new HashSet<>(List.of(LogicLang.MISSING_NODE, LogicLang.UNKNOWN_PORT,
                LogicLang.TWO_WIRES, LogicLang.DUPLICATE_NODE, LogicLang.LOOP));
        for (PortType type : PortType.values()) {
            structural.add(type.wrongTypeKey());
        }
        for (GraphError error : LogicCompiler.compile(graph, 1024).errors()) {
            assertFalse(structural.contains(error.key()), error + " in " + graph);
        }
        Set<Integer> ids = new HashSet<>();
        for (LogicNode<String> node : graph.nodes()) {
            assertTrue(ids.add(node.id()), "id " + node.id() + " twice");
        }
    }

    private static void assertRuns(LogicGraph<String> graph) {
        assertEquals(List.of(), LogicCompiler.compile(graph, 64).errors(), graph.toString());
    }

    /** The text of each field of {@code line}, as "node/setting=text". */
    private static List<String> fields(RuleCard.Line line) {
        List<String> out = new ArrayList<>();
        for (RuleCard.Field field : line.fields()) {
            out.add(field.node() + "/" + field.setting() + "=" + line.text().substring(field.start(), field.end()));
        }
        return out;
    }

    // ---- fields

    @Test
    void linesMarkTheirSettings() {
        List<TestGraph> showcase = RuleCardsTest.showcase();
        RuleCard sticks = card(showcase.get(3).build());
        assertEquals(List.of("1/item=Stick", "1/label=the network", "1/mode=below", "1/threshold=16"),
                fields(sticks.lines().get(0)));
        assertEquals(List.of("2/amount=32", "2/item=Stick"), fields(sticks.lines().get(1)));
        // A Compare's mode is the words between its value and limit.
        RuleCard lever = card(showcase.get(0).build());
        assertEquals(List.of("1/label=the controller"), fields(lever.lines().get(0)));
        assertEquals(List.of("2/mode=is above", "2/threshold=0"), fields(lever.lines().get(1)));
        assertEquals(List.of("3/label=intake"), fields(lever.lines().get(2)));
        // A delay's ticks, and a wired input is no field.
        RuleCard priority = card(showcase.get(1).build());
        assertEquals(List.of("2/label=bank"), fields(priority.lines().get(1)));
        LogicGraph<String> delayed = new TestGraph().node(1, NodeTypes.TIMER).node(2, NodeTypes.DELAY)
                .node(3, NodeTypes.NOTIFY, s -> s.withText("text", "hi")).wire(1, "fired", 2, "start")
                .wire(2, "done", 3, "do").build();
        assertEquals(List.of("2/ticks=1", "3/text=hi"), fields(card(delayed).lines().get(1)));
    }

    @Test
    void typedTextCannotFakeAField() {
        LogicGraph<String> graph = new TestGraph().node(1, NodeTypes.TIMER)
                .node(2, NodeTypes.NOTIFY, s -> s.withText("text", "abc")).wire(1, "fired", 2, "do").build();
        RuleCard.Line then = card(graph).lines().get(1);
        assertEquals("notify \"abc\"", then.text());
        assertEquals(List.of("2/text=abc"), fields(then));
    }

    // ---- settings

    @Test
    void settingsChangeTheGraph() {
        LogicGraph<String> sticks = RuleCardsTest.showcase().get(3).build();
        LogicGraph<String> more = set(sticks, 2, "amount", new SettingValue.Num<>(64));
        assertEquals("THEN craft 64 Stick", text(more).get(1));
        // Numbers stay in bounds; the craft amount is at least 1.
        LogicGraph<String> least = set(sticks, 2, "amount", new SettingValue.Num<>(-5));
        assertEquals("THEN craft 1 Stick", text(least).get(1));
        assertEquals(java.util.OptionalLong.of(1), node(least, 2).settings().number("amount"));
        // A click on a choice takes the next one, after the last the first.
        LogicNode<String> crossing = node(sticks, 1);
        SettingValue<String> next = CardEdits.next(crossing, "mode").orElseThrow();
        assertEquals(new SettingValue.Text<String>("above"), next);
        assertEquals("WHEN the count of Stick in the network goes above 16", text(set(sticks, 1, "mode", next)).get(0));
        assertEquals(Optional.of(new SettingValue.Flag<String>(false)),
                CardEdits.next(node(RuleCardsTest.showcase().get(5).build(), 2), "toast"));
        assertEquals(Optional.empty(), CardEdits.next(crossing, "threshold"));
        // Items and labels.
        assertEquals("THEN craft 32 Torch", text(set(sticks, 2, "item", new SettingValue.Item<>("Torch"))).get(1));
        LogicGraph<String> lamp = RuleCardsTest.showcase().get(2).build();
        assertEquals("THEN send redstone 15 from beacon while Cobblestone is above 100, otherwise off",
                text(set(lamp, 2, "label", new SettingValue.Text<>("beacon"))).get(1));
    }

    @Test
    void badSettingsAreRefused() {
        LogicGraph<String> priority = RuleCardsTest.showcase().get(1).build();
        // A wire sets the priority.
        assertEquals(CardEdits.REFUSED_WIRED,
                CardEdits.set(priority, 2, "priority", new SettingValue.Num<String>(3)).refusal());
        assertEquals(CardEdits.REFUSED_GONE,
                CardEdits.set(priority, 9, "label", new SettingValue.Text<String>("x")).refusal());
        assertEquals(CardEdits.REFUSED_GONE,
                CardEdits.set(priority, 2, "nonsense", new SettingValue.Text<String>("x")).refusal());
        assertEquals(CardEdits.REFUSED_VALUE,
                CardEdits.set(priority, 2, "label", new SettingValue.Num<String>(3)).refusal());
        assertEquals(CardEdits.REFUSED_VALUE,
                CardEdits.set(priority, 2, "label", new SettingValue.Text<String>("x".repeat(NetworkRef.MAX_REF + 1)))
                        .refusal());
        LogicGraph<String> sticks = RuleCardsTest.showcase().get(3).build();
        assertEquals(CardEdits.REFUSED_VALUE,
                CardEdits.set(sticks, 1, "mode", new SettingValue.Text<String>("sideways")).refusal());
    }

    // ---- adding

    @Test
    void blanksFillToARule() {
        LogicGraph<String> empty = LogicGraph.empty();
        assertFalse(CardEdits.hasTrigger(empty));
        assertEquals(CardEdits.REFUSED_NEED_ACTION,
                CardEdits.add(empty, CardEdits.Add.CONDITION, NodeTypes.COMPARE).refusal());
        LogicGraph<String> trigger = add(empty, CardEdits.Add.TRIGGER, NodeTypes.COUNT_CROSSES);
        assertTrue(CardEdits.hasTrigger(trigger));
        assertFalse(CardEdits.hasAction(trigger));
        LogicGraph<String> rule = add(trigger, CardEdits.Add.ACTION, NodeTypes.REQUEST_CRAFT);
        assertTrue(CardEdits.hasAction(rule));
        int crossing = id(rule, NodeTypes.COUNT_CROSSES);
        int craft = id(rule, NodeTypes.REQUEST_CRAFT);
        assertEquals(List.of(new Wire(crossing, "crossed", craft, "do")), rule.wires());
        rule = set(rule, crossing, "item", new SettingValue.Item<>("Stick"));
        rule = set(rule, craft, "item", new SettingValue.Item<>("Stick"));
        assertRuns(rule);
        assertEquals(List.of("WHEN the count of Stick in the network goes above 0", "THEN craft 1 Stick"), text(rule));
        // The action is placed right of the trigger, not on it.
        assertTrue(node(rule, craft).x() > node(rule, crossing).x());

        // An action first, then its trigger: the trigger goes into the waiting action.
        LogicGraph<String> later = add(add(empty, CardEdits.Add.ACTION, NodeTypes.NOTIFY), CardEdits.Add.TRIGGER,
                NodeTypes.TIMER);
        assertEquals(List.of(new Wire(id(later, NodeTypes.TIMER), "fired", id(later, NodeTypes.NOTIFY), "do")),
                later.wires());
    }

    @Test
    void conditionsJoinTheIfChain() {
        LogicGraph<String> sticks = RuleCardsTest.showcase().get(3).build();
        LogicGraph<String> one = add(sticks, CardEdits.Add.CONDITION, NodeTypes.DAYTIME);
        assertEquals(
                List.of("WHEN the count of Stick in the network goes below 16", "IF it is day", "THEN craft 32 Stick"),
                text(one));
        assertRuns(one);
        // A second condition ANDs into the same If; a Compare brings an Item count to compare.
        LogicGraph<String> two = add(one, CardEdits.Add.CONDITION, NodeTypes.COMPARE);
        int count = id(two, NodeTypes.ITEM_COUNT);
        two = set(two, count, "item", new SettingValue.Item<>("Coal"));
        assertEquals(List.of("WHEN the count of Stick in the network goes below 16", "IF it is day",
                "AND the count of Coal in the network is above 0", "THEN craft 32 Stick"), text(two));
        assertEquals(1, count(two, NodeTypes.IF));
        assertEquals(1, count(two, NodeTypes.AND));
        assertRuns(two);
        // A switch's on/off takes the condition through an And.
        LogicGraph<String> lever = RuleCardsTest.showcase().get(0).build();
        LogicGraph<String> both = add(lever, CardEdits.Add.CONDITION, NodeTypes.WEATHER);
        assertEquals(List.of("WHEN the redstone into the controller changes", "IF strength is above 0",
                "AND it is raining", "THEN switch intake on, otherwise off"), text(both));
        assertRuns(both);
    }

    @Test
    void actionsRunAfterTheLast() {
        LogicGraph<String> sticks = add(RuleCardsTest.showcase().get(3).build(), CardEdits.Add.CONDITION,
                NodeTypes.DAYTIME);
        LogicGraph<String> two = add(sticks, CardEdits.Add.ACTION, NodeTypes.NOTIFY);
        two = set(two, id(two, NodeTypes.NOTIFY), "text", new SettingValue.Text<>("crafting"));
        assertEquals(List.of("WHEN the count of Stick in the network goes below 16", "IF it is day",
                "THEN craft 32 Stick", "AND notify \"crafting\""), text(two));
        assertFalse(card(two).custom());
        assertRuns(two);
        // After a switch, a switch follows the same check.
        LogicGraph<String> lever = add(RuleCardsTest.showcase().get(0).build(), CardEdits.Add.ACTION,
                NodeTypes.REDSTONE_OUT);
        lever = set(lever, id(lever, NodeTypes.REDSTONE_OUT), "label", new SettingValue.Text<>("lamp"));
        // The check two actions read gets a name.
        assertEquals(List.of("LET Compare: strength is above 0", "WHEN the redstone into the controller changes",
                "IF Compare", "THEN switch intake on, otherwise off", "AND send redstone 15 from lamp, otherwise off"),
                text(lever));
        assertFalse(card(lever).custom());
        // After a switch, an action with an event runs on the trigger when the switch's check is yes.
        LogicGraph<String> notify = add(RuleCardsTest.showcase().get(0).build(), CardEdits.Add.ACTION,
                NodeTypes.NOTIFY);
        notify = set(notify, id(notify, NodeTypes.NOTIFY), "text", new SettingValue.Text<>("lever"));
        assertEquals(List.of("LET Compare: strength is above 0", "WHEN the redstone into the controller changes",
                "IF Compare", "THEN switch intake on, otherwise off", "AND notify \"lever\""), text(notify));
        assertFalse(card(notify).custom());
        assertRuns(notify);
    }

    @Test
    void triggersNeedAnOpenPlace() {
        LogicGraph<String> sticks = RuleCardsTest.showcase().get(3).build();
        assertEquals(CardEdits.REFUSED_NO_SLOT,
                CardEdits.add(sticks, CardEdits.Add.TRIGGER, NodeTypes.TIMER).refusal());
        // Without its trigger the action waits for one; a new one takes its place.
        LogicGraph<String> none = applied(CardEdits.remove(sticks, RuleCard.Section.WHEN, 1));
        assertEquals(1, none.nodes().size());
        LogicGraph<String> timer = add(none, CardEdits.Add.TRIGGER, NodeTypes.TIMER);
        assertEquals(List.of("WHEN every 10 seconds", "THEN craft 32 Stick"), text(timer));
        assertRuns(timer);
        // Behind an If the trigger goes into the If.
        LogicGraph<String> gated = add(sticks, CardEdits.Add.CONDITION, NodeTypes.DAYTIME);
        LogicGraph<String> again = add(applied(CardEdits.remove(gated, RuleCard.Section.WHEN, 1)),
                CardEdits.Add.TRIGGER, NodeTypes.TIMER);
        assertEquals(List.of("WHEN every 10 seconds", "IF it is day", "THEN craft 32 Stick"), text(again));
        // A trigger with a yes/no fills a switch's open on/off.
        LogicGraph<String> lamp = add(LogicGraph.empty(), CardEdits.Add.ACTION, NodeTypes.DEVICE_SWITCH);
        lamp = add(lamp, CardEdits.Add.TRIGGER, NodeTypes.DAYTIME_CHANGES);
        assertEquals(1, lamp.wires().size());
        assertEquals("day", lamp.wires().getFirst().output());
        assertEquals(CardEdits.REFUSED_TYPE, CardEdits.add(sticks, CardEdits.Add.TRIGGER, NodeTypes.NOTIFY).refusal());
    }

    // ---- removing

    @Test
    void removingLinesTakesWhatOnlyTheyRead() {
        LogicGraph<String> sticks = RuleCardsTest.showcase().get(3).build();
        LogicGraph<String> two = add(add(sticks, CardEdits.Add.CONDITION, NodeTypes.DAYTIME), CardEdits.Add.CONDITION,
                NodeTypes.WEATHER);
        // The first check goes: the And gives way to the other.
        LogicGraph<String> one = applied(CardEdits.remove(two, RuleCard.Section.IF, id(two, NodeTypes.DAYTIME)));
        assertEquals(List.of("WHEN the count of Stick in the network goes below 16", "IF it is raining",
                "THEN craft 32 Stick"), text(one));
        assertEquals(0, count(one, NodeTypes.AND));
        // The last check goes: so does the If, and the trigger runs the action again.
        LogicGraph<String> none = applied(CardEdits.remove(one, RuleCard.Section.IF, id(one, NodeTypes.WEATHER)));
        assertEquals(sticks.wires(), none.wires());
        assertEquals(2, none.nodes().size());
        // A Compare goes with the Item count it read; an action with the If and checks only it had.
        LogicGraph<String> compared = add(sticks, CardEdits.Add.CONDITION, NodeTypes.COMPARE);
        LogicGraph<String> plain = applied(
                CardEdits.remove(compared, RuleCard.Section.IF, id(compared, NodeTypes.COMPARE)));
        assertEquals(2, plain.nodes().size());
        LogicGraph<String> bare = applied(CardEdits.remove(compared, RuleCard.Section.THEN, 2));
        assertEquals(List.of(NodeTypes.COUNT_CROSSES), types(bare));
        // A switch's check: the switch loses its on/off and waits for a new one.
        LogicGraph<String> lever = RuleCardsTest.showcase().get(0).build();
        LogicGraph<String> open = applied(CardEdits.remove(lever, RuleCard.Section.IF, 2));
        assertEquals(List.of(NodeTypes.REDSTONE_CHANGES, NodeTypes.DEVICE_SWITCH), types(open));
        assertEquals(List.of(), open.wires());
        // A group header takes its whole group.
        LogicGraph<String> group = applied(CardEdits.remove(two, RuleCard.Section.IF, id(two, NodeTypes.AND)));
        assertEquals(sticks.wires(), group.wires());
    }

    @Test
    void someLinesStayForTheEditor() {
        List<TestGraph> showcase = RuleCardsTest.showcase();
        // Memory and named values, a "turns yes" step, custom steps.
        LogicGraph<String> counted = showcase.get(7).build();
        assertEquals(CardEdits.REFUSED_REMOVE, CardEdits.remove(counted, RuleCard.Section.REMEMBER, 2).refusal());
        LogicGraph<String> button = showcase.get(4).build();
        assertEquals(CardEdits.REFUSED_REMOVE, CardEdits.remove(button, RuleCard.Section.IF, 2).refusal());
        assertEquals(CardEdits.REFUSED_REMOVE, CardEdits.remove(button, RuleCard.Section.OTHER, 2).refusal());
        assertEquals(CardEdits.REFUSED_REMOVE, CardEdits.remove(button, RuleCard.Section.WHEN, 3).refusal());
        assertEquals(CardEdits.REFUSED_GONE, CardEdits.remove(button, RuleCard.Section.THEN, 42).refusal());
        // A check an Otherwise branch reads.
        LogicGraph<String> otherwise = new TestGraph().node(1, NodeTypes.REDSTONE_CHANGES)
                .node(3, NodeTypes.DEVICE_STATE, s -> s.withText("label", "lamp")).node(2, NodeTypes.IF)
                .node(4, NodeTypes.NOTIFY).node(6, NodeTypes.NOTIFY).wire(1, "changed", 2, "when")
                .wire(3, "on", 2, "condition").wire(2, "then", 4, "do").wire(2, "otherwise", 6, "do").build();
        assertEquals(CardEdits.REFUSED_REMOVE, CardEdits.remove(otherwise, RuleCard.Section.IF, 3).refusal());
    }

    /** Every line of every showcase card, removed, and every offered node, added: always a sound graph. */
    @Test
    void everyEditKeepsTheGraphSound() {
        for (TestGraph test : RuleCardsTest.showcase()) {
            LogicGraph<String> graph = test.build();
            for (RuleCard.Line line : card(graph).lines()) {
                CardEdits.Result<String> result = CardEdits.remove(graph, line.section(), line.node());
                result.graph().ifPresent(CardEditsTest::assertSound);
                if (result.graph().isPresent()) {
                    assertTrue(result.graph().get().nodes().size() < graph.nodes().size(), line.toString());
                }
            }
            for (CardEdits.Add add : CardEdits.Add.values()) {
                for (NodeType type : CardEdits.choices(add)) {
                    CardEdits.Result<String> result = CardEdits.add(graph, add, type);
                    result.graph().ifPresent(CardEditsTest::assertSound);
                    result.graph().ifPresent(next -> assertTrue(next.nodes().size() > graph.nodes().size()));
                }
            }
        }
        // The offers: events, actions, and yes/no reads and checks that need no wire.
        assertTrue(CardEdits.choices(CardEdits.Add.CONDITION).containsAll(
                List.of(NodeTypes.COMPARE, NodeTypes.DAYTIME, NodeTypes.DEVICE_STATE, NodeTypes.SHARED_VARIABLE)));
        assertFalse(CardEdits.choices(CardEdits.Add.CONDITION).contains(NodeTypes.AND));
        assertFalse(CardEdits.choices(CardEdits.Add.CONDITION).contains(NodeTypes.LATCH));
        assertFalse(CardEdits.choices(CardEdits.Add.CONDITION).contains(NodeTypes.ITEM_COUNT));
        assertTrue(CardEdits.choices(CardEdits.Add.ACTION).contains(NodeTypes.NOTIFY));
        assertTrue(CardEdits.choices(CardEdits.Add.TRIGGER).contains(NodeTypes.TIMER));
    }

    private static int count(LogicGraph<String> graph, NodeType type) {
        int n = 0;
        for (LogicNode<String> node : graph.nodes()) {
            if (node.type() == type) {
                n++;
            }
        }
        return n;
    }

    private static List<NodeType> types(LogicGraph<String> graph) {
        List<NodeType> out = new ArrayList<>();
        for (LogicNode<String> node : graph.nodes()) {
            out.add(node.type());
        }
        return out;
    }
}
