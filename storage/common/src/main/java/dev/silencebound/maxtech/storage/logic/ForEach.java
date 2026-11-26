package dev.silencebound.maxtech.storage.logic;

/**
 * Names and rules of the "For each" node (M7L2 row 18): when its "when" event fires it runs the nodes wired from its
 * "each" and "item" outputs (its branch) once per item of a source, with "item" carrying the item of that pass, then
 * fires "done" once. Sources: every item of an item tag, a list of up to {@link #MAX_LIST} items set in the node (a tag
 * in the list stands for each item in it), or the items of the rules of the stock keepers with a label.
 *
 * <p>
 * The branch is every node that reads the "each" or "item" output, directly or through other nodes, in the same run.
 * Only nodes that need no memory between runs may sit in it ({@link #allowedInBranch}): checks and logic, the item
 * count and stock rule status reads, and the craft, move, send-to-pool, cancel, notify and run-rule actions (a run-rule
 * node there passes the item of the pass to the rule it runs). The same rules hold for a call branch
 * ({@link Parameters}). A Compare in the branch is checked afresh for each item (no switch-back), and its "turned yes"
 * / "turned no" events fire for an item whose result is yes / no. Nodes that read an item input wired from "item" read
 * the item of the pass; their reads are not watched but made while the node runs. One run goes through at most
 * {@code logic.forEachMaxItems} items over all its For each nodes and stops early when the team's checks for the second
 * run out; each pass charges the normal cost of the nodes it runs. Pure; any thread.
 */
public final class ForEach {
    /** Most items the list source holds. */
    public static final int MAX_LIST = 32;
    /** Items of a new list. */
    public static final int DEFAULT_LIST = 3;
    /** Most items one run goes through whatever the config says; the config's upper bound. */
    public static final int HARD_MAX_ITEMS = 1024;

    /** The source choice. */
    static final String SOURCE = "source";
    /** How many items the list source shows. */
    static final String LIST_SIZE = "items";
    /** Output: fires once per item. */
    public static final String EACH = "each";
    /** Output: the item of the pass. */
    public static final String ITEM = "item";
    /** Output: fires once every item was gone through. */
    public static final String DONE = "done";
    /** Output: how many items the last run went through. */
    public static final String CHECKED = "checked";

    /** Source index: every item of an item tag. */
    public static final int SOURCE_TAG = 0;
    /** Source index: the items set in the node. */
    public static final int SOURCE_LIST = 1;
    /** Source index: the items of the labelled stock keepers' rules. */
    public static final int SOURCE_STOCK = 2;

    // Output indices, in the type's order.
    static final int OUT_EACH = 0;
    static final int OUT_ITEM = 1;
    static final int OUT_DONE = 2;
    static final int OUT_CHECKED = 3;

    private ForEach() {
    }

    /** The setting of list item {@code k} (1 based). */
    public static String item(int k) {
        return "item_" + k;
    }

    /** How many list items a node with {@code settings} shows, within 1 and {@link #MAX_LIST}. */
    public static int listSize(NodeSettings<?> settings) {
        return (int) Math.clamp(settings.number(LIST_SIZE).orElse(DEFAULT_LIST), 1, MAX_LIST);
    }

    /** The source index a node with {@code settings} uses. */
    public static int source(NodeSettings<?> settings) {
        return NodeTypes.FOR_EACH.setting(SOURCE).map(spec -> spec.choiceIndex(settings.text(SOURCE).orElse("")))
                .orElse(SOURCE_TAG);
    }

    /**
     * Whether a node of {@code type} may sit in a For each branch: it keeps no memory between runs, so running it once
     * per item means the same as running it once for one item. Remember and Time nodes, event nodes and actions that
     * act on a change (switch, redstone) may not.
     */
    public static boolean allowedInBranch(NodeType type) {
        return switch (type.id()) {
            case "and", "or", "not", "xor", "if", "compare", "between", "select", "math", "item_count", "stock_status",
                    "request_craft", "move_items", "move_to_pool", "cancel_craft", "notify", "run_rule" ->
                true;
            default -> false;
        };
    }
}
