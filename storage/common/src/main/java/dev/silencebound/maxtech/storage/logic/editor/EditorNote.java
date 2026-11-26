package dev.silencebound.maxtech.storage.logic.editor;

import dev.silencebound.maxtech.storage.logic.CompileResult;
import dev.silencebound.maxtech.storage.logic.GraphError;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.LogicNode;
import dev.silencebound.maxtech.storage.logic.NetworkRef;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.SettingKind;
import dev.silencebound.maxtech.storage.logic.SettingSpec;
import java.util.ArrayList;
import java.util.List;

/**
 * One note the editor shows on a node (M7L area F): a compile error, which keeps the graph paused, or a warning about
 * the network that does not stop it from running (a label no device carries, a switch aimed at storage). Immutable.
 *
 * @param node
 *            the node's id, or {@link GraphError#GRAPH} for the whole graph
 * @param port
 *            the input or setting it is about; empty for the node
 * @param key
 *            the lang key of the plain-words message
 * @param arg
 *            the message's argument; empty when it takes none
 * @param error
 *            true for an error that pauses the graph, false for a warning
 */
public record EditorNote(int node, String port, String key, String arg, boolean error) {
    /** Most notes a payload carries. */
    public static final int MAX = 256;

    /** What the editor knows about the network's labels. */
    public interface Labels {
        /** Labels are not known (no network): no label warnings. */
        Labels UNKNOWN = new Labels() {
            @Override
            public boolean has(String label) {
                return true;
            }

            @Override
            public boolean switchable(String label) {
                return true;
            }
        };

        /** Whether some device on the network carries {@code label} as typed. */
        boolean has(String label);

        /** Whether some device carrying {@code label} can be turned on and off. */
        boolean switchable(String label);
    }

    /**
     * The notes for {@code result}: its errors, then for every label setting a node uses (not fed by a wire) a warning
     * when no device carries it, and for a device switch whose label holds only devices that cannot be turned off.
     */
    public static List<EditorNote> of(CompileResult<?> result, Labels labels) {
        List<EditorNote> notes = new ArrayList<>();
        for (GraphError error : result.errors()) {
            notes.add(new EditorNote(error.node(), error.port(), error.key(), error.arg(), true));
        }
        LogicGraph<?> graph = result.source();
        for (LogicNode<?> node : graph.nodes()) {
            for (SettingSpec spec : node.type().settings()) {
                if (spec.kind() != SettingKind.LABEL || !SettingRules.shows(node.type(), node.settings(), spec)
                        || SettingRules.wired(graph, node, spec)) {
                    continue;
                }
                String label = node.settings().text(spec.name()).orElse("").strip();
                if (label.isEmpty()) {
                    continue;
                }
                if (NetworkRef.remote(label)) {
                    // Another network (M7L2 row 13): the rule's owner must be able to name it; its labels are not
                    // checked here.
                    String network = NetworkRef.network(label);
                    if (!labels.has(NetworkRef.MARK + network)) {
                        notes.add(new EditorNote(node.id(), spec.name(), LogicLang.NET_NOT_REACHABLE, network, false));
                    }
                } else if (!labels.has(NetworkRef.device(label))) {
                    notes.add(new EditorNote(node.id(), spec.name(), LogicLang.NO_SUCH_LABEL, label, false));
                } else if (node.type() == NodeTypes.DEVICE_SWITCH && !labels.switchable(NetworkRef.device(label))) {
                    notes.add(new EditorNote(node.id(), spec.name(), LogicLang.CANT_SWITCH, "", false));
                }
            }
        }
        return notes.size() > MAX ? List.copyOf(notes.subList(0, MAX)) : notes;
    }
}
