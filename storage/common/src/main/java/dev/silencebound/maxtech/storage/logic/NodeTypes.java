package dev.silencebound.maxtech.storage.logic;

import static dev.silencebound.maxtech.storage.logic.PortType.EVENT;
import static dev.silencebound.maxtech.storage.logic.PortType.ITEM;
import static dev.silencebound.maxtech.storage.logic.PortType.LABEL;
import static dev.silencebound.maxtech.storage.logic.PortType.NUMBER;
import static dev.silencebound.maxtech.storage.logic.PortType.YES_NO;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The node type registry: the first node types of the logic prototype (M7L area A). Internal for now; registering from
 * outside Storage comes with an api later. Ids are saved in graphs and must never change.
 */
public final class NodeTypes {
    private static final Map<String, NodeType> BY_ID = new LinkedHashMap<>();
    /**
     * The types the palette's "Common" view lists (owner decision 2026-10-07): the usual events, item count, compare,
     * and/or/not, latch, counter, the main actions, delay and timer. "All" lists every type.
     */
    private static final Set<String> COMMON = Set.of("count_crosses", "redstone_changes", "sensor_crosses",
            "item_count", "compare", "and", "or", "not", "latch", "counter", "device_switch", "redstone_out",
            "request_craft", "move_items", "notify", "delay", "timer");

    // Setting and port names the compiler and evaluator look up.
    static final String ITEM_NAME = "item";
    static final String LABEL_NAME = "label";
    static final String MODE = "mode";
    static final String THRESHOLD = "threshold";
    static final String SWITCH_BACK = "switch_back";
    static final String UPPER = "upper";
    static final String VALUE = "value";
    static final String KIND = "kind";
    static final String YES = "yes";
    static final String STEP = "step";
    static final String TICKS = "ticks";
    static final String AMOUNT = "amount";
    static final String PRIORITY = "priority";
    static final String STRENGTH = "strength";
    static final String FROM = "from";
    /** A request craft's network (M7L2 row 13): {@code @name} crafts on another network, empty on its own. */
    static final String NETWORK = "network";
    static final String TO = "to";
    static final String TEXT = "text";
    static final String LOG = "log";
    static final String TOAST = "toast";
    static final String CHAT = "chat";
    /** The crafting-job-failed node's item output (M7L2), and its index among the node's outputs. */
    static final String MISSING_ITEM = "missing_item";
    static final int CRAFT_FAILED_OUT_ITEM = 2;
    static final String DISPLAYS = "displays";
    static final String INTERVAL = "interval";
    static final String SECONDS = "seconds";
    static final String SAMPLES = "samples";
    static final String WINDOW = "window";
    static final String LOW = "low";
    static final String HIGH = "high";
    static final String INCLUDE_LOW = "include_low";
    static final String INCLUDE_HIGH = "include_high";
    static final String FLUID = "fluid";
    static final String TAG = "tag";
    static final String SLOT = "slot";
    static final String PORT_MODE = "port_mode";
    static final String SCOPE = "scope";
    /** A task node's board and task names (M7C item 15). */
    static final String BOARD = "board";
    static final String TASK = "task";
    static final String DONE = "done";
    static final String PROGRESS = "progress";
    static final String BOARD_SCOPE = "board_scope";
    static final String AGAIN = "again";
    static final String DIRECTION = "direction";
    static final String RADIUS = "radius";
    static final String NAME = "name";
    static final String RULE = "rule";
    static final String TIMEOUT = "timeout";
    static final String HOLD_MOST = "hold";
    static final String FIELD = "field";
    static final String CHANNEL = "channel";
    static final String STRATEGY = "strategy";
    static final String IDLE_VALUE = "idle_value";
    static final String IDLE_WHEN = "idle_when";
    static final String IDLE_LIMIT = "idle_limit";
    /** A run-rule node's settings naming the called rule's parameters its item and amount fill (M7L2 row 19). */
    static final String ITEM_PARAM = "item_param";
    static final String AMOUNT_PARAM = "amount_param";

    /**
     * The network redstone channels (M7L2) in dye colour id order, which is a channel's number: choice index 1 is
     * channel 0 (white). Choice 0, "none", addresses the label instead.
     */
    private static final String[] CHANNEL_COLOURS = {"white", "orange", "magenta", "light_blue", "yellow", "lime",
            "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    private static final String[] CHANNEL_ENGLISH = {"White", "Orange", "Magenta", "Light blue", "Yellow", "Lime",
            "Pink", "Gray", "Light gray", "Cyan", "Purple", "Blue", "Brown", "Green", "Red", "Black"};
    /** Per channel, the lang key of "the red channel" for sentences and cards. */
    private static final String[] CHANNEL_PHRASES = new String[CHANNEL_COLOURS.length];

    /** Most seconds a timer or cooldown waits (a day). */
    public static final long MAX_SECONDS = 86_400;
    /** Most samples a statistics node keeps; its memory is a ring of this size, allocated once. */
    public static final int STATS_RING = 32;
    /** Most seconds a statistics node looks back. */
    public static final long MAX_STATS_SECONDS = 3_600;
    /** Game ticks per second, for settings in seconds. */
    static final long TICKS_PER_SECOND = 20;

    /** Most ticks a delay waits (an hour). */
    public static final long MAX_DELAY_TICKS = 72_000;
    /** Most a priority setting can be, either way. */
    public static final long MAX_PRIORITY = Integer.MAX_VALUE;
    /** Most blocks a player-nearby node looks around; {@code logic.playerRadiusMax} may allow less. */
    public static final long MAX_RADIUS = 64;
    /** Most events one wait-until node holds; its memory has room for this many. */
    public static final int MAX_HELD = 16;

    private static final long MAX = SettingSpec.MAX_NUMBER;

    // Shared pieces.
    private static final Port IN_ITEM = Port.optional(ITEM_NAME, ITEM, "Item");
    private static final Port IN_LABEL = Port.optional(LABEL_NAME, LABEL, "Label");
    private static final Port IN_DO = Port.required("do", EVENT, "When");
    private static final SettingSpec ITEM_SETTING = SettingSpec.item(ITEM_NAME, true, "Item");
    private static final SettingSpec ANY_ITEM = SettingSpec.item(ITEM_NAME, false, "Item");
    private static final SettingSpec NETWORK_LABEL = SettingSpec.label(LABEL_NAME, false, "Label");
    private static final SettingSpec DEVICE_LABEL = SettingSpec.label(LABEL_NAME, true, "Label");
    private static final SettingSpec SENSOR_VALUE_NAME = SettingSpec.text(VALUE, true, "Value");
    private static final SettingSpec CROSS_MODE = SettingSpec.choice(MODE, "Mode", "above", "Above", "below", "Below");
    private static final SettingSpec THRESHOLD_SETTING = SettingSpec.number(THRESHOLD, -MAX, MAX, 0, "Limit");
    /** Which network redstone channel a redstone node uses instead of its label; "none" keeps the label. */
    private static final SettingSpec CHANNEL_SETTING = channelSetting();
    private static final SettingSpec SWITCH_BACK_SETTING = SettingSpec.number(SWITCH_BACK, -MAX, MAX, 0,
            "Switch back at");

    // Events
    public static final NodeType COUNT_CROSSES = register("count_crosses", "Item count crosses", NodeCategory.EVENTS,
            Op.COUNT_CROSSES, List.of(IN_ITEM, IN_LABEL),
            List.of(Port.output("crossed", EVENT, "Crossed"), Port.output("past", YES_NO, "Past the limit"),
                    Port.output("count", NUMBER, "Count")),
            List.of(ITEM_SETTING, NETWORK_LABEL, CROSS_MODE, THRESHOLD_SETTING, SWITCH_BACK_SETTING), 1, 1, false);
    public static final NodeType POWER_CHANGES = register("power_changes", "Power changes", NodeCategory.EVENTS,
            Op.POWER_CHANGES, List.of(),
            List.of(Port.output("changed", EVENT, "Changed"), Port.output("powered", YES_NO, "Powered")), List.of(), 1,
            1, false);
    public static final NodeType JOB_FINISHED = register("job_finished", "Crafting job finished", NodeCategory.EVENTS,
            Op.JOB_FINISHED, List.of(IN_ITEM), List.of(Port.output("finished", EVENT, "Finished")), List.of(ANY_ITEM),
            1, 0, false);
    public static final NodeType REDSTONE_CHANGES = register("redstone_changes", "Redstone changes",
            NodeCategory.EVENTS, Op.REDSTONE_CHANGES, List.of(IN_LABEL),
            List.of(Port.output("changed", EVENT, "Changed"), Port.output(STRENGTH, NUMBER, "Strength")),
            List.of(NETWORK_LABEL, CHANNEL_SETTING), 1, 1, false);
    public static final NodeType SENSOR_CROSSES = register("sensor_crosses", "Sensor value crosses",
            NodeCategory.EVENTS, Op.SENSOR_CROSSES, List.of(IN_LABEL),
            List.of(Port.output("crossed", EVENT, "Crossed"), Port.output("past", YES_NO, "Past the limit"),
                    Port.output(VALUE, NUMBER, "Value")),
            List.of(DEVICE_LABEL, SENSOR_VALUE_NAME, CROSS_MODE, THRESHOLD_SETTING, SWITCH_BACK_SETTING), 1, 1, false);

