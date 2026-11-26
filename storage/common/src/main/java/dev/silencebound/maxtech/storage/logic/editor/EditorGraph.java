package dev.silencebound.maxtech.storage.logic.editor;

import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicNode;
import dev.silencebound.maxtech.storage.logic.NodeSettings;
import dev.silencebound.maxtech.storage.logic.NodeType;
import dev.silencebound.maxtech.storage.logic.Port;
import dev.silencebound.maxtech.storage.logic.Wire;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The graph a player is editing (M7L area F): nodes and wires that change in place, with {@link #snapshot()} giving the
 * immutable {@link LogicGraph} to save or keep for undo. Only valid wires can be made ({@link #connect}): an output to
 * an input of the same type on another node; a new wire into an input replaces the old one, since an input takes one
 * wire. Deleting a node deletes its wires; a settings change that alters a node's ports (a constant's kind) drops the
 * wires that no longer fit. Node ids are never reused within an edit, so saved memory stays with its node.
 *
 * <p>
 * Client thread only; not thread-safe.
 *
 * @param <K>
 *            what items are
 */
public final class EditorGraph<K> {
    /** What {@link #connect} did. */
    public enum Connect {
        /** The wire was made (replacing any wire that went into the input before). */
        MADE,
        /** The same wire is already there; nothing changed. */
        ALREADY,
        /** A node cannot feed itself directly. */
        SAME_NODE,
        /** The output and input carry different types. */
        WRONG_TYPE,
        /** A node or port named does not exist. */
        MISSING;

        public boolean made() {
            return this == MADE;
        }
    }

    private final List<LogicNode<K>> nodes = new ArrayList<>();
    private final List<Wire> wires = new ArrayList<>();
    private int nextId = 1;

    public EditorGraph(LogicGraph<K> graph) {
        load(graph);
    }

    /** Replaces everything with {@code graph} (on open and undo). */
    public void load(LogicGraph<K> graph) {
        nodes.clear();
        wires.clear();
        nodes.addAll(graph.nodes());
        wires.addAll(graph.wires());
        int max = 0;
        for (LogicNode<K> node : nodes) {
            max = Math.max(max, node.id());
        }
        nextId = Math.max(nextId, max + 1);
    }

    /** The graph as it is now. */
    public LogicGraph<K> snapshot() {
        return new LogicGraph<>(nodes, wires);
    }

    /** The nodes in drawing order (the last on top). */
    public List<LogicNode<K>> nodes() {
        return Collections.unmodifiableList(nodes);
    }

    public List<Wire> wires() {
        return Collections.unmodifiableList(wires);
    }

    public int size() {
        return nodes.size();
    }

    public @Nullable LogicNode<K> node(int id) {
        for (LogicNode<K> node : nodes) {
            if (node.id() == id) {
                return node;
            }
        }
        return null;
    }

    private int indexOf(int id) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).id() == id) {
                return i;
            }
        }
        return -1;
    }

    /** Adds a node of {@code type} at ({@code x}, {@code y}) and returns its id. */
    public int add(NodeType type, int x, int y, NodeSettings<K> settings) {
        int id = nextId++;
        nodes.add(new LogicNode<>(id, type, x, y, settings));
        return id;
    }

    /** Moves node {@code id}; false when it does not exist or is already there. */
    public boolean move(int id, int x, int y) {
        int index = indexOf(id);
        if (index < 0) {
            return false;
        }
        LogicNode<K> node = nodes.get(index);
        if (node.x() == x && node.y() == y) {
            return false;
        }
        nodes.set(index, new LogicNode<>(id, node.type(), x, y, node.settings()));
        return true;
    }

    /** Draws node {@code id} last, on top of the others. */
    public void raise(int id) {
        int index = indexOf(id);
        if (index >= 0 && index < nodes.size() - 1) {
            nodes.add(nodes.remove(index));
        }
    }

    /** Deletes node {@code id} and every wire to or from it; false when it does not exist. */
    public boolean remove(int id) {
        int index = indexOf(id);
        if (index < 0) {
            return false;
        }
        nodes.remove(index);
        wires.removeIf(wire -> wire.from() == id || wire.to() == id);
        return true;
    }

    /** Deletes {@code wire}; false when it is not there. */
    public boolean remove(Wire wire) {
        return wires.remove(wire);
    }

    /** Changes node {@code id}'s settings and drops its wires that no longer fit; false when nothing changed. */
    public boolean setSettings(int id, NodeSettings<K> settings) {
        int index = indexOf(id);
        if (index < 0 || nodes.get(index).settings().equals(settings)) {
            return false;
        }
        nodes.set(index, nodes.get(index).withSettings(settings));
        wires.removeIf(wire -> (wire.from() == id || wire.to() == id) && !fits(wire));
        return true;
    }

    private boolean fits(Wire wire) {
        LogicNode<K> from = node(wire.from());
        LogicNode<K> to = node(wire.to());
        if (from == null || to == null) {
            return false;
        }
        Optional<Port> out = output(from, wire.output());
        Optional<Port> in = input(to, wire.input());
        return out.isPresent() && in.isPresent() && out.get().type() == in.get().type();
    }

    /** The wire going into {@code node}'s input {@code input}, or null. */
    public @Nullable Wire wireInto(int node, String input) {
        for (Wire wire : wires) {
            if (wire.to() == node && wire.input().equals(input)) {
                return wire;
            }
        }
        return null;
    }

    /** Joins {@code from}'s output {@code output} to {@code to}'s input {@code input} if the types match. */
    public Connect connect(int from, String output, int to, String input) {
        LogicNode<K> source = node(from);
        LogicNode<K> target = node(to);
        if (source == null || target == null) {
            return Connect.MISSING;
        }
        if (from == to) {
            return Connect.SAME_NODE;
        }
        Optional<Port> out = output(source, output);
        Optional<Port> in = input(target, input);
        if (out.isEmpty() || in.isEmpty()) {
            return Connect.MISSING;
        }
        if (out.get().type() != in.get().type()) {
            return Connect.WRONG_TYPE;
        }
        Wire wire = new Wire(from, output, to, input);
        Wire old = wireInto(to, input);
        if (wire.equals(old)) {
            return Connect.ALREADY;
        }
        if (old != null) {
            wires.remove(old);
        }
        wires.add(wire);
        return Connect.MADE;
    }

    /** {@code node}'s output called {@code name}, as its settings make it (a constant's output follows its kind). */
    public static Optional<Port> output(LogicNode<?> node, String name) {
        for (Port port : node.type().outputs(node.settings())) {
            if (port.name().equals(name)) {
                return Optional.of(port);
            }
        }
        return Optional.empty();
    }

    /** {@code node}'s input called {@code name}. */
    public static Optional<Port> input(LogicNode<?> node, String name) {
        for (Port port : node.type().inputs(node.settings())) {
            if (port.name().equals(name)) {
                return Optional.of(port);
            }
        }
        return Optional.empty();
    }
}
