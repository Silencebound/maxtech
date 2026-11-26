package dev.silencebound.maxtech.storage.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleCardsTest {
    private static final Map<String, String> ENGLISH = LogicLang.english();
    private static final RuleCards.Lang LANG = key -> ENGLISH.getOrDefault(key, key);

    private static RuleCard card(TestGraph graph) {
        return RuleCards.build(graph.build(), LANG, item -> item);
    }

    /** The card as text: the keyword column, then the line indented by its depth. */
    static List<String> text(RuleCard card) {
        List<String> out = new ArrayList<>();
        if (card.custom()) {
            out.add(String.format(ENGLISH.get(RuleCards.CUSTOM), card.nodes()));
        }
        for (int i = 0; i < card.lines().size(); i++) {
            RuleCard.Line line = card.lines().get(i);
            String key = card.keywordKey(i);
            String word = key.isEmpty() ? (line.depth() > 0 ? "·" : "") : ENGLISH.get(key);
            out.add((word + " " + "  ".repeat(line.depth()) + line.text()).strip());
        }
        return out;
    }

    @Test
    void everyNodeTypeReadsAlone() {
        for (NodeType type : NodeTypes.all()) {
            RuleCard card = card(new TestGraph().node(1, type));
            assertFalse(card.lines().isEmpty(), type.id());
            for (RuleCard.Line line : card.lines()) {
                assertFalse(line.text().contains("{") || line.text().contains("null"), type + ": " + line.text());
                assertEquals(1, line.node(), type.id());
            }
            RuleCard.Section expected = switch (type.category()) {
                case EVENTS -> RuleCard.Section.WHEN;
                case ACT -> RuleCard.Section.THEN;
                case REMEMBER -> type == NodeTypes.EDGE
                        ? RuleCard.Section.OTHER
                        : type == NodeTypes.SEQUENCE ? RuleCard.Section.STEPS : RuleCard.Section.REMEMBER;
                case READ, CHECK, TIME -> RuleCard.Section.OTHER;
            };
            if (expected == RuleCard.Section.OTHER) {
                // A lone value or check feeds nothing: the card cannot place it.
                assertEquals(List.of("Custom graph · 1 nodes", "ALSO Custom step: " + ENGLISH.get(type.nameKey())),
                        text(card), type.id());
            } else {
                assertEquals(expected, card.lines().getFirst().section(), type.id());
            }
        }
    }

    @Test
    void everyNodeTypeReadsWhenWired() {
        // Each type feeding (or fed by) an action reads in a line of its own or inline, never as a custom step.
        for (NodeType type : NodeTypes.all()) {
            TestGraph graph = new TestGraph().node(1, type).node(2, NodeTypes.GRAPH_START);
            Port event = null;
            Port value = null;
            for (Port port : type.outputs(NodeSettings.empty())) {
                if (port.type() == PortType.EVENT && event == null) {
                    event = port;
                } else if (port.type() == PortType.NUMBER && value == null) {
                    value = port;
                } else if (port.type() == PortType.YES_NO && value == null) {
                    value = port;
                }
            }
            for (Port port : type.inputs()) {
                if (port.type() == PortType.EVENT) {
                    graph.wire(2, "started", 1, port.name());
                }
            }
            if (event != null) {
                graph.node(3, NodeTypes.NOTIFY, s -> s.withText("text", "hi")).wire(1, event.name(), 3, "do");
            } else if (value != null && value.type() == PortType.NUMBER) {
                graph.node(3, NodeTypes.SET_PRIORITY, s -> s.withText("label", "bank")).wire(2, "started", 3, "do")
                        .wire(1, value.name(), 3, "priority");
            } else if (value != null) {
                graph.node(3, NodeTypes.DEVICE_SWITCH, s -> s.withText("label", "lamp")).wire(1, value.name(), 3, "on");
            } else if (type.category() != NodeCategory.ACT) {
                continue;
            }
            for (String line : text(card(graph))) {
                assertFalse(line.contains("Custom step") || line.contains("{"), type + ": " + line);
            }
        }
    }

    @Test
    void showcaseGraphsRead() {
        List<List<String>> expected = List.of(
                List.of("WHEN the redstone into the controller changes", "IF strength is above 0",
                        "THEN switch intake on, otherwise off"),
                List.of("WHEN the redstone into the controller changes", "THEN set the priority of bank to strength"),
                List.of("WHEN the count of Cobblestone in the network goes above 100",
                        "THEN send redstone 15 from lamp while Cobblestone is above 100, otherwise off"),
                List.of("WHEN the count of Stick in the network goes below 16", "THEN craft 32 Stick"),
                List.of("WHEN the redstone into outbox changes", "IF strength is above 0",
                        "THEN move 16 Cobblestone from the network to outbox"),
                List.of("WHEN a crafting job for Stick finishes", "THEN notify \"Sticks are ready\""),
                List.of("WHEN items.total of sensor chest sensor goes above 32",
                        "THEN notify \"The sensed chest holds over 32 items\""),
                List.of("REMEMBER Counter: counts each time the sensor crosses 0, back to 0 when the redstone changes",
                        "Latch: on once Counter is above 2, until the redstone changes",
                        "WHEN state.powered of sensor lever sensor goes above 0",
                        "OR the redstone into the controller changes", "IF Latch is on",
                        "THEN send redstone 15 from latch lamp, otherwise off"));
        List<TestGraph> graphs = showcase();
        for (int i = 0; i < graphs.size(); i++) {
            RuleCard card = card(graphs.get(i));
            assertEquals(expected.get(i), text(card), "graph " + (i + 1));
            assertFalse(card.custom(), "graph " + (i + 1));
        }
    }

    /** The eight graphs of the showcase's logic station ({@code dev.LogicExamples}), items as plain names. */
    static List<TestGraph> showcase() {
        List<TestGraph> graphs = new ArrayList<>();
        graphs.add(new TestGraph().node(1, NodeTypes.REDSTONE_CHANGES).node(2, NodeTypes.COMPARE)
                .node(3, NodeTypes.DEVICE_SWITCH, s -> s.withText("label", "intake")).wire(1, "strength", 2, "value")
                .wire(2, "result", 3, "on"));
        graphs.add(new TestGraph().node(1, NodeTypes.REDSTONE_CHANGES)
                .node(2, NodeTypes.SET_PRIORITY, s -> s.withText("label", "bank")).wire(1, "changed", 2, "do")
                .wire(1, "strength", 2, "priority"));
        graphs.add(new TestGraph()
                .node(1, NodeTypes.COUNT_CROSSES,
                        s -> s.withItem("item", "Cobblestone").withNumber("threshold", 100).withNumber("switch_back",
                                64))
                .node(2, NodeTypes.REDSTONE_OUT, s -> s.withText("label", "lamp")).wire(1, "past", 2, "on"));
        graphs.add(new TestGraph()
                .node(1, NodeTypes.COUNT_CROSSES,
                        s -> s.withItem("item", "Stick").withText("mode", "below").withNumber("threshold", 16))
                .node(2, NodeTypes.REQUEST_CRAFT, s -> s.withItem("item", "Stick").withNumber("amount", 32))
                .wire(1, "crossed", 2, "do"));
        graphs.add(new TestGraph().node(1, NodeTypes.REDSTONE_CHANGES, s -> s.withText("label", "outbox"))
                .node(2, NodeTypes.COMPARE)
                .node(3, NodeTypes.MOVE_ITEMS,
                        s -> s.withItem("item", "Cobblestone").withText("to", "outbox").withNumber("amount", 16))
                .wire(1, "strength", 2, "value").wire(2, "turned_yes", 3, "do"));
        graphs.add(new TestGraph().node(1, NodeTypes.JOB_FINISHED, s -> s.withItem("item", "Stick"))
                .node(2, NodeTypes.NOTIFY, s -> s.withText("text", "Sticks are ready").withFlag("displays", true))
                .wire(1, "finished", 2, "do"));
        graphs.add(new TestGraph()
                .node(1, NodeTypes.SENSOR_CROSSES,
                        s -> s.withText("label", "chest sensor").withText("value", "items.total")
                                .withNumber("threshold", 32).withNumber("switch_back", 16))
                .node(2, NodeTypes.NOTIFY, s -> s.withText("text", "The sensed chest holds over 32 items")
                        .withFlag("toast", false).withFlag("displays", true))
                .wire(1, "crossed", 2, "do"));
        graphs.add(new TestGraph()
                .node(1, NodeTypes.SENSOR_CROSSES,
                        s -> s.withText("label", "lever sensor").withText("value", "state.powered"))
                .node(2, NodeTypes.COUNTER).node(3, NodeTypes.COMPARE, s -> s.withNumber("threshold", 2))
                .node(4, NodeTypes.LATCH).node(5, NodeTypes.REDSTONE_OUT, s -> s.withText("label", "latch lamp"))
                .node(6, NodeTypes.REDSTONE_CHANGES).wire(1, "crossed", 2, "up").wire(2, "count", 3, "value")
                .wire(3, "turned_yes", 4, "set").wire(4, "state", 5, "on").wire(6, "changed", 4, "reset")
                .wire(6, "changed", 2, "reset"));
        return graphs;
    }

    @Test
    void nestedChecksAndSharedValue() {
        // Raw iron over 64, enough coal, and the furnaces off or (day and enough coal): switch the furnaces on.
        TestGraph graph = new TestGraph()
                .node(1, NodeTypes.COUNT_CROSSES, s -> s.withItem("item", "Raw Iron").withNumber("threshold", 64))
                .node(3, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "Coal"))
                .node(4, NodeTypes.COMPARE, s -> s.withText("mode", "below").withNumber("threshold", 32))
                .node(5, NodeTypes.NOT).node(6, NodeTypes.DEVICE_STATE, s -> s.withText("label", "furnaces"))
                .node(7, NodeTypes.NOT).node(8, NodeTypes.DAYTIME).node(15, NodeTypes.AND).node(9, NodeTypes.OR)
                .node(10, NodeTypes.AND).node(11, NodeTypes.AND)
                .node(12, NodeTypes.DEVICE_SWITCH, s -> s.withText("label", "furnaces")).wire(3, "count", 4, "value")
                .wire(4, "result", 5, "value").wire(6, "on", 7, "value").wire(8, "day", 15, "a")
                .wire(4, "result", 15, "b").wire(7, "result", 9, "a").wire(15, "result", 9, "b")
                .wire(5, "result", 10, "a").wire(9, "result", 10, "b").wire(1, "past", 11, "a")
                .wire(10, "result", 11, "b").wire(11, "result", 12, "on");
        RuleCard card = card(graph);
        assertEquals(List.of("LET Compare: the count of Coal in the network is below 32",
                "WHEN the count of Raw Iron in the network goes above 64", "IF Raw Iron is above 64",
                "AND Compare is no", "AND any of:", "·   furnaces is off", "·   all of:", "·     it is day",
                "·     Compare", "THEN switch furnaces on, otherwise off"), text(card));
        assertFalse(card.custom());
        // Every node is on some line; the nested group lines name their And / Or.
        RuleCard.Line any = card.lines().get(4);
        assertEquals(9, any.node());
        assertEquals(RuleCard.Live.YES_NO, any.live());
        assertEquals(1, card.lines().get(5).depth());
        assertEquals(2, card.lines().get(7).depth());
        assertTrue(card.lines().get(5).nodes().containsAll(List.of(6, 7)), card.lines().get(5).toString());
    }

    @Test
    void ifOtherwiseAndDelay() {
        TestGraph graph = new TestGraph().node(1, NodeTypes.REDSTONE_CHANGES)
                .node(3, NodeTypes.DEVICE_STATE, s -> s.withText("label", "lamp")).node(2, NodeTypes.IF)
                .node(5, NodeTypes.DELAY, s -> s.withNumber("ticks", 40))
                .node(4, NodeTypes.NOTIFY, s -> s.withText("text", "on"))
                .node(6, NodeTypes.NOTIFY, s -> s.withText("text", "off")).wire(1, "changed", 2, "when")
                .wire(3, "on", 2, "condition").wire(2, "then", 5, "start").wire(5, "done", 4, "do")
                .wire(2, "otherwise", 6, "do");
        RuleCard card = card(graph);
        assertEquals(List.of("WHEN the redstone into the controller changes",
                "THEN if lamp is on: after 2 s, notify \"on\"", "AND if lamp is off: notify \"off\""), text(card));
        assertFalse(card.custom());
        assertTrue(card.lines().get(1).nodes().containsAll(List.of(4, 2, 5, 3)), card.lines().get(1).toString());
    }

    @Test
    void sharedCheckGoesToIf() {
        TestGraph graph = new TestGraph().node(1, NodeTypes.TIMER, s -> s.withNumber("interval", 30))
                .node(2, NodeTypes.ENERGY).node(3, NodeTypes.COMPARE, s -> s.withNumber("threshold", 50))
                .node(4, NodeTypes.IF).node(5, NodeTypes.COOLDOWN, s -> s.withNumber("seconds", 60))
                .node(6, NodeTypes.NOTIFY, s -> s.withText("text", "full"))
                .node(7, NodeTypes.RUN_RULE, s -> s.withText("rule", "Cleanup")).wire(2, "percent", 3, "value")
                .wire(1, "fired", 4, "when").wire(3, "result", 4, "condition").wire(4, "then", 5, "in")
                .wire(5, "passed", 6, "do").wire(4, "then", 7, "do");
        assertEquals(List.of("WHEN every 30 seconds", "IF the energy stored in the network (percent full) is above 50",
                "THEN at most every 60 s, notify \"full\"", "AND run rule Cleanup"), text(card(graph)));
    }

    @Test
    void severalTriggersAreCustom() {
        TestGraph graph = new TestGraph().node(1, NodeTypes.TIMER).node(2, NodeTypes.RUN_BY_RULE)
                .node(3, NodeTypes.NOTIFY, s -> s.withText("text", "a"))
                .node(4, NodeTypes.NOTIFY, s -> s.withText("text", "b")).wire(1, "fired", 3, "do")
                .wire(2, "ran", 4, "do");
        RuleCard card = card(graph);
        assertTrue(card.custom());
        assertEquals(List.of("Custom graph · 4 nodes", "WHEN every 10 seconds", "OR another rule runs this one",
                "THEN on timer: notify \"a\"", "AND on run by another rule: notify \"b\""), text(card));
    }

    @Test
    void sharedVariableAndMath() {
        // A shared variable read twice gets its own name; math reads with symbols.
        TestGraph graph = new TestGraph().node(1, NodeTypes.GRAPH_START)
                .node(2, NodeTypes.SHARED_VARIABLE, s -> s.withText("name", "level"))
                .node(3, NodeTypes.MATH, s -> s.withText("operation", "multiply").withNumber("b", 2))
                .node(4, NodeTypes.SET_PRIORITY, s -> s.withText("label", "bank"))
                .node(5, NodeTypes.WRITE_DISPLAY, s -> s.withText("label", "wall").withText("text", "{a}"))
                .wire(2, "value", 3, "a").wire(2, "value", 5, "a").wire(1, "started", 4, "do")
                .wire(3, "result", 4, "priority");
        assertEquals(
                List.of("LET \"level\": the shared value level", "WHEN the graph starts",
                        "THEN set the priority of bank to \"level\" × 2", "AND show \"{a}\" on wall (a: \"level\")"),
                text(card(graph)));
    }

    @Test
    void fallbacks() {
        // A value nothing reads, and a loop of value nodes, cannot be placed.
        TestGraph graph = new TestGraph().node(1, NodeTypes.GRAPH_START)
                .node(2, NodeTypes.NOTIFY, s -> s.withText("text", "x")).node(3, NodeTypes.ITEM_COUNT)
                .node(4, NodeTypes.AND).node(5, NodeTypes.OR)
                .node(6, NodeTypes.DEVICE_SWITCH, s -> s.withText("label", "a")).wire(1, "started", 2, "do")
                .wire(4, "result", 5, "a").wire(5, "result", 4, "a").wire(4, "result", 6, "on");
        RuleCard card = card(graph);
        assertTrue(card.custom());
        List<String> text = text(card);
        assertTrue(text.contains("ALSO Custom step: Item count"), text.toString());
        for (String line : text) {
            assertFalse(line.contains("{"), line);
        }
    }

    @Test
    void lowerCaseFollowsTheLanguage() {
        TestGraph graph = new TestGraph().node(1, NodeTypes.COUNT_CROSSES, s -> s.withItem("item", "Stick"))
                .node(2, NodeTypes.NOTIFY, s -> s.withText("text", "x")).wire(1, "crossed", 2, "do");
        RuleCards.Lang keepCase = key -> key.equals(RuleCards.LOWER_WORDS) ? "no" : ENGLISH.getOrDefault(key, key);
        assertEquals("the count of Stick in the network goes Above 0",
                RuleCards.build(graph.build(), keepCase, item -> item).lines().getFirst().text());
        assertEquals("the count of Stick in the network goes above 0", card(graph).lines().getFirst().text());
    }

    @Test
    void emptyGraph() {
        RuleCard card = card(new TestGraph());
        assertTrue(card.lines().isEmpty());
        assertFalse(card.custom());
    }
}
