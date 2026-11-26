package dev.silencebound.maxtech.lib.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.silencebound.maxtech.lib.client.gui.GridScroll;
import org.junit.jupiter.api.Test;

class GridScrollTest {
    @Test
    void scrollsOnlyPastTheVisibleRows() {
        assertEquals(0, GridScroll.maxScroll(45, 9, 6), "five rows fit in six");
        assertEquals(0, GridScroll.maxScroll(54, 9, 6));
        assertEquals(1, GridScroll.maxScroll(55, 9, 6), "a seventh row starts");
        assertEquals(1112, GridScroll.maxScroll(10_062, 9, 6));
    }

    @Test
    void thumbFollowsTheScrollBothWays() {
        int thumb = GridScroll.thumbHeight(108, 900, 9, 6, 8);
        assertEquals(8, thumb, "100 rows: the thumb is at its minimum");
        assertEquals(108, GridScroll.thumbHeight(108, 20, 9, 6, 8), "everything fits: the thumb fills the track");
        int max = GridScroll.maxScroll(900, 9, 6);
        assertEquals(0, GridScroll.thumbOffset(0, max, 108, thumb));
        assertEquals(100, GridScroll.thumbOffset(max, max, 108, thumb));
        assertEquals(max, GridScroll.scrollForThumb(100, max, 108, thumb));
        assertEquals(0, GridScroll.scrollForThumb(-30, max, 108, thumb), "dragging above the track clamps");
        assertEquals(max, GridScroll.scrollForThumb(500, max, 108, thumb), "and below it too");
        assertEquals(47, GridScroll.scrollForThumb(50, max, 108, thumb));
    }
}
