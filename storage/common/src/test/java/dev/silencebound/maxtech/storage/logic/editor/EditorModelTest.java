package dev.silencebound.maxtech.storage.logic.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.storage.logic.LogicCompiler;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.NodeCategory;
import dev.silencebound.maxtech.storage.logic.NodeSettings;
import dev.silencebound.maxtech.storage.logic.NodeType;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.PortType;
import dev.silencebound.maxtech.storage.logic.SettingSpec;
import dev.silencebound.maxtech.storage.logic.Wire;
import dev.silencebound.maxtech.storage.logic.LogicNode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The editor's pure model: wiring rules, node geometry and hit tests, the palette filter, settings shown, notes. */
class EditorModelTest {
    private static EditorGraph<String> empty() {
        return new EditorGraph<>(LogicGraph.empty());
    }

    @Test
    void onlyMatchingTypesConnect() {
        EditorGraph<String> graph = empty();
        int count = graph.add(NodeTypes.ITEM_COUNT, 0, 0, NodeSettings.<String>empty().withItem("item", "iron"));
        int compare = graph.add(NodeTypes.COMPARE, 100, 0, NodeSettings.empty());
        int notify = graph.add(NodeTypes.NOTIFY, 200, 0, NodeSettings.empty());
        assertEquals(EditorGraph.Connect.WRONG_TYPE, graph.connect(count, "count", notify, "do"), "number into event");
        assertEquals(EditorGraph.Connect.SAME_NODE, graph.connect(compare, "turned_yes", compare, "value"));
        assertEquals(EditorGraph.Connect.MISSING, graph.connect(count, "nope", compare, "value"));
        assertEquals(EditorGraph.Connect.MADE, graph.connect(count, "count", compare, "value"));
        assertEquals(EditorGraph.Connect.ALREADY, graph.connect(count, "count", compare, "value"));
        assertEquals(EditorGraph.Connect.MADE, graph.connect(compare, "turned_yes", notify, "do"));
        assertEquals(2, graph.wires().size());

        int other = graph.add(NodeTypes.ITEM_COUNT, 0, 50, NodeSettings.<String>empty().withItem("item", "gold"));
        assertEquals(EditorGraph.Connect.MADE, graph.connect(other, "count", compare, "value"));
        assertEquals(2, graph.wires().size(), "a new wire into an input replaces the old one");
        assertEquals(new Wire(other, "count", compare, "value"), graph.wireInto(compare, "value"));
    }

    @Test
    void deletingANodeDeletesItsWiresAndIdsAreNotReused() {
        EditorGraph<String> graph = empty();
        int a = graph.add(NodeTypes.COUNT_CROSSES, 0, 0, NodeSettings.empty());
        int b = graph.add(NodeTypes.NOTIFY, 100, 0, NodeSettings.empty());
        graph.connect(a, "crossed", b, "do");
        assertTrue(graph.remove(a));
        assertTrue(graph.wires().isEmpty());
        int c = graph.add(NodeTypes.DELAY, 0, 0, NodeSettings.empty());
        assertTrue(c > b, "a deleted node's id is not handed out again");
        assertTrue(graph.move(c, 8, 12));
        assertFalse(graph.move(c, 8, 12), "already there");
        assertEquals(8, node(graph.snapshot(), c).x());
    }

    @Test
    void aConstantsKindChangeDropsWiresThatNoLongerFit() {
        EditorGraph<String> graph = empty();
        int constant = graph.add(NodeTypes.CONSTANT, 0, 0, NodeSettings.empty());
        int compare = graph.add(NodeTypes.COMPARE, 100, 0, NodeSettings.empty());
        assertTrue(graph.connect(constant, "value", compare, "value").made(), "a constant is a number by default");
        graph.setSettings(constant, NodeSettings.<String>empty().withText("kind", "label"));
        assertTrue(graph.wires().isEmpty(), "a label does not fit a number input");
    }

    @Test
    void undoSnapshotsAreUnchangedByLaterEdits() {
        EditorGraph<String> graph = empty();
        int a = graph.add(NodeTypes.DELAY, 0, 0, NodeSettings.empty());
        LogicGraph<String> before = graph.snapshot();
        graph.move(a, 40, 40);
        graph.add(NodeTypes.NOTIFY, 0, 0, NodeSettings.empty());
        assertEquals(1, before.nodes().size());
        assertEquals(0, node(before, a).x());
        graph.load(before);
        assertEquals(1, graph.size());
        assertTrue(graph.add(NodeTypes.NOTIFY, 0, 0, NodeSettings.empty()) > a + 1,
                "ids after an undo still skip the undone node");
    }

