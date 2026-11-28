package dev.silencebound.maxtech.lib.api.menu;

import java.util.List;
import org.jetbrains.annotations.ApiStatus;

/**
 * Handle of a server action that sets an enum value from the screen, declared with
 * {@link MenuDefinition.Builder#cycle}; a {@link Layout#cycleButton} shows it. A click asks the server for the next
 * (left click) or the previous (right click) of {@link #choices()}, counted from the value the client shows. The click
 * names the choice itself, not "next", so a click the server sees twice sets the same choice twice and never skips a
 * step. The server takes it only while the menu is open (vanilla's checks) and only for a constant of the enum, then
 * hands it to the definition's setter; the new value reaches the screen with the next values payload. Immutable; any
 * thread.
 *
 * @param <E>
 *            the enum, whose constants are the choices in declaration order
 */
public final class CycleAction<E extends Enum<E> & MenuChoice> {
    /** The most constants an enum may have for a cycle action; the button id carries the ordinal below it. */
    public static final int MAX_CHOICES = 256;

    private final int index;
    private final EnumValue<E> value;
    private final List<E> choices;

    CycleAction(int index, EnumValue<E> value) {
        E[] constants = value.type().getEnumConstants();
        if (constants.length > MAX_CHOICES) {
            throw new IllegalArgumentException(
                    value.type().getName() + " has " + constants.length + " constants, over " + MAX_CHOICES);
        }
        this.index = index;
        this.value = value;
        this.choices = List.of(constants);
    }

    /** The synced value the action sets and the button shows. */
    public EnumValue<E> value() {
        return value;
    }

    /** Every constant of the enum, in declaration order: the cycle. */
    public List<E> choices() {
        return choices;
    }

    /** The choice after {@code current}; the first after the last. */
    @SuppressWarnings("EnumOrdinal") // choices holds the constants in ordinal order
    public E next(E current) {
        return choices.get((current.ordinal() + 1) % choices.size());
    }

    /** The choice before {@code current}; the last before the first. */
    @SuppressWarnings("EnumOrdinal") // choices holds the constants in ordinal order
    public E previous(E current) {
        return choices.get((current.ordinal() + choices.size() - 1) % choices.size());
    }

    /** The action's position among its definition's cycle actions, for the button id. */
    @ApiStatus.Internal
    public int index() {
        return index;
    }
}
