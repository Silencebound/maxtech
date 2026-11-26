package dev.silencebound.maxtech.lib.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.silencebound.maxtech.lib.client.gui.TextScale;
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
    void scaledWidthRoundsUpAndFitInvertsIt() {
        assertEquals(10, TextScale.scaled(10, 1.0F));
        assertEquals(13, TextScale.scaled(10, 1.25F));
        assertEquals(15, TextScale.scaled(10, 1.5F));
        assertEquals(40, TextScale.unscaledFit(60, 1.5F));
        assertEquals(48, TextScale.unscaledFit(60, 1.25F));
    }
}
