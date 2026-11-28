package dev.silencebound.maxtech.lib.client.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.lib.api.menu.Layout;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Text in a toolkit screen's fixed text boxes stays inside them at every text size. */
class BoxTextTest {
    /** Vanilla's font line height, the gap below the glyphs included. */
    private static final int LINE_HEIGHT = 9;

    @Test
    void textFitsItsBoxAtEveryTextSize() {
        for (String option : List.of(TextScale.NORMAL, TextScale.LARGE, TextScale.LARGER)) {
            float scale = BoxText.factor(LINE_HEIGHT, Layout.TEXT_HEIGHT, TextScale.factorFor(option));
            float glyphs = (LINE_HEIGHT - 1) * scale;
            assertTrue(BoxText.TOP + glyphs <= Layout.TEXT_HEIGHT, option + ": " + glyphs + " px of glyphs");
        }
    }

    @Test
    void normalTextKeepsItsSize() {
        assertEquals(1.0F, BoxText.factor(LINE_HEIGHT, Layout.TEXT_HEIGHT, 1.0F));
        // A taller box lets the larger sizes through unchanged.
        assertEquals(1.5F, BoxText.factor(LINE_HEIGHT, 14, 1.5F));
    }
}
