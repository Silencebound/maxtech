package dev.silencebound.maxtech.lib.api.menu;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import java.util.Optional;

/**
 * The current values of one open menu. On the client: what the server sent last, or zero, the enum's first constant and
 * no fluid before the first values arrive (on the first tick after the menu opens). On the server: what the getters
 * returned when the menu last read them. Reads never allocate. Each method throws {@link IllegalArgumentException} for
 * a handle from another declaration (wrong position or kind). Main thread of its side.
 */
public interface MenuValueView {
    /** The int value. */
    int get(IntValue value);

    /** The long value. */
    long get(LongValue value);

    /** The enum constant; the enum's first constant before the first values arrive. */
    <E extends Enum<E>> E get(EnumValue<E> value);

    /** Stored energy in MU. */
    long stored(EnergyValue value);

    /** Energy capacity in MU. */
    long capacity(EnergyValue value);

    /** Work done. */
    long current(ProgressValue value);

    /** Work in total; zero when the host reports none. */
    long total(ProgressValue value);

    /** The tank's fluid with its components; empty when the tank is. */
    Optional<FluidKey> fluid(TankValue value);

    /** Amount in mB. */
    long amount(TankValue value);

    /** Capacity in mB. */
    long capacity(TankValue value);
}
