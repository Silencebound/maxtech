package dev.silencebound.maxtech.storage.logic;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Runs one compiled graph. Holds the graph's changing state: wire values, memory, what changed since the last run, and
 * the actions of the run in progress. Server thread only.
 *
 * <p>
 * Events only (PRD 8.1): the controller calls {@link #changed} when a watched value changed and {@link #signal} when a
 * watched event happened; several calls before a run count once. {@link #pending} says whether a run has work, and
 * {@link #run} runs only the nodes downstream of what changed, in the compiled order. Triggers fire on threshold
 * crossings with a switch-back value, not on every change. Actions are collected while nodes run, duplicates within the
 * run are dropped (one-shot work) or replaced (settings), and the batch goes to the {@link LogicContext} once at the
 * end. A delay asks for a wake ({@link #nextWake}); nothing polls.
 *
 * <p>
 * Each run counts its cost in checks ({@link #lastCost}): the node type's cost per node evaluated plus
 * {@link #READ_COST} per value read from the context. Its actions cost action points apart from that
 * ({@link #lastActionPoints}). The controller charges both against the team's budgets; a graph evaluates at most once
 * per tick, and a loop through a Remember or Delay node asks for another run next tick ({@link #wantsRerun}).
 * {@link #run} allocates nothing.
 *
 * @param <K>
 *            what items are
 */
public final class LogicEvaluator<K> {
    /** Checks one read from the context costs (a watched value, or whether a craft is running). */
    public static final int READ_COST = 2;
    /** {@link #nextWake} when no delay is waiting. */
    public static final long NO_WAKE = Long.MAX_VALUE;

    // A statistics node's memory: where the next sample goes, how many it holds, when its oldest sample in a seconds
    // window leaves it (0: none), then the ring of values and the ring of their game times.
    private static final int STATS_HEAD = 0;
    private static final int STATS_COUNT = 1;
    private static final int STATS_DUE = 2;
    private static final int STATS_VALUES = 3;
    private static final int STATS_TIMES = STATS_VALUES + NodeTypes.STATS_RING;
    /** Longs of memory a statistics node keeps. */
    static final int STATS_MEMORY = STATS_TIMES + NodeTypes.STATS_RING;
    // A change-per-second node's memory: the last sample, its game time + 1 (0: none yet), the rate.
    private static final int RATE_LAST = 0;
    private static final int RATE_TIME = 1;
    private static final int RATE_VALUE = 2;
    /** Statistics and rates work within this, so sums of a ring and differences times 20 never overflow. */
    private static final long SAFE = SettingSpec.MAX_NUMBER / 2;

    private final CompiledGraph<K> graph;
    private final CompiledNode<K>[] nodes;
    private final long[] values;
    private final long[] memory;
    private final boolean[] dirty;
    private final boolean[] nextDirty;
    private final boolean[] watchDirty;
    private final boolean[] watchSignal;
    /** Per signal watch, the amounts told with it since the last run (items moved, items missing). */
    private final long[] signalAmount;
    /**
     * Per signal watch, the first item told with it since the last run (a failed job's missing item) and the line the
     * why trace shows for it; null and empty when none.
     */
    private final @Nullable Object[] signalItem;
    private final String[] signalText;
    /** Per signal watch, {@link #signalItem} and {@link #signalText} as this run read them. */
    private final @Nullable Object[] watchItem;
    private final String[] watchText;
    /**
     * Per node position, the item a crafting-job-failed node gave in run {@link #nodeItemRun}: what nodes whose item
     * input its "missing item" output feeds take ({@link CompiledNode#itemFrom}). Only valid in that run.
     */
    private final @Nullable Object[] nodeItem;
    private final long[] nodeItemRun;
    /** The missing item of a crafting-job-failed node that fired in run {@link #runItemRun}; a notice's {item}. */
    private @Nullable K runItem;
    private long runItemRun;
    private final long[] watchValue;
    /** Per watch, whether only crossing nodes read it and nothing reads their value output; see {@link #matters}. */
    private final boolean[] crossingOnly;
    private final long[] actionRun;
    private final long[] actionValue;
    /** Per action slot, the position of the node whose action the run sends (the last one, for settings). */
    private final int[] actionEmitter;
    private final int[] touched;
    private final RunTrace trace = new RunTrace();
    /** Per value slot, how often its event fired; read only for slots that feed a loop-closing wire. */
    private final long[] fireCount;
    /** Per node position, where its inputs start in {@link #lateSeen}. */
    private final int[] inputBase;
    /** Per input of every node, how many of its loop-closing wire's events the node has taken. */
    private final long[] lateSeen;
    /** The position of the node being evaluated. */
    private int current;
    private int touchedCount;
    /** The id of the current (or last) run; event outputs hold the id of the run they fired in. 0 before any run. */
    private long run;
    private int cost;
    /** Action points the actions of the current (or last) run cost; see {@link NodeType#actionPoints}. */
    private int points;
    private boolean rerun;
    private boolean wanted;
    private long nextWake = NO_WAKE;
    /** The id of the action node being sent to the context, for {@link #why}; -1 outside a flush. */
    private int acting = -1;
    /** The id of the node reading through {@link LogicContext#readEach} now; -1 otherwise. */
    private int reading = -1;
    /** A why line {@link #whyRead} kept for after the branch passes: its node (-1 for none), reason and text. */
    private int readWhyNode = -1;
    private String readWhyKey = "";
    private String readWhyText = "";
    /** Per watch, whether every run reads it again ({@link WatchKind#volatileValue()}). */
    private final boolean[] volatileWatch;
    /** The graph starts with its next run: graph-start nodes fire then. Set when made and by {@link #restart}. */
    private boolean starting = true;
    /** What script nodes see; one per evaluator, bound to the node running. */
    private final Script scriptContext = new Script();
    /** The id of the script node that failed in the last run, or -1. */
    private int scriptFailedNode = -1;
    /** The failed script's message; empty when none failed. */
    private String scriptFailure = "";

    // For each (M7L2 row 18).
    /** The item of the pass a For each is running, which its branch's nodes with an item from it take; else null. */
    private @Nullable K currentItem;
    /** Whether a For each's or a call's pass is running: actions go to the per-item queue. */
    private boolean iterating;
    /** Most calls one rule holds waiting (M7L2 row 19). */
    public static final int MAX_CALLS = 256;
    /** Calls from other rules waiting for the next run: per call, the item parameter and item, number and number. */
    private int[] callItemParam = new int[0];
    private @Nullable Object[] callItem = new Object[0];
    private int[] callNumberParam = new int[0];
    private long[] callNumber = new long[0];
    private int callCount;
    /** During a call's pass, each parameter's item; null outside passes. */
    private @Nullable Object[] passItems = new Object[0];
    /** Items the run may still go through over all its For each nodes; -1 until the first For each asks. */
    private int eachLeft;
    /** The items of the For each running now, reused from run to run. */
    private final List<K> eachItems = new ArrayList<>();
    /** What the context hands for one source entry, before repeats are dropped. */
    private final List<K> eachScratch = new ArrayList<>();
    /** The per-item actions of the run: the emitting node's position, the item and the value. Grows, never shrinks. */
    private int[] eachNode = new int[0];
    private Object[] eachItem = new Object[0];
    /** In {@link #eachItem}: an action without an item (a notice). */
    private static final Object NO_ITEM = new Object();
    /** In a notice's text: the name of the missing item of a crafting job that failed in this run. */
    public static final String ITEM_PLACEHOLDER = "{item}";
    private long[] eachValue = new long[0];
    private int eachCount;
    /** The per-item queue's lookup table by action and item: queue index, and the run that wrote it (else free). */
    private int[] queueSlot = new int[0];
    private long[] queueMark = new long[0];
    /** Items a For each has gathered so far, as a table marked by {@link #seenRound}; made at the first For each. */
    private Object[] seenKey = new Object[0];
    private long[] seenMark = new long[0];
    private long seenRound;
    /**
     * Slots of {@link #seenKey}: a power of two, eight times the most items one For each gathers
     * ({@link ForEach#HARD_MAX_ITEMS} + 1).
     */
    private static final int SEEN_SIZE = 8192;

    public LogicEvaluator(CompiledGraph<K> graph) {
        this.graph = graph;
        this.nodes = graph.nodes;
        int n = nodes.length;
        values = graph.initialValues.clone();
        memory = new long[graph.memory];
        dirty = new boolean[n];
        nextDirty = new boolean[n];
        int watches = graph.watches().size();
        watchDirty = new boolean[watches];
        watchSignal = new boolean[watches];
        signalAmount = new long[watches];
        signalItem = new Object[watches];
        signalText = new String[watches];
        watchItem = new Object[watches];
        watchText = new String[watches];
        java.util.Arrays.fill(signalText, "");
        java.util.Arrays.fill(watchText, "");
        nodeItem = new Object[n];
        nodeItemRun = new long[n];
        watchValue = new long[watches];
        crossingOnly = new boolean[watches];
        volatileWatch = new boolean[watches];
        for (int w = 0; w < watches; w++) {
            crossingOnly[w] = crossingOnly(graph, w);
            volatileWatch[w] = graph.watches().get(w).kind().volatileValue();
        }
        int actions = graph.actionNodes.length;
        actionRun = new long[actions];
        actionValue = new long[actions];
        actionEmitter = new int[actions];
        touched = new int[actions];
        fireCount = new long[values.length];
        inputBase = new int[n];
        int inputs = 0;
        for (int pos = 0; pos < n; pos++) {
            inputBase[pos] = inputs;
            inputs += nodes[pos].in.length;
        }
        lateSeen = new long[inputs];
        everythingChanged();
    }

    public CompiledGraph<K> graph() {
        return graph;
    }

    private static <K> boolean crossingOnly(CompiledGraph<K> graph, int watch) {
        for (int pos : graph.watchNodes[watch]) {
            CompiledNode<K> node = graph.nodes[pos];
            if (node.op != Op.COUNT_CROSSES && node.op != Op.SENSOR_CROSSES && node.op != Op.LOAD_CROSSES) {
                return false;
            }
            // The third output carries the value itself: a node reading it needs every change.
            int value = node.out + 2;
            for (CompiledNode<K> other : graph.nodes) {
                for (int slot : other.in) {
                    if (slot == value) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Whether {@code value} as the new value of watch {@code watch} can change anything in the next run: false only
     * when every node reading the watch is a crossing (count or sensor) whose remembered state the value keeps, and no
     * node reads their value output. The controller asks before {@link #changed}, so a change that crosses no limit
     * costs no run (PRD 8.1: triggers fire on crossings). Marks nothing; allocates nothing.
     */
    public boolean matters(int watch, long value) {
        if (!crossingOnly[watch]) {
            return true;
        }
        for (int pos : graph.watchNodes[watch]) {
            CompiledNode<K> node = nodes[pos];
            long was = memory[node.mem];
            if (Checks.state(node.mode, value, node.threshold, node.switchBack, 0, was) != was) {
                return true;
            }
        }
        return false;
    }

    /** Watch {@code watch}'s value changed; the next run reads it again and runs the nodes it feeds. */
    public void changed(int watch) {
        watchDirty[watch] = true;
        wanted = true;
    }

    /** The event watch {@code watch} happened (a crafting job finished); the next run fires its nodes once. */
    public void signal(int watch) {
        watchSignal[watch] = true;
        wanted = true;
    }

    /**
     * The event watch {@code watch} happened with {@code amount} (items moved, items missing): the next run fires its
     * nodes once, with the amounts of every signal since the last run added up (never past the number range).
     */
    public void signal(int watch, long amount) {
        long sum = signalAmount[watch] + Math.clamp(amount, 0, SettingSpec.MAX_NUMBER);
        signalAmount[watch] = Math.min(sum, SettingSpec.MAX_NUMBER);
        signal(watch);
    }

    /**
     * {@link #signal(int, long)} with the item it is about and the why line's text for it: a failed crafting job's
     * first missing item, and every missing item. When several come before a run, the first one's item and text stay.
     */
    public void signal(int watch, long amount, @Nullable K item, String text) {
        if (item != null && signalItem[watch] == null) {
            signalItem[watch] = item;
            signalText[watch] = text;
        }
        signal(watch, amount);
    }

    /**
     * Rereads every watch and reruns every node on the next run, as after loading or when the network was unloaded;
     * memory keeps crossings from firing again.
     */
    public void everythingChanged() {
        java.util.Arrays.fill(dirty, true);
        for (int w = 0; w < watchDirty.length; w++) {
            watchDirty[w] = !graph.watches().get(w).kind().signal();
        }
        wanted = true;
    }

    /**
     * The graph starts again (switched on): its graph-start nodes fire on the next run, and every node reruns. A new
     * evaluator (a save, a load) starts that way by itself.
     */
    public void restart() {
        starting = true;
        everythingChanged();
    }

    /** Whether a run at {@code gameTime} has work: something changed, a loop asked to continue, or a delay is due. */
    public boolean pending(long gameTime) {
        return wanted || nextWake <= gameTime;
    }

    /** Runs the graph at {@code gameTime}, reading from and acting through {@code context}. */
    public void run(LogicContext<K> context, long gameTime) {
        run++;
        cost = 0;
        points = 0;
        scriptFailedNode = -1;
        scriptFailure = "";
        trace.clear();
        readWhyNode = -1;
        touchedCount = 0;
        eachCount = 0;
        eachLeft = -1;
        rerun = false;
        wanted = false;
        List<Watch<K>> watches = graph.watches();
        for (int w = 0; w < watchDirty.length; w++) {
            if (watchDirty[w] || volatileWatch[w]) {
                watchDirty[w] = false;
                watchValue[w] = context.read(watches.get(w));
                cost += READ_COST;
                markAll(graph.watchNodes[w], dirty);
            } else if (watchSignal[w]) {
                watchValue[w] = signalAmount[w];
                signalAmount[w] = 0;
                watchItem[w] = signalItem[w];
                watchText[w] = signalText[w];
                signalItem[w] = null;
                signalText[w] = "";
                markAll(graph.watchNodes[w], dirty);
            }
        }
        for (int pos : graph.timed) {
            long due = dueAt(nodes[pos]);
            if (due != 0 && due <= gameTime) {
                dirty[pos] = true;
            }
        }
        for (int pos = 0; pos < nodes.length; pos++) {
            if (nextDirty[pos]) {
                nextDirty[pos] = false;
                dirty[pos] = true;
            }
        }
        for (int pos = 0; pos < nodes.length; pos++) {
            if (!dirty[pos]) {
                continue;
            }
            dirty[pos] = false;
            CompiledNode<K> node = nodes[pos];
            if (node.branch >= 0) {
                // A For each's branch runs only in its passes, from the For each itself.
                continue;
            }
            cost += node.type.cost();
            current = pos;
            if (evaluate(node, context, gameTime)) {
                markAll(node.down, dirty);
                if (node.lateDown.length > 0) {
                    markAll(node.lateDown, nextDirty);
                    rerun = true;
                }
            }
            if (scriptFailedNode >= 0) {
                // A script node failed: the run stops and nothing it asked for is carried out; the controller pauses
                // the graph.
                touchedCount = 0;
                eachCount = 0;
                break;
            }
        }
        // Signals were read by the nodes; clear them before the actions, which may signal again (kept for next run).
        java.util.Arrays.fill(watchSignal, false);
        flush(context);
        starting = false;
        nextWake = earliestDue();
        // A watch the actions changed (a device turned off) asks for the next run.
        wanted = rerun || anyWatchMarked();
    }

    private boolean anyWatchMarked() {
        for (int w = 0; w < watchDirty.length; w++) {
            if (watchDirty[w] || watchSignal[w]) {
                return true;
            }
        }
        return false;
    }

    private static void markAll(int[] positions, boolean[] marks) {
        for (int pos : positions) {
            marks[pos] = true;
        }
    }

    /** Runs one node; returns whether its outputs changed or an event fired, so the nodes it feeds must run. */
    private boolean evaluate(CompiledNode<K> node, LogicContext<K> context, long gameTime) {
        int out = node.out;
        int m = node.mem;
        return switch (node.op) {
            case COUNT_CROSSES, SENSOR_CROSSES, LOAD_CROSSES -> {
                long value = watchValue[node.watch];
                boolean changed = set(out + 2, value);
                long was = memory[m];
                long state = Checks.state(node.mode, value, node.threshold, node.switchBack, 0, was);
                // The level output follows the state on every run, so after a load it shows the remembered state.
                changed |= set(out + 1, state == Checks.YES ? 1 : 0);
                if (state == was) {
                    trace.add(node.id, LogicLang.WHY_STILL, value, limit(node, state));
                    yield changed;
                }
                memory[m] = state;
                if (state == Checks.YES) {
                    fire(out);
                    changed = true;
                }
                yield changed;
            }
            case POWER_CHANGES -> {
                long state = watchValue[node.watch] != 0 ? Checks.YES : Checks.NO;
                yield changeEvent(node, state, state == Checks.YES ? 1 : 0);
            }
            case REDSTONE_CHANGES -> {
                long strength = Math.clamp(watchValue[node.watch], 0, 15);
                yield changeEvent(node, strength + 1, strength);
            }
            case DEVICES_CHANGE -> {
                long count = Math.max(0, watchValue[node.watch]);
                boolean changed = set(out + 2, count);
                long was = memory[m];
                memory[m] = count + 1;
                // 0 is "not looked yet": the first look fires nothing, as for change events.
                if (was != 0 && count + 1 > was) {
                    fire(out);
                    changed = true;
                } else if (was != 0 && count + 1 < was) {
                    fire(out + 1);
                    changed = true;
                }
                yield changed;
            }
            case CELLS_FULL -> {
                long full = Math.max(0, watchValue[node.watch]);
                boolean changed = set(out + 1, full > 0 ? 1 : 0);
                changed |= set(out + 2, full);
                long before = Math.max(0, memory[m] - 1);
                memory[m] = full + 1;
                // A cell turning full fires, including one already full on the first look; memory keeps a loaded
                // graph from firing again for the same cells.
                if (full > before) {
                    fire(out);
                    changed = true;
                }
                yield changed;
            }
            case RULE_CALLED -> {
                if (current == graph.callOwner) {
                    yield calls(node, context, gameTime);
                }
                if (watchSignal[node.watch]) {
                    fire(out);
                    yield true;
                }
                yield false;
            }
            case JOB_FINISHED, TERMINAL_OPENED -> {
                if (watchSignal[node.watch]) {
                    fire(out);
                    yield true;
                }
                yield false;
            }
            case ITEM_MOVES, CRAFT_FAILED, POOL_IDLE -> {
                if (!watchSignal[node.watch]) {
                    yield false;
                }
                set(out + 1, watchValue[node.watch]);
                if (node.op == Op.CRAFT_FAILED) {
                    missingItem(node);
                }
                fire(out);
                yield true;
            }
            case PLAYERS_NEAR -> {
                long players = Math.max(0, watchValue[node.watch]);
                boolean changed = set(out + 2, players > 0 ? 1 : 0);
                changed |= set(out + 3, players);
                long was = memory[m];
                memory[m] = players + 1;
                // 0 is "not looked yet": the first look fires nothing, as for labelled devices.
                if (was != 0 && players + 1 > was) {
                    fire(out);
                    changed = true;
                } else if (was != 0 && players + 1 < was) {
                    fire(out + 1);
                    changed = true;
                }
                yield changed;
            }
            case VARIABLE -> {
                long value = watchValue[node.watch];
                boolean changed = set(out, value);
                yield set(out + 1, value != 0 ? 1 : 0) || changed;
            }
            case TASK -> {
                long value = watchValue[node.watch];
                boolean changed = set(out, (value & 1) != 0 ? 1 : 0);
                yield set(out + 1, value >>> 1) || changed;
            }
            case SET_TASK -> {
                // As a shared variable's setter: unwired "when" writes whenever "done" changed.
                if (!wired(node, 0) || fired(node, 0)) {
                    emit(node, values[node.in[1]] != 0 ? 1 : 0);
                }
                yield false;
            }
            case SET_VARIABLE -> {
                // Unwired "when": the node runs when its value changed (or on a recheck), so it writes then.
                if (!wired(node, 0) || fired(node, 0)) {
                    emit(node, node.mode == 1 ? (values[node.in[2]] != 0 ? 1 : 0) : number(node, 1));
                }
                yield false;
            }
            case RUN_RULE -> {
                if (fired(node, 0)) {
                    // The amount argument; the item is the node's (or the pass's).
                    emit(node, number(node, 2));
                }
                yield false;
            }
            case WAIT_UNTIL -> waitUntil(node, gameTime);
            case SEQUENCE -> sequence(node, gameTime);
            case STEP_IS -> set(out, values[node.in[0]] == node.number ? 1 : 0);
            case SCRIPT -> script(node, gameTime);
            case FOR_EACH -> forEach(node, context, gameTime);
            case ITEM_COUNT -> {
                if (node.eachItem || node.itemFrom >= 0) {
                    // In a For each's or a call's branch the item of the pass, or a failed job's missing item: read
                    // now.
                    K item = itemOf(node);
                    cost += READ_COST;
                    reading = node.id;
                    long count = item == null ? 0 : context.readEach(WatchKind.ITEM_COUNT, item, node.label, 0);
                    reading = -1;
                    yield set(out, count);
                }
                yield set(out, watchValue[node.watch]);
            }
            case SENSOR_VALUE -> {
                yield set(out, watchValue[node.watch]);
            }
            case DEVICE_STATE -> {
                yield set(out, watchValue[node.watch] != 0 ? 1 : 0);
            }
            case REDSTONE_STRENGTH -> {
                long strength = Math.clamp(watchValue[node.watch], 0, 15);
                boolean changed = set(out, strength);
                changed |= set(out + 1, strength > 0 ? 1 : 0);
                yield changed;
            }
            case CONSTANT, PARAMETER -> {
                yield set(out, node.number);
            }
            case COMPARE -> {
                long value = values[node.in[0]];
                // A wired limit (M7L2 row 19) has no switch-back; a between check takes its bounds in either order.
                boolean wiredLimit = wired(node, 1);
                long threshold = wiredLimit ? number(node, 1) : node.threshold;
                long upper = node.upper;
                if (wiredLimit && node.mode == Checks.BETWEEN && upper < threshold) {
                    upper = threshold;
                    threshold = node.upper;
                }
                long switchBack = wiredLimit ? threshold : node.switchBack;
                if (node.branch >= 0) {
                    // In a For each's or a call's branch: checked afresh for each item, without switch-back or
                    // memory; "turned yes" fires for an item whose result is yes, "turned no" for one whose result is
                    // no.
                    long state = Checks.state(node.mode, value, threshold, switchBack, upper, Checks.NO);
                    set(out, state == Checks.YES ? 1 : 0);
                    fire(state == Checks.YES ? out + 1 : out + 2);
                    yield true;
                }
                long was = memory[m];
                long state = Checks.state(node.mode, value, threshold, switchBack, upper, was);
                // The result follows the state on every run, so after a load it shows the remembered state.
                boolean changed = set(out, state == Checks.YES ? 1 : 0);
                if (state == was) {
                    trace.add(node.id, LogicLang.WHY_STILL, value, limit(node, state));
                    yield changed;
                }
                memory[m] = state;
                if (state == Checks.YES) {
                    fire(out + 1);
                } else if (was == Checks.YES) {
                    fire(out + 2);
                }
                yield true;
            }
            case AND -> {
                yield set(out, values[node.in[0]] != 0 && values[node.in[1]] != 0 ? 1 : 0);
            }
            case OR -> {
                yield set(out, values[node.in[0]] != 0 || values[node.in[1]] != 0 ? 1 : 0);
            }
            case NOT -> {
                yield set(out, values[node.in[0]] != 0 ? 0 : 1);
            }
            case IF -> {
                if (!fired(node, 0)) {
                    yield false;
                }
                if (values[node.in[1]] != 0) {
                    fire(out);
                } else {
                    fire(out + 1);
                    trace.add(node.id, LogicLang.WHY_IF_NO, 0, 0);
                }
                yield true;
            }
            case LATCH -> {
                boolean set = fired(node, 0);
                if (fired(node, 1)) {
                    memory[m] = 0;
                } else if (set) {
                    memory[m] = 1;
                }
                yield set(out, memory[m]);
            }
            case COUNTER -> {
                long count = memory[m];
                boolean up = fired(node, 0);
                boolean down = fired(node, 1);
                if (fired(node, 2)) {
                    count = 0;
                } else {
                    if (up) {
                        count = Math.min(count + node.number, SettingSpec.MAX_NUMBER);
                    }
                    if (down) {
                        count = Math.max(count - node.number, -SettingSpec.MAX_NUMBER);
                    }
                }
                memory[m] = count;
                yield set(out, count);
            }
            case HOLD -> {
                if (fired(node, 1)) {
                    memory[m] = values[node.in[0]];
                }
                yield set(out, memory[m]);
            }
            case DEVICE_SWITCH -> {
                long state = values[node.in[0]] != 0 ? Checks.YES : Checks.NO;
                if (state != memory[m]) {
                    memory[m] = state;
                    emit(node, state == Checks.YES ? 1 : 0);
                }
                yield false;
            }
            case SET_PRIORITY -> {
                if (fired(node, 0)) {
                    emit(node, Math.clamp(values[node.in[1]], -NodeTypes.MAX_PRIORITY, NodeTypes.MAX_PRIORITY));
                }
                yield false;
            }
            case REDSTONE_OUT -> {
                long strength = values[node.in[0]] != 0 ? Math.clamp(values[node.in[1]], 0, 15) : 0;
                if (strength + 1 != memory[m]) {
                    memory[m] = strength + 1;
                    emit(node, strength);
                }
                yield false;
            }
            case REQUEST_CRAFT -> {
                K item = itemOf(node);
                if (fired(node, 0) && item != null) {
                    cost += READ_COST;
                    if (context.craftRunning(item, node.label)) {
                        trace.add(node.id, LogicLang.WHY_CRAFT_RUNNING, 0, 0);
                    } else {
                        emit(node, Math.clamp(values[node.in[2]], 1, SettingSpec.MAX_NUMBER));
                    }
                }
                yield false;
            }
            case MOVE_ITEMS -> {
                if (fired(node, 0)) {
                    emit(node, Math.clamp(values[node.in[2]], 1, SettingSpec.MAX_NUMBER));
                }
                yield false;
            }
            case POOL_SEND -> {
                if (fired(node, 0)) {
                    emit(node, Math.clamp(values[node.in[1]], 1, SettingSpec.MAX_NUMBER));
                }
                yield false;
            }
            case POOL_STATUS -> {
                long status = watchValue[node.watch];
                int members = Pools.members(status);
                int idle = Pools.idleMembers(status);
                boolean changed = set(out, members);
                changed |= set(out + 1, idle);
                changed |= set(out + 2, members - idle);
                yield set(out + 3, idle > 0 ? 1 : 0) || changed;
            }
            case NOTIFY -> {
                if (fired(node, 0)) {
                    emit(node, 0);
                }
                yield false;
            }
            case DELAY -> {
                boolean done = false;
                long due = memory[m];
                if (due != 0 && due <= gameTime) {
                    memory[m] = 0;
                    fire(out);
                    done = true;
                }
                if (fired(node, 0)) {
                    if (memory[m] != 0) {
                        trace.add(node.id, LogicLang.WHY_DELAY_WAITING, memory[m] - gameTime, node.number);
                    } else {
                        memory[m] = gameTime + node.number;
                    }
                }
                yield done;
            }
            case TIMER -> {
                long due = memory[m];
                if (values[node.in[0]] == 0) {
                    // Off: no wake, and the next interval counts from when it is on again.
                    memory[m] = 0;
                    yield false;
                }
                if (due == 0 || due > gameTime + node.number) {
                    // Just on (or its interval was shortened by an edit): the first one fires an interval from now.
                    memory[m] = gameTime + node.number;
                    yield false;
                }
                if (due > gameTime) {
                    yield false;
                }
                fire(out);
                // A late run fires once and counts the next interval from now: never a burst to catch up.
                long next = due + node.number;
                memory[m] = next > gameTime ? next : gameTime + node.number;
                yield true;
            }
            case GRAPH_START -> {
                if (starting) {
                    fire(out);
                    yield true;
                }
                yield false;
            }
            case DAYTIME_CHANGES -> {
                long state = (watchValue[node.watch] & 1) != 0 ? Checks.YES : Checks.NO;
                long was = memory[m];
                boolean changed = set(out + 2, state == Checks.YES ? 1 : 0);
                if (state == was) {
                    yield changed;
                }
                memory[m] = state;
                if (was == 0) {
                    yield changed;
                }
                fire(state == Checks.YES ? out : out + 1);
                yield true;
            }
            case WEATHER_CHANGES -> {
                long weather = Math.clamp(watchValue[node.watch], 0, 2);
                yield changeEvent(node, weather + 1, weather);
            }
            case DAYTIME -> {
                long packed = watchValue[node.watch];
                boolean changed = set(out, packed & 1);
                yield set(out + 1, Math.max(0, packed >> 1)) || changed;
            }
            case WEATHER -> {
                long weather = Math.clamp(watchValue[node.watch], 0, 2);
                boolean changed = set(out, weather);
                yield set(out + 1, weather > 0 ? 1 : 0) || changed;
            }
            case ENERGY -> {
                long stored = Math.clamp(watchValue[node.watch], 0, SettingSpec.MAX_NUMBER);
                long capacity = Math.clamp(watchValue[node.watch2], 0, SettingSpec.MAX_NUMBER);
                boolean changed = set(out, stored);
                changed |= set(out + 1, capacity);
                yield set(out + 2, percent(stored, capacity)) || changed;
            }
            case FREE_SPACE -> {
                long total = Math.clamp(watchValue[node.watch2], 0, SettingSpec.MAX_NUMBER);
                long used = Math.clamp(watchValue[node.watch], 0, total);
                boolean changed = set(out, total - used);
                changed |= set(out + 1, total);
                yield set(out + 2, percent(used, total)) || changed;
            }
            case CRAFT_STATUS -> {
                long jobs = Math.max(0, watchValue[node.watch]);
                boolean changed = set(out, jobs & 1);
                changed |= set(out + 1, jobs >>> 1);
                long cpus = Math.max(0, watchValue[node.watch2]);
                changed |= set(out + 2, cpus & 0xFFFF_FFFFL);
                yield set(out + 3, cpus >>> 32) || changed;
            }
            case STOCK_STATUS -> {
                if (node.eachItem) {
                    // In a For each's branch: the keepers' rule for the item of the pass and its count, read now.
                    K item = itemOf(node);
                    if (item == null) {
                        yield stockStatus(node, 0, 0, 0, 0, 0);
                    }
                    cost += 5 * READ_COST;
                    yield stockStatus(node, context.readEach(WatchKind.STOCK, item, node.label, WatchKind.STOCK_KEEP),
                            context.readEach(WatchKind.STOCK, item, node.label, WatchKind.STOCK_RESTART),
                            context.readEach(WatchKind.STOCK, item, node.label, WatchKind.STOCK_BATCH),
                            context.readEach(WatchKind.STOCK, item, node.label, WatchKind.STOCK_FLAGS),
                            context.readEach(WatchKind.ITEM_COUNT, item, "", 0));
                }
                yield stockStatus(node, watchValue[node.watch], watchValue[node.more[0]], watchValue[node.more[1]],
                        watchValue[node.more[2]], watchValue[node.more[3]]);
            }
            case STOCK_SHORT -> {
                long state = (watchValue[node.watch] & WatchKind.STOCK_SHORT) != 0 ? Checks.YES : Checks.NO;
                boolean changed = set(out + 2, state == Checks.YES ? 1 : 0);
                long was = memory[m];
                if (state == was) {
                    yield changed;
                }
                memory[m] = state;
                // A rule already short on the first look fires, like a crossing past its limit; memory keeps a loaded
                // graph from firing again. "Recovered" needs a short look before it.
                if (state == Checks.YES) {
                    fire(out);
                    changed = true;
                } else if (was == Checks.YES) {
                    fire(out + 1);
                    changed = true;
                }
                yield changed;
            }
            case STOCK_SWITCH -> {
                long state = values[node.in[0]] != 0 ? Checks.YES : Checks.NO;
                if (state != memory[m]) {
                    memory[m] = state;
                    emit(node, state == Checks.YES ? 1 : 0);
                }
                yield false;
            }
            case STOCK_SET -> {
                // Unwired "when": the node runs when its amount changed (or on a recheck), so it sets it then.
                if (!wired(node, 0) || fired(node, 0)) {
                    emit(node, Math.max(0, number(node, 1)));
                }
                yield false;
            }
            case SHED_LEVEL -> {
                long level = Math.clamp(watchValue[node.watch], 0, 4);
                boolean changed = set(out, level);
                yield set(out + 1, level > 0 ? 1 : 0) || changed;
            }
            case SHEDDING_CHANGES -> {
                long level = Math.clamp(watchValue[node.watch], 0, 4);
                yield changeEvent(node, level + 1, level);
            }
            case SET_POWER_PRIORITY -> {
                if (fired(node, 0)) {
                    emit(node, Math.clamp(values[node.in[1]], 1, 5));
                }
                yield false;
            }
            case MATH -> {
                yield set(out, math(node, number(node, 0), number(node, 1)));
            }
            case BETWEEN -> {
                long value = values[node.in[0]];
                long low = values[node.in[1]];
                long high = values[node.in[2]];
                if (low > high) {
                    long swap = low;
                    low = high;
                    high = swap;
                }
                boolean aboveLow = (node.flags & CompiledNode.INCLUDE_LOW) != 0 ? value >= low : value > low;
                boolean belowHigh = (node.flags & CompiledNode.INCLUDE_HIGH) != 0 ? value <= high : value < high;
                yield set(out, aboveLow && belowHigh ? 1 : 0);
            }
            case SELECT -> {
                yield set(out, values[node.in[0]] != 0 ? values[node.in[1]] : values[node.in[2]]);
            }
            case XOR -> {
                yield set(out, (values[node.in[0]] != 0) != (values[node.in[1]] != 0) ? 1 : 0);
            }
            case TOGGLE -> {
                boolean flip = fired(node, 0);
                if (fired(node, 1)) {
                    memory[m] = 0;
                } else if (flip) {
                    memory[m] = memory[m] != 0 ? 0 : 1;
                }
                yield set(out, memory[m]);
            }
            case EDGE -> {
                long state = values[node.in[0]] != 0 ? Checks.YES : Checks.NO;
                long was = memory[m];
                if (state == was) {
                    yield false;
                }
                memory[m] = state;
                // 0 is "not looked yet": the first look fires nothing, so a load does not fire again either.
                if (was == 0) {
                    yield false;
                }
                fire(state == Checks.YES ? out : out + 1);
                yield true;
            }
            case STATS -> stats(node, gameTime);
            case CHANGE_RATE -> {
                long value = number(node, 0);
                long lastTime = memory[m + RATE_TIME];
                boolean take = wired(node, 1) ? fired(node, 1) : lastTime == 0 || value != memory[m + RATE_LAST];
                if (take) {
                    if (lastTime == 0) {
                        memory[m + RATE_VALUE] = 0;
                    } else if (gameTime > lastTime - 1) {
                        long ticks = gameTime - (lastTime - 1);
                        long diff = Math.clamp(value - memory[m + RATE_LAST], -SAFE, SAFE);
                        long rate = diff / ticks * NodeTypes.TICKS_PER_SECOND
                                + diff % ticks * NodeTypes.TICKS_PER_SECOND / ticks;
                        memory[m + RATE_VALUE] = Math.clamp(rate, -SettingSpec.MAX_NUMBER, SettingSpec.MAX_NUMBER);
                    }
                    memory[m + RATE_LAST] = value;
                    memory[m + RATE_TIME] = gameTime + 1;
                }
                yield set(out, memory[m + RATE_VALUE]);
            }
            case CANCEL_CRAFT, SET_FILTER -> {
                if (fired(node, 0) && (node.op == Op.SET_FILTER || itemOf(node) != null)) {
                    emit(node, 0);
                }
                yield false;
            }
            case PULSE_REDSTONE -> {
                long due = memory[m];
                if (due != 0 && due <= gameTime) {
                    memory[m] = 0;
                    due = 0;
                    emit(node, 0);
                }
                if (fired(node, 0)) {
                    if (due != 0 && node.mode == 1) {
                        trace.add(node.id, LogicLang.WHY_PULSE_ON, due - gameTime, node.number);
                    } else {
                        // A new pulse, or one started again: on (again) and off once its ticks have passed.
                        memory[m] = gameTime + node.number;
                        long strength = Math.clamp(values[node.in[1]], 0, 15);
                        memory[m + 1] = strength;
                        // Strength + 1, so a pulse of strength 0 is still told apart from the end of a pulse (0).
                        emit(node, strength + 1);
                    }
                }
                yield false;
            }
            case WRITE_DISPLAY -> {
                // Unwired "when": the node runs when a or b changed (or on a recheck), so it writes then.
                if (!wired(node, 0) || fired(node, 0)) {
                    emit(node, 0);
                }
                yield false;
            }
            case COOLDOWN -> {
                if (!fired(node, 0)) {
                    yield false;
                }
                long last = memory[m];
                if (last != 0 && gameTime < last - 1 + node.number) {
                    trace.add(node.id, LogicLang.WHY_COOLDOWN, last - 1 + node.number - gameTime, node.number);
                    yield false;
                }
                memory[m] = gameTime + 1;
                fire(out);
                yield true;
            }
        };
    }

    /** Number input {@code port} of {@code node}, kept within the number range. */
    private long number(CompiledNode<K> node, int port) {
        return Math.clamp(values[node.in[port]], -SettingSpec.MAX_NUMBER, SettingSpec.MAX_NUMBER);
    }

    /** Whether a wire goes into input {@code port}: unwired inputs read value slots past the outputs. */
    private boolean wired(CompiledNode<K> node, int port) {
        return node.in[port] < graph.outputSlots;
    }

    /** Math node {@code node} on {@code a} and {@code b} (each within the number range); clamped to it. */
    private long math(CompiledNode<K> node, long a, long b) {
        long result = switch (node.mode) {
            case 0 -> a + b;
            case 1 -> a - b;
            case 2 -> {
                long high = Math.multiplyHigh(a, b);
                long low = a * b;
                // The product does not fit a long when the high half is more than the low half's sign.
                yield high == (low >> 63) ? low : (high < 0 ? Long.MIN_VALUE : Long.MAX_VALUE);
            }
            case 3, 6 -> {
                if (b == 0) {
                    trace.add(node.id, LogicLang.WHY_DIVIDE_ZERO, a, 0);
                    yield 0;
                }
                yield node.mode == 3 ? a / b : Math.floorMod(a, b);
            }
            case 4 -> Math.min(a, b);
            default -> Math.max(a, b);
        };
        return Math.clamp(result, -SettingSpec.MAX_NUMBER, SettingSpec.MAX_NUMBER);
    }

    /**
     * A statistics node: takes a sample when "sample" fired (unwired: when the value changed), then sets average,
     * lowest and highest over its window. A seconds window keeps one wake for when its oldest sample leaves it. With no
     * sample in the window, all three are the value now. Allocates nothing; at most {@link NodeTypes#STATS_RING} steps.
     */
    private boolean stats(CompiledNode<K> node, long gameTime) {
        int m = node.mem;
        long value = number(node, 0);
        int ring = NodeTypes.STATS_RING;
        // Clamped, so memory loaded from a damaged save cannot point outside the ring.
        int count = (int) Math.clamp(memory[m + STATS_COUNT], 0, ring);
        int head = (int) Math.floorMod(memory[m + STATS_HEAD], (long) ring);
        int newest = Math.floorMod(head - 1, ring);
        boolean take = wired(node, 1) ? fired(node, 1) : count == 0 || value != memory[m + STATS_VALUES + newest];
        if (take) {
            memory[m + STATS_VALUES + head] = value;
            memory[m + STATS_TIMES + head] = gameTime;
            head = (head + 1) % ring;
            count = Math.min(count + 1, ring);
            memory[m + STATS_HEAD] = head;
            memory[m + STATS_COUNT] = count;
        }
        boolean bySamples = node.mode == 0;
        int window = bySamples ? (int) Math.min(node.number, count) : count;
        long since = gameTime - node.number;
        int used = 0;
        long quotient = 0;
        long remainder = 0;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        long oldest = 0;
        // First pass counts the samples in the window, newest first, so the average divides by the right number.
        for (int i = 0; i < window; i++) {
            int at = Math.floorMod(head - 1 - i, ring);
            if (!bySamples && memory[m + STATS_TIMES + at] <= since) {
                break;
            }
            used++;
            oldest = memory[m + STATS_TIMES + at];
        }
        for (int i = 0; i < used; i++) {
            long sample = memory[m + STATS_VALUES + Math.floorMod(head - 1 - i, ring)];
            quotient += sample / used;
            remainder += sample % used;
            min = Math.min(min, sample);
            max = Math.max(max, sample);
        }
        memory[m + STATS_DUE] = !bySamples && used > 0 ? oldest + node.number : 0;
        if (used == 0) {
            min = value;
            max = value;
        }
        long average = used == 0 ? value : quotient + remainder / used;
        int out = node.out;
        boolean changed = set(out, average);
        changed |= set(out + 1, min);
        return set(out + 2, max) || changed;
    }

    /**
     * A wait-until node: held events whose timeout passed leave through "gave up" (one event for all of them); while
     * the condition is yes the oldest held event leaves through "let through", one per run, and the node runs again
     * next tick for the next one; a new event passes at once when the condition is yes and none is held, else it is
     * held, or dropped when the node holds its most. Memory: the count, then each held event's timeout tick (0: none),
     * oldest first. Allocates nothing; at most {@link NodeTypes#MAX_HELD} steps.
     */
    private boolean waitUntil(CompiledNode<K> node, long gameTime) {
        int m = node.mem;
        int out = node.out;
        int held = (int) Math.clamp(memory[m], 0, NodeTypes.MAX_HELD);
        int most = (int) Math.clamp(node.upper, 1, NodeTypes.MAX_HELD);
        boolean changed = false;
        int kept = 0;
        boolean gaveUp = false;
        for (int i = 0; i < held; i++) {
            long timeout = memory[m + 1 + i];
            if (timeout != 0 && timeout <= gameTime) {
                gaveUp = true;
            } else {
                memory[m + 1 + kept++] = timeout;
            }
        }
        if (gaveUp) {
            fire(out + 1);
            changed = true;
        }
        int count = kept;
        boolean yes = values[node.in[1]] != 0;
        boolean passed = false;
        if (yes && count > 0) {
            for (int i = 1; i < count; i++) {
                memory[m + i] = memory[m + 1 + i];
            }
            count--;
            fire(out);
            passed = true;
            changed = true;
        }
        if (fired(node, 0)) {
            if (yes && !passed) {
                fire(out);
                passed = true;
                changed = true;
            } else if (count < most) {
                memory[m + 1 + count] = node.number > 0 ? gameTime + node.number : 0;
                count++;
            } else {
                trace.add(node.id, LogicLang.WHY_WAIT_FULL, count, 0);
            }
        }
        if (yes && count > 0) {
            // More are held: the next one leaves next run.
            rerun = true;
            nextDirty[current] = true;
        }
        for (int i = count; i < held; i++) {
            memory[m + 1 + i] = 0;
        }
        memory[m] = count;
        return set(out + 2, count) || changed;
    }

    /**
     * A step sequence ({@link Sequences}): moves at most one step per run, by reset, then the lowest "go to" input that
     * is yes, then the current step's "go on" input, then its time limit. Entering a step fires its "entered" event,
     * sets its time limit's due tick (one scheduled wake) and asks for the next run, which looks at the new step's own
     * inputs; so a chain of yes conditions moves one step per tick and never loops within a run, and stops after one
     * round until an input changes (a "go on" input counts when it turned yes, or once right after entering its step).
     * Before its first run (memory 0) it enters step 1, or the step a "go to" input holds; a step past the count (after
     * an edit made it shorter) starts again the same way. Memory: the step, the due tick, the "go on" inputs last seen
     * and the chain length ({@link Sequences}). Allocates nothing.
     */
    private boolean sequence(CompiledNode<K> node, long gameTime) {
        int m = node.mem;
        int out = node.out;
        int steps = (int) node.number;
        long step = memory[m + Sequences.MEM_STEP];
        boolean reset = fired(node, 0);
        int held = 0;
        for (int k = 1; k <= steps; k++) {
            if (values[node.in[Sequences.goIndex(k)]] != 0) {
                held = k;
                break;
            }
        }
        // Which "go on" inputs are yes now; one counts when it turned yes since the last look, or once on entering.
        long yes = 0;
        for (int k = 1; k <= steps; k++) {
            if (values[node.in[Sequences.nextIndex(k)]] != 0) {
                yes |= 1L << (k - 1);
            }
        }
        long seen = memory[m + Sequences.MEM_SEEN];
        boolean entering = (seen & Sequences.ENTERING) != 0;
        long chain = memory[m + Sequences.MEM_CHAIN];
        memory[m + Sequences.MEM_SEEN] = yes;
        int target = 0;
        boolean timeUp = false;
        boolean chained = false;
        if (step < 1 || step > steps) {
            target = held != 0 ? held : 1;
        } else if (reset) {
            target = 1;
        } else if (held != 0) {
            // Held where it is (or moved there): the step's own input and time limit wait.
            target = held != step ? held : 0;
        } else if ((yes & 1L << (step - 1)) != 0 && ((seen & 1L << (step - 1)) == 0 || (entering && chain < steps))) {
            // Turned yes, or already yes on entering; a whole round of the latter waits for an input to change.
            target = (int) step % steps + 1;
            chained = (seen & 1L << (step - 1)) != 0;
        } else {
            long limit = node.table[(int) step - 1];
            long due = memory[m + Sequences.MEM_DUE];
            if (limit == 0) {
                // No limit (any more, after an edit): no wake.
                memory[m + Sequences.MEM_DUE] = 0;
            } else if (due == 0 || due > gameTime + limit) {
                // A limit set or shortened by an edit counts from now.
                memory[m + Sequences.MEM_DUE] = gameTime + limit;
            } else if (due <= gameTime) {
                target = (int) step % steps + 1;
                timeUp = true;
            }
        }
        if (target == 0) {
            return set(out, step);
        }
        if (step < 1 || step > steps) {
            trace.add(node.id, LogicLang.WHY_STEP_START, target, 0);
        } else {
            trace.add(node.id, LogicLang.WHY_STEP_MOVED, step, target);
        }
        memory[m + Sequences.MEM_STEP] = target;
        memory[m + Sequences.MEM_SEEN] = yes | Sequences.ENTERING;
        memory[m + Sequences.MEM_CHAIN] = chained ? chain + 1 : 0;
        long limit = node.table[target - 1];
        memory[m + Sequences.MEM_DUE] = limit > 0 ? gameTime + limit : 0;
        set(out, target);
        if (timeUp) {
            fire(out + 1);
        }
        fire(out + Sequences.enteredIndex(target));
        // The next run looks at the new step's inputs, which may already say to go on.
        rerun = true;
        nextDirty[current] = true;
        return true;
    }

    /**
     * A stock status node: the first matching rule's amounts, the item's count, whether the count is below where the
     * rule starts filling ({@code StockRule#shouldStart}: the restart threshold, or keep when that is 0), whether a job
     * is out and whether the rule is switched on.
     */
    private boolean stockStatus(CompiledNode<K> node, long keepRead, long restartRead, long batchRead, long flags,
            long countRead) {
        int out = node.out;
        long keep = Math.max(0, keepRead);
        long restart = Math.max(0, restartRead);
        long batch = Math.max(0, batchRead);
        long count = Math.max(0, countRead);
        boolean found = (flags & WatchKind.STOCK_FOUND) != 0;
        boolean changed = set(out, keep);
        changed |= set(out + 1, restart);
        changed |= set(out + 2, batch);
        changed |= set(out + 3, count);
        changed |= set(out + 4, found && count < (restart > 0 ? restart : keep) ? 1 : 0);
        changed |= set(out + 5, (flags & WatchKind.STOCK_RUNNING) != 0 ? 1 : 0);
        return set(out + 6, (flags & WatchKind.STOCK_ON) != 0 ? 1 : 0) || changed;
    }

    /**
     * The item {@code node} works on: the item of the running pass for a node fed by a For each's "item", the call's
     * item for a node fed by a parameter in a call branch, the missing item of this run for a node fed by a
     * crafting-job-failed node's "missing item" (null in a run where it did not fire), else its own.
     */
    @SuppressWarnings("unchecked") // pass and node items only hold items of the graph, which are K
    private @Nullable K itemOf(CompiledNode<K> node) {
        if (node.eachItem) {
            return node.param >= 0 ? (K) passItems[node.param] : currentItem;
        }
        int from = node.itemFrom;
        if (from >= 0) {
            return nodeItemRun[from] == run ? (K) nodeItem[from] : null;
        }
        return node.item;
    }

    /**
     * Queues a call from another rule (M7L2 row 19): the next run runs the call branch once for it, with item parameter
     * {@code itemParam} set to {@code item} and number parameter {@code numberParam} to {@code number} (-1: not
     * filled), the other parameters at their own values. Calls queued before a run each get their pass, in order; past
     * {@link #MAX_CALLS} waiting, a call is refused. Only for a {@link CompiledGraph#callable()} graph. Returns false
     * when refused. Allocates only while its arrays grow.
     */
    public boolean call(int itemParam, @Nullable K item, int numberParam, long number) {
        int owner = graph.callOwner;
        if (owner < 0 || callCount >= MAX_CALLS) {
            return false;
        }
        if (callCount == callItem.length) {
            int size = Math.max(4, callCount * 2);
            callItem = java.util.Arrays.copyOf(callItem, size);
            callItemParam = java.util.Arrays.copyOf(callItemParam, size);
            callNumberParam = java.util.Arrays.copyOf(callNumberParam, size);
            callNumber = java.util.Arrays.copyOf(callNumber, size);
        }
        boolean itemFits = itemParam >= 0 && itemParam < graph.params.size()
                && graph.params.kinds[itemParam] == Parameters.ITEM && item != null;
        boolean numberFits = numberParam >= 0 && numberParam < graph.params.size()
                && graph.params.kinds[numberParam] == Parameters.NUMBER;
        callItemParam[callCount] = itemFits ? itemParam : -1;
        callItem[callCount] = itemFits ? item : null;
        callNumberParam[callCount] = numberFits ? numberParam : -1;
        callNumber[callCount] = Math.clamp(number, -SettingSpec.MAX_NUMBER, SettingSpec.MAX_NUMBER);
        callCount++;
        dirty[owner] = true;
        wanted = true;
        return true;
    }

    /** Calls waiting for the next run. */
    public int callsWaiting() {
        return callCount;
    }

    /**
     * A call branch's "Run by another rule" node (M7L2 row 19): runs the branch once per waiting call, oldest first,
     * with the parameters set to the call's values, then puts the parameters back. Shares the run's item limit with For
     * each nodes and stops when the run's cost reaches the checks the team had left or its actions the action points
     * the team had left; calls left over wait for the next run. Allocates nothing once its arrays have grown.
     */
    private boolean calls(CompiledNode<K> node, LogicContext<K> context, long gameTime) {
        if (callCount == 0) {
            return false;
        }
        if (eachLeft < 0) {
            eachLeft = Math.clamp(context.maxEachItems(), 1, ForEach.HARD_MAX_ITEMS);
        }
        CompiledGraph.Params params = graph.params;
        if (passItems.length < params.size()) {
            passItems = new Object[params.size()];
        }
        long left = context.checksLeft();
        long pointsLeft = context.actionPointsLeft();
        int queuedBefore = eachCount;
        int served = 0;
        boolean capped = false;
        boolean broke = false;
        boolean noPoints = false;
        int self = current;
        trace.mute(true);
        iterating = true;
        try {
            for (int b : node.body) {
                dirty[b] = false;
            }
            while (served < callCount) {
                if (eachLeft == 0) {
                    capped = true;
                    break;
                }
                if (cost >= left) {
                    broke = true;
                    break;
                }
                if (points >= pointsLeft) {
                    noPoints = true;
                    break;
                }
                eachLeft--;
                for (int k = 0; k < params.size(); k++) {
                    values[params.slots[k]] = k == callNumberParam[served] ? callNumber[served] : params.numbers[k];
                    passItems[k] = k == callItemParam[served] ? callItem[served] : params.items[k];
                }
                served++;
                for (int slot : node.bodyEvents) {
                    values[slot] = 0;
                }
                fire(node.out);
                // The parameters may differ from the last call: the whole branch runs again.
                markAll(node.body, dirty);
                body(node, context, gameTime);
            }
        } finally {
            current = self;
            iterating = false;
            trace.mute(false);
            flushReadWhy();
            for (int k = 0; k < params.size(); k++) {
                values[params.slots[k]] = params.numbers[k];
                passItems[k] = null;
            }
        }
        // Calls served leave the queue; the rest wait for the next run.
        int rest = callCount - served;
        System.arraycopy(callItemParam, served, callItemParam, 0, rest);
        System.arraycopy(callItem, served, callItem, 0, rest);
        System.arraycopy(callNumberParam, served, callNumberParam, 0, rest);
        System.arraycopy(callNumber, served, callNumber, 0, rest);
        java.util.Arrays.fill(callItem, rest, callCount, null);
        callCount = rest;
        if (rest > 0) {
            rerun = true;
            nextDirty[self] = true;
        }
        trace.add(node.id, LogicLang.WHY_CALLS, served, eachCount - queuedBefore);
        if (capped) {
            trace.add(node.id, LogicLang.WHY_EACH_CAP, served,
                    Math.clamp(context.maxEachItems(), 1, ForEach.HARD_MAX_ITEMS));
        } else if (broke) {
            trace.add(node.id, LogicLang.WHY_EACH_BUDGET, served, 0);
        } else if (noPoints) {
            trace.add(node.id, LogicLang.WHY_EACH_POINTS, served, 0);
        }
        return true;
    }

    /**
     * A crafting-job-failed node fired: keeps the job's first missing item for the nodes its "missing item" feeds and
     * for a notice's {item} in this run, and adds the why line listing every missing item.
     */
    @SuppressWarnings("unchecked") // watchItem only holds the signals' items, which are K
    private void missingItem(CompiledNode<K> node) {
        K item = (K) watchItem[node.watch];
        nodeItem[current] = item;
        nodeItemRun[current] = run;
        if (item != null) {
            if (runItemRun != run) {
                runItem = item;
                runItemRun = run;
            }
            trace.add(node.id, LogicLang.WHY_MISSING, watchValue[node.watch], 0, watchText[node.watch]);
        }
    }

    /**
     * A For each node ({@link ForEach}): when "when" fired, gathers the items of its source (a tag's items through the
     * context, the list's entries with tags in it standing for their items, or the labelled stock keepers' items),
     * drops repeats, and runs its branch once per item in evaluation order, with "each" fired and the item handed to
     * the nodes that read "item". Each pass clears the branch's events first, so an event of one item never counts for
     * the next. Stops at the run's item limit, when the run's cost reaches the checks the team had left, or when its
     * actions reach the action points the team had left; then sets "items checked", fires "done" and adds one why line
     * for all passes. Allocates nothing once its lists have grown.
     */
    private boolean forEach(CompiledNode<K> node, LogicContext<K> context, long gameTime) {
        if (!fired(node, 0)) {
            return false;
        }
        int out = node.out;
        if (eachLeft < 0) {
            eachLeft = Math.clamp(context.maxEachItems(), 1, ForEach.HARD_MAX_ITEMS);
        }
        // One past the limit, so the why line can say that more were left.
        int most = eachLeft + 1;
        List<K> items = eachItems;
        items.clear();
        // A new round of the seen table: what an earlier For each gathered counts as unseen again.
        seenRound++;
        switch (node.mode) {
            case ForEach.SOURCE_TAG -> {
                K tag = node.item;
                if (tag != null) {
                    cost += READ_COST;
                    gather(context, tag, most);
                }
            }
            case ForEach.SOURCE_LIST -> {
                for (int i = 0; i < node.items.size() && items.size() < most; i++) {
                    gather(context, node.items.get(i), most);
                }
            }
            default -> {
                cost += READ_COST;
                eachScratch.clear();
                context.stockItems(node.label, eachScratch, most);
                keepNew(most);
            }
        }
        long left = context.checksLeft();
        long pointsLeft = context.actionPointsLeft();
        int queuedBefore = eachCount;
        int checked = 0;
        boolean capped = false;
        boolean broke = false;
        boolean noPoints = false;
        int self = current;
        trace.mute(true);
        iterating = true;
        try {
            for (int b : node.body) {
                dirty[b] = false;
            }
            for (int i = 0; i < items.size(); i++) {
                if (eachLeft == 0) {
                    capped = true;
                    break;
                }
                if (cost >= left) {
                    broke = true;
                    break;
                }
                if (points >= pointsLeft) {
                    noPoints = true;
                    break;
                }
                eachLeft--;
                checked++;
                pass(node, items.get(i), i, context, gameTime);
            }
        } finally {
            // Also when a node throws: later runs must not send their actions to the per-item queue.
            current = self;
            iterating = false;
            currentItem = null;
            trace.mute(false);
            flushReadWhy();
        }
        set(out + ForEach.OUT_CHECKED, checked);
        fire(out + ForEach.OUT_DONE);
        trace.add(node.id, LogicLang.WHY_EACH, checked, eachCount - queuedBefore);
        if (capped) {
            trace.add(node.id, LogicLang.WHY_EACH_CAP, checked,
                    Math.clamp(context.maxEachItems(), 1, ForEach.HARD_MAX_ITEMS));
        } else if (broke) {
            trace.add(node.id, LogicLang.WHY_EACH_BUDGET, checked, 0);
        } else if (noPoints) {
            trace.add(node.id, LogicLang.WHY_EACH_POINTS, checked, 0);
        }
        return true;
    }

    /** One pass of For each {@code node} for {@code item}, the {@code index}th: its branch runs for the item. */
    private void pass(CompiledNode<K> node, K item, int index, LogicContext<K> context, long gameTime) {
        int out = node.out;
        currentItem = item;
        for (int slot : node.bodyEvents) {
            values[slot] = 0;
        }
        values[out + ForEach.OUT_ITEM] = index + 1L;
        fire(out + ForEach.OUT_EACH);
        markAll(node.entry, dirty);
        body(node, context, gameTime);
    }

    /** Runs the branch of For each or call node {@code node} once, from the nodes its pass marked. */
    private void body(CompiledNode<K> node, LogicContext<K> context, long gameTime) {
        for (int b : node.body) {
            // A compare fires "turned yes" or "turned no" for every item, also one whose count equals the last.
            dirty[b] |= nodes[b].op == Op.COMPARE;
        }
        for (int b : node.body) {
            if (!dirty[b]) {
                continue;
            }
            dirty[b] = false;
            CompiledNode<K> inner = nodes[b];
            cost += inner.type.cost();
            current = b;
            if (evaluate(inner, context, gameTime)) {
                markAll(inner.down, dirty);
                if (inner.lateDown.length > 0) {
                    markAll(inner.lateDown, nextDirty);
                    rerun = true;
                }
            }
        }
    }

    /** Adds the items {@code key} stands for (a tag's items, or the item) to the run's list, without repeats. */
    private void gather(LogicContext<K> context, K key, int most) {
        eachScratch.clear();
        // Room for repeats of items the list holds already.
        context.eachItem(key, eachScratch, most + eachItems.size());
        keepNew(most);
    }

    /** Moves the scratch list's items the run's list does not hold yet into it, up to {@code most}. */
    private void keepNew(int most) {
        List<K> items = eachItems;
        for (int i = 0; i < eachScratch.size() && items.size() < most; i++) {
            K item = eachScratch.get(i);
            if (firstSeen(item)) {
                items.add(item);
            }
        }
        eachScratch.clear();
    }

    /**
     * Whether {@code item} is new to the For each gathering now ({@link #seenRound}), marking it seen: one table probe,
     * so gathering stays linear in the items. The table is made at the first For each and reused.
     */
    private boolean firstSeen(K item) {
        if (seenKey.length == 0) {
            seenKey = new Object[SEEN_SIZE];
            seenMark = new long[SEEN_SIZE];
        }
        int mask = SEEN_SIZE - 1;
        int slot = spread(item.hashCode()) & mask;
        while (seenMark[slot] == seenRound) {
            if (item.equals(seenKey[slot])) {
                return false;
            }
            slot = (slot + 1) & mask;
        }
        seenMark[slot] = seenRound;
        seenKey[slot] = item;
        return true;
    }

    /** For change events: fires when {@code state} differs from the remembered one, but not on the first look. */
    private boolean changeEvent(CompiledNode<K> node, long state, long output) {
        long was = memory[node.mem];
        boolean changed = set(node.out + 1, output);
        if (state == was) {
            return changed;
        }
        memory[node.mem] = state;
        if (was != 0) {
            fire(node.out);
            changed = true;
        }
        return changed;
    }

    private static <K> long limit(CompiledNode<K> node, long state) {
        return state == Checks.YES && node.mode != Checks.BETWEEN && node.mode != Checks.EQUAL
                ? node.switchBack
                : node.threshold;
    }

    /**
     * {@code part} of {@code whole} in whole percent, rounded down and kept within 0 to 100; 0 when {@code whole} is 0
     * (an empty network, a label no device carries). Both are at least 0; no overflow for any long.
     */
    static long percent(long part, long whole) {
        if (whole <= 0 || part <= 0) {
            return 0;
        }
        if (part >= whole) {
            return 100;
        }
        // part < whole here, so a part too large to multiply has a whole of at least 100.
        return part <= Long.MAX_VALUE / 100 ? part * 100 / whole : Math.min(99, part / (whole / 100));
    }

    /**
     * Runs a script node ({@link ScriptNodes}). A script that throws stops the run: {@link #scriptFailedNode} and
     * {@link #scriptFailure} say which and why. Script code allocates as it likes; this adds nothing.
     */
    private boolean script(CompiledNode<K> node, long gameTime) {
        ScriptNodeLogic logic = node.type.script();
        if (logic == null) {
            // A missing script's stand-in never compiles, so it never runs.
            return false;
        }
        Script context = scriptContext;
        context.node = node;
        context.time = gameTime;
        context.changed = false;
        try {
            logic.evaluate(context);
        } catch (RuntimeException e) {
            // Any failure of pack code (a script error, a wrong port name) pauses this graph, never the server.
            scriptFailedNode = node.id;
            String message = e.getMessage();
            scriptFailure = message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
            trace.add(node.id, LogicLang.WHY_SCRIPT_FAILED, 0, 0);
        } catch (StackOverflowError e) {
            // Endless recursion in a script unwinds here with the stack free again, so it is safe to go on; errors
            // that leave the JVM unsure (out of memory, linkage) are not caught.
            scriptFailedNode = node.id;
            scriptFailure = "The script called itself too deeply (stack overflow)";
            trace.add(node.id, LogicLang.WHY_SCRIPT_FAILED, 0, 0);
        } finally {
            context.node = null;
        }
        return context.changed;
    }

    /** The id of the script node that failed in the last run, or -1 when none did. */
    public int scriptFailedNode() {
        return scriptFailedNode;
    }

    /** The failed script node's error message from the last run; empty when none failed. */
    public String scriptFailure() {
        return scriptFailure;
    }

    /** The script context, bound to one script node while it runs. */
    private final class Script extends ScriptNodeContext {
        @org.jspecify.annotations.Nullable
        CompiledNode<K> node;
        long time;
        boolean changed;

        private CompiledNode<K> bound() {
            CompiledNode<K> current = node;
            if (current == null) {
                throw new IllegalStateException("A script node's context was used outside its run");
            }
            return current;
        }

        @Override
        NodeType type() {
            return bound().type;
        }

        @Override
        long inputValue(int port) {
            return values[bound().in[port]];
        }

        @Override
        boolean inputFired(int port) {
            return LogicEvaluator.this.fired(bound(), port);
        }

        @Override
        void setOutput(int port, long value) {
            changed |= LogicEvaluator.this.set(bound().out + port, value);
        }

        @Override
        void fireOutput(int port) {
            LogicEvaluator.this.fire(bound().out + port);
            changed = true;
        }

        @Override
        long memoryAt(int slot) {
            return memory[bound().mem + slot];
        }

        @Override
        void memorySet(int slot, long value) {
            memory[bound().mem + slot] = value;
        }

        @Override
        public long gameTime() {
            return time;
        }
    }

    private boolean set(int slot, long value) {
        if (values[slot] == value) {
            return false;
        }
        values[slot] = value;
        return true;
    }

    private void fire(int slot) {
        values[slot] = run;
        fireCount[slot]++;
    }

    /**
     * Whether the event into input {@code port} fired this run, or, for a wire that closes a loop, in an earlier run
     * the node has not taken yet. Counting rather than comparing run ids means an event is never lost when the source
     * fires again before the node reads it; each call takes at most one event, and nodes call it once per input.
     */
    private boolean fired(CompiledNode<K> node, int port) {
        int slot = node.in[port];
        if (!node.late[port]) {
            return values[slot] == run;
        }
        // A slot fires at most once per run; leave this run's event for the next run.
        long before = fireCount[slot] - (values[slot] == run ? 1 : 0);
        int seen = inputBase[current] + port;
        if (before > lateSeen[seen]) {
            lateSeen[seen]++;
            if (lateSeen[seen] < before) {
                // More are waiting: run again next tick to take them one at a time.
                rerun = true;
                nextDirty[current] = true;
            }
            return true;
        }
        return false;
    }

    private void emit(CompiledNode<K> node, long value) {
        if (iterating) {
            emitEach(node, value);
            return;
        }
        int slot = node.action;
        if (actionRun[slot] == run) {
            if (node.op.replaces()) {
                actionValue[slot] = value;
                actionEmitter[slot] = current;
            } else {
                trace.add(node.id, LogicLang.WHY_ALREADY_DONE, 0, 0);
            }
            return;
        }
        actionRun[slot] = run;
        actionValue[slot] = value;
        actionEmitter[slot] = current;
        touched[touchedCount++] = slot;
        points += node.type.actionPoints();
    }

    /**
     * An action of a For each's pass: kept per item, so each item's craft or move is its own action. A second one of
     * the same action for the same item in the run is dropped (one-shot work), as outside a For each.
     */
    private void emitEach(CompiledNode<K> node, long value) {
        @Nullable
        K found = itemOf(node);
        Object item = found == null ? NO_ITEM : found;
        if (eachCount == eachNode.length) {
            int size = Math.max(16, eachCount * 2);
            eachNode = java.util.Arrays.copyOf(eachNode, size);
            eachItem = java.util.Arrays.copyOf(eachItem, size);
            eachValue = java.util.Arrays.copyOf(eachValue, size);
            // The lookup table holds at most half its slots, so probes stay short.
            queueSlot = new int[size * 2];
            queueMark = new long[size * 2];
            for (int q = 0; q < eachCount; q++) {
                int slot = queueSlotOf(nodes[eachNode[q]].action, eachItem[q]);
                queueSlot[slot] = q;
                queueMark[slot] = run;
            }
        }
        int slot = queueSlotOf(node.action, item);
        if (queueMark[slot] == run) {
            // The same action for the same item already ran this time.
            return;
        }
        queueSlot[slot] = eachCount;
        queueMark[slot] = run;
        eachNode[eachCount] = current;
        eachItem[eachCount] = item;
        eachValue[eachCount] = value;
        eachCount++;
        points += node.type.actionPoints();
    }

    /**
     * The slot of the queue's lookup table that holds the entry for action slot {@code action} and {@code item}, or the
     * free slot where it goes. Slots marked with an older run are free, so the table needs no clearing.
     */
    private int queueSlotOf(int action, Object item) {
        int mask = queueSlot.length - 1;
        int slot = spread(action * 31 + item.hashCode()) & mask;
        while (queueMark[slot] == run) {
            int q = queueSlot[slot];
            if (nodes[eachNode[q]].action == action && item.equals(eachItem[q])) {
                return slot;
            }
            slot = (slot + 1) & mask;
        }
        return slot;
    }

    /** Mixes a hash's bits, so keys with close hashes spread over a power-of-two table. */
    private static int spread(int hash) {
        int h = hash * 0x9E3779B9;
        return h ^ (h >>> 16);
    }

    @SuppressWarnings("unchecked") // The queue's items are only ever the passes' items, which are K.
    private void flush(LogicContext<K> context) {
        for (int t = 0; t < touchedCount; t++) {
            int slot = touched[t];
            CompiledNode<K> node = nodes[actionEmitter[slot]];
            send(context, node, itemOf(node), actionValue[slot]);
        }
        for (int q = 0; q < eachCount; q++) {
            Object item = eachItem[q];
            send(context, nodes[eachNode[q]], NO_ITEM.equals(item) ? null : (K) item, eachValue[q]);
            eachItem[q] = NO_ITEM;
        }
        acting = -1;
    }

    /**
     * A notice's text with {@link #ITEM_PLACEHOLDER} replaced by the name of this run's missing item (a
     * crafting-job-failed node fired); unchanged when there is none or the text has no placeholder.
     */
    private String noticeText(LogicContext<K> context, String text) {
        K item = runItem;
        if (runItemRun != run || item == null || !text.contains(ITEM_PLACEHOLDER)) {
            return text;
        }
        return text.replace(ITEM_PLACEHOLDER, context.itemName(item));
    }

    /** Sends one action of {@code node} on {@code item} with {@code value} to the context. */
    private void send(LogicContext<K> context, CompiledNode<K> node, @Nullable K item, long value) {
        acting = node.id;
        switch (node.op) {
            case DEVICE_SWITCH -> context.switchDevice(node.label, value != 0);
            case SET_PRIORITY -> context.setPriority(node.label, value);
            case REDSTONE_OUT -> {
                if (node.colour >= 0) {
                    context.channel(node.colour, (int) value);
                } else {
                    context.redstone(node.label, (int) value);
                }
            }
            case REQUEST_CRAFT -> {
                if (item != null) {
                    context.requestCraft(item, value, node.label);
                }
            }
            case MOVE_ITEMS -> {
                if (item != null) {
                    context.moveItems(item, node.label, node.to, value);
                }
            }
            case NOTIFY -> context.notify(noticeText(context, node.text), node.channels);
            case PULSE_REDSTONE -> {
                if (node.colour >= 0) {
                    if (value == 0) {
                        context.pulseChannel(node.colour, (int) memory[node.mem + 1], false);
                    } else {
                        context.pulseChannel(node.colour, (int) value - 1, true);
                    }
                } else if (value == 0) {
                    context.pulse(node.label, (int) memory[node.mem + 1], false);
                } else {
                    context.pulse(node.label, (int) value - 1, true);
                }
            }
            case CANCEL_CRAFT -> {
                if (item != null) {
                    context.cancelCraft(item, node.mode == 1);
                }
            }
            case WRITE_DISPLAY -> context.writeDisplay(node.label, node.text, values[node.in[1]], values[node.in[2]]);
            case SET_FILTER -> context.setFilter(node.label, item, (int) node.number, node.mode);
            case SET_VARIABLE -> context.setVariable(node.text, value, node.mode == 1);
            case SET_TASK -> context.setTask(node.text, value != 0);
            case RUN_RULE -> context.runRule(node.text, item, node.label, value, node.to);
            case STOCK_SWITCH -> context.switchStockRule(node.label, item, value != 0);
            case STOCK_SET -> {
                if (item != null) {
                    context.setStockAmount(node.label, item, node.mode, value);
                }
            }
            case POOL_SEND -> {
                // The memory is where round robin and spread evenly start next; the context says where to go on.
                if (item != null) {
                    memory[node.mem] = context.sendToPool(item, node.label, value, node.mode, node.text, node.number,
                            memory[node.mem]);
                }
            }
            case SET_POWER_PRIORITY -> context.setPowerPriority(node.label, value);
            default -> throw new IllegalStateException("Not an action: " + node.op);
        }
    }

    /**
     * Adds a line to the why trace for the action the context is carrying out now (an action that could not run, or ran
     * only in part). Only from inside the {@link LogicContext} action calls; ignored elsewhere.
     */
    public void why(String reasonKey, long a, long b) {
        if (acting >= 0) {
            trace.add(acting, reasonKey, a, b);
        }
    }

    /**
     * {@link #why} with {@code text} filling the reason's first {@code %s} (a network's name, M7L2 row 13). Only from
     * inside the action calls; ignored elsewhere.
     */
    public void why(String reasonKey, String text) {
        if (acting >= 0) {
            trace.add(acting, reasonKey, 0, 0, text);
        }
    }

    /**
     * Adds a why line, once per run, to the node reading through {@link LogicContext#readEach} now (a For each's or a
     * call's count of another network that is not usable, M7L2 row 13); {@code text} fills the first {@code %s}.
     * Ignored outside such a read.
     */
    public void whyRead(String reasonKey, String text) {
        if (reading >= 0 && !trace.muted()) {
            if (trace.find(reading) < 0) {
                trace.add(reading, reasonKey, 0, 0, text);
            }
        } else if (reading >= 0 && readWhyNode < 0) {
            // Branch passes mute the trace; the line is added once they are over (flushReadWhy).
            readWhyNode = reading;
            readWhyKey = reasonKey;
            readWhyText = text;
        }
    }

    /** Adds the line {@link #whyRead} kept, if any, unless the node has one already. */
    private void flushReadWhy() {
        if (readWhyNode >= 0) {
            if (trace.find(readWhyNode) < 0) {
                trace.add(readWhyNode, readWhyKey, 0, 0, readWhyText);
            }
            readWhyNode = -1;
        }
    }

    /** The id of the action node the context is carrying out now; -1 outside the action calls. */
    public int actingNode() {
        return acting;
    }

    /**
     * Whether some crossing node reading watch {@code watch} is past its limit now. The controller asks for link load,
     * which falls with time and no event says so: while it is past, the load is looked at again on a timer.
     */
    public boolean past(int watch) {
        for (int pos : graph.watchNodes[watch]) {
            CompiledNode<K> node = nodes[pos];
            if ((node.op == Op.COUNT_CROSSES || node.op == Op.SENSOR_CROSSES || node.op == Op.LOAD_CROSSES)
                    && memory[node.mem] == Checks.YES) {
                return true;
            }
        }
        return false;
    }

    /** Checks the last run cost. */
    public int lastCost() {
        return cost;
    }

    /**
     * Action points the last run's actions cost: each action it sent (a For each's per-item actions included, a repeat
     * that replaced or was dropped not) at its type's {@link NodeType#actionPoints}. Simulated actions count too.
     */
    public int lastActionPoints() {
        return points;
    }

    /** Actions the last run sent to the context, a For each's per-item actions included. */
    public int lastActions() {
        return touchedCount + eachCount;
    }

    /** Whether a loop through a Remember or Delay node moved and wants the next run (next tick, not this one). */
    public boolean wantsRerun() {
        return rerun;
    }

    /** The game time the earliest waiting delay is due, or {@link #NO_WAKE}; the controller schedules one wake. */
    public long nextWake() {
        return nextWake;
    }

    /** Why the last run stopped where it did. */
    public RunTrace trace() {
        return trace;
    }

    /** The value in {@code slot} ({@link CompiledGraph#slot}); for events, the id of the run it last fired in. */
    public long value(int slot) {
        return values[slot];
    }

    /**
     * The game tick the delay or timer node at evaluation {@code position} ({@link CompiledGraph#position}) fires at,
     * or a pulse switches off at; 0 while it is not counting down, and for other nodes. For the editor's live view;
     * allocates nothing.
     */
    public long due(int position) {
        CompiledNode<K> node = nodes[position];
        return node.op == Op.DELAY || node.op == Op.TIMER || node.op == Op.PULSE_REDSTONE ? memory[node.mem] : 0;
    }

    /**
     * The labels of the pulse nodes whose pulse is on now (an end tick in memory). The controller ends those pulses
     * itself when the graph stops acting for real (simulate mode). Allocates; not for runs.
     */
    public List<String> pulsing() {
        List<String> out = new ArrayList<>();
        for (CompiledNode<K> node : nodes) {
            if (node.op == Op.PULSE_REDSTONE && memory[node.mem] != 0 && node.colour < 0) {
                out.add(node.label);
            }
        }
        return out;
    }

    /**
     * The network redstone channels (M7L2) pulse nodes hold on now, as bits (bit n for channel n). Like
     * {@link #pulsing()}, for ending them when the graph stops acting for real.
     */
    public int pulsingChannels() {
        int bits = 0;
        for (CompiledNode<K> node : nodes) {
            if (node.op == Op.PULSE_REDSTONE && memory[node.mem] != 0 && node.colour >= 0) {
                bits |= 1 << node.colour;
            }
        }
        return bits;
    }

    /** Runs so far. */
    public long runs() {
        return run;
    }

    /**
     * What to save of the graph's memory. Allocates; call when saving. Actions' last states are not saved: after a load
     * they set their devices and redstone once more, in case the world changed meanwhile. A pulse's end is saved, so a
     * pulse running at a save still switches off.
     */
    public LogicMemory memory() {
        List<LogicMemory.Entry> entries = new ArrayList<>();
        for (CompiledNode<K> node : nodes) {
            int size = node.type.memory();
            if (size > 0 && node.op.savesMemory()) {
                List<Long> saved = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    saved.add(memory[node.mem + i]);
                }
                entries.add(new LogicMemory.Entry(node.id, node.type.id(), saved));
            }
        }
        return new LogicMemory(entries);
    }

    /**
     * Restores saved memory; entries whose node is gone or changed type are dropped. Call before the first run; every
     * node runs again on the next run.
     */
    public void load(LogicMemory saved) {
        for (LogicMemory.Entry entry : saved.nodes()) {
            for (CompiledNode<K> node : nodes) {
                if (node.id == entry.node() && node.type.id().equals(entry.type()) && node.op.savesMemory()
                        && node.type.memory() == entry.values().size()) {
                    for (int i = 0; i < entry.values().size(); i++) {
                        memory[node.mem + i] = entry.values().get(i);
                    }
                }
            }
        }
        nextWake = earliestDue();
        everythingChanged();
    }

    /** The earliest due tick of a timed node, or {@link #NO_WAKE}. */
    private long earliestDue() {
        long earliest = NO_WAKE;
        for (int pos : graph.timed) {
            long due = dueAt(nodes[pos]);
            if (due != 0 && due < earliest) {
                earliest = due;
            }
        }
        return earliest;
    }

    /** The game tick a timed node (delay, timer, statistics, pulse, wait-until) wants to run at; 0 for none. */
    private long dueAt(CompiledNode<K> node) {
        if (node.op == Op.WAIT_UNTIL) {
            // The earliest timeout of the held events.
            int held = (int) Math.clamp(memory[node.mem], 0, NodeTypes.MAX_HELD);
            long earliest = 0;
            for (int i = 0; i < held; i++) {
                long timeout = memory[node.mem + 1 + i];
                if (timeout != 0 && (earliest == 0 || timeout < earliest)) {
                    earliest = timeout;
                }
            }
            return earliest;
        }
        if (node.op == Op.SEQUENCE) {
            return memory[node.mem + Sequences.MEM_DUE];
        }
        return node.op == Op.STATS ? memory[node.mem + STATS_DUE] : memory[node.mem];
    }

    /**
     * The step the step sequence at evaluation {@code position} is at (1 based), or 0 before its first run and for
     * other nodes. For tests and the live views; allocates nothing.
     */
    public long step(int position) {
        CompiledNode<K> node = nodes[position];
        return node.op == Op.SEQUENCE ? memory[node.mem + Sequences.MEM_STEP] : 0;
    }
}
