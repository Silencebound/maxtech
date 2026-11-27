package dev.silencebound.maxtech.lib.api.menu;

/**
 * Handle of an energy buffer: stored and capacity, in MU, declared in a {@link MenuValues}. Reads with
 * {@link MenuValueView#stored(EnergyValue)} and {@link MenuValueView#capacity(EnergyValue)}; both travel together as
 * var-longs. Immutable; any thread.
 */
public final class EnergyValue extends MenuValue {
    EnergyValue(int index) {
        super(index);
    }
}
