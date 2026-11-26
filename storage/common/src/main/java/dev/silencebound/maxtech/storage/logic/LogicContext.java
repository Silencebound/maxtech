package dev.silencebound.maxtech.storage.logic;

import org.jspecify.annotations.Nullable;

/**
 * What a graph's evaluation sees of the world and how its actions leave it, implemented by the Logic Controller (areas
 * B to E) and by fakes in tests. Called only from {@link LogicEvaluator#run}, on the server thread.
 *
 * <p>
 * Reads come first, while nodes run; actions come last, in one batch after every node has run, each at most once per
 * run (duplicates are dropped or merged by the evaluator). Actions are requests: moving items still goes through
 * transfer transactions and counts only what moved, and a device that is gone is skipped. Implementations must not run
 * a graph from inside these calls.
 *
 * @param <K>
 *            what items are (Lib's {@code StackKey} in game)
 */
public interface LogicContext<K> {
    /** {@link #notify} channel: the network's log. */
    int CHANNEL_LOG = 1;
    /** {@link #notify} channel: a toast for the network team's players. */
    int CHANNEL_TOAST = 2;
    /** {@link #notify} channel: a chat line for the network team's players. */
    int CHANNEL_CHAT = 4;
    /** {@link #notify} channel: the displays bound to the controller. */
    int CHANNEL_DISPLAYS = 8;

    /**
     * The current value of {@code watch}: a count, 1 or 0 for yes/no kinds, 0 to 15 for redstone. A label no device
     * carries reads 0. Never called for signal watches ({@link WatchKind#signal()}).
     */
    long read(Watch<K> watch);

    /** Whether a crafting job for {@code item} is running or waiting, so a request now would be a second one. */
    boolean craftRunning(K item);

    /** Switch the devices labelled {@code label} on or off. */
    void switchDevice(String label, boolean on);

    /** Set the priority of the devices labelled {@code label}. */
    void setPriority(String label, long priority);

    /**
     * Set the power priority (1 essential to 5 optional, M7L2 load shedding) of the devices labelled {@code label} that
     * load shedding can pause. Does nothing by default.
     */
    default void setPowerPriority(String label, long priority) {
    }

    /**
     * Emit redstone {@code strength} (0 to 15) from the devices labelled {@code label}, or the controller when empty.
     */
    void redstone(String label, int strength);

    /**
     * Set the controller's share of the network's bundled redstone channel {@code channel} (0 white to 15 black, M7L2)
     * to {@code strength} (0 to 15). The channel gives the strongest share of all its writers. Does nothing by default.
     */
    default void channel(int channel, int strength) {
    }

    /**
     * A pulse on network redstone channel {@code channel}: on at {@code strength}, or its end ({@code on} false, with
     * the strength the pulse set), which sets the controller's share back to 0 only while it is still the pulse's. Does
     * nothing by default.
     */
    default void pulseChannel(int channel, int strength, boolean on) {
    }

    /** Request a crafting job for {@code amount} of {@code item}. */
    void requestCraft(K item, long amount);

    /**
     * {@link #craftRunning(Object)} on the network {@code label} names ({@link NetworkRef}: {@code @name} is another
     * network, empty the controller's own). By default the own network.
     */
    default boolean craftRunning(K item, String label) {
        return craftRunning(item);
    }

    /**
     * {@link #requestCraft(Object, long)} on the network {@code label} names ({@link NetworkRef}; empty: the
     * controller's own). By default the own network.
     */
    default void requestCraft(K item, long amount, String label) {
        requestCraft(item, amount);
    }

    /**
     * Move up to {@code amount} of {@code item} from the devices labelled {@code from} to those labelled {@code to}; an
     * empty label is the network's storage. The controller caps the amount per run.
     */
    void moveItems(K item, String from, String to, long amount);

    /** Post {@code text} (the player's own words) to the channels set in {@code channels} (the CHANNEL bits). */
    void notify(String text, int channels);

    /**
     * The name a notice shows for {@code item} in place of {@code {item}} (a failed job's missing item, M7L2); by
     * default the key's text.
     */
    default String itemName(K item) {
        return item.toString();
    }

    /**
     * A pulse from the devices labelled {@code label}: on at {@code strength}, or its end ({@code on} false, with the
     * strength the pulse set), which turns off only what this pulse turned on and so needs no fresh access.
     */
    void pulse(String label, int strength, boolean on);

    /**
     * Cancel the waiting and running crafting jobs for {@code item} on the network, or only those the running graph
     * requested when {@code ownOnly}. Cancelling goes through the job queue's own cancel, which gives back what the
     * jobs hold; jobs the controller's owner may not cancel are left alone.
     */
    void cancelCraft(K item, boolean ownOnly);

    /**
     * Show {@code text} (display markup, the player's own words) on the displays labelled {@code label}, with
     * {@code {a}} and {@code {b}} replaced by {@code a} and {@code b} ({@link #fillValues}). The controller limits how
     * often one display is written.
     */
    void writeDisplay(String label, String text, long a, long b);

    /**
     * Change the ports labelled {@code label}: when {@code slot} is 1 to 9, filter slot {@code slot} names {@code item}
     * (null clears it); when {@code portMode} is {@link #PORT_IMPORT} or {@link #PORT_EXPORT}, the port switches to
     * that mode. As a player's edit, and only for an owner allowed to change the port.
     */
    void setFilter(String label, @Nullable K item, int slot, int portMode);

