package dev.silencebound.maxtech.lib.api.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TextScaleTest {
    @Test
    void factorsFollowTheOption() {
        assertEquals(1.0F, TextScale.factorFor("normal"));
        assertEquals(1.25F, TextScale.factorFor("large"));
        assertEquals(1.5F, TextScale.factorFor("larger"));
        assertEquals(1.0F, TextScale.factorFor("bogus"));
    }

    @Test
    void factorReadsTheOptionSource() {
        try {
            TextScale.setOptionSource(() -> TextScale.LARGER);
            assertEquals(1.5F, TextScale.factor());
        } finally {
            TextScale.setOptionSource(() -> TextScale.NORMAL);
        }
        assertEquals(1.0F, TextScale.factor());
    }

    @Test
    void scaledWidthRoundsUpAndFitInvertsIt() {
        assertEquals(10, TextScale.scaled(10, 1.0F));
        assertEquals(13, TextScale.scaled(10, 1.25F));
        assertEquals(15, TextScale.scaled(10, 1.5F));
        assertEquals(40, TextScale.unscaledFit(60, 1.5F));
        assertEquals(48, TextScale.unscaledFit(60, 1.25F));
    }
}
