package dev.silencebound.maxtech.lib.api.menu;

/**
 * A state a {@link Layout#status} line shows: an enum implements it, and the line draws a dot in the tone's colour and
 * the constant's text. Implementations are enum constants, so immutable; any thread.
 */
public interface MenuStatus {
    /** The lang key of the text shown for this state. */
    String translationKey();

    /** The colour of the dot. */
    Tone tone();

    /** A dot's colour; the same four as the client's {@code AccentStatus}. */
    enum Tone {
        /** Running, online, done: green. */
        OK,
        /** Paused, waiting, low: amber. */
        WARN,
        /** Failed or broken; needs the player: red. */
        ERROR,
        /** Switched off or unknown: grey. */
        OFF
    }
}
