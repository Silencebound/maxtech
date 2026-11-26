package dev.silencebound.maxtech.storage.logic.controller.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.client.gui.accent.SideTabs;
import org.junit.jupiter.api.Test;

class ControllerLayoutTest {
    @Test
    void frameTakesThePreferredSizeInALargeWindow() {
        assertEquals(460, ControllerLayout.fit(960, 460, 304, 460));
        assertEquals(280, ControllerLayout.fit(540, 280, 224, 280));
    }

    @Test
    void frameShrinksWithAMarginAndNeverBelowTheMinimum() {
        // GUI scale 4 on 1920 x 1080: 480 x 270.
        assertEquals(460, ControllerLayout.fit(480, 460, 304, 460));
        assertEquals(254, ControllerLayout.fit(270, 280, 224, 280));
        // The smallest automatic scale leaves at least 320 x 240: the margin gives way, the minimum stays.
        assertEquals(304, ControllerLayout.fit(320, 460, 304, 460));
        assertEquals(224, ControllerLayout.fit(240, 280, 224, 280));
        // A window smaller than the minimum: the frame fills it.
        assertEquals(300, ControllerLayout.fit(300, 460, 304, 460));
    }

    @Test
    void frameAndSideTabsFitTheSmallestWindowTogether() {
        int window = 320;
        int frame = ControllerLayout.fit(window - ControllerLayout.SIDE_TABS, 460, 304, 460);
        int left = ControllerLayout.left(window, frame, ControllerLayout.SIDE_TABS);
        SideTabs.Rect column = SideTabs.column(new SideTabs.Rect(left, 20, frame, 200), 2);
        assertEquals(column.x() + column.width(), left + frame + ControllerLayout.SIDE_TABS,
                "the side room is exactly the tab column");
        assertTrue(left >= 0 && column.right() <= window, "the tabs stay on the window: " + left + ", " + frame);
        int wide = ControllerLayout.fit(960 - ControllerLayout.SIDE_TABS, 460, 304, 460);
        int centred = ControllerLayout.left(960, wide, ControllerLayout.SIDE_TABS);
        assertEquals(960 - (centred + wide + ControllerLayout.SIDE_TABS), centred, "frame and tabs centred together");
    }

    @Test
    void frameNeverGrowsPastTheSizeTheScreenWasMadeWith() {
        assertEquals(400, ControllerLayout.fit(1000, 460, 304, 400));
    }

    @Test
    void listTakesTwoFifthsWithinBounds() {
        assertEquals(160, ControllerLayout.listWidth(444));
        assertEquals(120, ControllerLayout.listWidth(300));
        assertEquals(100, ControllerLayout.listWidth(200));
    }

    @Test
    void buttonsFlowIntoRows() {
        int[] widths = {40, 50, 60, 30};
        int[] xs = new int[4];
        int[] rows = new int[4];
        assertEquals(2, ControllerLayout.flow(widths, 100, 2, xs, rows));
        assertArrayEquals(new int[]{0, 42, 0, 62}, xs);
        assertArrayEquals(new int[]{0, 0, 1, 1}, rows);
        assertEquals(1, ControllerLayout.flow(widths, 500, 2, xs, rows));
        assertArrayEquals(new int[]{0, 0, 0, 0}, rows);
    }

    @Test
    void aButtonWiderThanTheRowGetsItsOwnRow() {
        int[] xs = new int[3];
        int[] rows = new int[3];
        assertEquals(3, ControllerLayout.flow(new int[]{30, 200, 30}, 100, 2, xs, rows));
        assertArrayEquals(new int[]{0, 1, 2}, rows);
        assertEquals(2, ControllerLayout.flow(new int[]{200, 30}, 100, 2, xs, rows),
                "a wide first button: no empty row");
        assertArrayEquals(new int[]{0, 1}, new int[]{rows[0], rows[1]});
        assertEquals(0, ControllerLayout.flow(new int[0], 100, 2, new int[0], new int[0]));
    }

    @Test
    void aClickHitsTheRowDrawnUnderIt() {
        // Rows drawn from x 12 for 100 wide, from y 40, 11 tall, 5 shown, scrolled by 2, 9 rows in all.
        int left = 12;
        int top = 40;
        int width = 100;
        int height = 11;
        for (int i = 0; i < 5; i++) {
            int y = ControllerLayout.rowY(top, i, height);
            assertEquals(2 + i, ControllerLayout.rowAt(left, y, left, top, width, height, 5, 2, 9), "top of row " + i);
            assertEquals(2 + i,
                    ControllerLayout.rowAt(left + width - 1, y + height - 1, left, top, width, height, 5, 2, 9),
                    "bottom right of row " + i);
        }
        assertEquals(-1, ControllerLayout.rowAt(left - 1, top, left, top, width, height, 5, 2, 9), "left of the rows");
        assertEquals(-1, ControllerLayout.rowAt(left + width, top, left, top, width, height, 5, 2, 9), "right of them");
        assertEquals(-1, ControllerLayout.rowAt(left, top - 1, left, top, width, height, 5, 2, 9), "above them");
        assertEquals(-1,
                ControllerLayout.rowAt(left, ControllerLayout.rowY(top, 5, height), left, top, width, height, 5, 2, 9),
                "below the shown rows");
    }

    @Test
    void aClickPastTheLastRuleHitsNothing() {
        assertEquals(2, ControllerLayout.rowAt(20, 40 + 2 * 11, 12, 40, 100, 11, 5, 0, 3));
        assertEquals(-1, ControllerLayout.rowAt(20, 40 + 3 * 11, 12, 40, 100, 11, 5, 0, 3));
        assertEquals(-1, ControllerLayout.rowAt(20, 40, 12, 40, 100, 0, 5, 0, 3), "no row height");
    }

    @Test
    void scrollKeepsTheSelectedRowVisible() {
        assertEquals(0, ControllerLayout.scrollTo(0, 2, 5, 8));
        assertEquals(3, ControllerLayout.scrollTo(0, 7, 5, 8));
        assertEquals(1, ControllerLayout.scrollTo(3, 1, 5, 8));
        assertEquals(0, ControllerLayout.scrollTo(4, -1, 5, 3), "clamped when the list got shorter");
        assertTrue(ControllerLayout.visibleRows(100, 12) == 8);
        assertEquals(0, ControllerLayout.visibleRows(100, 0));
    }
}
