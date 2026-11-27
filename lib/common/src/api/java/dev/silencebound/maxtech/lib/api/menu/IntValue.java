package dev.silencebound.maxtech.lib.api.menu;

/**
 * Handle of a whole number, declared in a {@link MenuValues}. Reads with {@link MenuValueView#get(IntValue)}; sent as a
 * var-int. Immutable; any thread.
 */
public final class IntValue extends MenuValue {
    IntValue(int index) {
        super(index);
    }
}
