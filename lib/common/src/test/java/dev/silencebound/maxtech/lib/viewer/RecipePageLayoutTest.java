package dev.silencebound.maxtech.lib.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RecipePageLayoutTest {
    @Test
    void oneInOneOutIsCentredOnTheMinimumWidth() {
        RecipePageLayout layout = RecipePageLayout.of(1, 1, 3);
        // 18 + 4 + 24 + 4 + 18 = 68 wide, centred in 150: starts at 41.
        assertEquals(150, layout.width());
        assertEquals(42, layout.inputX(0));
        assertEquals(1, layout.inputY(0));
        assertEquals(63, layout.arrowX());
        assertEquals(0, layout.arrowY());
        assertEquals(92, layout.outputX(0));
        assertEquals(1, layout.outputY(0));
        // Text starts below the 18 pixel row; three lines of 10.
        assertEquals(22, layout.lineY(0));
        assertEquals(42, layout.lineY(2));
        assertEquals(52, layout.height());
    }

    @Test
    void fourInputsMakeASquareAndTheRowGrows() {
        RecipePageLayout layout = RecipePageLayout.of(4, 1, 3);
        // Two rows: the single output and the arrow sit in their middle.
        assertEquals(layout.inputX(0), layout.inputX(2));
        assertEquals(layout.inputX(0) + 18, layout.inputX(1));
        assertEquals(layout.inputY(0) + 18, layout.inputY(2));
        assertEquals(10, layout.outputY(0));
        assertEquals((36 - RecipePageLayout.ARROW_HEIGHT) / 2, layout.arrowY());
        assertEquals(40, layout.lineY(0));
    }

    @Test
    void nineSlotsMakeThreeRows() {
        RecipePageLayout layout = RecipePageLayout.of(9, 0, 3);
        assertEquals(layout.inputY(0) + 36, layout.inputY(6));
        assertEquals(layout.inputX(0) + 36, layout.inputX(2));
    }

    @Test
    void wideCategoriesGrowPastTheMinimum() {
        // Twelve slots each way: three rows of four columns, 72 wide per side.
        RecipePageLayout layout = RecipePageLayout.of(12, 12, 3);
        assertEquals(72 + 4 + 24 + 4 + 72, layout.width());
        assertEquals(1, layout.inputX(0));
        assertEquals(layout.width() - 18 + 1, layout.outputX(11));
    }

    @Test
    void noOutputsLeaveNoGapAfterTheArrow() {
        RecipePageLayout layout = RecipePageLayout.of(1, 0, 3);
        // 18 + 4 + 24 = 46, centred in 150: the slot frame starts at 52.
        assertEquals(53, layout.inputX(0));
        assertEquals(74, layout.arrowX());
    }

    @Test
    void textUsesTheWholeWidthAndSetsTheHeight() {
        RecipePageLayout layout = RecipePageLayout.of(1, 1, 4);
        assertEquals(layout.width(), layout.textWidth());
        assertEquals(22 + 40, layout.height());
        assertEquals(22 + 10, RecipePageLayout.of(1, 1, 1).height());
    }

    /** Without text lines the page ends just below the slot row. */
    @Test
    void noTextLinesEndBelowTheRow() {
        RecipePageLayout layout = RecipePageLayout.of(1, 1, 0);
        assertEquals(0, layout.textLines());
        assertEquals(22, layout.height());
    }

    @Test
    void negativeCountsThrow() {
        assertThrows(IllegalArgumentException.class, () -> RecipePageLayout.of(-1, 1, 3));
    }
}
