package dev.silencebound.maxtech.storage.terminal;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * How tall the terminal screen's item grid is (B09 item 21c, like AE2's terminal sizes): {@link #SMALL} the base
 * layout, {@link #TALL} {@link TerminalLayout#TALL_EXTRA_ROWS} more rows, {@link #FILL} as many rows as fit the window.
 * No size makes the screen taller than the window ({@link TerminalLayout#extraRows}). The player cycles it with the
 * button beside the sort buttons; {@link TerminalPrefs} keeps one per {@link TerminalKind}.
 */
public enum TerminalSize {
    SMALL, TALL, FILL;

    /** The size the button switches to: forward small, tall, fill, small; back the other way. */
    public TerminalSize step(boolean forward) {
        return switch (this) {
            case SMALL -> forward ? TALL : FILL;
            case TALL -> forward ? FILL : SMALL;
            case FILL -> forward ? SMALL : TALL;
        };
    }

    /** The name saved in the prefs file. */
    public String serialName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The size saved as {@code name}; {@link #SMALL} for a missing or unknown one. */
    public static TerminalSize bySerialName(@Nullable String name) {
        for (TerminalSize size : values()) {
            if (size.serialName().equals(name)) {
                return size;
            }
        }
        return SMALL;
    }
}