    /**
     * Set the controller's shared variable {@code name} (as {@link LogicCompiler#variableName} keys it) to
     * {@code value}, a yes/no as 1 or 0 when {@code yesNo}. Graphs reading it see the change on their next run; the
     * controller holds a bounded number of variables and refuses a new one past it.
     */
    void setVariable(String name, long value, boolean yesNo);

    /**
     * Tick the task {@code name} names (scope, board and task, {@code TaskNames}) on a task board, or open it again
     * with {@code done} false, as a player's right-click would (M7C item 15). A task that does not exist is left alone.
     */
    default void setTask(String name, boolean done) {
    }

    /**
     * Run the controller's graph that {@code rule} names (a row number, or a name): its "Run by another rule" nodes
     * fire on its next run. Graphs that would run each other in a circle are paused before they run, and the controller
     * limits how deep calls nest.
     */
    void runRule(String rule);

    /**
     * {@link #runRule(String)} with arguments (M7L2 row 19): when the rule has parameters and a "Run by another rule"
     * node, its call branch runs once for this call with the item parameter keyed {@code itemParam} set to {@code item}
     * and the number parameter keyed {@code amountParam} set to {@code amount} (an empty key fills nothing; keys as
     * {@link Parameters#key}). A rule without parameters runs as with {@link #runRule(String)}.
     */
    default void runRule(String rule, @Nullable K item, String itemParam, long amount, String amountParam) {
        runRule(rule);
    }

    /**
     * Switch the rules for {@code item} (every rule when null; a tag matches each item in it) of the stock keepers
     * labelled {@code label} on or off. A switched-off rule requests no more crafts; a job it has out keeps running. As
     * a player's edit at the keeper's screen, and only for an owner allowed to change the keeper.
     */
    void switchStockRule(String label, @Nullable K item, boolean on);

    /**
     * Set one amount of the rules for {@code item} of the stock keepers labelled {@code label}: {@code field}
     * {@link #STOCK_KEEP}, {@link #STOCK_RESTART} or {@link #STOCK_BATCH}, clamped as the keeper clamps it (a restart
     * threshold at most keep). Only rules that exist change; logic never adds a rule. As {@link #switchStockRule}.
     */
    void setStockAmount(String label, K item, int field, long amount);

    /**
     * Send up to {@code amount} of {@code item} from the network's storage to the machine pool labelled {@code label}
     * (its ports and interfaces) by {@code strategy} ({@code Pools.FIRST_IDLE}, {@code ROUND_ROBIN}, {@code LEAST_FULL}
     * or {@code SPREAD_EVENLY}). Only idle members take part: those whose machine's sensor value {@code idleValue}
     * meets {@code idle} ({@link Pools#packIdle}), every member when {@code idleValue} is empty. Members the owner may
     * not change are skipped; each move runs through the member itself, so only what moved counts. {@code cursor} is
     * where round robin and spread evenly start (saved in the node); returns where the next send starts.
     */
    long sendToPool(K item, String label, long amount, int strategy, String idleValue, long idle, long cursor);

    /**
     * For a For each node's branch (M7L2 row 18): the current value of {@code kind} ({@link WatchKind#ITEM_COUNT} or
     * {@link WatchKind#STOCK}, with {@code number} as {@link Watch#number()}) for {@code item}, the item of the pass,
     * in the devices labelled {@code label} (empty: the network). Not a watch: read while the node runs, once per item.
     * 0 by default. Allocates nothing.
     */
    default long readEach(WatchKind kind, K item, String label, long number) {
        return 0;
    }

    /**
     * Adds the items a For each node goes through for {@code key} to {@code out}, while it holds fewer than
     * {@code max}: each item of an item tag in the tag's order, or the item itself. By default the key itself.
     */
    default void eachItem(K key, java.util.List<K> out, int max) {
        if (out.size() < max) {
            out.add(key);
        }
    }

    /**
     * Adds the items of the rules of the stock keepers labelled {@code label} (keepers in label order, rules in slot
     * order, only those the keeper runs: within {@code crafting.stockKeeperRules} and not switched off) to {@code out},
     * while it holds fewer than {@code max}. None by default.
     */
    default void stockItems(String label, java.util.List<K> out, int max) {
    }

    /** Most items one run goes through over all its For each nodes ({@code logic.forEachMaxItems}). */
    default int maxEachItems() {
        return 64;
    }

    /**
     * Checks the team had left this second when the run started; a For each stops going through its items once the run
     * cost that much. Unlimited by default.
     */
    default long checksLeft() {
        return Long.MAX_VALUE;
    }

    /**
     * The action points the team has left in this second when the run started; a For each starts no further pass once
     * the run's actions cost that much. Unlimited by default.
     */
    default long actionPointsLeft() {
        return Long.MAX_VALUE;
    }

    /** {@link #setStockAmount} field: the keep amount. */
    int STOCK_KEEP = 0;
    /** {@link #setStockAmount} field: the restart threshold. */
    int STOCK_RESTART = 1;
    /** {@link #setStockAmount} field: the batch size (0 for no limit). */
    int STOCK_BATCH = 2;

    /** {@link #setFilter} port mode: leave the mode as it is. */
    int PORT_KEEP = 0;
    /** {@link #setFilter} port mode: import. */
    int PORT_IMPORT = 1;
    /** {@link #setFilter} port mode: export. */
    int PORT_EXPORT = 2;

    /** {@code text} with every {@code {a}} and {@code {b}} replaced by the numbers; other braces stay as they are. */
    static String fillValues(String text, long a, long b) {
        if (text.indexOf('{') < 0) {
            return text;
        }
        return text.replace("{a}", Long.toString(a)).replace("{b}", Long.toString(b));
    }
}
