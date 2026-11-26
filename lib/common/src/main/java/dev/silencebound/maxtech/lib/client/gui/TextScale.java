package dev.silencebound.maxtech.lib.client.gui;

import dev.silencebound.maxtech.lib.config.ClientConfig;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * The client's text size option for MaxTech screens, independent of the GUI scale. Screens draw text through
 * {@link #text} and size layouts with {@link #width} and {@link #lineHeight}, so the option changes both what is drawn
 * and the room it takes. Text is scaled around its top-left corner. Client thread only.
 */
public final class TextScale {
    /** Config value for the unscaled size. */
    public static final String NORMAL = "normal";
    /** Config value for 1.25 times. */
    public static final String LARGE = "large";
    /** Config value for 1.5 times. */
    public static final String LARGER = "larger";

    private TextScale() {
    }

    /** The factor for a config value; anything unknown is 1. */
    public static float factorFor(String option) {
        return switch (option) {
            case LARGE -> 1.25F;
            case LARGER -> 1.5F;
            default -> 1.0F;
        };
    }

    /** The current factor: 1, 1.25 or 1.5. */
    public static float factor() {
        return factorFor(ClientConfig.textScale());
    }

    /** {@code width} (an unscaled text width) as drawn, rounded up. */
    public static int scaled(int width) {
        return scaled(width, factor());
    }

    /** {@code width} times {@code factor}, rounded up. */
    public static int scaled(int width, float factor) {
        return (int) Math.ceil(width * factor);
    }

    /** The unscaled width that still fits into {@code pixels} once drawn at {@code factor}, for trimming. */
    public static int unscaledFit(int pixels, float factor) {
        return (int) Math.floor(pixels / factor);
    }

    /** Width of {@code text} as drawn at the current size. */
    public static int width(Font font, String text) {
        return scaled(font.width(text));
    }

    /** Width of {@code text} as drawn at the current size. */
    public static int width(Font font, Component text) {
        return scaled(font.width(text));
    }

    /** Width of {@code text} as drawn at the current size. */
    public static int width(Font font, FormattedCharSequence text) {
        return scaled(font.width(text));
    }

    /** Line height at the current size, for row heights and line steps. */
    public static int lineHeight(Font font) {
        return scaled(font.lineHeight);
    }

    /** Draws {@code text} like {@code graphics.text}, at the current size. */
    public static void text(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int colour,
            boolean shadow) {
        float f = factor();
        if (f == 1.0F) {
            graphics.text(font, text, x, y, colour, shadow);
            return;
        }
        begin(graphics, x, y, f);
        graphics.text(font, text, 0, 0, colour, shadow);
        graphics.pose().popMatrix();
    }

    /** See {@link #text(GuiGraphicsExtractor, Font, String, int, int, int, boolean)}. */
    public static void text(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int colour,
            boolean shadow) {
        float f = factor();
        if (f == 1.0F) {
            graphics.text(font, text, x, y, colour, shadow);
            return;
        }
        begin(graphics, x, y, f);
        graphics.text(font, text, 0, 0, colour, shadow);
        graphics.pose().popMatrix();
    }

    /** See {@link #text(GuiGraphicsExtractor, Font, String, int, int, int, boolean)}. */
    public static void text(GuiGraphicsExtractor graphics, Font font, FormattedCharSequence text, int x, int y,
            int colour, boolean shadow) {
        float f = factor();
        if (f == 1.0F) {
            graphics.text(font, text, x, y, colour, shadow);
            return;
        }
        begin(graphics, x, y, f);
        graphics.text(font, text, 0, 0, colour, shadow);
        graphics.pose().popMatrix();
    }

    private static void begin(GuiGraphicsExtractor graphics, int x, int y, float factor) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(factor, factor);
    }
}
