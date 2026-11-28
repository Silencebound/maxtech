package dev.silencebound.maxtech.lib.api.menu;

/**
 * A choice a {@link Layout#cycleButton} steps through: an enum implements it, and the button shows the current
 * constant's text with its hint in the tooltip. Implementations are enum constants, so immutable; any thread.
 */
public interface MenuChoice {
    /** The lang key of the choice's short name, shown on the button. */
    String translationKey();

    /** The lang key of the line that says what the choice does, for the tooltip; the name's key plus ".hint". */
    default String hintKey() {
        return translationKey() + ".hint";
    }
}
