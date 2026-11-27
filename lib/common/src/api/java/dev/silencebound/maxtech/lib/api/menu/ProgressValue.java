package dev.silencebound.maxtech.lib.api.menu;

/**
 * Handle of work progress: current and total, in ticks or any unit the host counts, declared in a {@link MenuValues}.
 * Reads with {@link MenuValueView#current(ProgressValue)} and {@link MenuValueView#total(ProgressValue)}; both travel
 * together as var-longs. Immutable; any thread.
 */
public final class ProgressValue extends MenuValue {
    ProgressValue(int index) {
        super(index);
    }
}
