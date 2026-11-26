package dev.silencebound.maxtech.storage.logic;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The logic module's lang keys and their English text, kept beside the code that uses them so the rule core stays free
 * of registry classes; {@link LogicTexts} hands them to datagen. Keys are {@code gui.maxtech_storage.logic.*}.
 */
public final class LogicLang {
    private static final Map<String, String> ENGLISH = new LinkedHashMap<>();

    public static final String PREFIX = "gui." + MaxtechStorage.MOD_ID + ".logic.";

    // Compiler problems (GraphError keys). Plain words: the player sees them on the node.
    public static final String TOO_MANY_NODES = add("error.too_many_nodes", "A graph holds at most %s nodes");
    public static final String DUPLICATE_NODE = add("error.duplicate_node",
            "Two nodes share a number; delete this one and add it again");
    public static final String MISSING_NODE = add("error.missing_node", "A wire leads to a node that isn't there");
    public static final String UNKNOWN_PORT = add("error.unknown_port",
            "A wire is fixed to a connection this node doesn't have");
    public static final String TWO_WIRES = add("error.two_wires", "Only one wire can go into this");
    public static final String FILL_IN = add("error.fill_in", "Fill this in");
    public static final String MOVE_NOWHERE = add("error.move_nowhere", "Pick a label to move from or to");
    public static final String LOOP = add("error.loop", "This loop needs a Remember or Delay node to break it");
    /**
     * A label no device on the network carries (area C's {@code NetworkLabels#has}); the argument is the label. Checked
     * against the network, not by the compiler.
     */
    public static final String NO_SUCH_LABEL = add("error.no_such_label", "No device on this network has the label %s");

    /** A step-is node names a step its sequence does not have; the argument is the name. */
    public static final String NO_SUCH_STEP = add("error.no_such_step",
            "The sequence wired in has no step called %s (give a step name or number)");

    // Rules across networks (M7L2 row 13). Each argument is a network's name.
    /** On a node that cannot reach another network (signals, pools, stock keepers, pulses). */
    public static final String NET_NOT_HERE = add("error.net_not_here",
            "This node works only on its own network, so it can't use %s");
    /** On a request craft whose network setting names a device too. */
    public static final String NET_NETWORK_ONLY = add("error.net_network_only",
            "Name only a network here, like @%s, or leave it empty for this network");
    /** On a move whose two sides are on different networks. */
    public static final String NET_MOVE_SIDES = add("error.net_move_sides",
            "Both sides of a move must be on one network; %s is not the other side's");
    /** The editor's warning: no network the rule's owner may use has this name. */
    public static final String NET_NOT_REACHABLE = add("error.net_not_reachable",
            "No network named %s that this rule's owner may use with logic");
    /** The rule names a network no controller has (renamed, broken, or a rule from another world). */
    public static final String NET_MISSING = add("error.net_missing", "No network is named %s");
    /** The rule's owner lacks the logic access level on the named network (or the controller has no owner). */
    public static final String NET_DENIED = add("error.net_denied",
            "The rule's owner has no logic access to %s, so the rule is paused");
    /** A read of a network that is not loaded keeps the last value it saw. */
    public static final String WHY_NET_UNAVAILABLE = add("why.net_unavailable",
            "%s is not loaded or not online; the last value seen is used");
    /** An action on a network that is not loaded is not done; the rule does not wait for it. */
    public static final String WHY_NET_UNAVAILABLE_ACT = add("why.net_unavailable_act",
            "%s is not loaded or not online; nothing was done");

    // The "why" line: the node that stopped a run, with the values it saw.
    public static final String WHY_STILL = add("why.still", "Nothing changed: the value is %s, the limit %s");
    public static final String WHY_IF_NO = add("why.if_no", "The condition was no");
    public static final String WHY_CRAFT_RUNNING = add("why.craft_running", "A craft of this item is already running");
    public static final String WHY_ALREADY_DONE = add("why.already_done", "The same action already ran this time");
    public static final String WHY_DELAY_WAITING = add("why.delay_waiting", "The delay is already counting down");

