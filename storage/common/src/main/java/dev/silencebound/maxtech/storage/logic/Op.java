package dev.silencebound.maxtech.storage.logic;

/**
 * What a node does when it runs; {@link LogicEvaluator} switches on it. Every node type has one. A new behaviour adds a
 * constant here and a case in the evaluator; node types that only differ in ports or settings share one.
 */
enum Op {
    COUNT_CROSSES, POWER_CHANGES, JOB_FINISHED, REDSTONE_CHANGES, SENSOR_CROSSES, ITEM_COUNT, SENSOR_VALUE, DEVICE_STATE, CONSTANT, COMPARE, AND, OR, NOT, IF, LATCH, COUNTER, HOLD, DEVICE_SWITCH, SET_PRIORITY, REDSTONE_OUT, REQUEST_CRAFT, MOVE_ITEMS, NOTIFY, DELAY, LOAD_CROSSES, DEVICES_CHANGE, CELLS_FULL, TIMER, GRAPH_START, DAYTIME_CHANGES, WEATHER_CHANGES, DAYTIME, WEATHER, MATH, BETWEEN, SELECT, XOR, TOGGLE, EDGE, STATS, CHANGE_RATE, COOLDOWN, ENERGY, FREE_SPACE, CRAFT_STATUS, CANCEL_CRAFT, PULSE_REDSTONE, WRITE_DISPLAY, SET_FILTER, ITEM_MOVES, CRAFT_FAILED, PLAYERS_NEAR, TERMINAL_OPENED, VARIABLE, SET_VARIABLE, RUN_RULE, RULE_CALLED, WAIT_UNTIL, REDSTONE_STRENGTH, SCRIPT, STOCK_STATUS, STOCK_SHORT, STOCK_SWITCH, STOCK_SET, SEQUENCE, STEP_IS, POOL_SEND, POOL_STATUS, POOL_IDLE, SHED_LEVEL, SHEDDING_CHANGES, SET_POWER_PRIORITY, FOR_EACH, PARAMETER, TASK, SET_TASK;

    /** The watch kind a source node of this op reads, or null for nodes that read only their wires. */
    @org.jspecify.annotations.Nullable
    WatchKind watch() {
        return switch (this) {
            case COUNT_CROSSES, ITEM_COUNT -> WatchKind.ITEM_COUNT;
            case POWER_CHANGES -> WatchKind.POWER;
            case JOB_FINISHED -> WatchKind.JOB_FINISHED;
            case REDSTONE_CHANGES, REDSTONE_STRENGTH -> WatchKind.REDSTONE;
            case SENSOR_CROSSES, SENSOR_VALUE -> WatchKind.SENSOR;
            case DEVICE_STATE -> WatchKind.DEVICE;
            case LOAD_CROSSES -> WatchKind.LOAD;
            case DEVICES_CHANGE -> WatchKind.DEVICE_COUNT;
            case CELLS_FULL -> WatchKind.FULL;
            case DAYTIME_CHANGES, DAYTIME -> WatchKind.DAYTIME;
            case WEATHER_CHANGES, WEATHER -> WatchKind.WEATHER;
            case ENERGY -> WatchKind.ENERGY;
            case FREE_SPACE -> WatchKind.BYTES_USED;
            case CRAFT_STATUS -> WatchKind.CRAFT_JOBS;
            case ITEM_MOVES -> WatchKind.ITEM_MOVES;
            case CRAFT_FAILED -> WatchKind.CRAFT_FAILED;
            case PLAYERS_NEAR -> WatchKind.PLAYERS_NEAR;
            case TERMINAL_OPENED -> WatchKind.TERMINAL_OPENED;
            case VARIABLE -> WatchKind.VARIABLE;
            case RULE_CALLED -> WatchKind.RULE_CALLED;
            case STOCK_STATUS, STOCK_SHORT -> WatchKind.STOCK;
            case POOL_STATUS -> WatchKind.POOL;
            case POOL_IDLE -> WatchKind.POOL_IDLE;
            case SHED_LEVEL, SHEDDING_CHANGES -> WatchKind.SHED_LEVEL;
            case TASK -> WatchKind.TASK;
            default -> null;
        };
    }

    /**
     * A second watch kind the node reads beside {@link #watch()}, with the same label and no item, or null: a capacity
     * that goes with an amount, or the free crafting CPUs.
     */
    @org.jspecify.annotations.Nullable
    WatchKind secondWatch() {
        return switch (this) {
            case ENERGY -> WatchKind.ENERGY_CAPACITY;
            case FREE_SPACE -> WatchKind.BYTES_TOTAL;
            case CRAFT_STATUS -> WatchKind.CRAFT_CPUS;
            default -> null;
        };
    }

    /** Whether the op emits an action. */
    boolean acts() {
        return switch (this) {
            case DEVICE_SWITCH, SET_PRIORITY, REDSTONE_OUT, REQUEST_CRAFT, MOVE_ITEMS, NOTIFY, CANCEL_CRAFT,
                    PULSE_REDSTONE, WRITE_DISPLAY, SET_FILTER, SET_VARIABLE, RUN_RULE, STOCK_SWITCH, STOCK_SET,
                    POOL_SEND, SET_POWER_PRIORITY, SET_TASK ->
                true;
            default -> false;
        };
    }

    /**
     * Action points one action of this op costs against the team's action budget
     * ({@code logic.teamActionPointsPerSecond}, M7L2 chunk 5): about 1 point per microsecond of server time the action
     * took on a real level, from the higher median of the two measurements in
     * {@code docs/milestones/M7L2/cost-benchmark.md}, rounded, at least 1. Measured weights like the nodes' check
     * costs, so constants rather than config; the budget is config. 0 for ops that do not act.
     */
    int actionPoints() {
        return switch (this) {
            case NOTIFY, SET_PRIORITY, SET_POWER_PRIORITY, SET_VARIABLE, RUN_RULE, CANCEL_CRAFT, STOCK_SWITCH,
                    STOCK_SET, SET_TASK ->
                1;
            // A pulse is a redstone write like redstone out (its end is a second action, charged again).
            case DEVICE_SWITCH, REDSTONE_OUT, PULSE_REDSTONE, WRITE_DISPLAY, SET_FILTER -> 2;
            case REQUEST_CRAFT -> 4;
            case MOVE_ITEMS -> 12;
            // The pool's plan and one member's move; a send that several members take moves more.
            case POOL_SEND -> 13;
            default -> 0;
        };
    }

    /**
     * For acting ops, whether a second emission of the same action within one run replaces the first (settings such as
     * on/off) rather than being dropped as a duplicate (one-shot work such as a craft request).
     */
    boolean replaces() {
        return this == DEVICE_SWITCH || this == SET_PRIORITY || this == REDSTONE_OUT || this == PULSE_REDSTONE
                || this == WRITE_DISPLAY || this == SET_VARIABLE || this == STOCK_SWITCH || this == STOCK_SET
                || this == SET_POWER_PRIORITY || this == SET_TASK;
    }

    /**
     * Whether a node of this op saves its memory. Actions do not, so after a load they set their devices once more; a
     * pulse does, since its memory is when it switches off again, and a pool send, since its memory is whose turn it
     * is.
     */
    boolean savesMemory() {
        return !acts() || this == PULSE_REDSTONE || this == POOL_SEND;
    }
}