    /**
     * The busiest link on the route of the labelled devices (or the controller) crosses a load in percent, with a
     * switch-back value, like the condition switch's link load. Load falls with time and no event says so: while over
     * the limit the controller looks again every {@code condition.minIntervalTicks}.
     */
    public static final NodeType LINK_OVERLOADED = register("link_overloaded", "Link overloaded", NodeCategory.EVENTS,
            Op.LOAD_CROSSES, List.of(IN_LABEL),
            List.of(Port.output("overloaded", EVENT, "Overloaded"), Port.output("over", YES_NO, "Over the limit"),
                    Port.output("load", NUMBER, "Load")),
            List.of(NETWORK_LABEL, SettingSpec.number(THRESHOLD, 0, 100, 90, "Limit"),
                    SettingSpec.number(SWITCH_BACK, 0, 100, 90, "Switch back at")),
            1, 1, false);
    /** A device with the label joined or left the network, or got or lost the label. */
    public static final NodeType LABEL_DEVICES = register("label_devices", "Labelled device added or removed",
            NodeCategory.EVENTS, Op.DEVICES_CHANGE, List.of(IN_LABEL), List.of(Port.output("added", EVENT, "Added"),
                    Port.output("removed", EVENT, "Removed"), Port.output("devices", NUMBER, "Devices")),
            List.of(DEVICE_LABEL), 1, 1, false);
    /** A drive cell (in the labelled drives, or anywhere on the network) turned full. */
    public static final NodeType STORAGE_FULL = register("storage_full", "Drive or cell full", NodeCategory.EVENTS,
            Op.CELLS_FULL, List.of(IN_LABEL), List.of(Port.output("filled", EVENT, "A cell filled"),
                    Port.output("any_full", YES_NO, "Any cell full"), Port.output("full_cells", NUMBER, "Full cells")),
            List.of(NETWORK_LABEL), 1, 1, false);

    // Read
    public static final NodeType ITEM_COUNT = register("item_count", "Item count", NodeCategory.READ, Op.ITEM_COUNT,
            List.of(IN_ITEM, IN_LABEL), List.of(Port.output("count", NUMBER, "Count")),
            List.of(ITEM_SETTING, NETWORK_LABEL), 1, 0, false);
    public static final NodeType SENSOR_VALUE = register("sensor_value", "Sensor value", NodeCategory.READ,
            Op.SENSOR_VALUE, List.of(IN_LABEL), List.of(Port.output(VALUE, NUMBER, "Value")),
            List.of(DEVICE_LABEL, SENSOR_VALUE_NAME), 1, 0, false);
    public static final NodeType DEVICE_STATE = register("device_state", "Device state", NodeCategory.READ,
            Op.DEVICE_STATE, List.of(IN_LABEL), List.of(Port.output("on", YES_NO, "On")), List.of(DEVICE_LABEL), 1, 0,
            false);
    /**
     * The strongest redstone signal, 0 to 15, into the controller (empty label) or into any device with the label, and
     * whether there is any; a plain read of what {@link #REDSTONE_CHANGES} watches.
     */
    public static final NodeType REDSTONE_STRENGTH = register("redstone_strength", "Redstone strength",
            NodeCategory.READ, Op.REDSTONE_STRENGTH, List.of(IN_LABEL),
            List.of(Port.output(STRENGTH, NUMBER, "Strength"), Port.output("powered", YES_NO, "Powered")),
            List.of(NETWORK_LABEL, CHANNEL_SETTING), 1, 0, false);
    /** One output whose type the kind setting picks; the outputs here are in the kind choice's order. */
    public static final NodeType CONSTANT = register("constant", "Constant", NodeCategory.READ, Op.CONSTANT, List.of(),
            List.of(Port.output(VALUE, NUMBER, "Value"), Port.output(VALUE, YES_NO, "Value"),
                    Port.output(VALUE, ITEM, "Value"), Port.output(VALUE, LABEL, "Value")),
            List.of(SettingSpec.choice(KIND, "Kind", "number", "Number", "yes_no", "Yes/no", "item", "Item", "label",
                    "Label"), SettingSpec.number(VALUE, -MAX, MAX, 0, "Value"), SettingSpec.flag(YES, true, "Yes"),
                    ANY_ITEM, NETWORK_LABEL),
            1, 0, false);

    // Check
    /**
     * The limit is a setting, or a number wired into its "Limit" input (M7L2 row 19, so a rule's parameter can set it);
     * a wired limit has no switch-back.
     */
    public static final NodeType COMPARE = register("compare", "Compare", NodeCategory.CHECK, Op.COMPARE,
            List.of(Port.required(VALUE, NUMBER, "Value"), Port.optional(THRESHOLD, NUMBER, "Limit")),
            List.of(Port.output("result", YES_NO, "Result"), Port.output("turned_yes", EVENT, "Turned yes"),
                    Port.output("turned_no", EVENT, "Turned no")),
            List.of(SettingSpec.choice(MODE, "Mode", "above", "Above", "below", "Below", "between", "Between", "equal",
                    "Equal"), THRESHOLD_SETTING, SettingSpec.number(UPPER, -MAX, MAX, 0, "Upper limit"),
                    SWITCH_BACK_SETTING),
            1, 1, false);
    public static final NodeType AND = register("and", "And", NodeCategory.CHECK, Op.AND,
            List.of(Port.required("a", YES_NO, "A"), Port.required("b", YES_NO, "B")),
            List.of(Port.output("result", YES_NO, "Result")), List.of(), 1, 0, false);
    public static final NodeType OR = register("or", "Or", NodeCategory.CHECK, Op.OR,
            List.of(Port.required("a", YES_NO, "A"), Port.required("b", YES_NO, "B")),
            List.of(Port.output("result", YES_NO, "Result")), List.of(), 1, 0, false);
    public static final NodeType NOT = register("not", "Not", NodeCategory.CHECK, Op.NOT,
            List.of(Port.required(VALUE, YES_NO, "Value")), List.of(Port.output("result", YES_NO, "Result")), List.of(),
            1, 0, false);
    public static final NodeType IF = register("if", "If", NodeCategory.CHECK, Op.IF,
            List.of(Port.required("when", EVENT, "When"), Port.required("condition", YES_NO, "Condition")),
            List.of(Port.output("then", EVENT, "Then"), Port.output("otherwise", EVENT, "Otherwise")), List.of(), 1, 0,
            false);

    // Remember
    public static final NodeType LATCH = register("latch", "Latch", NodeCategory.REMEMBER, Op.LATCH,
            List.of(Port.required("set", EVENT, "Set"), Port.optional("reset", EVENT, "Reset")),
            List.of(Port.output("state", YES_NO, "State")), List.of(), 1, 1, true);
    public static final NodeType COUNTER = register("counter", "Counter", NodeCategory.REMEMBER, Op.COUNTER,
            List.of(Port.required("up", EVENT, "Count up"), Port.optional("down", EVENT, "Count down"),
                    Port.optional("reset", EVENT, "Reset")),
            List.of(Port.output("count", NUMBER, "Count")), List.of(SettingSpec.number(STEP, 1, MAX, 1, "Step")), 1, 1,
            true);
    public static final NodeType HOLD = register("hold", "Remember value", NodeCategory.REMEMBER, Op.HOLD,
            List.of(Port.required(VALUE, NUMBER, "Value"), Port.required("store", EVENT, "Store")),
            List.of(Port.output(VALUE, NUMBER, "Value")), List.of(), 1, 1, true);

    // Act
    public static final NodeType DEVICE_SWITCH = register("device_switch", "Switch device", NodeCategory.ACT,
            Op.DEVICE_SWITCH, List.of(Port.required("on", YES_NO, "On"), IN_LABEL), List.of(), List.of(DEVICE_LABEL), 2,
            1, false);
    public static final NodeType SET_PRIORITY = register("set_priority", "Set priority", NodeCategory.ACT,
            Op.SET_PRIORITY, List.of(IN_DO, Port.optional(PRIORITY, NUMBER, "Priority"), IN_LABEL), List.of(),
            List.of(DEVICE_LABEL, SettingSpec.number(PRIORITY, -MAX_PRIORITY, MAX_PRIORITY, 0, "Priority")), 2, 0,
            false);
    public static final NodeType REDSTONE_OUT = register("redstone_out", "Redstone out", NodeCategory.ACT,
            Op.REDSTONE_OUT,
            List.of(Port.optional("on", YES_NO, 1, "On"), Port.optional(STRENGTH, NUMBER, "Strength"), IN_LABEL),
            List.of(), List.of(DEVICE_LABEL, SettingSpec.number(STRENGTH, 0, 15, 15, "Strength"), CHANNEL_SETTING), 2,
            1, false);
    public static final NodeType REQUEST_CRAFT = register(
            "request_craft", "Request craft", NodeCategory.ACT, Op.REQUEST_CRAFT,
            List.of(IN_DO, IN_ITEM, Port.optional(AMOUNT, NUMBER, "Amount")), List.of(), List.of(ITEM_SETTING,
                    SettingSpec.number(AMOUNT, 1, MAX, 1, "Amount"), SettingSpec.label(NETWORK, false, "Network")),
            2, 0, false);
    /** Moves between the network's storage and labelled devices; an empty label is the network's storage. */
    public static final NodeType MOVE_ITEMS = register("move_items", "Move items", NodeCategory.ACT, Op.MOVE_ITEMS,
            List.of(IN_DO, IN_ITEM, Port.optional(AMOUNT, NUMBER, "Amount"), Port.optional(FROM, LABEL, "From"),
                    Port.optional(TO, LABEL, "To")),
            List.of(), List.of(ITEM_SETTING, SettingSpec.label(FROM, false, "From"), SettingSpec.label(TO, false, "To"),
                    SettingSpec.number(AMOUNT, 1, MAX, 64, "Amount")),
            4, 0, false);
    public static final NodeType NOTIFY = register("notify", "Notify", NodeCategory.ACT, Op.NOTIFY, List.of(IN_DO),
            List.of(),
            List.of(SettingSpec.text(TEXT, true, "Message"), SettingSpec.flag(LOG, true, "Network log"),
                    SettingSpec.flag(TOAST, true, "Team toast"), SettingSpec.flag(CHAT, false, "Team chat"),
                    SettingSpec.flag(DISPLAYS, false, "Bound displays")),
            2, 0, false);

