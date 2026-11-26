package dev.silencebound.maxtech.storage.terminal;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Which terminal screen is open, as far as the client can tell from the menu: a plain terminal (block, part, wireless
 * terminal, portable cell, chest and tablet share it), a crafting terminal or a pattern terminal. Each keeps its own
 * {@link TerminalSize}.
 */
public enum TerminalKind {
    TERMINAL, CRAFTING, PATTERN;

    /** The kind of a menu with a crafting panel ({@code crafting}) or a pattern panel ({@code pattern}). */
    public static TerminalKind of(boolean crafting, boolean pattern) {
        return pattern ? PATTERN : crafting ? CRAFTING : TERMINAL;
    }

    /** The name saved in the prefs file. */
    public String serialName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The kind saved as {@code name}, or null for an unknown one. */
    public static @Nullable TerminalKind bySerialName(String name) {
        for (TerminalKind kind : values()) {
            if (kind.serialName().equals(name)) {
                return kind;
            }
        }
        return null;
    }
}
