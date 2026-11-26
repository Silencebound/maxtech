package dev.silencebound.maxtech.lib.api.info;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Receives what a {@link BlockInfoProvider} says about a block. Consumers (the scanner, overlay plugins) implement it.
 * An interface rather than a {@code Consumer} so structured entries can be added as default methods that fall back to
 * {@link #line(Component)}: a consumer that only implements {@code line} (a lambda, the scanner's chat) still gets
 * every entry as text, while an overlay such as Jade draws a status icon, a bar, channel pips, item icons or a mode
 * icon. Every structured entry therefore carries a text that says the same in words.
 *
 * <p>
 * Sprites are GUI sprite ids ({@code assets/<namespace>/textures/gui/sprites/<path>.png}), drawn 8 x 8.
 *
 * <p>
 * Called on the server thread, only during {@link BlockInfoProvider#addInfo}; providers must not keep the sink after
 * that call returns.
 */
public interface BlockInfoSink {
    /** How a status reads at a glance. Overlays colour the entry by it; the colours are fixed RGB values. */
    enum Tone {
        /** Working as meant: online, running. Cyan, the MaxTech accent. */
        GOOD(0x3FC3E0),
        /** Needs a look but still works or waits: paused, standby, nearly full. Amber. */
        WARN(0xF0AA3C),
        /** Does not work: no channel, no power, blocked, full. Red. */
        BAD(0xE05A5A),
        /** Off or not connected, nothing wrong with it. Grey. */
        DIM(0x9AA1AA);

        private final int rgb;

        Tone(int rgb) {
            this.rgb = rgb;
        }

        /** The tone's colour as {@code 0xRRGGBB}. */
        public int rgb() {
            return rgb;
        }
    }

    /** Adds one line of text; lang-keyed, never blank. */
    void line(Component line);

    /**
     * Adds the block's status, such as "Online" or "No channel", meant as the first entry an overlay shows. A compact
     * overlay draws only a coloured mark for it and shows the word with the details. The default adds {@code text} in
     * the tone's colour as a line, keeping {@code text}'s own contents (a translatable stays one), so readers that look
     * at keys still find them.
     */
    default void status(Tone tone, Component text) {
        line(text.copy().withColor(tone.rgb()));
    }

    /**
     * Like {@link #status(Tone, Component)}, with the overlay's mark drawn as {@code sprite} instead of a plain dot in
     * the tone's colour. The default ignores the sprite.
     */
    default void status(Tone tone, Component text, Identifier sprite) {
        status(tone, text);
    }

    /**
     * Adds a bar filled {@code value} of {@code max}, labelled inside with {@code text}. A consumer without bars shows
     * only the text, so it should still say what the bar is, briefly ("Bytes 64%", "21 / 32 channels"). {@code max} of
     * 0 or less draws an empty bar; {@code value} is clamped to {@code 0..max}. {@code rgb} is the fill colour as
     * {@code 0xRRGGBB}. The default adds {@code text} as a line.
     */
    default void progress(Component text, long value, long max, int rgb) {
        line(text);
    }

    /**
     * Like {@link #progress(Component, long, long, int)}, with {@code sprite} drawn before the bar to say what it
     * measures (energy, channels). The default ignores the sprite.
     */
    default void progress(Identifier sprite, Component text, long value, long max, int rgb) {
        progress(text, value, max, rgb);
    }

    /**
     * Adds a row of channel pips, {@code used} of {@code capacity} filled; an overlay draws a limited number and a "+n"
     * for the rest. {@code text} says the same in words ("5 / 16 channels"); the default adds it as a line.
     */
    default void pips(Component text, int used, int capacity) {
        line(text);
    }

    /**
     * Adds a row of item and fluid icons with their amounts, most important first; an overlay may draw only the first
     * few. {@code text} says the same in words; the default adds it as a line. Adds nothing for an empty list.
     */
    default void stacks(Component text, List<InfoStack> stacks) {
        if (!stacks.isEmpty()) {
            line(text);
        }
    }

    /**
     * Adds a small icon ({@code sprite}) followed by {@code text}, such as a port's mode arrow and its rate. The
     * default adds {@code text} as a line.
     */
    default void icon(Identifier sprite, Component text) {
        line(text);
    }
}
