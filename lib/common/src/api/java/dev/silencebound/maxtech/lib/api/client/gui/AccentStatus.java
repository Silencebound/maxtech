package dev.silencebound.maxtech.lib.api.client.gui;

import org.jetbrains.annotations.ApiStatus;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A state shown as a colour in the Accent look: a status dot ({@link #dot}), a list row's dot, a tag or a toast's
 * strip. The dot is a small square with a dark rim, {@link #SIZE} across with the rim, centred on a line of text drawn
 * at the same {@code y} when placed with {@link #dotY}. Client only for drawing.
 */
@ApiStatus.Experimental
public enum AccentStatus {
    /** Running, online, done. */
    OK(Accent.GREEN),
    /** Paused, waiting, low. */
    WARN(Accent.AMBER),
    /** Failed or broken; needs the player. */
    ERROR(Accent.RED),
    /** Switched off or unknown. */
    OFF(Accent.DIM);

    /** A dot's size, its rim included. */
    public static final int SIZE = 5;

    private final int colour;

    AccentStatus(int colour) {
        this.colour = colour;
    }

    /** The state's colour (ARGB). */
    public int colour() {
        return colour;
    }

    /** The dot's top for a line of text {@code textHeight} tall at {@code y}. */
    public static int dotY(int y, int textHeight) {
        return y + (textHeight - SIZE) / 2;
    }

    /** A dot {@link #SIZE} square, rim included, at ({@code x}, {@code y}) in {@code colour}. */
    public static void dot(GuiGraphicsExtractor graphics, int x, int y, int colour) {
        Accent.dot(graphics, x + 1, y + 1, SIZE - 2, colour);
    }
}