    // Time
    /**
     * Fires "done" a set number of ticks after "start", through one scheduled wake (never polling). A start while it is
     * already counting down is ignored, not a restart, so a trigger that keeps firing cannot push "done" back forever;
     * the why trace says so. The due time is remembered across saves.
     */
    public static final NodeType DELAY = register("delay", "Delay", NodeCategory.TIME, Op.DELAY,
            List.of(Port.required("start", EVENT, "Start")), List.of(Port.output("done", EVENT, "Done")),
            List.of(SettingSpec.number(TICKS, 1, MAX_DELAY_TICKS, 20, "Ticks")), 1, 1, true);

    // Extra node types, batch 1 (owner decision 2026-10-07: many node types). Ids are saved like the ones above.

    /**
     * Fires every {@code interval} seconds while "on" is yes (unwired: always), through one scheduled wake at the due
     * tick, never polling. It first fires one interval after it starts; a run that comes late (the budget, an unloaded
     * network) fires once and counts the next interval from then, so it never fires in a burst. The due tick is
     * remembered across saves.
     */
    public static final NodeType TIMER = register("timer", "Timer", NodeCategory.EVENTS, Op.TIMER,
            List.of(Port.optional("on", YES_NO, 1, "On")), List.of(Port.output("fired", EVENT, "Fired")),
            List.of(SettingSpec.number(INTERVAL, 1, MAX_SECONDS, 10, "Every (seconds)")), 1, 1, false);
    /**
     * Fires once when the graph starts: saved, switched on, or loaded with the world or its chunk. Not when the network
     * merely comes back online.
     */
    public static final NodeType GRAPH_START = register("graph_start", "Graph starts", NodeCategory.EVENTS,
            Op.GRAPH_START, List.of(), List.of(Port.output("started", EVENT, "Started")), List.of(), 1, 0, false);
    /** Day turned to night or back in the controller's level; nothing on the first look. */
    public static final NodeType DAYTIME_CHANGES = register("daytime_changes", "Day or night begins",
            NodeCategory.EVENTS, Op.DAYTIME_CHANGES, List.of(), List.of(Port.output("day_began", EVENT, "Day began"),
                    Port.output("night_began", EVENT, "Night began"), Port.output("day", YES_NO, "Day")),
            List.of(), 1, 1, false);
    /** The weather in the controller's level changed between clear, rain and thunder; nothing on the first look. */
    public static final NodeType WEATHER_CHANGES = register("weather_changes", "Weather changes", NodeCategory.EVENTS,
            Op.WEATHER_CHANGES, List.of(),
            List.of(Port.output("changed", EVENT, "Changed"), Port.output("weather", NUMBER, "Weather")), List.of(), 1,
            1, false);

    /** Whether it is day, and the time of day in ticks (0 to 23999; 0 is sunrise), read afresh on every run. */
    public static final NodeType DAYTIME = register("daytime", "Daytime", NodeCategory.READ, Op.DAYTIME, List.of(),
            List.of(Port.output("day", YES_NO, "Day"), Port.output("time", NUMBER, "Time of day")), List.of(), 1, 0,
            false);
    /** The weather: 0 clear, 1 rain, 2 thunder, and whether it rains (or thunders). */
    public static final NodeType WEATHER = register("weather", "Weather", NodeCategory.READ, Op.WEATHER, List.of(),
            List.of(Port.output("weather", NUMBER, "Weather"), Port.output("raining", YES_NO, "Raining")), List.of(), 1,
            0, false);

    /**
     * Arithmetic on two numbers. Dividing (or taking the remainder) by zero gives 0 and says so in the why line;
     * results are kept within the number range.
     */
    public static final NodeType MATH = register("math", "Math", NodeCategory.CHECK, Op.MATH,
            List.of(Port.optional("a", NUMBER, "A"), Port.optional("b", NUMBER, "B")),
            List.of(Port.output("result", NUMBER, "Result")),
            List.of(SettingSpec.choice("operation", "Operation", "add", "Add", "subtract", "Subtract", "multiply",
                    "Multiply", "divide", "Divide", "min", "Smaller", "max", "Larger", "modulo", "Remainder"),
                    SettingSpec.number("a", -MAX, MAX, 0, "A"), SettingSpec.number("b", -MAX, MAX, 0, "B")),
            1, 0, false);
    /** Whether a number lies between a low and a high bound, each included or not; swapped bounds are put right. */
    public static final NodeType BETWEEN = register(
            "between", "Between", NodeCategory.CHECK, Op.BETWEEN, List.of(Port.required(VALUE, NUMBER, "Value"),
                    Port.optional(LOW, NUMBER, "Low"), Port.optional(HIGH, NUMBER, "High")),
            List.of(Port.output("result", YES_NO, "Result")),
            List.of(SettingSpec.number(LOW, -MAX, MAX, 0, "Low"), SettingSpec.number(HIGH, -MAX, MAX, 100, "High"),
                    SettingSpec.flag(INCLUDE_LOW, true, "Include low"),
                    SettingSpec.flag(INCLUDE_HIGH, true, "Include high")),
            1, 0, false);
    /** Picks A while "choose A" is yes, else B. */
    public static final NodeType SELECT = register("select", "Select", NodeCategory.CHECK, Op.SELECT,
            List.of(Port.required("choose", YES_NO, "Choose A"), Port.optional("a", NUMBER, "A"),
                    Port.optional("b", NUMBER, "B")),
            List.of(Port.output(VALUE, NUMBER, "Value")),
            List.of(SettingSpec.number("a", -MAX, MAX, 0, "A"), SettingSpec.number("b", -MAX, MAX, 0, "B")), 1, 0,
            false);
    public static final NodeType XOR = register("xor", "Either, not both", NodeCategory.CHECK, Op.XOR,
            List.of(Port.required("a", YES_NO, "A"), Port.required("b", YES_NO, "B")),
            List.of(Port.output("result", YES_NO, "Result")), List.of(), 1, 0, false);

    /** Each event into "flip" turns the state over; reset turns it off and wins. */
    public static final NodeType TOGGLE = register("toggle", "Toggle", NodeCategory.REMEMBER, Op.TOGGLE,
            List.of(Port.required("flip", EVENT, "Flip"), Port.optional("reset", EVENT, "Reset")),
            List.of(Port.output("state", YES_NO, "State")), List.of(), 1, 1, true);
    /** Turns a yes/no into events when it changes; nothing on the first look, nothing again after a load. */
    public static final NodeType EDGE = register("edge", "Turns yes or no", NodeCategory.REMEMBER, Op.EDGE,
            List.of(Port.required(VALUE, YES_NO, "Value")),
            List.of(Port.output("turned_yes", EVENT, "Turned yes"), Port.output("turned_no", EVENT, "Turned no")),
            List.of(), 1, 1, true);
    /**
     * Average, lowest and highest of a number over its last samples or its samples of the last seconds. It samples when
     * "sample" fires, or, unwired, whenever the value changes. Memory is a fixed ring of {@link #STATS_RING} samples;
     * with no sample in the window the outputs are the value itself. In the seconds window it wakes once when its
     * oldest sample leaves the window.
     */
    public static final NodeType STATS = register("stats", "Statistics", NodeCategory.REMEMBER, Op.STATS,
            List.of(Port.required(VALUE, NUMBER, "Value"), Port.optional("sample", EVENT, "Sample")),
            List.of(Port.output("average", NUMBER, "Average"), Port.output("min", NUMBER, "Lowest"),
                    Port.output("max", NUMBER, "Highest")),
            List.of(SettingSpec.choice(WINDOW, "Window", "samples", "Last samples", "seconds", "Last seconds"),
                    SettingSpec.number(SAMPLES, 1, STATS_RING, 10, "Samples"),
                    SettingSpec.number(SECONDS, 1, MAX_STATS_SECONDS, 60, "Seconds")),
            8, LogicEvaluator.STATS_MEMORY, true);
    /**
     * How fast a number changes, per second, from the difference to the previous sample and the game time between them.
     * It samples when "sample" fires, or, unwired, whenever the value changes (so it shows the last rate seen until the
     * value changes again; wire a timer into "sample" to see it fall to 0).
     */
    public static final NodeType CHANGE_RATE = register("change_rate", "Change per second", NodeCategory.REMEMBER,
            Op.CHANGE_RATE, List.of(Port.required(VALUE, NUMBER, "Value"), Port.optional("sample", EVENT, "Sample")),
            List.of(Port.output("rate", NUMBER, "Per second")), List.of(), 3, 3, true);

    /**
     * Lets an event through at most once every so many seconds; the ones in between are dropped and the why line says
     * how long is left. The time it last let one through is remembered across saves.
     */
    public static final NodeType COOLDOWN = register("cooldown", "Cooldown", NodeCategory.TIME, Op.COOLDOWN,
            List.of(Port.required("in", EVENT, "Event")), List.of(Port.output("passed", EVENT, "Let through")),
            List.of(SettingSpec.number(SECONDS, 1, MAX_SECONDS, 5, "Seconds")), 1, 1, false);

    // Extra node types, batch 2: reads. Ids are saved like the ones above.

