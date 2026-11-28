package dev.silencebound.maxtech.lib.api.menu;

/**
 * Handle of an enum constant, declared in a {@link MenuValues}. Reads with {@link MenuValueView#get(EnumValue)}; sent
 * as the constant's ordinal (same build on both sides), and an ordinal the client does not know reads as the nearest
 * constant. Immutable; any thread.
 */
public final class EnumValue<E extends Enum<E>> extends MenuValue {
    private final Class<E> type;

    EnumValue(int index, Class<E> type) {
        super(index);
        this.type = type;
    }

    /** The enum's class, for the definition's checks. */
    Class<E> type() {
        return type;
    }
}
