package dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy;

/**
 * One 1.21.1 handler as seen by a {@link LegacyLedger}: real, non-transactional moves on it. Slots are the handler's
 * own slot numbers; a handler without slots (fluids, energy) uses slot 0.
 *
 * @param <R> the resource type
 */
interface LegacyTarget<R> {
    /**
     * {@return whether extraction can be done for real at once and undone with {@link #restore}}; otherwise it is
     * simulated and applied on commit like insertion
     */
    boolean extractsImmediately();

    /** {@return how much of {@code amount} the slot would accept now} */
    int simulateInsert(int slot, R resource, int amount);

    /** {@return how much of {@code amount} of the resource the slot would give now} */
    int simulateExtract(int slot, R resource, int amount);

    /** Inserts for real into the slot; {@return the amount accepted} */
    int insert(int slot, R resource, int amount);

    /** Inserts for real into any slot; {@return the amount accepted} */
    int insertAnywhere(R resource, int amount);

    /** Extracts for real from the slot, only if it holds the resource; {@return the amount extracted} */
    int extract(int slot, R resource, int amount);

    /**
     * Puts back an amount extracted earlier in the same transaction, bypassing insertion rules (output slots refuse
     * inserts); {@return the amount put back}. Only called when {@link #extractsImmediately()}.
     */
    int restore(int slot, R resource, int amount);

    /**
     * Takes what neither the slot nor any other slot accepted after the change was already counted, for example items
     * dropped into the world. Must not throw.
     */
    void overflow(R resource, int amount);

    /** {@return the wrapped 1.21.1 object, for log messages} */
    Object handler();
}