    /**
     * Millibuckets of a fluid, or of every fluid in a fluid tag, on the network or in the labelled storage: the item
     * count's read, through the same watch.
     */
    public static final NodeType FLUID_AMOUNT = register("fluid_amount", "Fluid amount", NodeCategory.READ,
            Op.ITEM_COUNT, List.of(Port.optional(FLUID, ITEM, "Fluid"), IN_LABEL),
            List.of(Port.output("amount_mb", NUMBER, "Amount (mB)")),
            List.of(SettingSpec.fluid(FLUID, true, "Fluid"), NETWORK_LABEL), 1, 0, false);
    /**
     * FE stored in the network's store, or in the labelled energy cells and controllers, with the capacity and how full
     * it is in percent. Looked at most once per {@code condition.minIntervalTicks}, since it can change every tick.
     */
    public static final NodeType ENERGY = register("energy", "Energy", NodeCategory.READ, Op.ENERGY, List.of(IN_LABEL),
            List.of(Port.output("stored", NUMBER, "Stored MU"), Port.output("capacity", NUMBER, "Capacity MU"),
                    Port.output("percent", NUMBER, "Percent full")),
            List.of(NETWORK_LABEL), 1, 0, false);
    /**
     * Free and total bytes of the network's storage cells, or of the cells in the labelled drives, and how full they
     * are in percent. Bulk drives and storage chests count items, not bytes, and are left out. Looked at once per
     * {@code condition.minIntervalTicks} at most, after the contents changed.
     */
    public static final NodeType FREE_SPACE = register("free_space", "Free storage", NodeCategory.READ, Op.FREE_SPACE,
            List.of(IN_LABEL), List.of(Port.output("free_bytes", NUMBER, "Free bytes"),
                    Port.output("total_bytes", NUMBER, "Total bytes"), Port.output("percent", NUMBER, "Percent full")),
            List.of(NETWORK_LABEL), 1, 0, false);
    /**
     * Whether a crafting job for the item (any item when empty) runs on a CPU, how many wait for one, how many CPUs
     * could take a job of the network now (idle ones on it or on a subnet linked to it), and how many of those an
     * automated job may take (not kept free for players, M7L2 chunk 20). Changes with each job that is queued, starts
     * or ends.
     */
    public static final NodeType CRAFT_STATUS = register("craft_status", "Crafting status", NodeCategory.READ,
            Op.CRAFT_STATUS, List.of(IN_ITEM),
            List.of(Port.output("running", YES_NO, "Running"), Port.output("waiting", NUMBER, "Waiting jobs"),
                    Port.output("free_cpus", NUMBER, "Free CPUs"),
                    Port.output("auto_cpus", NUMBER, "Free CPUs for automation")),
            List.of(ANY_ITEM), 1, 0, false);
    /** How many items of an item tag the network (or the labelled storage) holds: the item count's read for tags. */
    public static final NodeType COUNT_BY_TAG = register("count_by_tag", "Count by tag", NodeCategory.READ,
            Op.ITEM_COUNT, List.of(Port.optional(TAG, ITEM, "Tag"), IN_LABEL),
            List.of(Port.output("count", NUMBER, "Count")), List.of(SettingSpec.tag(TAG, true, "Tag"), NETWORK_LABEL),
            1, 0, false);

    // Extra node types, batch 3: actions. Ids are saved like the ones above.

    /**
     * Cancels the waiting and running crafting jobs for an item on the network, or only those this graph requested
     * (tracked while the controller is loaded). Through the job queue's cancel, which gives back what a job holds; jobs
     * the controller's owner may not cancel are left alone.
     */
    public static final NodeType CANCEL_CRAFT = register("cancel_craft", "Cancel craft", NodeCategory.ACT,
            Op.CANCEL_CRAFT, List.of(IN_DO, IN_ITEM), List.of(),
            List.of(ITEM_SETTING,
                    SettingSpec.choice(SCOPE, "Which jobs", "all", "Every job for it", "own", "Only this graph's")),
            2, 0, false);
    /**
     * Turns the labelled condition switches in logic mode on for a number of ticks, then off again through one
     * scheduled wake. A new pulse while one is on starts it again or is ignored (setting). The time it switches off is
     * remembered across saves, so a pulse never stays on.
     */
    public static final NodeType PULSE_REDSTONE = register("pulse_redstone", "Pulse redstone", NodeCategory.ACT,
            Op.PULSE_REDSTONE, List.of(IN_DO, Port.optional(STRENGTH, NUMBER, "Strength"), IN_LABEL), List.of(),
            List.of(DEVICE_LABEL, SettingSpec.number(TICKS, 1, MAX_DELAY_TICKS, 10, "Ticks"),
                    SettingSpec.number(STRENGTH, 0, 15, 15, "Strength"),
                    SettingSpec.choice(AGAIN, "A pulse while on", "restart", "Starts it again", "ignore", "Is ignored"),
                    CHANNEL_SETTING),
            2, 2, false);
    /**
     * Shows a text on the labelled display panels and display parts, as their text source; {@code {a}} and {@code {b}}
     * in it show the inputs. Writes when "when" fires, or, unwired, whenever a or b changes. The controller writes one
     * display at most once per {@code logic.displayWriteTicks}; a later text waits for that and is then shown.
     */
    public static final NodeType WRITE_DISPLAY = register("write_display", "Write to display", NodeCategory.ACT,
            Op.WRITE_DISPLAY,
            List.of(Port.optional("do", EVENT, "When"), Port.optional("a", NUMBER, "A"),
                    Port.optional("b", NUMBER, "B"), IN_LABEL),
            List.of(), List.of(DEVICE_LABEL, SettingSpec.text(TEXT, true, "Message"),
                    SettingSpec.number("a", -MAX, MAX, 0, "A"), SettingSpec.number("b", -MAX, MAX, 0, "B")),
            2, 0, false);
    /**
     * Sets one filter slot of the labelled ports to an item (empty clears it) and, or, switches their mode between
     * import and export, as a player's edit would, saved with the port. Only for an owner allowed to change the port.
     */
    public static final NodeType SET_FILTER = register("set_filter", "Set port filter", NodeCategory.ACT, Op.SET_FILTER,
            List.of(IN_DO, IN_ITEM, IN_LABEL), List.of(),
            List.of(DEVICE_LABEL, SettingSpec.number(SLOT, 0, 9, 1, "Filter slot (0: none)"), ANY_ITEM, SettingSpec
                    .choice(PORT_MODE, "Port mode", "keep", "Keep it", "import", "Import", "export", "Export")),
            2, 0, false);

    // Extra node types, batch 4: hooks. Ids are saved like the ones above.

