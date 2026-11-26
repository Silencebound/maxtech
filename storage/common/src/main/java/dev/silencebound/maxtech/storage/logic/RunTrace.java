package dev.silencebound.maxtech.storage.logic;

/**
 * The "why" line of one run: the nodes that stopped it, each with the reason and the values it saw (for example a
 * compare whose result did not change, or an if whose condition was no). Holds at most {@link #CAPACITY} entries and
 * counts the rest. Reused from run to run, so it allocates nothing; read it on the server thread between runs.
 */
public final class RunTrace {
    /** Most entries kept per run. */
    public static final int CAPACITY = 8;

    private final int[] node = new int[CAPACITY];
    private final String[] reason = new String[CAPACITY];
    private final long[] first = new long[CAPACITY];
    private final long[] second = new long[CAPACITY];
    private final String[] text = new String[CAPACITY];
    private int size;
    private int dropped;
    /** While set, entries are not kept: a For each's passes add one summary line instead. */
    private boolean muted;

    RunTrace() {
        java.util.Arrays.fill(reason, "");
        java.util.Arrays.fill(text, "");
    }

    void clear() {
        size = 0;
        dropped = 0;
        muted = false;
    }

    /** Stops (or starts again) keeping entries; for the passes of a For each node (M7L2 row 18). */
    void mute(boolean mute) {
        muted = mute;
    }

    /** Whether entries are not kept now (a For each's passes). */
    boolean muted() {
        return muted;
    }

    /**
     * Adds an entry for node {@code nodeId}: the evaluator's own reasons, and the controller's after a run (a sensor
     * value that was not found). Past {@link #CAPACITY} it is only counted.
     */
    public void add(int nodeId, String reasonKey, long a, long b) {
        add(nodeId, reasonKey, a, b, "");
    }

    /**
     * {@link #add(int, String, long, long)} with a text that fills the reason's first {@code %s} instead of {@code a}
     * (a list of items, M7L2); empty for none.
     */
    public void add(int nodeId, String reasonKey, long a, long b, String detail) {
        if (muted) {
            return;
        }
        if (size == CAPACITY) {
            dropped++;
            return;
        }
        node[size] = nodeId;
        reason[size] = reasonKey;
        first[size] = a;
        second[size] = b;
        text[size] = detail;
        size++;
    }

    /** Entries in the last run. */
    public int size() {
        return size;
    }

    /** Entries beyond {@link #CAPACITY} that were not kept. */
    public int dropped() {
        return dropped;
    }

    /** The id of the node of entry {@code i}. */
    public int node(int i) {
        return node[i];
    }

    /** The lang key of entry {@code i}'s reason ({@code LogicLang.WHY_*}); values fill its {@code %s} in order. */
    public String reason(int i) {
        return reason[i];
    }

    /** The first value entry {@code i} saw (the value checked); 0 when the reason has none. */
    public long first(int i) {
        return first[i];
    }

    /** The second value entry {@code i} saw (the limit); 0 when the reason has none. */
    public long second(int i) {
        return second[i];
    }

    /** The text of entry {@code i}, which fills its first {@code %s} when not empty; empty for most reasons. */
    public String text(int i) {
        return text[i];
    }

    /** The entry index of node {@code id}'s first entry in the last run, or -1. */
    public int find(int id) {
        for (int i = 0; i < size; i++) {
            if (node[i] == id) {
                return i;
            }
        }
        return -1;
    }
}