    // Extra node types (batch 1).
    public static final String WHY_DIVIDE_ZERO = add("why.divide_zero", "Divided by zero; the result is 0");
    public static final String WHY_COOLDOWN = add("why.cooldown", "Cooling down: %s more ticks of %s");

    // Words a sentence fills in for yes/no settings.
    public static final String WORD_YES = add("word.yes", "yes");
    public static final String WORD_NO = add("word.no", "no");

    // Area E: what an action did or why it could not (the why line), and the editor's hint for on/off nodes.
    /** Also the editor's hint on a switch node whose label holds storage. */
    public static final String CANT_SWITCH = add("why.cant_switch", "Storage can't be turned off; it stays available");
    public static final String WHY_NO_TARGET = add("why.no_target", "No device with this label can do this");
    public static final String WHY_NOT_CRAFTABLE = add("why.not_craftable",
            "Nothing can craft this now: no pattern, or the queue refused it");
    /** M7L2 chunk 20: the network's limits for automated crafting changed what a request does. */
    public static final String WHY_CRAFT_CAPPED = add("why.craft_capped",
            "Capped to %s of %s: the network's most items per automated job");
    public static final String WHY_CRAFT_KEPT_FREE = add("why.craft_kept_free",
            "Waiting: %s CPUs are kept free for players");
    public static final String WHY_CRAFT_NO_CPU = add("why.craft_no_cpu",
            "Automation can't use any CPU: the network has %s and keeps %s free for players");
    public static final String WHY_TAG = add("why.tag", "Pick one item; a tag can't be crafted or moved");
    public static final String WHY_MOVED = add("why.moved", "Moved %s of %s");
    public static final String WHY_MOVE_SIDES = add("why.move_sides",
            "Moves go between the network's storage and one label");

    // Extra node types, batch 3 (actions).
    public static final String FILTER_NOTHING = add("error.filter_nothing", "Pick a filter slot or a port mode to set");
    public static final String WHY_NO_JOB = add("why.no_job", "No crafting job for this item to cancel");
    public static final String WHY_CANCEL_DENIED = add("why.cancel_denied",
            "%s jobs were left alone: the controller's owner may not cancel them");
    public static final String WHY_PULSE_ON = add("why.pulse_on", "The pulse is still on: %s more ticks of %s");
    public static final String WHY_DISPLAY_WAIT = add("why.display_wait",
            "Written too recently; the display shows it in %s ticks");
    public static final String WHY_DISPLAY_TEXT = add("why.display_text",
            "The text can't be shown: an unknown item icon or a broken placeholder");
    public static final String WHY_DENIED = add("why.denied",
            "%s devices were left alone: the controller's owner may not change them");

    // Machine pools (M7L2 row 17).
    public static final String WHY_POOL_BUSY = add("why.pool_busy", "No member of the pool is idle (%s of %s busy)");
    public static final String WHY_POOL_NO_SENSOR = add("why.pool_no_sensor",
            "%s members have no machine to read and no sensor with the pool's label, so they count as busy");
    public static final String LIVE_POOL_IDLE = add("live.pool_idle", "Idle: %s");

    // For each (M7L2 row 18).
    public static final String EACH_NOT_HERE = add("error.each_not_here",
            "This node can't run once per item in a For each branch: it remembers between runs or acts on a change");
    public static final String EACH_NESTED = add("error.each_nested",
            "A node can be in only one For each branch, and a For each can't sit in another's branch");
    public static final String EACH_MIXED = add("error.each_mixed",
            "This node is wired both from a For each's branch and from its Done or Items checked; use two nodes");
    /** Arguments: the items gone through, the actions the branch asked for. */
    public static final String WHY_EACH = add("why.each", "Checked %s items, %s actions");
    /** A crafting job failed: every missing item, the first of which the "missing item" output carries. */
    public static final String WHY_MISSING = add("why.missing", "Missing %s");
    public static final String MISSING_ITEM_NOT_HERE = add("error.missing_item_not_here",
            "Only item count, request craft, move items, send to pool and cancel craft can take the missing item");
    /** Arguments: the items gone through, the most one run goes through. */
    public static final String WHY_EACH_CAP = add("why.each_cap",
            "Stopped after %s items: one run goes through at most %s (logic.forEachMaxItems)");
    /** Argument: the items gone through. */
    public static final String WHY_EACH_BUDGET = add("why.each_budget",
            "Stopped after %s items: the team's checks for this second ran out");
    /** Argument: the items gone through. */
    public static final String WHY_EACH_POINTS = add("why.each_points",
            "Stopped after %s items: the team's action points for this second ran out");
    public static final String LIVE_CHECKED = add("live.checked", "Checked: %s");
    /** A For each node's summary with the list source; one argument, how many items the list holds. */
    public static final String EACH_LIST_COUNT = add("for_each.list_count", "%s items");

