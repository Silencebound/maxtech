package dev.silencebound.maxtech.storage.logic.editor;

import dev.silencebound.maxtech.storage.logic.NodeCategory;
import dev.silencebound.maxtech.storage.logic.NodeSettings;
import dev.silencebound.maxtech.storage.logic.NodeType;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.Port;
import dev.silencebound.maxtech.storage.logic.PortType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The editor's node palette (M7L area F): node types grouped by category, and, while a wire is dragged from a port,
 * which of them could take the other end. From an output, a node fits when it has an input of the wire's type; from an
 * input, when it has an output of that type. A constant fits any value type from an input, by setting its kind. Pure.
 */
public final class Palette {
    /** The setting that picks a constant's output type; its choice ids are the port types' ids. */
    private static final String KIND = "kind";

    private Palette() {
    }

    /** A palette row: a category heading ({@code type} empty) or a node type. */
    public record Row(NodeCategory category, Optional<NodeType> type) {
    }

    /** Every category with its node types under it, in registration order. */
    public static List<Row> rows() {
        return rows(true);
    }

    /**
     * The palette's rows: with {@code all}, every category with its node types under it in registration order;
     * otherwise only the common types ({@link NodeType#common()}), and only the categories that have one.
     */
    public static List<Row> rows(boolean all) {
        List<Row> rows = new ArrayList<>();
        List<NodeType> types = NodeTypes.all();
        for (NodeCategory category : NodeCategory.values()) {
            int heading = rows.size();
            for (NodeType type : types) {
                if (type.category() == category && (all || type.common())) {
                    if (rows.size() == heading) {
                        rows.add(new Row(category, Optional.empty()));
                    }
                    rows.add(new Row(category, Optional.of(type)));
                }
            }
        }
        return rows;
    }

    /**
     * The port of a new {@code node} that the end of a wire dragged from a port of {@code type} would join: an input
     * when the wire comes from an output ({@code fromOutput}), else an output. A required port is preferred. Empty when
     * the node has none of that type.
     */
    public static Optional<String> joinPort(NodeType node, PortType type, boolean fromOutput) {
        if (fromOutput) {
            Optional<String> optional = Optional.empty();
            for (Port port : node.inputs(NodeSettings.empty())) {
                if (port.type() == type) {
                    if (port.required()) {
                        return Optional.of(port.name());
                    }
                    if (optional.isEmpty()) {
                        optional = Optional.of(port.name());
                    }
                }
            }
            return optional;
        }
        for (Port port : node.outputs(settingsFor(node, type, NodeSettings.empty()))) {
            if (port.type() == type) {
                return Optional.of(port.name());
            }
        }
        return Optional.empty();
    }

    /**
     * Settings for a new {@code node} that will feed a port of {@code type}: a constant or parameter gets the kind that
     * gives that type; other nodes keep {@code settings}.
     */
    public static <K> NodeSettings<K> settingsFor(NodeType node, PortType type, NodeSettings<K> settings) {
        if (node != NodeTypes.CONSTANT && node != NodeTypes.PARAMETER) {
            return settings;
        }
        return node.setting(KIND).filter(spec -> spec.choices().contains(type.id()))
                .map(spec -> settings.withText(KIND, type.id())).orElse(settings);
    }
}
