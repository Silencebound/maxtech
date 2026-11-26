package dev.silencebound.maxtech.storage.logic;

/**
 * What a {@link Watch} follows. The controller subscribes to each watch of a compiled graph and tells the evaluator
 * when it changed ({@link LogicEvaluator#changed}) or, for job events, when it happened
 * ({@link LogicEvaluator#signal}).
 */
public enum WatchKind {
    /** The count of {@link Watch#item()} in the network, or in the devices labelled {@link Watch#label()}. */
    ITEM_COUNT,
    /** Whether the network has power: 1 or 0. */
    POWER,
    /** A crafting job for {@link Watch#item()} (any job when empty) finished. A signal, never read. */
    JOB_FINISHED,
    /** The redstone strength (0 to 15) into the devices labelled {@link Watch#label()}, or into the controller. */
    REDSTONE,
    /** The value {@link Watch#name()} of the sensor labelled {@link Watch#label()}. */
    SENSOR,
    /** Whether the devices labelled {@link Watch#label()} are switched on: 1 or 0. */
    DEVICE,
    /**
     * The busiest link on the route of the devices labelled {@link Watch#label()} (or the controller's) to the
     * network's controller, in percent of its channels in use (M7L area E).
     */
    LOAD,
    /** How many devices of the network carry {@link Watch#label()} (M7L area E). */
    DEVICE_COUNT,
    /** How many drive cells have no byte free, in the drives labelled {@link Watch#label()} or the whole network. */
    FULL,
    /**
     * The controller's level's time of day and whether it is day, packed as {@code timeOfDay * 2 + (day ? 1 : 0)}:
     * {@link #packDaytime}. The day bit follows {@code Level.isBrightOutside()}; the controller offers a new value only
     * when it flips, and every run reads the time afresh ({@link #volatileValue()}).
     */
    DAYTIME,
    /** The controller's level's weather: 0 clear, 1 rain, 2 thunder. */
    WEATHER,
    /**
     * FE stored in the network's store, or in the devices labelled {@link Watch#label()} (energy cells and
     * controllers). The controller looks at most once per {@code condition.minIntervalTicks}, since it can change every
     * tick.
     */
    ENERGY,
    /** The FE capacity that goes with {@link #ENERGY}. */
    ENERGY_CAPACITY,
    /**
     * Bytes used in the network's cells, or in the cells of the drives labelled {@link Watch#label()}. Looked at, like
     * {@link #ENERGY}, at most once per {@code condition.minIntervalTicks} after the contents changed.
     */
    BYTES_USED,
    /** The byte capacity that goes with {@link #BYTES_USED}. */
    BYTES_TOTAL,
    /**
     * The network's unfinished crafting jobs for {@link Watch#item()} (any item when empty), packed by
     * {@link #packCraft}: how many wait for a CPU, and whether one runs.
     */
    CRAFT_JOBS,
    /**
     * The network's crafting CPUs that are free to take a job, and of them the ones automation may take (not kept free
     * for players, M7L2 chunk 20), packed by {@link #packCpus}.
     */
    CRAFT_CPUS,
    /**
     * Items of {@link Watch#item()} (any item when empty; a tag matches each item in it) went into or out of the
     * network through a device labelled {@link Watch#label()} (any device, or a player, when empty);
     * {@link Watch#number()} is the direction: 0 both, 1 into the network, 2 out of it. A signal with an amount: the
     * items moved since the last run (M7L2 batch 4).
     */
    ITEM_MOVES,
    /**
     * A crafting job for {@link Watch#item()} (any job when empty) stopped with something missing. A signal with an
     * amount: how many of the first missing item were short.
     */
    CRAFT_FAILED,
    /**
     * Players within {@link Watch#number()} blocks of a device labelled {@link Watch#label()} (or the controller when
     * empty). Looked at on a timer ({@code logic.playerCheckTicks}) while a graph uses it; no event says a player
     * moved.
     */
    PLAYERS_NEAR,
    /** A player opened a terminal of the network labelled {@link Watch#label()} (any terminal when empty). A signal. */
    TERMINAL_OPENED,
    /** The value of the controller's shared variable {@link Watch#name()}; 0 while it has none. */
    VARIABLE,
    /** Another graph of the controller ran this one ("Run another rule"). A signal. */
    RULE_CALLED,
    /**
     * The value (0 to 15) of the network's bundled redstone channel {@link Watch#number()} (a dye colour id, M7L2). The
     * controller hears the network's channel changes; it never reads a block for it.
     */
    CHANNEL,
    /**
     * The stock keepers labelled {@link Watch#label()}: their first rule for {@link Watch#item()} (a tag matches each
     * item in it; every rule when empty). {@link Watch#number()} picks the value: {@link #STOCK_KEEP},
     * {@link #STOCK_RESTART}, {@link #STOCK_BATCH} or {@link #STOCK_FLAGS} (the {@code STOCK_*} bits, over every
     * matching rule). Read from the keepers' own state, which tells the controller when it changes (M7L2 chunk 15).
     */
    STOCK,
    /**
     * The machine pool labelled {@link Watch#label()} (M7L2 row 17): its members (the ports and interfaces with the
     * label) and how many of them are idle, packed by {@link Pools#packStatus}. A member is idle while the sensor value
     * {@link Watch#name()} on its machine meets the condition {@link Watch#number()} ({@link Pools#packIdle}); with no
     * value named every member is. Read from the pool's sensors, which tell the controller after a read.
     */
    POOL,
    /**
     * A member of the machine pool labelled {@link Watch#label()} turned idle by the value and condition as for
     * {@link #POOL}. A signal with how many did.
     */
    POOL_IDLE,
    /**
     * The network's load shedding level (M7L2 row 21): how many power priorities low power has paused, 0 (none) to 4.
     * The network tells the controller when it changes; never read per tick.
     */
    SHED_LEVEL,
    /**
     * A task on a task board (M7C item 15), named by {@link Watch#name()} (scope, board and task as
     * {@code TaskBoards.logicName} writes them): its goal's progress shifted left by one, with bit 0 set while it is
     * done. The boards tell the controller after every change.
     */
    TASK;

