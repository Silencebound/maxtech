package dev.silencebound.maxtech.storage.ioport;

/**
 * The IO port's rules that need no world (B09 item 11): its speed and when a cell leaves for the output. Pure, so it is
 * unit-tested; safe from any thread.
 */
public final class IoPortLogic {
    /**
     * Keys the port may look at per operation in one run without moving anything: a load pass goes over every kind in
     * the network and a partitioned cell refuses most of them, so a refusal costs no operation but the looking is
     * bounded.
     */
    public static final int SCANS_PER_OPERATION = 16;
    /** Most operations per tick, whatever the config and cards say. */
    public static final int MAX_OPERATIONS = 4096;

    private IoPortLogic() {
    }

    /**
     * Operations per tick: {@code base} times {@code multiplier} once per speed card, rounded down, at least 1 and at
     * most {@link #MAX_OPERATIONS}.
     */
    public static int operations(int base, int cards, double multiplier) {
        double rate = Math.max(1, base);
        for (int i = 0; i < cards; i++) {
            rate *= Math.max(1.0, multiplier);
        }
        return (int) Math.max(1, Math.min(MAX_OPERATIONS, Math.floor(rate)));
    }

    /**
     * Whether a cell leaves for the output now. {@link IoPortOutput#EMPTY} and {@link IoPortOutput#FULL} hold as soon
     * as the cell is empty or full, before or after any work; {@link IoPortOutput#WORK_DONE} only once {@code done}
     * (the port can do no more with it in its mode).
     */
    public static boolean leaves(IoPortOutput when, boolean empty, boolean full, boolean done) {
        return switch (when) {
            case EMPTY -> empty;
            case FULL -> full;
            case WORK_DONE -> done;
        };
    }

    /**
     * Whether the port is finished with a cell in {@code mode} without another pass: an unloading cell that is empty, a
     * loading cell that is full.
     */
    public static boolean finished(IoPortMode mode, boolean empty, boolean full) {
        return mode == IoPortMode.UNLOAD ? empty : full;
    }
}