    @Test
    void portsAreHitAtTheirEdges() {
        int inputs = NodeTypes.COUNT_CROSSES.inputs().size();
        int outputs = NodeTypes.COUNT_CROSSES.outputs(NodeSettings.empty()).size();
        assertEquals(NodeLayout.HEADER + 3 * NodeLayout.PORT_PITCH + 2, NodeLayout.height(inputs, outputs));
        int hit = NodeLayout.portAt(inputs, outputs, NodeLayout.WIDTH + 1, NodeLayout.portY(2) - 2);
        assertTrue(NodeLayout.hitOutput(hit));
        assertEquals(2, NodeLayout.hitIndex(hit), "the third output");
        hit = NodeLayout.portAt(inputs, outputs, -2, NodeLayout.portY(1) + 3);
        assertFalse(NodeLayout.hitOutput(hit));
        assertEquals(1, NodeLayout.hitIndex(hit), "the second input");
        assertEquals(NodeLayout.MISS, NodeLayout.portAt(inputs, outputs, -2, NodeLayout.portY(2)),
                "there is no third input");
        assertEquals(NodeLayout.MISS, NodeLayout.portAt(inputs, outputs, NodeLayout.WIDTH / 2, NodeLayout.portY(0)),
                "the middle of the node is no port");
        assertTrue(NodeLayout.onHeader(5, 5));
        assertFalse(NodeLayout.onHeader(5, NodeLayout.HEADER));
        assertTrue(NodeLayout.inside(inputs, outputs, 5, NodeLayout.HEADER + 1));
        assertEquals(8, NodeLayout.snap(9));
        assertEquals(-8, NodeLayout.snap(-9));
    }

    @Test
    void paletteOffersOnlyNodesThatTakeTheWire() {
        assertEquals(Optional.of("value"), Palette.joinPort(NodeTypes.COMPARE, PortType.NUMBER, true));
        assertEquals(Optional.empty(), Palette.joinPort(NodeTypes.IF, PortType.NUMBER, true), "if takes no number");
        assertEquals(Optional.of("do"), Palette.joinPort(NodeTypes.MOVE_ITEMS, PortType.EVENT, true));
        assertEquals(Optional.of("on"), Palette.joinPort(NodeTypes.REDSTONE_OUT, PortType.YES_NO, true));
        assertEquals(Optional.of("value"), Palette.joinPort(NodeTypes.CONSTANT, PortType.LABEL, false),
                "a constant feeds a label by its kind");
        assertEquals(Optional.empty(), Palette.joinPort(NodeTypes.CONSTANT, PortType.EVENT, false));
        assertEquals(Optional.of("crossed"), Palette.joinPort(NodeTypes.COUNT_CROSSES, PortType.EVENT, false));
        assertEquals(Optional.of("label"),
                Palette.settingsFor(NodeTypes.CONSTANT, PortType.LABEL, NodeSettings.<String>empty()).text("kind"));

        List<Palette.Row> rows = Palette.rows();
        assertEquals(NodeTypes.all().size() + NodeCategory.values().length, rows.size(),
                "every type once under its category");
        assertTrue(rows.get(0).type().isEmpty(), "starts with a heading");

        List<Palette.Row> common = Palette.rows(false);
        long commonTypes = NodeTypes.all().stream().filter(NodeType::common).count();
        assertEquals(commonTypes, common.stream().filter(row -> row.type().isPresent()).count(),
                "the common view lists exactly the common types");
        assertTrue(common.stream().allMatch(row -> row.type().map(NodeType::common).orElse(true)));
        for (int i = 0; i < common.size(); i++) {
            if (common.get(i).type().isEmpty()) {
                assertTrue(i + 1 < common.size() && common.get(i + 1).type().isPresent(), "no empty group");
            }
        }
        assertTrue(common.size() < rows.size());
    }