    // Rules with parameters (M7L2 row 19).
    /** On a second Parameter node with a name another one has. */
    public static final String PARAM_DUPLICATE = add("error.param_duplicate",
            "Another parameter of this rule has this name");
    public static final String PARAM_TOO_MANY = add("error.param_too_many", "A rule has at most 8 parameters");
    /** One argument: the parameter's name. On a copy's value, or a run-rule node naming a parameter. */
    public static final String PARAM_UNKNOWN = add("error.param_unknown",
            "The rule has no parameter \"%s\" (renamed or removed?)");
    /** One argument: the parameter's name. */
    public static final String PARAM_KIND = add("error.param_kind",
            "Parameter \"%s\" takes another kind of value than this");
    /** On a copy whose template rule is gone or is itself a copy. */
    public static final String INSTANCE_NO_TEMPLATE = add("error.instance_no_template",
            "The rule this is a copy of is no longer on this controller (removed or replaced); detach this copy to keep it as it is");
    public static final String CALL_NOT_HERE = add("error.call_not_here",
            "This node reads a parameter or the call, so it runs once per call: only checks, counts and one-shot actions fit here");
    public static final String CALL_ONE_ENTRY = add("error.call_one_entry",
            "A rule with parameters can have only one \"Run by another rule\" node");
    /** Arguments: the calls served, the actions they asked for. */
    public static final String WHY_CALLS = add("why.calls", "Ran for %s calls, %s actions");
    /** Argument: the most calls one rule holds. */
    public static final String WHY_CALLS_FULL = add("why.calls_full",
            "That rule holds %s calls waiting already; this one was not run");
    public static final String HISTORY_INSTANCE = add("history.instance", "made as a copy of %s with its own values");
    public static final String HISTORY_DETACHED = add("history.detached", "detached from %s: now its own rule");
    public static final String HISTORY_TEMPLATE_GONE = add("history.template_gone",
            "its template rule was removed, so it was detached and kept as it was");

    // Logic controls the Stock Keeper (M7L2 row 15).
    public static final String WHY_NO_STOCK_RULE = add("why.no_stock_rule",
            "No stock keeper with this label has a rule for this item; logic changes rules, it never adds one");
    public static final String LIVE_STOCK_SHORT = add("live.stock_short", "Short: %s");

    // Extra node types, batch 4 (hooks).
    /** On a Run another rule node whose rule runs this graph again, directly or through others. */
    public static final String RULE_LOOP = add("error.rule_loop",
            "These rules would run each other in a circle; break the circle to run them");
    public static final String WHY_VARIABLES_FULL = add("why.variables_full",
            "The controller holds %s shared values already; this one was not set");
    public static final String WHY_NO_RULE = add("why.no_rule", "No graph of this controller has that name or row");
    public static final String WHY_NO_TASK = add("why.no_task", "No task board of this network has that task");
    public static final String WHY_RULE_OFF = add("why.rule_off",
            "That graph is switched off, paused or over the limit");
    public static final String WHY_RULE_NO_ENTRY = add("why.rule_no_entry",
            "That graph has no \"Run by another rule\" node");
    public static final String WHY_RULE_DEPTH = add("why.rule_depth",
            "Rules ran each other %s deep already; this one was not run");
    public static final String WHY_WAIT_FULL = add("why.wait_full", "Already holding %s events; this one was dropped");