    /** {@link #STOCK} value: the rule's keep amount. */
    public static final long STOCK_KEEP = 0;
    /** {@link #STOCK} value: the rule's restart threshold. */
    public static final long STOCK_RESTART = 1;
    /** {@link #STOCK} value: the rule's batch size, 0 for no limit. */
    public static final long STOCK_BATCH = 2;
    /** {@link #STOCK} value: the state bits below. */
    public static final long STOCK_FLAGS = 3;
    /** {@link #STOCK_FLAGS} bit: a labelled keeper has a rule for the item. */
    public static final long STOCK_FOUND = 1;
    /** {@link #STOCK_FLAGS} bit: the first matching rule is switched on. */
    public static final long STOCK_ON = 2;
    /** {@link #STOCK_FLAGS} bit: a matching rule has a background job out. */
    public static final long STOCK_RUNNING = 4;
    /** {@link #STOCK_FLAGS} bit: a matching rule is filling (fell below its restart threshold, not full yet). */
    public static final long STOCK_FILLING = 8;
    /** {@link #STOCK_FLAGS} bit: a matching rule cannot be satisfied now (refused or ended with something missing). */
    public static final long STOCK_SHORT = 16;

    /** Whether the watch is a signal the controller passes on rather than a value the evaluator reads. */
    public boolean signal() {
        return this == JOB_FINISHED || this == ITEM_MOVES || this == CRAFT_FAILED || this == TERMINAL_OPENED
                || this == RULE_CALLED || this == POOL_IDLE;
    }

    /**
     * Whether every run reads the watch again, changed or not: its value moves all the time (the time of day) but only
     * some changes (day turning to night) wake a graph.
     */
    public boolean volatileValue() {
        return this == DAYTIME;
    }

    /** A {@link #CRAFT_JOBS} value: {@code waiting} jobs (at most {@link Integer#MAX_VALUE}) and whether one runs. */
    public static long packCraft(long waiting, boolean running) {
        return Math.clamp(waiting, 0L, (long) Integer.MAX_VALUE) * 2 + (running ? 1 : 0);
    }

    /** A {@link #CRAFT_CPUS} value: the free CPUs in the low 32 bits, those automation may take above them. */
    public static long packCpus(int free, int forAutomation) {
        return ((long) Math.max(0, forAutomation) << 32) | Math.max(0, free);
    }

    /** A {@link #DAYTIME} value: {@code timeOfDay} (0 to 23999) and whether it is day. */
    public static long packDaytime(long timeOfDay, boolean day) {
        return timeOfDay * 2 + (day ? 1 : 0);
    }
}