    /**
     * Fires when items of an item or tag (any item when empty) go into or out of the network through a labelled device
     * (any device, or a player at a terminal, when the label is empty): once per committed transfer, with the amount.
     * The network looks the moved item up only while some graph listens for it, so transfers cost nothing otherwise.
     */
    public static final NodeType ITEM_MOVES = register("item_moves", "Items move in or out", NodeCategory.EVENTS,
            Op.ITEM_MOVES, List.of(IN_ITEM, IN_LABEL),
            List.of(Port.output("moved", EVENT, "Moved"), Port.output(AMOUNT, NUMBER, "Amount")),
            List.of(ANY_ITEM, NETWORK_LABEL, SettingSpec.choice(DIRECTION, "Direction", "both", "In or out", "into",
                    "Into the network", "out_of", "Out of the network")),
            1, 0, false);
    /**
     * Fires when a crafting job for the item, or any item of the tag (any job when empty), stops with something
     * missing: it ends partial, or cannot start for want of materials. The counterpart of "Crafting job finished";
     * "missing" is how many of the first missing item were short, and "missing item" is that item: wired into the item
     * input of item count, request craft, move items, send to pool or cancel craft ({@link #takesMissingItem}), those
     * take it in the run the job failed ("craft 16 of the missing item"), and a notice's {@code {item}} names it. The
     * why line lists every missing item.
     */
    public static final NodeType CRAFT_FAILED = register("craft_failed", "Crafting job failed", NodeCategory.EVENTS,
            Op.CRAFT_FAILED, List.of(IN_ITEM), List.of(Port.output("failed", EVENT, "Failed"),
                    Port.output("missing", NUMBER, "Missing amount"), Port.output(MISSING_ITEM, ITEM, "Missing item")),
            List.of(ANY_ITEM), 1, 0, false);
    /**
     * Players within a radius of a labelled device (or the controller): fires when one comes or leaves. Looked at every
     * {@code logic.playerCheckTicks} while a graph uses it, since no event says a player moved; nothing on the first
     * look.
     */
    public static final NodeType PLAYER_NEARBY = register("player_nearby", "Player nearby", NodeCategory.EVENTS,
            Op.PLAYERS_NEAR, List.of(IN_LABEL),
            List.of(Port.output("came", EVENT, "A player came"), Port.output("left", EVENT, "A player left"),
                    Port.output("near", YES_NO, "Anyone near"), Port.output("players", NUMBER, "Players")),
            List.of(NETWORK_LABEL, SettingSpec.number(RADIUS, 1, MAX_RADIUS, 8, "Radius (blocks)")), 1, 1, false);
    /** Fires when a player opens a terminal of the network with the label (any terminal when empty). */
    public static final NodeType PLAYER_USES_TERMINAL = register("player_uses_terminal", "Player opens a terminal",
            NodeCategory.EVENTS, Op.TERMINAL_OPENED, List.of(IN_LABEL), List.of(Port.output("opened", EVENT, "Opened")),
            List.of(NETWORK_LABEL), 1, 0, false);
    /** Fires when another graph of the controller runs this one ("Run another rule"). */
    public static final NodeType RUN_BY_RULE = register("run_by_rule", "Run by another rule", NodeCategory.EVENTS,
            Op.RULE_CALLED, List.of(), List.of(Port.output("ran", EVENT, "Run")), List.of(), 1, 0, false);
    /**
     * A value shared by the graphs of one controller, by name: a number, or yes/no as 1 and 0. Saved with the
     * controller; 0 until a graph sets it.
     */
    public static final NodeType SHARED_VARIABLE = register("shared_variable", "Shared variable", NodeCategory.READ,
            Op.VARIABLE, List.of(), List.of(Port.output(VALUE, NUMBER, "Value"), Port.output(YES, YES_NO, "Yes")),
            List.of(SettingSpec.text(NAME, true, "Name")), 1, 0, false);
    /**
     * Sets a shared variable of the controller, which every graph reading it sees. Writes when "when" fires, or,
     * unwired, whenever its value changes. The controller holds at most {@code logic.maxVariables} of them.
     */
    public static final NodeType SET_SHARED_VARIABLE = register("set_shared_variable", "Set shared variable",
            NodeCategory.ACT, Op.SET_VARIABLE,
            List.of(Port.optional("do", EVENT, "When"), Port.optional(VALUE, NUMBER, "Value"),
                    Port.optional(YES, YES_NO, 1, "Yes")),
            List.of(),
            List.of(SettingSpec.text(NAME, true, "Name"),
                    SettingSpec.choice(KIND, "Kind", "number", "Number", "yes_no", "Yes/no"),
                    SettingSpec.number(VALUE, -MAX, MAX, 0, "Value")),
            2, 0, false);
    /**
     * A task on a task board (M7C item 15), by board and task name, the team's board or the network's: whether it is
     * done, and its goal's progress (the item's count, the items crafted, the labelled devices). The board's screens
     * and every other change tell the controller; nothing is read per tick.
     */
    public static final NodeType TASK_STATE = register("task_state", "Task", NodeCategory.READ, Op.TASK, List.of(),
            List.of(Port.output(DONE, YES_NO, "Done"), Port.output(PROGRESS, NUMBER, "Progress")),
            List.of(SettingSpec.text(BOARD, false, "Board"), SettingSpec.text(TASK, true, "Task"), taskScope()), 1, 0,
            false);
    /**
     * Ticks a task on a task board or opens it again (M7C item 15), as a player's right-click would; writes when "when"
     * fires, or, unwired, whenever "done" changes.
     */
    public static final NodeType SET_TASK = register("set_task", "Set task", NodeCategory.ACT, Op.SET_TASK,
            List.of(Port.optional("do", EVENT, "When"), Port.optional(DONE, YES_NO, 1, "Done")), List.of(),
            List.of(SettingSpec.text(BOARD, false, "Board"), SettingSpec.text(TASK, true, "Task"), taskScope()), 2, 0,
            false);
    /**
     * Runs another graph of the same controller, named or by its row number: its "Run by another rule" nodes fire. A
     * graph that would end up running itself is paused with an error; calls nest at most {@code logic.maxRuleDepth}
     * deep.
     */
    public static final NodeType RUN_RULE = register("run_rule", "Run another rule", NodeCategory.ACT, Op.RUN_RULE,
            List.of(IN_DO, IN_ITEM, Port.optional(AMOUNT, NUMBER, "Amount")), List.of(),
            List.of(SettingSpec.text(RULE, true, "Rule (name or row number)"), ANY_ITEM,
                    SettingSpec.text(ITEM_PARAM, false, "Item fills parameter"),
                    SettingSpec.number(AMOUNT, -MAX, MAX, 0, "Amount"),
                    SettingSpec.text(AMOUNT_PARAM, false, "Amount fills parameter")),
            2, 0, false);
    /**
     * Holds each event until a yes/no turns yes, then lets them through one per run, oldest first; an event held longer
     * than the timeout leaves through "gave up" instead. Holds at most a set number; the held events and their timeouts
     * are remembered across saves.
     */
    public static final NodeType WAIT_UNTIL = register("wait_until", "Wait until", NodeCategory.TIME, Op.WAIT_UNTIL,
            List.of(Port.required("in", EVENT, "Event"), Port.required("condition", YES_NO, "Condition")),
            List.of(Port.output("passed", EVENT, "Let through"), Port.output("timed_out", EVENT, "Gave up"),
                    Port.output("held", NUMBER, "Held")),
            List.of(SettingSpec.number(TIMEOUT, 0, MAX_SECONDS, 60, "Give up after (seconds, 0: never)"),
                    SettingSpec.number(HOLD_MOST, 1, MAX_HELD, 4, "Most held")),
            1, 1 + MAX_HELD, false);

    // Logic controls the Stock Keeper (M7L2 row 15). Ids are saved like the ones above.

    /**
     * A labelled stock keeper's rule for an item: its keep amount, restart threshold and batch, the item's count in the
     * network, whether the count is below where the rule starts filling, whether a background job for it is out and
     * whether the rule is switched on. Read from the keeper's own state, which tells the controller when it changes.
     */
    public static final NodeType STOCK_STATUS = register("stock_status", "Stock rule status", NodeCategory.READ,
            Op.STOCK_STATUS, List.of(IN_ITEM, IN_LABEL),
            List.of(Port.output("keep", NUMBER, "Keep"), Port.output("restart", NUMBER, "Restart below"),
                    Port.output("batch", NUMBER, "Batch"), Port.output("count", NUMBER, "Count"),
                    Port.output("below", YES_NO, "Below restart"), Port.output("crafting", YES_NO, "Job out"),
                    Port.output("on", YES_NO, "On")),
            List.of(ITEM_SETTING, DEVICE_LABEL), 1, 0, false);
    /**
     * Fires when a labelled stock keeper's rule for the item (any rule when empty) cannot be satisfied: its request was
     * refused or its job ended with something missing. Once per change, and "recovered" once a job ends done or the
     * stock is reached.
     */
    public static final NodeType STOCK_SHORT = register("stock_short", "Stock rule short", NodeCategory.EVENTS,
            Op.STOCK_SHORT, List.of(IN_ITEM, IN_LABEL), List.of(Port.output("went_short", EVENT, "Went short"),
                    Port.output("recovered", EVENT, "Recovered"), Port.output("short", YES_NO, "Short")),
            List.of(ANY_ITEM, DEVICE_LABEL), 1, 1, false);
    /**
     * Switches a labelled stock keeper's rule for the item (every rule when empty) on or off, as its screen's button
     * does. A switched-off rule requests nothing; a job it has out keeps running.
     */
    public static final NodeType STOCK_RULE_SWITCH = register("stock_rule_switch", "Switch stock rule",
            NodeCategory.ACT, Op.STOCK_SWITCH, List.of(Port.required("on", YES_NO, "On"), IN_ITEM, IN_LABEL), List.of(),
            List.of(DEVICE_LABEL, ANY_ITEM), 2, 1, false);
    /**
     * Sets the keep amount, restart threshold or batch of a labelled stock keeper's rule for the item, clamped as the
     * keeper's screen clamps it. Sets it when "when" fires, or, unwired, whenever the amount changes. Only an existing
     * rule changes.
     */
    public static final NodeType STOCK_RULE_SET = register("stock_rule_set", "Set stock amount", NodeCategory.ACT,
            Op.STOCK_SET,
            List.of(Port.optional("do", EVENT, "When"), Port.optional(AMOUNT, NUMBER, "Amount"), IN_ITEM, IN_LABEL),
            List.of(),
            List.of(DEVICE_LABEL, ITEM_SETTING, SettingSpec.choice(FIELD, "Which amount", "keep_amount", "Keep",
                    "restart_below", "Restart below", "batch_size", "Batch"),
                    SettingSpec.number(AMOUNT, 0, MAX, 64, "Amount")),
            2, 0, false);

    // Step sequences (M7L2 row 16). Ids are saved like the ones above.

    /**
     * Named steps run one after another (fill, wait, empty, clean): each step moves on when its "go on" input is yes or
     * its time limit passes, a "go to" input holds the sequence at its step, and "reset" starts again at the first. The
     * step and when its time limit is due are remembered across saves; the time limit is one scheduled wake. See
     * {@link Sequences} for the order in which the inputs count.
     */
    public static final NodeType SEQUENCE = register("sequence", "Step sequence", NodeCategory.REMEMBER, Op.SEQUENCE,
            sequenceInputs(), sequenceOutputs(), sequenceSettings(), 1, Sequences.MEMORY, true);
    /** Yes while the sequence wired in is at a step, named or by number; the compiler resolves the name. */
    public static final NodeType STEP_IS = register("step_is", "Step is", NodeCategory.CHECK, Op.STEP_IS,
            List.of(Port.required(Sequences.STEP, NUMBER, "Step")), List.of(Port.output("result", YES_NO, "Result")),
            List.of(SettingSpec.text(Sequences.WHICH, true, "Step (name or number)")), 1, 0, false);

    // Machine pools (M7L2 row 17). Ids are saved like the ones above. A member is idle while the sensor value named in
    // "idle_value" on its machine meets "idle_when" and "idle_limit"; with no value named every member counts as idle.

    /**
     * Sends items from the network's storage to the idle members of a machine pool (the ports and interfaces with the
     * label) by a strategy: the first idle one, round robin (the turn is saved), the least full, or spread evenly. A
     * member that takes less leaves the rest for the next; members the owner may not change are skipped.
     */
    public static final NodeType MOVE_TO_POOL = register("move_to_pool", "Send to pool", NodeCategory.ACT, Op.POOL_SEND,
            List.of(IN_DO, Port.optional(AMOUNT, NUMBER, "Amount"), IN_ITEM, IN_LABEL), List.of(),
            List.of(SettingSpec.choice(STRATEGY, "Strategy", "first_idle", "First idle", "round_robin", "Round robin",
                    "least_full", "Least full", "spread_evenly", "Spread evenly"), DEVICE_LABEL, ITEM_SETTING,
                    SettingSpec.number(AMOUNT, 1, MAX, 16, "Amount"), idleValue(false), idleWhen(), idleLimit()),
            4, 1, false);
    /** How many members a machine pool has and how many of them are idle or busy now. */
    public static final NodeType POOL_STATUS = register("pool_status", "Pool status", NodeCategory.READ, Op.POOL_STATUS,
            List.of(IN_LABEL),
            List.of(Port.output("members", NUMBER, "Members"), Port.output("idle", NUMBER, "Idle"),
                    Port.output("busy", NUMBER, "Busy"), Port.output("any_idle", YES_NO, "Any idle")),
            List.of(DEVICE_LABEL, idleValue(false), idleWhen(), idleLimit()), 1, 0, false);
    /**
     * Fires when members of a machine pool turn idle, with how many did since the last run. Needs an idle value; the
     * first look after a change of the pool's devices fires nothing.
     */
    public static final NodeType POOL_MEMBER_IDLE = register("pool_member_idle", "Pool member turns idle",
            NodeCategory.EVENTS, Op.POOL_IDLE, List.of(IN_LABEL),
            List.of(Port.output("turned_idle", EVENT, "Turned idle"), Port.output("count", NUMBER, "Count")),
            List.of(DEVICE_LABEL, idleValue(true), idleWhen(), idleLimit()), 1, 0, false);

