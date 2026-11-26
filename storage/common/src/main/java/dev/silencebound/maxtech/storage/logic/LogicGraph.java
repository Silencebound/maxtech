package dev.silencebound.maxtech.storage.logic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;

/**
 * A logic graph as the player built it: nodes and wires, nothing checked yet. {@link LogicCompiler} turns it into a
 * {@link CompiledGraph} or a list of errors. Immutable.
 *
 * <p>
 * Saved as {@code {"version": 1, "nodes": [...], "wires": [...]}}. A graph saved by a newer format version fails to
 * decode rather than losing what it does not understand; older versions are upgraded here when the format changes. The
 * codec caps list sizes ({@link #HARD_MAX_NODES}, {@link #HARD_MAX_WIRES}) so a bad payload cannot allocate much; the
 * configured size limit is the compiler's.
 *
 * @param <K>
 *            what item settings hold
 * @param nodes
 *            the nodes
 * @param wires
 *            the wires
 */
public record LogicGraph<K>(List<LogicNode<K>> nodes, List<Wire> wires) {
    /** The format version this code writes. */
    public static final int FORMAT_VERSION = 1;
    /** Most nodes the codec reads, whatever the config allows. */
    public static final int HARD_MAX_NODES = 1024;
    /** Most wires the codec reads. */
    public static final int HARD_MAX_WIRES = 4096;

    public LogicGraph {
        nodes = List.copyOf(nodes);
        wires = List.copyOf(wires);
    }

    public static <K> LogicGraph<K> empty() {
        return new LogicGraph<>(List.of(), List.of());
    }

    /** The codec of graphs whose item settings use {@code itemCodec}. */
    public static <K> Codec<LogicGraph<K>> codec(Codec<K> itemCodec) {
        Codec<NodeSettings<K>> settings = NodeSettings.codec(itemCodec);
        Codec<LogicNode<K>> node = RecordCodecBuilder.create(instance -> instance
                .group(Codec.INT.fieldOf("id").forGetter(LogicNode::id),
                        NodeTypes.CODEC.fieldOf("type").forGetter(LogicNode::type),
                        Codec.INT.optionalFieldOf("x", 0).forGetter(LogicNode::x),
                        Codec.INT.optionalFieldOf("y", 0).forGetter(LogicNode::y),
                        settings.optionalFieldOf("settings", NodeSettings.empty()).forGetter(LogicNode::settings))
                .apply(instance, LogicNode::new));
        Codec<Integer> version = Codec.INT.validate(value -> value >= 1 && value <= FORMAT_VERSION
                ? DataResult.success(value)
                : DataResult.error(() -> "Logic graph format " + value + " is not supported"));
        return RecordCodecBuilder.create(instance -> instance
                .group(version.fieldOf("version").forGetter(graph -> FORMAT_VERSION),
                        node.listOf(0, HARD_MAX_NODES).optionalFieldOf("nodes", List.of()).forGetter(LogicGraph::nodes),
                        Wire.CODEC.listOf(0, HARD_MAX_WIRES).optionalFieldOf("wires", List.of())
                                .forGetter(LogicGraph::wires))
                .apply(instance, (formatVersion, nodes, wires) -> new LogicGraph<>(nodes, wires)));
    }

    /** The node with id {@code id}, or null. */
    @org.jspecify.annotations.Nullable
    public LogicNode<K> node(int id) {
        for (LogicNode<K> node : nodes) {
            if (node.id() == id) {
                return node;
            }
        }
        return null;
    }

    /** A copy with {@code node} added. */
    public LogicGraph<K> with(LogicNode<K> node) {
        List<LogicNode<K>> copy = new ArrayList<>(nodes);
        copy.add(node);
        return new LogicGraph<>(copy, wires);
    }

    /** A copy with {@code wire} added. */
    public LogicGraph<K> with(Wire wire) {
        List<Wire> copy = new ArrayList<>(wires);
        copy.add(wire);
        return new LogicGraph<>(nodes, copy);
    }
}
