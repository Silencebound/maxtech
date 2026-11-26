package dev.silencebound.maxtech.storage.logic;

import org.jspecify.annotations.Nullable;

/**
 * One node of a {@link CompiledGraph}, flattened for the evaluator: slot indices instead of wires, settings resolved
 * and clamped. Written once by the compiler, then only read; nodes sit in evaluation order and refer to each other by
 * that position.
 */
final class CompiledNode<K> {
    /** The node's id in the source graph. */
    final int id;
    final NodeType type;
    final Op op;
    /** Per input port (the type's order), the value slot it reads; -1 for item and label inputs. */
    final int[] in;
    /** Per input port, whether its wire closes a loop and so is read one run later. */
    final boolean[] late;
    /** The first output's value slot; outputs follow in order. */
    final int out;
    /** The first memory slot. */
    final int mem;
    /** The watch the node reads, or -1. */
    final int watch;
    /** The second watch the node reads ({@link Op#secondWatch()}), or -1. */
    final int watch2;
    /**
     * Further watches a node reads beyond two, in the op's order; empty for most. Stock status: the restart threshold,
     * the batch, the state bits and the item's count in the network.
     */
    final int[] more;
    /** The action slot the node emits into, or -1. */
    final int action;
    /** Positions of the nodes this one feeds within a run. */
    final int[] down;
    /** Positions of loop-breaking nodes this one feeds through a loop; they see the change next run. */
    final int[] lateDown;
    /** The mode or kind choice's index. */
    final int mode;
    final long threshold;
    /** For crossing checks, where a met condition switches back: never past the threshold on the wrong side. */
    final long switchBack;
    /** The upper bound of a between check, or the most events a wait-until node holds. */
    final long upper;
    /**
     * The counter step, delay or pulse ticks, constant value, timer or cooldown ticks, statistics window (samples or
     * ticks), filter slot, player radius, or wait-until timeout in ticks.
     */
    final long number;
    final @Nullable K item;
    /** The label (or move source); empty is the network. */
    final String label;
    /** A move's destination label; empty is the network. */
    final String to;
    /** A notice's or a display's text, a shared variable's name, or the rule a run-rule node names. */
    final String text;
    /** A notice's channel bits. */
    final int channels;
    /** Yes/no settings as bits: for between, {@link #INCLUDE_LOW} and {@link #INCLUDE_HIGH}. */
    final int flags;
    /** The network redstone channel (0 to 15) a redstone node uses instead of its label (M7L2), or -1. */
    final int colour;
    /** Per step of a step sequence, its time limit in ticks (0: none); empty for other nodes. */
    final long[] table;

    /**
     * The position of the For each whose branch the node is in (M7L2 row 18), or of the "Run by another rule" node
     * whose call branch it is in (M7L2 row 19); -1 for neither.
     */
    final int branch;
    /**
     * Whether the node's item input is wired from a For each's "item", or from a parameter in a call branch: it takes
     * the item of the pass.
     */
    final boolean eachItem;
    /**
     * The position of the crafting-job-failed node whose "missing item" output feeds the node's item input (M7L2): it
     * takes that run's missing item. -1 for none.
     */
    final int itemFrom;
    /** With {@link #eachItem}: the parameter whose call item it takes (its place among the rule's), or -1. */
    final int param;
    /**
     * For a For each or a call branch's "Run by another rule": the positions of its branch's nodes, in evaluation
     * order; empty for other nodes.
     */
    final int[] body;
    /**
     * For a For each: the positions of the nodes that read its "each" or "item" output directly; for a call, its own.
     */
    final int[] entry;
    /** For a For each or a call: the event output slots of its branch's nodes, cleared before each pass. */
    final int[] bodyEvents;
    /** For a For each with the list source: the items set in it, in order; empty otherwise. */
    final java.util.List<K> items;

    static final int INCLUDE_LOW = 1;
    static final int INCLUDE_HIGH = 2;

    CompiledNode(int id, NodeType type, int[] in, boolean[] late, int out, int mem, int watch, int watch2, int[] more,
            int action, int[] down, int[] lateDown, int mode, long threshold, long switchBack, long upper, long number,
            @Nullable K item, String label, String to, String text, int channels, int flags, int colour, long[] table,
            int branch, boolean eachItem, int param, int itemFrom, int[] body, int[] entry, int[] bodyEvents,
            java.util.List<K> items) {
        this.table = table;
        this.itemFrom = itemFrom;
        this.branch = branch;
        this.eachItem = eachItem;
        this.param = param;
        this.body = body;
        this.entry = entry;
        this.bodyEvents = bodyEvents;
        this.items = items;
        this.colour = colour;
        this.id = id;
        this.type = type;
        this.op = type.op();
        this.in = in;
        this.late = late;
        this.out = out;
        this.mem = mem;
        this.watch = watch;
        this.watch2 = watch2;
        this.more = more;
        this.action = action;
        this.down = down;
        this.lateDown = lateDown;
        this.mode = mode;
        this.threshold = threshold;
        this.switchBack = switchBack;
        this.upper = upper;
        this.number = number;
        this.item = item;
        this.label = label;
        this.to = to;
        this.text = text;
        this.channels = channels;
        this.flags = flags;
    }
}
