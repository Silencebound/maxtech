package dev.silencebound.maxtech.storage.palette;

import dev.silencebound.maxtech.storage.config.StorageClientConfig;

/**
 * The colours and glyphs of Storage's status signals (M7C item 23): good, warning, bad and dim, used by the display
 * panels, the controller's alarm list, the crafting screens and the logic editor. Every status colour is read here, and
 * every status also has a glyph that is always drawn, so colour is never the only signal. The client picks the palette
 * with {@code visuals.palette}. Pure arithmetic; the lookups without a palette argument read the client config and
 * belong on the client thread.
 */
public final class StatusPalette {
    /** A status. */
    public enum Tone {
        /** Fine, done, in stock. */
        GOOD("+"),
        /** Needs a look: waiting, low, held back. */
        WARN("!"),
        /** Failed, missing, blocked. */
        BAD("x"),
        /** Inactive, finished, of no interest. */
        DIM("-");

        private final String glyph;

        Tone(String glyph) {
            this.glyph = glyph;
        }

        /** A one-character ASCII mark that every font has; differs per tone and not per palette. */
        public String glyph() {
            return glyph;
        }
    }

    /** The palettes a player can pick. Colours are ARGB in tone order GOOD, WARN, BAD, DIM. */
    public enum Palette {
        /** Green, amber and red. */
        DEFAULT(0xFF6CC48A, 0xFFE0A84C, 0xFFE0675C, 0xFF8E9AA6),
        /** For red-green colour blindness (deuteranopia and protanopia): blue, yellow and orange. */
        RED_GREEN_SAFE(0xFF56B4E9, 0xFFF0E442, 0xFFE8702A, 0xFF8E9AA6),
        /** For blue-yellow colour blindness (tritanopia): green, orange and a strong crimson, apart in lightness. */
        BLUE_YELLOW_SAFE(0xFF5FD068, 0xFFFF9A3C, 0xFFE8344A, 0xFF8E9AA6);

        private final int good;
        private final int warn;
        private final int bad;
        private final int dim;

        Palette(int good, int warn, int bad, int dim) {
            this.good = good;
            this.warn = warn;
            this.bad = bad;
            this.dim = dim;
        }

        /** The ARGB colour of {@code tone}. */
        public int colour(Tone tone) {
            return switch (tone) {
                case GOOD -> good;
                case WARN -> warn;
                case BAD -> bad;
                case DIM -> dim;
            };
        }
    }

    private StatusPalette() {
    }

    /** The colour of {@code tone} in {@code palette}. */
    public static int colour(Palette palette, Tone tone) {
        return palette.colour(tone);
    }

    /** The colour of {@code tone} in the client's palette. */
    public static int colour(Tone tone) {
        return StorageClientConfig.palette().colour(tone);
    }

    /** The good colour in the client's palette. */
    public static int good() {
        return colour(Tone.GOOD);
    }

    /** The warning colour in the client's palette. */
    public static int warn() {
        return colour(Tone.WARN);
    }

    /** The bad colour in the client's palette. */
    public static int bad() {
        return colour(Tone.BAD);
    }

    /** The dim colour in the client's palette. */
    public static int dim() {
        return colour(Tone.DIM);
    }

    /** {@code tone}'s colour darkened to a tile background (a quarter as bright, opaque). */
    public static int tile(Tone tone) {
        int argb = colour(tone);
        int r = (argb >> 16 & 0xFF) / 4;
        int g = (argb >> 8 & 0xFF) / 4;
        int b = (argb & 0xFF) / 4;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** {@code tone}'s glyph followed by a space, to put before a status line. */
    public static String mark(Tone tone) {
        return tone.glyph() + " ";
    }
}
