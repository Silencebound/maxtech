package dev.silencebound.maxtech.storage.logic;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A checked graph ready to run: nodes in evaluation order with their wires turned into value slots, the things it
 * watches, and its action slots. Made by {@link LogicCompiler}; immutable, so one compiled graph can back several
 * evaluators. Each {@link LogicEvaluator} holds the changing state.
 *
 * @param <K>
 *            what items are
 */
public final class CompiledGraph<K> {
    private final LogicGraph<K> source;
    final CompiledNode<K>[] nodes;
    final int slots;
    /** Slots below this hold node outputs; the rest hold the values of unwired inputs. */
    final int outputSlots;
    /** Each slot's value before the first run: setting values for unwired inputs, 0 otherwise. */
    final long[] initialValues;
    final int memory;
    private final List<Watch<K>> watches;
    /** Per watch, the positions of the nodes that read it. */
    final int[][] watchNodes;
    /** Positions of timed nodes (delay, timer, statistics), which a run checks for being due. */
    final int[] timed;
    /** Per action slot, the position of the first node emitting into it; its settings name the target. */
    final int[] actionNodes;
    /**
     * The position of the "Run by another rule" node that runs the call branch once per call (M7L2 row 19), or -1 when
     * the rule has no parameters or no such node.
     */
    final int callOwner;
    /** The rule's parameters, in node order. */
    final Params params;

    /** The parameters of a compiled rule, in node order (M7L2 row 19). Written once by the compiler, then only read. */
    static final class Params {
        /** Each one's output value slot. */
        final int[] slots;
        /** Each one's name as {@link Parameters#key} keys it. */
        final String[] keys;
        /** Each one's kind ({@link Parameters#NUMBER} and so on). */
        final int[] kinds;
        /** Each number or yes/no parameter's own value (1 is yes). */
        final long[] numbers;
        /** Each item parameter's own item (a {@code K}), or null. */
        final @Nullable Object[] items;

        Params(int[] slots, String[] keys, int[] kinds, long[] numbers, @Nullable Object[] items) {
            this.slots = slots;
            this.keys = keys;
            this.kinds = kinds;
            this.numbers = numbers;
            this.items = items;
        }

        int size() {
            return slots.length;
        }
    }

    CompiledGraph(LogicGraph<K> source, CompiledNode<K>[] nodes, long[] initialValues, int outputSlots, int memory,
            List<Watch<K>> watches, int[][] watchNodes, int[] timed, int[] actionNodes, int callOwner, Params params) {
        this.callOwner = callOwner;
        this.params = params;
        this.source = source;
        this.nodes = nodes;
        this.slots = initialValues.length;
        this.outputSlots = outputSlots;
        this.initialValues = initialValues;
        this.memory = memory;
        this.watches = List.copyOf(watches);
        this.watchNodes = watchNodes;
        this.timed = timed;
        this.actionNodes = actionNodes;
    }

    /**
     * A node's use of another network (M7L2 row 13).
     *
     * @param node
     *            the node's id
     * @param port
     *            the label setting or input that names it
     * @param network
     *            the network's name as typed ({@link NetworkRef#network})
     */
    public record NetworkUse(int node, String port, String network) {
    }

    /**
     * Every node's use of another network, in evaluation order, each label once ({@link NetworkRef}); empty for a graph
     * that stays on its own network. Allocates; for when a graph is made or its networks are checked.
     */
    public java.util.List<NetworkUse> networks() {
        java.util.List<NetworkUse> out = new java.util.ArrayList<>();
        for (CompiledNode<K> node : nodes) {
            if (!NetworkRef.supports(node.op) || node.op == Op.CONSTANT || node.op == Op.PARAMETER) {
                continue;
            }
            String from = NetworkRef.network(node.label);
            if (!from.isEmpty()) {
                out.add(new NetworkUse(node.id, LogicCompiler.labelName(node.op), from));
            }
            String to = node.op == Op.MOVE_ITEMS ? NetworkRef.network(node.to) : "";
            if (!to.isEmpty()) {
                out.add(new NetworkUse(node.id, NodeTypes.TO, to));
            }
        }
        return out;
    }

    /** The graph as the player built it. */
    public LogicGraph<K> source() {
        return source;
    }

    /** Nodes in the graph. */
    public int size() {
        return nodes.length;
    }

    /**
     * Whether calls from other rules run this rule's call branch with their own values (M7L2 row 19): it has parameters
     * and a "Run by another rule" node. Such a rule is run with {@link LogicEvaluator#call}.
     */
    public boolean callable() {
        return callOwner >= 0;
    }

    /** The place among the rule's parameters of the one keyed {@code key} ({@link Parameters#key}), or -1. */
    public int param(String key) {
        String[] keys = params.keys;
        for (int k = 0; k < keys.length; k++) {
            if (keys[k].equals(key)) {
                return k;
            }
        }
        return -1;
    }

    /** The kind of parameter {@code k} ({@link Parameters#NUMBER} and so on). */
    public int paramKind(int k) {
        return params.kinds[k];
    }

    /**
     * What the graph watches, by {@link Watch#index()}. The controller subscribes to each and calls
     * {@link LogicEvaluator#changed} or {@link LogicEvaluator#signal} with the index.
     */
    public List<Watch<K>> watches() {
        return watches;
    }

    /** How many nodes read watch {@code watch}. */
    public int readers(int watch) {
        return watchNodes[watch].length;
    }

    /** The id of the {@code i}th node that reads watch {@code watch}. */
    public int reader(int watch, int i) {
        return nodes[watchNodes[watch][i]].id;
    }

    /** The value slot of node {@code id}'s output {@code output}, for live values in the editor; -1 if none. */
    public int slot(int id, String output) {
        for (CompiledNode<K> node : nodes) {
            if (node.id == id) {
                // Declared outputs: a constant's are all called "value" (its one slot is the first), a sequence shows a
                // prefix of its own.
                List<Port> outputs = node.type.declaredOutputs();
                int shown = node.op == Op.SEQUENCE ? Sequences.outputCount((int) node.number) : outputs.size();
                for (int i = 0; i < shown; i++) {
                    if (outputs.get(i).name().equals(output)) {
                        return node.out + i;
                    }
                }
            }
        }
        return -1;
    }

    /** The evaluation position of node {@code id}, for the editor's live view; -1 if the graph has no such node. */
    public int position(int id) {
        for (int pos = 0; pos < nodes.length; pos++) {
            if (nodes[pos].id == id) {
                return pos;
            }
        }
        return -1;
    }

    /** The index of the watch the node at {@code position} reads ({@link #watches()}), or -1 if it reads none. */
    public int watchAt(int position) {
        return nodes[position].watch;
    }

    /**
     * The highest cost one run can have, for budget checks before running, counting a For each's branch once: each item
     * it goes through adds the branch's cost again, up to {@code logic.forEachMaxItems} items.
     */
    public int maxCost() {
        int cost = watches.size() * LogicEvaluator.READ_COST;
        for (CompiledNode<K> node : nodes) {
            cost += node.type.cost() + (node.op == Op.REQUEST_CRAFT ? LogicEvaluator.READ_COST : 0);
        }
        return cost;
    }

    /**
     * The most action points one run can cost if every action node acts once (a For each's branch counted once, as in
     * {@link #maxCost}); 0 when the graph has no action. For the rule detail and the budget page.
     */
    public int maxActionPoints() {
        int points = 0;
        for (CompiledNode<K> node : nodes) {
            points += node.type.actionPoints();
        }
        return points;
    }

    /** Whether the graph has an action node, so its runs need action points as well as checks. */
    public boolean acts() {
        return actionNodes.length > 0;
    }
}
