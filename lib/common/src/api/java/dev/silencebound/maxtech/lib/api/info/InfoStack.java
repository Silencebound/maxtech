package dev.silencebound.maxtech.lib.api.info;

import dev.silencebound.maxtech.lib.api.resource.StackKey;

/**
 * An item or fluid and an amount, for {@link BlockInfoSink#stacks}: items in their count, fluids in millibuckets.
 * Immutable.
 *
 * @param key
 *            the item or fluid
 * @param amount
 *            how much; 0 or more
 */
public record InfoStack(StackKey key, long amount) {
}