    // Step sequences (M7L2 row 16): a step change, with the step numbers.
    public static final String WHY_STEP_MOVED = add("why.step_moved", "The sequence went from step %s to step %s");
    public static final String WHY_STEP_START = add("why.step_start", "The sequence started at step %s");
    /** A sequence's live line; one argument, the step's name. */
    public static final String LIVE_STEP = add("live.step", "At: %s");
    /** A step without a name the player gave; one argument, its number. */
    public static final String STEP_DEFAULT_NAME = add("sequence.default_name", "Step %s");
    /** A sequence node's summary; one argument, the step count. */
    public static final String STEP_COUNT = add("sequence.steps", "%s steps");

    // Area E: lines in the network's log (one argument: an item's name).
    public static final String LOG_CRAFT_REFUSED = add("log.craft_refused", "Logic couldn't request a craft of %s");
    /** A logic action left a device alone; one argument, the device's label. Posted once per refusal, not per run. */
    public static final String LOG_DENIED = add("log.denied",
            "Logic left %s alone: the controller's owner may not change it");
    public static final String LOG_CRAFT_FAILED = add("log.craft_failed", "Logic's craft of %s failed");

    // The editor's live line inside a node (one argument: the value) and its tooltip for crossings.
    public static final String LIVE_COUNT = add("live.count", "Count: %s");
    public static final String LIVE_VALUE = add("live.value", "Value: %s");
    public static final String LIVE_ON = add("live.on", "On: %s");
    public static final String LIVE_POWERED = add("live.powered", "Powered: %s");
    public static final String LIVE_STRENGTH = add("live.strength", "Strength: %s");
    public static final String LIVE_LATCHED = add("live.latched", "Latched: %s");
    public static final String LIVE_DUE = add("live.due", "Fires in %s s");
    public static final String LIVE_IDLE = add("live.idle", "Not counting");
    public static final String LIVE_PULSE = add("live.pulse", "Off in %s s");
    public static final String LIVE_AVERAGE = add("live.average", "Average: %s");
    public static final String LIVE_RATE = add("live.rate", "Per second: %s");
    public static final String LIVE_DAY = add("live.day", "Day: %s");
    public static final String LIVE_WEATHER = add("live.weather", "Weather: %s");
    public static final String LIVE_AMOUNT_MB = add("live.amount_mb", "Amount: %s mB");
    public static final String LIVE_FULL = add("live.full", "Full: %s%%");
    public static final String LIVE_FREE_BYTES = add("live.free_bytes", "Free: %s bytes");
    public static final String LIVE_RUNNING = add("live.running", "Running: %s");
    public static final String LIVE_NOW = add("live.now", "Now %s, limit %s");
    public static final String LIVE_PAST = add("live.past", "Past the limit");
    public static final String LIVE_BETWEEN = add("live.between",
            "Between the limit and the switch-back value %s: stays as it was");
    public static final String LIVE_SHORT = add("live.short", "Not past the limit");
    public static final String LIVE_HELD = add("live.held", "Held: %s");
    public static final String LIVE_PLAYERS = add("live.players", "Players: %s");
    public static final String LIVE_AMOUNT = add("live.amount", "Last: %s");

    // Debugging (M7L2 chunk 8): an action a graph in simulate mode only recorded.
    public static final String WHY_SIMULATED = add("why.simulated", "Simulate: recorded, not carried out");

