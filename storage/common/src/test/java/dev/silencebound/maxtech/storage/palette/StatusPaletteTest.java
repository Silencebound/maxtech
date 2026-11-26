package dev.silencebound.maxtech.storage.palette;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.storage.palette.StatusPalette.Palette;
import dev.silencebound.maxtech.storage.palette.StatusPalette.Tone;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StatusPaletteTest {
    @Test
    void everyToneHasADistinctColourInEveryPalette() {
        for (Palette palette : Palette.values()) {
            Set<Integer> seen = new HashSet<>();
            for (Tone tone : Tone.values()) {
                assertEquals(0xFF000000, StatusPalette.colour(palette, tone) & 0xFF000000, "opaque");
                assertTrue(seen.add(StatusPalette.colour(palette, tone)), palette + " repeats a colour for " + tone);
            }
        }
    }

    @Test
    void everyToneHasADistinctGlyph() {
        Set<String> seen = new HashSet<>();
        for (Tone tone : Tone.values()) {
            assertEquals(1, tone.glyph().length());
            assertTrue(seen.add(tone.glyph()), "glyph repeated for " + tone);
        }
    }

    @Test
    void safePalettesDifferFromDefaultForTheSignalTones() {
        for (Palette palette : new Palette[]{Palette.RED_GREEN_SAFE, Palette.BLUE_YELLOW_SAFE}) {
            for (Tone tone : new Tone[]{Tone.GOOD, Tone.WARN, Tone.BAD}) {
                assertNotEquals(Palette.DEFAULT.colour(tone), palette.colour(tone), palette + " " + tone);
            }
        }
    }
}