    // For each (M7L2 row 18). The id is saved like the ones above.

    /**
     * Runs the nodes wired from "each" and "item" once per item of a tag, a list or the labelled stock keepers' rules,
     * with "item" carrying the item of that pass, then fires "done". See {@link ForEach} for what the branch may hold
     * and the limits of one run.
     */
    public static final NodeType FOR_EACH = register("for_each", "For each", NodeCategory.CHECK, Op.FOR_EACH,
            List.of(IN_DO),
            List.of(Port.output(ForEach.EACH, EVENT, "Each item"), Port.output(ForEach.ITEM, ITEM, "Item"),
                    Port.output(ForEach.DONE, EVENT, "Done"), Port.output(ForEach.CHECKED, NUMBER, "Items checked")),
            forEachSettings(), 1, 0, false);

    static {
        // Hover descriptions and sentence templates; a sentence's {name} is one of the type's settings or inputs.
        describe(SEQUENCE,
                "Named steps one after another, such as fill, wait and empty: each goes on when its condition is yes or its time runs out.",
                "go through {steps} steps in turn");
        describe(STEP_IS, "Yes while a step sequence is at a step, named or by number.", "{step} is at {which}");
        describe(COUNT_CROSSES, "Fires when an item's count in the network (or in labelled devices) goes past a limit.",
                "when the count of {item} in {label} goes {mode} {threshold}");
        describe(POWER_CHANGES, "Fires when the network gains or loses power.",
                "when the network's power goes on or off");
        describe(JOB_FINISHED, "Fires when a crafting job for the item (or any job) finishes.",
                "when a crafting job for {item} finishes");
        describe(REDSTONE_CHANGES,
                "Fires when the redstone into the controller or a labelled device, or a network channel, changes.",
                "when the redstone into {label} changes");
        describe(SENSOR_CROSSES, "Fires when a value a labelled sensor reads goes past a limit.",
                "when {value} of sensor {label} goes {mode} {threshold}");
        describe(LINK_OVERLOADED,
                "Fires when the busiest link on a device's route uses more than a share of its channels.",
                "when the link to {label} uses over {threshold}% of its channels");
        describe(LABEL_DEVICES, "Fires when a device with the label joins or leaves the network.",
                "when a device labelled {label} is added or removed");
        describe(STORAGE_FULL, "Fires when a drive cell, bulk drive or storage chest fills up.",
                "when storage in {label} fills up");
        describe(ITEM_COUNT, "How many of an item the network (or labelled devices) holds.",
                "the count of {item} in {label}");
        describe(SENSOR_VALUE, "A value a labelled sensor reads.", "{value} of sensor {label}");
        describe(DEVICE_STATE, "Whether some device with the label is switched on.", "whether {label} is on");
        describe(REDSTONE_STRENGTH,
                "The strongest redstone signal (0 to 15) into the controller or a labelled device, or a network channel's value.",
                "the redstone strength into {label}");
        describe(CONSTANT, "A fixed number, yes/no, item or label.", "the {kind} {value}");
        describe(COMPARE, "Checks a number against a limit, with a switch-back value so it does not flicker.",
                "{value} is {mode} {threshold}");
        describe(AND, "Yes while both inputs are yes.", "{a} and {b}");
        describe(OR, "Yes while either input is yes.", "{a} or {b}");
        describe(NOT, "Turns yes into no and no into yes.", "not {value}");
        describe(IF, "Passes an event on to Then or Otherwise, by a condition.", "when {when}, if {condition}");
        describe(LATCH, "Turns on at Set and stays on until Reset.", "on after {set} until {reset}");
        describe(COUNTER, "Counts events up or down by a step.", "count {up} in steps of {step}");
        describe(HOLD, "Remembers a number when Store fires.", "{value} as it was at {store}");
        describe(DEVICE_SWITCH, "Switches labelled devices on or off.", "switch {label} on while {on}");
        describe(SET_PRIORITY, "Sets the storage priority of labelled devices.",
                "set the priority of {label} to {priority}");
        describe(REDSTONE_OUT,
                "Sends redstone from labelled condition switches in logic mode, or onto a network channel.",
                "send redstone {strength} from {label} while {on}");
        describe(REQUEST_CRAFT,
                "Asks the network (or another one, named as @name) to craft an item, once while a craft of it runs.",
                "craft {amount} {item}");
        LogicLang.add("node.request_craft.network_sentence", "craft {amount} {item} on {network}");
        describe(MOVE_ITEMS, "Moves items between the network's storage and labelled ports or interfaces.",
                "move {amount} {item} from {from} to {to}");
        describe(NOTIFY, "Posts a message to the network's log, the team or displays.", "notify \"{text}\"");
        describe(DELAY, "Fires Done a number of ticks after Start.", "{ticks} ticks after {start}");
        describe(TIMER, "Fires every so many seconds, without costing anything in between.",
                "every {interval} seconds");
        describe(GRAPH_START,
                "Fires once each time the graph is saved (every edit), switched on or loaded with the world.",
                "when the graph starts");
        describe(DAYTIME_CHANGES, "Fires when day or night begins where the controller is.",
                "when day or night begins");
        describe(WEATHER_CHANGES, "Fires when the weather turns clear, rainy or stormy.", "when the weather changes");
        describe(DAYTIME, "Whether it is day, and the time of day in ticks (0 at sunrise).",
                "whether it is day and the time of day");
        describe(WEATHER, "The weather: 0 clear, 1 rain, 2 thunder.", "the weather");
        describe(MATH, "Adds, subtracts, multiplies or divides two numbers, or takes the smaller, larger or remainder.",
                "{a} {operation} {b}");
        describe(BETWEEN, "Yes while a number is between a low and a high bound.",
                "{value} is between {low} and {high}");
        describe(SELECT, "Passes on A while the choice is yes, otherwise B.", "{a} if {choose}, else {b}");
        describe(XOR, "Yes while exactly one input is yes.", "either {a} or {b}, not both");
        describe(TOGGLE, "Each event flips it between on and off.", "flip on and off at {flip}");
        describe(EDGE, "Fires when a yes/no turns yes, or turns no.", "when {value} turns yes or no");
        describe(STATS, "The average, lowest and highest of a number over its last samples or seconds.",
                "the average of {value} over the {window}");
        describe(CHANGE_RATE, "How fast a number changes, per second.", "how fast {value} changes per second");
        describe(COOLDOWN, "Lets an event through at most once every so many seconds.",
                "{in} at most every {seconds} seconds");
        describe(FLUID_AMOUNT,
                "How many millibuckets of a fluid, or of every fluid in a tag, the network (or labelled devices) holds.",
                "the amount of {fluid} in {label}");
        describe(ENERGY,
                "MU stored in the network (or in labelled energy cells and controllers), its capacity and how full it is.",
                "the energy stored in {label}");
        describe(FREE_SPACE, "Free and total bytes of the storage cells in the network (or in labelled drives).",
                "the free bytes in {label}");
        describe(CRAFT_STATUS,
                "Whether a crafting job for the item (any item when empty) runs, how many wait, and the free CPUs.",
                "whether {item} is being crafted");
        describe(COUNT_BY_TAG, "How many items of a tag the network (or labelled devices) holds.",
                "the count of {tag} in {label}");
        describe(CANCEL_CRAFT, "Cancels crafting jobs for an item; what they hold goes back to storage.",
                "cancel the crafts of {item} ({scope})");
        describe(PULSE_REDSTONE,
                "Turns labelled condition switches in logic mode, or a network channel, on for some ticks, then off.",
                "pulse redstone {strength} from {label} for {ticks} ticks");
        describe(WRITE_DISPLAY, "Shows a text on labelled display panels; {a} and {b} in it show the inputs.",
                "show \"{text}\" on {label}");
        describe(SET_FILTER, "Sets a filter slot of labelled ports to an item, or switches them to import or export.",
                "set filter slot {slot} of {label} to {item}, port mode {port_mode}");
        describe(ITEM_MOVES, "Fires when items move into or out of the network through labelled devices.",
                "when {item} moves {direction} through {label}");
        describe(CRAFT_FAILED,
                "Fires when a crafting job for the item stops because something is missing. \"Missing item\" is the first item it lacked; craft it or name it in a notice with {item}.",
                "when a crafting job for {item} fails for lack of an item");
        describe(PLAYER_NEARBY, "Fires when a player comes within a radius of a labelled device, or leaves it.",
                "when a player comes within {radius} blocks of {label}");
        describe(PLAYER_USES_TERMINAL, "Fires when a player opens a terminal of the network.",
                "when a player opens terminal {label}");
        describe(RUN_BY_RULE, "Fires when another graph of this controller runs this one.",
                "when another rule runs this one");
        describe(TASK_STATE, "Whether a task on a task board is done, and how far its goal is.",
                "task {task} on board {board}");
        describe(SET_TASK, "Ticks a task on a task board, or opens it again.", "set task {task} on board {board}");
        describe(SHARED_VARIABLE, "A value the graphs of this controller share by name.", "the shared value {name}");
        describe(SET_SHARED_VARIABLE, "Sets a value the graphs of this controller share by name.",
                "set shared value {name} ({kind})");
        describe(RUN_RULE,
                "Runs another graph of this controller, by name or row number; can fill that rule's parameters with an item and an amount.",
                "run rule {rule}");
        describe(WAIT_UNTIL, "Holds events until a yes/no turns yes, or gives up after a while.",
                "hold {in} until {condition}, giving up after {timeout} seconds");
        describe(STOCK_STATUS,
                "A labelled stock keeper's rule for an item: its amounts, the count, and whether it is crafting or on.",
                "the stock rule for {item} in {label}");
        describe(STOCK_SHORT, "Fires when a stock keeper's rule can't get its item crafted (refused or missing).",
                "when the stock rule for {item} in {label} runs short");
        describe(STOCK_RULE_SWITCH, "Switches a labelled stock keeper's rule (or all of them) on or off.",
                "switch the stock rule for {item} in {label} on while {on}");
        describe(STOCK_RULE_SET, "Sets the keep, restart or batch amount of a labelled stock keeper's rule.",
                "set {field} of the stock rule for {item} in {label} to {amount}");
        describe(MOVE_TO_POOL, "Sends items to the idle machines of a pool: ports or interfaces sharing a label.",
                "send {amount} {item} to the idle members of {label} ({strategy}), idle while {idle_value} is {idle_when} {idle_limit}");
        describe(POOL_STATUS, "How many machines a pool has, and how many of them are idle or busy.",
                "the members of {label}, idle while {idle_value} is {idle_when} {idle_limit}");
        describe(POOL_MEMBER_IDLE, "Fires when machines of a pool turn idle.",
                "when a member of {label} turns idle ({idle_value} is {idle_when} {idle_limit})");
        describe(FOR_EACH,
                "Runs the nodes after it once for each item of a tag, a list or a stock keeper's rules, then fires Done.",
                "for each item of {source}");
        // For each (M7L2 row 18): sentences of the nodes whose item comes from a For each's "item" ("it").
        LogicLang.add("node.item_count.each_sentence", "its count in {label}");
        LogicLang.add("node.item_count.each_network_sentence", "its count");
        LogicLang.add("node.stock_status.each_sentence", "its stock rule in {label}");
        LogicLang.add("node.request_craft.each_sentence", "craft {amount} of it");
        LogicLang.add("node.move_items.each_sentence", "move {amount} of it from {from} to {to}");
        LogicLang.add("node.move_to_pool.each_sentence",
                "send {amount} of it to the idle members of {label} ({strategy}), idle while {idle_value} is {idle_when} {idle_limit}");
        LogicLang.add("node.move_to_pool.each_any_sentence",
                "send {amount} of it to the members of {label} ({strategy}); idle check: {idle_value}");
        LogicLang.add("node.cancel_craft.each_sentence", "cancel the crafts of it ({scope})");
        // A failed job's missing item (M7L2): sentences of the nodes whose item comes from "missing item".
        LogicLang.add("node.item_count.missing_sentence", "the missing item's count in {label}");
        LogicLang.add("node.item_count.missing_network_sentence", "the missing item's count");
        LogicLang.add("node.request_craft.missing_sentence", "craft {amount} of the missing item");
        LogicLang.add("node.move_items.missing_sentence", "move {amount} of the missing item from {from} to {to}");
        LogicLang.add("node.move_to_pool.missing_sentence",
                "send {amount} of the missing item to the idle members of {label} ({strategy}), idle while {idle_value} is {idle_when} {idle_limit}");
        LogicLang.add("node.move_to_pool.missing_any_sentence",
                "send {amount} of the missing item to the members of {label} ({strategy}); idle check: {idle_value}");
        LogicLang.add("node.cancel_craft.missing_sentence", "cancel the crafts of the missing item ({scope})");
        // Machine pools (M7L2): the sentence while no idle value is named, so every member counts as idle.
        LogicLang.add("node.move_to_pool.any_sentence",
                "send {amount} {item} to the members of {label} ({strategy}); idle check: {idle_value}");
        LogicLang.add("node.pool_status.any_sentence", "the members of {label}; idle check: {idle_value}");
        // Network redstone channels (M7L2): the sentence when the channel setting replaces the label.
        LogicLang.add("node.redstone_changes.channel_sentence", "when {label} changes");
        LogicLang.add("node.redstone_strength.channel_sentence", "the value of {label}");
        LogicLang.add("node.redstone_out.channel_sentence", "set {label} to {strength} while {on}");
        LogicLang.add("node.pulse_redstone.channel_sentence", "pulse {label} at {strength} for {ticks} ticks");
    }

