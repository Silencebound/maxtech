package dev.silencebound.maxtech.storage.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class GraphDiffTest {
    private static final LogicGraph<String> BASE = new TestGraph()
            .node(1, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "cobble"))
            .node(2, NodeTypes.COMPARE, s -> s.withNumber("threshold", 100)).wire(1, "count", 2, "value").build();

    private static List<GraphChange> diff(LogicGraph<String> before, LogicGraph<String> after) {
        return GraphDiff.of(before, after, item -> GraphChange.Arg.text(item));
    }

    private static GraphChange.Arg key(String key) {
        return GraphChange.Arg.key(key);
    }

    private static GraphChange.Arg text(String text) {
        return GraphChange.Arg.text(text);
    }

    @Test
    void sameGraphSaysNoChange() {
        assertEquals(List.of(GraphChange.of(LogicLang.HISTORY_NO_CHANGE)), diff(BASE, BASE));
    }

    @Test
    void changedNumberSettingShowsOldAndNew() {
        LogicGraph<String> after = new TestGraph().node(1, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "cobble"))
                .node(2, NodeTypes.COMPARE, s -> s.withNumber("threshold", 200)).wire(1, "count", 2, "value").build();
        assertEquals(List.of(new GraphChange(LogicLang.HISTORY_SETTING, List.of(key(NodeTypes.COMPARE.nameKey()),
                key(NodeTypes.COMPARE.setting("threshold").orElseThrow().nameKey()), text("100"), text("200")))),
                diff(BASE, after));
    }

    @Test
    void addedNodesAndWiresAreCounted() {
        LogicGraph<String> one = new TestGraph().node(1, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "cobble"))
                .node(2, NodeTypes.COMPARE, s -> s.withNumber("threshold", 100)).node(3, NodeTypes.NOT)
                .wire(1, "count", 2, "value").wire(2, "result", 3, "value").build();
        assertEquals(List.of(new GraphChange(LogicLang.HISTORY_NODE_ADDED, List.of(key(NodeTypes.NOT.nameKey()))),
                GraphChange.of(LogicLang.HISTORY_WIRE_ADDED)), diff(BASE, one));

        LogicGraph<String> two = new TestGraph().node(1, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "cobble"))
                .node(2, NodeTypes.COMPARE, s -> s.withNumber("threshold", 100)).node(3, NodeTypes.NOT)
                .node(4, NodeTypes.NOT).wire(1, "count", 2, "value").wire(2, "result", 3, "value")
                .wire(3, "result", 4, "value").build();
        assertEquals(List.of(GraphChange.of(LogicLang.HISTORY_NODES_ADDED, "2"),
                GraphChange.of(LogicLang.HISTORY_WIRES_ADDED, "2")), diff(BASE, two));
    }

    @Test
    void removedNodeAndItsWire() {
        LogicGraph<String> after = new TestGraph().node(1, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "cobble"))
                .build();
        assertEquals(List.of(new GraphChange(LogicLang.HISTORY_NODE_REMOVED, List.of(key(NodeTypes.COMPARE.nameKey()))),
                GraphChange.of(LogicLang.HISTORY_WIRE_REMOVED)), diff(BASE, after));
    }

    @Test
    void typeChangedUnderSameIdIsRemovedAndAdded() {
        LogicGraph<String> after = new TestGraph().node(1, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "cobble"))
                .node(2, NodeTypes.NOT).build();
        assertEquals(List.of(new GraphChange(LogicLang.HISTORY_NODE_ADDED, List.of(key(NodeTypes.NOT.nameKey()))),
                new GraphChange(LogicLang.HISTORY_NODE_REMOVED, List.of(key(NodeTypes.COMPARE.nameKey()))),
                GraphChange.of(LogicLang.HISTORY_WIRE_REMOVED)), diff(BASE, after));
    }

    @Test
    void unsetAndDefaultAreTheSame() {
        LogicGraph<String> before = new TestGraph().node(2, NodeTypes.COMPARE).build();
        LogicGraph<String> after = new TestGraph()
                .node(2, NodeTypes.COMPARE, s -> s.withNumber("threshold", 0).withText("mode", "above")).build();
        assertEquals(List.of(GraphChange.of(LogicLang.HISTORY_NO_CHANGE)), diff(before, after));
    }

    @Test
    void choicesFlagsItemsAndTextRead() {
        LogicGraph<String> before = new TestGraph().node(1, NodeTypes.COMPARE)
                .node(2, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "cobble")).build();
        LogicGraph<String> after = new TestGraph().node(1, NodeTypes.COMPARE, s -> s.withText("mode", "below"))
                .node(2, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "stick").withText("label", "bank")).build();
        String mode = NodeTypes.COMPARE.setting("mode").orElseThrow().nameKey();
        String item = NodeTypes.ITEM_COUNT.setting("item").orElseThrow().nameKey();
        String label = NodeTypes.ITEM_COUNT.setting("label").orElseThrow().nameKey();
        assertEquals(List.of(
                new GraphChange(LogicLang.HISTORY_SETTING,
                        List.of(key(NodeTypes.COMPARE.nameKey()), key(mode), key(SettingSpec.choiceKey("above")),
                                key(SettingSpec.choiceKey("below")))),
                new GraphChange(LogicLang.HISTORY_SETTING,
                        List.of(key(NodeTypes.ITEM_COUNT.nameKey()), key(item), text("cobble"), text("stick"))),
                new GraphChange(LogicLang.HISTORY_SETTING, List.of(key(NodeTypes.ITEM_COUNT.nameKey()), key(label),
                        key(LogicLang.HISTORY_EMPTY), text("\"bank\"")))),
                diff(before, after));
    }

    @Test
    void settingsPastTheDetailedOnesAreCounted() {
        LogicGraph<String> before = new TestGraph().node(1, NodeTypes.COMPARE).node(2, NodeTypes.COMPARE).build();
        LogicGraph<String> after = new TestGraph()
                .node(1, NodeTypes.COMPARE,
                        s -> s.withNumber("threshold", 1).withNumber("upper", 2).withNumber("switch_back", 3))
                .node(2, NodeTypes.COMPARE, s -> s.withNumber("threshold", 4).withNumber("upper", 5)).build();
        List<GraphChange> changes = diff(before, after);
        assertEquals(GraphDiff.DETAILED_SETTINGS + 1, changes.size(), changes::toString);
        assertEquals(GraphChange.of(LogicLang.HISTORY_MORE_SETTINGS, "2"), changes.getLast());
    }

    @Test
    void movedOnlyNodesAreCounted() {
        LogicGraph<String> before = new TestGraph().node(1, NodeTypes.NOT).node(2, NodeTypes.NOT).build();
        LogicGraph<String> after = new LogicGraph<>(
                List.of(new LogicNode<>(1, NodeTypes.NOT, 50, 50, NodeSettings.empty()),
                        new LogicNode<>(2, NodeTypes.NOT, 70, 50, NodeSettings.empty())),
                List.of());
        assertEquals(List.of(GraphChange.of(LogicLang.HISTORY_NODES_MOVED, "2")), diff(before, after));
    }
}