    @Test
    void statisticsShowTheSizeOfTheirWindow() {
        SettingSpec samples = NodeTypes.STATS.setting("samples").orElseThrow();
        SettingSpec seconds = NodeTypes.STATS.setting("seconds").orElseThrow();
        NodeSettings<String> bySamples = NodeSettings.empty();
        assertTrue(SettingRules.shows(NodeTypes.STATS, bySamples, samples));
        assertFalse(SettingRules.shows(NodeTypes.STATS, bySamples, seconds));
        NodeSettings<String> bySeconds = bySamples.withText("window", "seconds");
        assertFalse(SettingRules.shows(NodeTypes.STATS, bySeconds, samples));
        assertTrue(SettingRules.shows(NodeTypes.STATS, bySeconds, seconds));
    }

    @Test
    void settingsShownFollowKindAndMode() {
        SettingSpec upper = NodeTypes.COMPARE.setting("upper").orElseThrow();
        SettingSpec back = NodeTypes.COMPARE.setting("switch_back").orElseThrow();
        NodeSettings<String> above = NodeSettings.empty();
        assertFalse(SettingRules.shows(NodeTypes.COMPARE, above, upper));
        assertTrue(SettingRules.shows(NodeTypes.COMPARE, above, back), "above by default");
        NodeSettings<String> between = above.withText("mode", "between");
        assertTrue(SettingRules.shows(NodeTypes.COMPARE, between, upper));
        assertFalse(SettingRules.shows(NodeTypes.COMPARE, between, back));
        SettingSpec item = NodeTypes.CONSTANT.setting("item").orElseThrow();
        assertFalse(SettingRules.shows(NodeTypes.CONSTANT, above, item), "a number constant");
        assertTrue(SettingRules.shows(NodeTypes.CONSTANT, above.withText("kind", "item"), item));

        EditorGraph<String> graph = empty();
        int count = graph.add(NodeTypes.ITEM_COUNT, 0, 0, NodeSettings.empty());
        int constant = graph.add(NodeTypes.CONSTANT, 0, 0, NodeSettings.<String>empty().withText("kind", "item"));
        SettingSpec itemSetting = NodeTypes.ITEM_COUNT.setting("item").orElseThrow();
        assertFalse(SettingRules.wired(graph.snapshot(), node(graph.snapshot(), count), itemSetting));
        graph.connect(constant, "value", count, "item");
        assertTrue(SettingRules.wired(graph.snapshot(), node(graph.snapshot(), count), itemSetting),
                "the wire sets the item");
    }

    @Test
    void notesHaveErrorsAndLabelWarnings() {
        EditorGraph<String> graph = empty();
        int sw = graph.add(NodeTypes.DEVICE_SWITCH, 0, 0, NodeSettings.<String>empty().withText("label", "Drives"));
        int count = graph.add(NodeTypes.ITEM_COUNT, 0, 0,
                NodeSettings.<String>empty().withItem("item", "iron").withText("label", "nowhere"));
        EditorNote.Labels labels = new EditorNote.Labels() {
            @Override
            public boolean has(String label) {
                return label.equalsIgnoreCase("drives");
            }

            @Override
            public boolean switchable(String label) {
                return false;
            }
        };
        List<EditorNote> notes = EditorNote.of(LogicCompiler.compile(graph.snapshot(), 64), labels);
        EditorNote missingOn = find(notes, sw, "on");
        assertNotNull(missingOn, "the switch needs its on input: " + notes);
        assertTrue(missingOn.error());
        EditorNote cant = find(notes, sw, "label");
        assertNotNull(cant);
        assertEquals(LogicLang.CANT_SWITCH, cant.key());
        assertFalse(cant.error(), "a warning, not an error");
        EditorNote nowhere = find(notes, count, "label");
        assertNotNull(nowhere);
        assertEquals(LogicLang.NO_SUCH_LABEL, nowhere.key());
        assertEquals("nowhere", nowhere.arg());
        assertNull(find(EditorNote.of(LogicCompiler.compile(graph.snapshot(), 64), EditorNote.Labels.UNKNOWN), count,
                "label"), "no network, no label warnings");
        assertTrue(notes.get(0).error() && !notes.get(notes.size() - 1).error(), "errors first, then warnings");
    }

    private static <K> LogicNode<K> node(LogicGraph<K> graph, int id) {
        return Objects.requireNonNull(graph.node(id));
    }

    private static @Nullable EditorNote find(List<EditorNote> notes, int node, String port) {
        for (EditorNote note : notes) {
            if (note.node() == node && note.port().equals(port)) {
                return note;
            }
        }
        return null;
    }
}