    // Rules with parameters (M7L2 row 19). The id is saved like the ones above.

    /**
     * A value the rule takes as an input, by name: a number, yes/no, item (or tag) or label, with a default. Inside the
     * rule it reads like a Constant. A copy of the rule "with values" ({@link Parameters}) and a run-rule node's
     * arguments give it other values; in a rule with a "Run by another rule" node every node reading a parameter runs
     * once per call.
     */
    public static final NodeType PARAMETER = register("parameter", "Parameter", NodeCategory.READ, Op.PARAMETER,
            List.of(),
            List.of(Port.output(VALUE, NUMBER, "Value"), Port.output(VALUE, YES_NO, "Value"),
                    Port.output(VALUE, ITEM, "Value"), Port.output(VALUE, LABEL, "Value")),
            List.of(SettingSpec.text(NAME, true, "Name"),
                    SettingSpec.choice(KIND, "Kind", "number", "Number", "yes_no", "Yes/no", "item", "Item", "label",
                            "Label"),
                    SettingSpec.number(VALUE, -MAX, MAX, 0, "Value"), SettingSpec.flag(YES, true, "Yes"), ANY_ITEM,
                    NETWORK_LABEL),
            1, 0, false);

    static {
        describe(PARAMETER,
                "A named input of this rule with a default value. Copies of the rule with their own values, and other rules running it, can fill it.",
                "the {kind} parameter {name}");
        LogicLang.add("node.run_rule.each_sentence", "run rule {rule} for it");
    }

    // Load shedding (M7L2 row 21). Ids are saved like the ones above.

    /** The network's load shedding level (0 to 4) and whether any device is paused for low power. */
    public static final NodeType SHED_LEVEL = register("shed_level", "Load shedding level", NodeCategory.READ,
            Op.SHED_LEVEL, List.of(),
            List.of(Port.output("level", NUMBER, "Level"), Port.output("shedding", YES_NO, "Shedding")), List.of(), 1,
            0, false);
    /** Fires when the load shedding level changes; nothing on the first look. */
    public static final NodeType SHEDDING_CHANGES = register("shedding_changes", "Load shedding changes",
            NodeCategory.EVENTS, Op.SHEDDING_CHANGES, List.of(),
            List.of(Port.output("changed", EVENT, "Changed"), Port.output("level", NUMBER, "Level")), List.of(), 1, 1,
            false);
    /**
     * Sets the power priority (1 to 5) of the labelled devices load shedding can pause, when "when" fires; needs the
     * owner's access to each device, like every action.
     */
    public static final NodeType SET_POWER_PRIORITY = register("set_power_priority", "Set power priority",
            NodeCategory.ACT, Op.SET_POWER_PRIORITY,
            List.of(IN_DO, Port.optional(PRIORITY, NUMBER, "Priority"), IN_LABEL), List.of(),
            List.of(DEVICE_LABEL, SettingSpec.number(PRIORITY, 1, 5, 3, "Priority")), 2, 0, false);

    static {
        describe(SHED_LEVEL,
                "How many power priorities low power has paused on the network: 0 none, 1 optional devices, up to 4.",
                "the network's load shedding level");
        describe(SHEDDING_CHANGES,
                "Fires when low power pauses more devices, or the power comes back and resumes them.",
                "when load shedding changes");
        describe(SET_POWER_PRIORITY,
                "Sets the power priority of labelled devices: 1 essential (never paused) to 5 optional (paused first).",
                "set the power priority of {label} to {priority}");
    }

    /**
     * Saved as the type's id. An unknown id fails to decode, except a script type's id ({@code namespace:path}) that no
     * script registers now: it decodes to a stand-in ({@link ScriptNodes#missing}), so the graph is kept.
     */
    public static final Codec<NodeType> CODEC = Codec.STRING
            .comapFlatMap(id -> byId(id).or(() -> ScriptNodes.missing(id)).map(DataResult::success)
                    .orElseGet(() -> DataResult.error(() -> "Unknown node type " + id)), NodeType::id);

    private NodeTypes() {
    }

    private static NodeType register(String id, String english, NodeCategory category, Op op, List<Port> inputs,
            List<Port> outputs, List<SettingSpec> settings, int cost, int memory, boolean breaksLoops) {
        NodeType type = new NodeType(id, english, category, op, inputs, outputs, settings, cost, memory, breaksLoops,
                COMMON.contains(id));
        if (BY_ID.putIfAbsent(id, type) != null) {
            throw new IllegalStateException("Duplicate node type " + id);
        }
        return type;
    }

    /** A task node's board: the team's (as the screens' default) or the network's. */
    private static SettingSpec taskScope() {
        return SettingSpec.choice(BOARD_SCOPE, "Board of", "team", "The team", "network", "This network");
    }

