package dev.silencebound.maxtech.storage.network;

/**
 * Recurring work of one device, run by its network's budgeted scheduler (PRD 7.2, Performance): a port's import or
 * export, later a bus or a stock rule. No storage device ticks on its own; a device hands its tasks to
 * {@link StorageDeviceBlockEntity#schedule} and the network runs each when it is due, inside the network's time and
 * operation budget, player work first. A network over its budget runs the rest next tick, so it slows down, never the
 * server.
 *
 * <p>
 * {@link #run} says when the task wants to run next. A task with nothing to do returns {@link #IDLE} (it backs off) or
 * {@link #WAIT} (it sleeps until woken); events such as a neighbour change call {@link StorageDeviceBlockEntity#wake}.
 * Server thread only.
 */
public interface StorageTask {
    /** Run again next tick: there is more to do now. */
    int NEXT_TICK = 1;
    /** Nothing to do: retry after a delay that doubles with each idle run, up to Lib's maximum backoff. */
    int IDLE = 0;
    /** Nothing to do until woken; no timer. */
    int WAIT = -1;
    /** Finished: leave the network's scheduler. */
    int DONE = -2;

    /**
     * Whether a player is waiting for this work (a request from a terminal); such tasks run before background work each
     * tick and are never held back by a surge regulator. Must not change while the task is scheduled. False by default.
     */
    default boolean playerPriority() {
        return false;
    }

    /**
     * Whether the task keeps running while load shedding pauses its device (M7L2): recording that must have no gap,
     * such as a display's history chart, while the device's own work waits. A device turned off by logic still stops
     * it. Must not change while the task is scheduled. False by default.
     */
    default boolean runsWhileShed() {
        return false;
    }

    /**
     * Does the work through {@code transfer}, which is valid only during this call, and returns when to run next:
     * {@link #NEXT_TICK}, a delay in ticks above 1, {@link #IDLE}, {@link #WAIT} or {@link #DONE}. A transfer refused
     * for lack of energy says how long to wait ({@link StorageTransfer#retryTicks()}); returning that keeps the task
     * from spinning. Must not block.
     */
    int run(StorageTransfer transfer, long gameTime);
}
