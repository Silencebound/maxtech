package dev.silencebound.maxtech.storage.port;

/**
 * When a port in storage mode looks at its inventory again (B09 item 8). External inventories send no events the
 * network can rely on, so the port rescans on its network's scheduler: at most once per {@code min} ticks however often
 * it is woken (a hopper feeding the chest wakes it on every item), soon after a change or the port's own moves, and
 * with each unchanged look twice as long as before, up to {@code max} ticks. Pure arithmetic; one per port, server
 * thread.
 */
final class BusCadence {
    private static final long NEVER = Long.MIN_VALUE;

    private long lastScan = NEVER;
    /** The current wait between looks; 0 after activity, so the next unchanged look starts from {@code min} again. */
    private int interval;

    /** Ticks to wait before a look may run at {@code now}; 0 when it may run now. */
    int delay(long now, int min) {
        if (lastScan == NEVER) {
            return 0;
        }
        long since = now - lastScan;
        return since >= min ? 0 : (int) Math.min(Integer.MAX_VALUE, min - since);
    }

    /** A look ran at {@code now}. */
    void scanned(long now) {
        lastScan = now;
    }

    /** Something happened (a change seen, the port's own move, an event): look again in {@code min} ticks. */
    int active(int min) {
        interval = 0;
        return Math.max(1, min);
    }

    /** The look found nothing new: wait twice as long as last time, from {@code min} up to {@code max}. */
    int unchanged(int min, int max) {
        int floor = Math.max(1, min);
        int cap = Math.max(floor, max);
        interval = interval <= 0 ? floor : (int) Math.min(cap, 2L * interval);
        return interval;
    }

    /**
     * An event or the port's own move: the next unchanged look waits {@code min} again instead of the grown wait. Does
     * not lift the {@code min} spacing of looks.
     */
    void poke() {
        interval = 0;
    }

    /** Forgets the last look, so the next run looks at once; for a new target, mode or settings. */
    void reset() {
        lastScan = NEVER;
        interval = 0;
    }

    /** The current wait between unchanged looks; 0 after activity. Tests read it. */
    int interval() {
        return interval;
    }
}