    // Change history (M7L2 chunk 9): what each change to a rule did, joined with commas in one history line.
    // Arguments: node type name, setting name, old value, new value (or a count as the first).
    public static final String HISTORY_NODE_ADDED = add("history.node_added", "added a %s node");
    public static final String HISTORY_NODES_ADDED = add("history.nodes_added", "added %s nodes");
    public static final String HISTORY_NODE_REMOVED = add("history.node_removed", "removed a %s node");
    public static final String HISTORY_NODES_REMOVED = add("history.nodes_removed", "removed %s nodes");
    public static final String HISTORY_SETTING = add("history.setting", "changed %s %s %s \u2192 %s");
    public static final String HISTORY_MORE_SETTINGS = add("history.more_settings", "changed %s more settings");
    public static final String HISTORY_WIRE_ADDED = add("history.wire_added", "added a wire");
    public static final String HISTORY_WIRES_ADDED = add("history.wires_added", "added %s wires");
    public static final String HISTORY_WIRE_REMOVED = add("history.wire_removed", "removed a wire");
    public static final String HISTORY_WIRES_REMOVED = add("history.wires_removed", "removed %s wires");
    public static final String HISTORY_NODE_MOVED = add("history.node_moved", "moved a node");
    public static final String HISTORY_NODES_MOVED = add("history.nodes_moved", "moved %s nodes");
    public static final String HISTORY_NO_CHANGE = add("history.no_change", "saved without changes");
    public static final String HISTORY_EMPTY = add("history.empty", "(empty)");
    public static final String HISTORY_CREATED = add("history.created", "created the rule with %s nodes");
    public static final String HISTORY_RENAMED = add("history.renamed", "renamed %s \u2192 %s");
    public static final String HISTORY_PINS = add("history.pins", "changed the pinned values");
    public static final String HISTORY_REVERTED = add("history.reverted", "reverted to the last working version");
    public static final String HISTORY_SWITCHED_ON = add("history.switched_on", "switched the rule on");
    public static final String HISTORY_SWITCHED_OFF = add("history.switched_off", "paused the rule (switched off)");
    public static final String HISTORY_REMOTE_ON = add("history.remote_on", "resumed the rule with a logic remote");
    public static final String HISTORY_REMOTE_OFF = add("history.remote_off", "paused the rule with a logic remote");
    public static final String HISTORY_ADMIN_ON = add("history.admin_on", "resumed the rule as an operator");
    public static final String HISTORY_ADMIN_OFF = add("history.admin_off", "paused the rule as an operator");
    public static final String HISTORY_SIMULATE_ON = add("history.simulate_on", "started simulating");
    public static final String HISTORY_SIMULATE_OFF = add("history.simulate_off", "stopped simulating");
    public static final String HISTORY_IMPORTED = add("history.imported", "added from a pasted or imported text");
    public static final String HISTORY_FROM_CARD = add("history.from_card", "added from a logic card");
    public static final String HISTORY_FROM_TEMPLATE = add("history.from_template", "added from the template %s");

    // Script node types (M7L2 chunk 14, KubeJS): a pack's node whose script is gone, and one whose script failed.
    /** The name of a node whose script node type no script registers now. */
    public static final String MISSING_SCRIPT_NODE = add("node.missing_script", "Missing script node");
    public static final String SCRIPT_MISSING = add("error.script_missing",
            "The script that added this node is not loaded; add it back to run this graph");
    /** One argument: the script's error message. */
    public static final String SCRIPT_FAILED = add("error.script_failed",
            "The script of this node failed, so the graph was paused: %s");
    public static final String WHY_SCRIPT_FAILED = add("why.script_failed",
            "The script of this node failed; nothing this run asked for was done");

    private LogicLang() {
    }

    /** Declares {@code PREFIX + path} with its English text and returns the full key. */
    static String add(String path, String english) {
        String key = PREFIX + path;
        if (ENGLISH.putIfAbsent(key, english) != null) {
            throw new IllegalStateException("Duplicate logic lang key " + key);
        }
        return key;
    }

    /** Like {@link #add}, for keys several node types share (a port called "item"); the text must agree. */
    static String shared(String path, String english) {
        String key = PREFIX + path;
        String old = ENGLISH.putIfAbsent(key, english);
        if (old != null && !old.equals(english)) {
            throw new IllegalStateException("Logic lang key " + key + " has two texts");
        }
        return key;
    }

    /**
     * Every key with its English text, in declaration order. Node types, ports and settings add theirs when
     * {@link NodeTypes} loads, so this loads it first.
     */
    public static Map<String, String> english() {
        NodeTypes.init();
        GraphText.init();
        RuleCards.init();
        CardEdits.init();
        return Collections.unmodifiableMap(ENGLISH);
    }
}
