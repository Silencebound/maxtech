package dev.silencebound.maxtech.storage.terminal.client;

import org.jspecify.annotations.Nullable;

/**
 * The link between the terminal's search box and a recipe viewer's search (client config
 * {@code terminal.jeiSearchSync}). The JEI compat installs a {@link Bridge} once JEI's runtime is up and removes it
 * when the runtime goes; without one the terminal searches on its own. Client thread.
 */
public final class TerminalSearchSync {
    /** A recipe viewer's search field. */
    public interface Bridge {
        /** The viewer's current search text. */
        String get();

        /** Replaces the viewer's search text. */
        void set(String text);
    }

    private static @Nullable Bridge bridge;

    private TerminalSearchSync() {
    }

    /** Installs {@code next}, or removes the bridge with {@code null}. */
    public static void install(@Nullable Bridge next) {
        bridge = next;
    }

    /** The viewer's search text, or null without a viewer. */
    public static @Nullable String get() {
        Bridge current = bridge;
        return current == null ? null : current.get();
    }

    /** Sends {@code text} to the viewer's search; nothing without a viewer. */
    public static void set(String text) {
        Bridge current = bridge;
        if (current != null) {
            current.set(text);
        }
    }
}
