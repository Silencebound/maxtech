package dev.silencebound.maxtech.lib.api.menu;

/**
 * A value declared in a {@link MenuValues}: its position there. Handles are only meaningful with the declaration that
 * made them; a view refuses a handle whose position or kind does not match its own declaration. Immutable; any thread.
 */
public abstract sealed class MenuValue permits IntValue, LongValue, EnumValue, EnergyValue, ProgressValue, TankValue {
    private final int index;

    MenuValue(int index) {
        this.index = index;
    }

    /** Position in the declaration, from 0 in the order values were declared. */
    public final int index() {
        return index;
    }
}
