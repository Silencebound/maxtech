package dev.silencebound.maxtech.storage.priority;

/**
 * A block with a priority that the shared priority page edits: the drive, the bulk drive and the subnet controller,
 * where higher priorities take inserts first, and the generator provider, which the planner tries before lower priority
 * patterns for the same output. Server side.
 */
public interface Prioritized {
    /** The current priority, within {@link Integer#MIN_VALUE}..{@link Integer#MAX_VALUE}. */
    int priority();

    /**
     * Sets the priority, clamped to {@link Integer#MIN_VALUE}..{@link Integer#MAX_VALUE}; the block re-indexes its
     * providers when it changed.
     */
    void setPriority(int priority);
}
