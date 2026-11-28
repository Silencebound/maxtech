package dev.silencebound.maxtech.lib.api.machine;

import dev.silencebound.maxtech.lib.api.menu.MenuChoice;

/**
 * When a machine may run by the redstone signal at it (owner, 2026-11-28): always (the default), only with a signal,
 * only without one, one run per rising edge of the signal, or never. {@link RedstoneControl} holds a machine's mode.
 * Saves use {@link #serializedName()}, never the ordinal; menus sync the ordinal, which is the same on both sides of
 * one build. The order is the cycle a {@code Layout.cycleButton} steps through. Immutable; any thread.
 */
public enum RedstoneMode implements MenuChoice {
    /** Runs whatever the signal; the default. */
    ALWAYS("always"),
    /** Runs only while the machine gets a redstone signal. */
    HIGH("high"),
    /** Runs only while the machine gets no redstone signal. */
    LOW("low"),
    /** Starts one run per rising edge of the signal. */
    PULSE("pulse"),
    /** Never runs, whatever the signal. */
    NEVER("never");

    /**
     * The lang key of a redstone cycle button's tooltip line, with the mode's name as its {@code %s}: pass it to
     * {@code Layout.cycleButton}.
     */
    public static final String BUTTON_KEY = "gui.maxtech_lib.redstone";

    private final String name;

    RedstoneMode(String name) {
        this.name = name;
    }

    /** The mode's fixed name in saves. */
    public String serializedName() {
        return name;
    }

    /** The mode saved as {@code name}; {@link #ALWAYS} for an unknown name. */
    public static RedstoneMode byName(String name) {
        for (RedstoneMode mode : values()) {
            if (mode.name.equals(name)) {
                return mode;
            }
        }
        return ALWAYS;
    }

    /** {@code gui.maxtech_lib.redstone.<name>}; the hint is that plus ".hint". */
    @Override
    public String translationKey() {
        return BUTTON_KEY + "." + name;
    }
}