    private static void describe(NodeType type, String description, String sentence) {
        LogicLang.add("node." + type.id() + ".desc", description);
        LogicLang.add("node." + type.id() + ".sentence", sentence);
    }

    /** Reset, then per step "go on when" and "go to this step while" ({@link Sequences}). */
    private static List<Port> sequenceInputs() {
        List<Port> ports = new ArrayList<>(Sequences.inputCount(Sequences.MAX_STEPS));
        ports.add(Port.optional(Sequences.RESET, EVENT, "Reset"));
        for (int k = 1; k <= Sequences.MAX_STEPS; k++) {
            ports.add(Port.optional(Sequences.next(k), YES_NO, "Step " + k + ": go on when"));
            ports.add(Port.optional(Sequences.go(k), YES_NO, "Go to step " + k + " while"));
        }
        return ports;
    }

    /** The step, "time ran out", then per step "entered". */
    private static List<Port> sequenceOutputs() {
        List<Port> ports = new ArrayList<>(Sequences.outputCount(Sequences.MAX_STEPS));
        ports.add(Port.output(Sequences.STEP, NUMBER, "Step"));
        ports.add(Port.output(Sequences.TIME_UP, EVENT, "Time ran out"));
        for (int k = 1; k <= Sequences.MAX_STEPS; k++) {
            ports.add(Port.output(Sequences.entered(k), EVENT, "Entered step " + k));
        }
        return ports;
    }

    /** The step count, then per step its name and time limit. */
    private static List<SettingSpec> sequenceSettings() {
        List<SettingSpec> settings = new ArrayList<>(1 + 2 * Sequences.MAX_STEPS);
        settings.add(SettingSpec.number(Sequences.STEPS, Sequences.MIN_STEPS, Sequences.MAX_STEPS,
                Sequences.DEFAULT_STEPS, "Steps"));
        for (int k = 1; k <= Sequences.MAX_STEPS; k++) {
            settings.add(SettingSpec.text(Sequences.name(k), false, "Step " + k + " name"));
            settings.add(SettingSpec.number(Sequences.timeout(k), 0, Sequences.MAX_SECONDS, 0,
                    "Step " + k + ": go on after (seconds, 0: no limit)"));
        }
        return settings;
    }

    /** The source, its tag and label, the list's size, then each list item ({@link ForEach}). */
    private static List<SettingSpec> forEachSettings() {
        List<SettingSpec> settings = new ArrayList<>(4 + ForEach.MAX_LIST);
        settings.add(SettingSpec.choice(ForEach.SOURCE, "Go through", "tag", "A tag", "list", "A list", "stock_keeper",
                "A stock keeper's rules"));
        settings.add(SettingSpec.tag(TAG, false, "Tag"));
        settings.add(SettingSpec.label(LABEL_NAME, false, "Label"));
        settings.add(
                SettingSpec.number(ForEach.LIST_SIZE, 1, ForEach.MAX_LIST, ForEach.DEFAULT_LIST, "Items in the list"));
        for (int k = 1; k <= ForEach.MAX_LIST; k++) {
            settings.add(SettingSpec.item(ForEach.item(k), false, "Item " + k));
        }
        return settings;
    }

    /**
     * The sentence of a node whose item input a crafting-job-failed node's "missing item" feeds: "craft {amount} of the
     * missing item"; empty for types that cannot take it ({@link #takesMissingItem}).
     */
    public static <K> Optional<String> missingSentenceKey(NodeType type, NodeSettings<K> settings) {
        if (!takesMissingItem(type)) {
            return Optional.empty();
        }
        String suffix = "";
        if (type == ITEM_COUNT && settings.text(LABEL_NAME).filter(text -> !text.isBlank()).isEmpty()) {
            suffix = "_network";
        } else if (type == MOVE_TO_POOL && settings.text(IDLE_VALUE).filter(text -> !text.isBlank()).isEmpty()) {
            suffix = "_any";
        }
        return Optional.of(LogicLang.PREFIX + "node." + type.id() + ".missing" + suffix + "_sentence");
    }

    /**
     * Whether a node of {@code type} can take the item a crafting-job-failed node's "missing item" output carries into
     * its item input: it reads or acts on that item only while it runs, with no watch and no memory of the item.
     */
    public static boolean takesMissingItem(NodeType type) {
        return type == ITEM_COUNT || type == REQUEST_CRAFT || type == MOVE_ITEMS || type == MOVE_TO_POOL
                || type == CANCEL_CRAFT;
    }

    /**
     * The sentence of a node whose item input a For each's "item" output feeds ({@link ForEach}), which names the item
     * "it": "its count", "craft {amount} of it"; empty for types without one.
     */
    public static <K> Optional<String> eachSentenceKey(NodeType type, NodeSettings<K> settings) {
        if (type == ITEM_COUNT) {
            boolean network = settings.text(LABEL_NAME).filter(text -> !text.isBlank()).isEmpty();
            return Optional
                    .of(LogicLang.PREFIX + "node.item_count." + (network ? "each_network" : "each") + "_sentence");
        }
        if (type == MOVE_TO_POOL && settings.text(IDLE_VALUE).filter(text -> !text.isBlank()).isEmpty()) {
            return Optional.of(LogicLang.PREFIX + "node.move_to_pool.each_any_sentence");
        }
        if (type == STOCK_STATUS || type == REQUEST_CRAFT || type == MOVE_ITEMS || type == MOVE_TO_POOL
                || type == CANCEL_CRAFT || type == RUN_RULE) {
            return Optional.of(LogicLang.PREFIX + "node." + type.id() + ".each_sentence");
        }
        return Optional.empty();
    }

    /** The value of a pool member's machine (a sensor value id) that says it is idle; the pool event needs one. */
    private static SettingSpec idleValue(boolean required) {
        return SettingSpec.text(IDLE_VALUE, required, "Idle value");
    }

    private static SettingSpec idleWhen() {
        return SettingSpec.choice(IDLE_WHEN, "Idle while it is", "equal_to", "Equal to", "below", "Below", "above",
                "Above");
    }

    private static SettingSpec idleLimit() {
        return SettingSpec.number(IDLE_LIMIT, -Pools.MAX_IDLE_LIMIT, Pools.MAX_IDLE_LIMIT, 0, "Idle limit");
    }

    private static SettingSpec channelSetting() {
        String[] choices = new String[2 + 2 * CHANNEL_COLOURS.length];
        choices[0] = "none";
        choices[1] = "None (use the label)";
        for (int i = 0; i < CHANNEL_COLOURS.length; i++) {
            choices[2 + 2 * i] = CHANNEL_COLOURS[i];
            choices[3 + 2 * i] = CHANNEL_ENGLISH[i];
            CHANNEL_PHRASES[i] = LogicLang.shared("channel." + CHANNEL_COLOURS[i],
                    "the " + CHANNEL_ENGLISH[i].toLowerCase(java.util.Locale.ROOT) + " channel");
        }
        return SettingSpec.choice(CHANNEL, "Channel", choices);
    }

    /**
     * The network redstone channel (0 white to 15 black) a node of {@code type} with {@code settings} uses instead of
     * its label, or -1 when it uses the label (no channel set, or a type without the setting).
     */
    public static <K> int channel(NodeType type, NodeSettings<K> settings) {
        Optional<SettingSpec> spec = type.setting(CHANNEL);
        if (spec.isEmpty()) {
            return -1;
        }
        return spec.get().choiceIndex(settings.text(CHANNEL).orElse("")) - 1;
    }

    /**
     * The sentence template of a node: the type's own ({@link NodeType#sentenceKey()}), or for a redstone node set to a
     * network channel, its channel sentence ("set {label} to {strength} while {on}", with {@code {label}} filled in as
     * "the red channel").
     */
    public static <K> String sentenceKey(NodeType type, NodeSettings<K> settings) {
        if (type == REQUEST_CRAFT && settings.text(NETWORK).filter(text -> !text.isBlank()).isPresent()) {
            // A craft on another network (M7L2 row 13) names it.
            return LogicLang.PREFIX + "node.request_craft.network_sentence";
        }
        if ((type == MOVE_TO_POOL || type == POOL_STATUS)
                && settings.text(IDLE_VALUE).filter(text -> !text.isBlank()).isEmpty()) {
            return LogicLang.PREFIX + "node." + type.id() + ".any_sentence";
        }
        return channel(type, settings) >= 0 ? channelSentenceKey(type) : type.sentenceKey();
    }

    private static String channelSentenceKey(NodeType type) {
        return LogicLang.PREFIX + "node." + type.id() + ".channel_sentence";
    }

    /** The lang key of "the red channel" for channel {@code channel} (0 to 15). */
    public static String channelPhraseKey(int channel) {
        return CHANNEL_PHRASES[Math.clamp(channel, 0, CHANNEL_PHRASES.length - 1)];
    }

    /** The choice id that selects channel {@code channel} (0 to 15) in a node's channel setting. */
    public static String channelId(int channel) {
        return CHANNEL_COLOURS[Math.clamp(channel, 0, CHANNEL_COLOURS.length - 1)];
    }

    /** Loads the registry. */
    static void init() {
    }

    /** Every node type in registration (palette) order: Storage's, then the pack's script types. */
    public static List<NodeType> all() {
        List<NodeType> all = new ArrayList<>(BY_ID.values());
        all.addAll(ScriptNodes.all());
        return Collections.unmodifiableList(all);
    }

    /** The registered type {@code id}: one of Storage's or a script's ({@link ScriptNodes}). */
    public static Optional<NodeType> byId(String id) {
        NodeType type = BY_ID.get(id);
        return type != null ? Optional.of(type) : ScriptNodes.byId(id);
    }

    /** Whether {@code id} is one of Storage's own types. */
    static boolean builtIn(String id) {
        return BY_ID.containsKey(id);
    }
}
