package dev.silencebound.maxtech.lib.api.menu;

/**
 * Handle of a fluid tank: fluid with its components, amount and capacity in mB, declared in a {@link MenuValues}. Reads
 * with {@link MenuValueView#fluid(TankValue)}, {@link MenuValueView#amount(TankValue)} and
 * {@link MenuValueView#capacity(TankValue)}; the fluid travels with {@code FluidKey.OPTIONAL_STREAM_CODEC}, the numbers
 * as var-longs, all three together. Immutable; any thread.
 */
public final class TankValue extends MenuValue {
    TankValue(int index) {
        super(index);
    }
}
