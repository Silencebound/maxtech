package dev.silencebound.maxtech.lib.api.menu;

/**
 * Handle of a long number, declared in a {@link MenuValues}. Reads with {@link MenuValueView#get(LongValue)}; sent as a
 * var-long, so values above {@link Integer#MAX_VALUE} arrive whole. Immutable; any thread.
 */
public final class LongValue extends MenuValue {
    LongValue(int index) {
        super(index);
    }
}
