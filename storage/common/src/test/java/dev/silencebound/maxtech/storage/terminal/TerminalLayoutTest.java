package dev.silencebound.maxtech.storage.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TerminalLayoutTest {
    private static final int SLOT = 16;

    @Test
    void craftingTerminalIsAsHighAsAPlainOne() {
        for (int extra : new int[]{0, 3}) {
            TerminalLayout plain = new TerminalLayout(extra, false);
            TerminalLayout crafting = new TerminalLayout(extra, true);
            assertEquals(plain.height(), crafting.height());
            assertEquals(plain.statusY(), crafting.statusY());
            assertEquals(plain.inventoryY(), crafting.inventoryY());
            assertEquals(plain.itemRows() - TerminalLayout.PANEL_ROWS, crafting.itemRows());
        }
        assertEquals(240, new TerminalLayout(0, true).height(), "the base layout fits a 240 high window");
        assertTrue(new TerminalLayout(0, true).itemRows() >= 3, "the item grid keeps at least three rows");
    }

    @Test
    void panelFitsBetweenItemGridAndStatusLine() {
        for (int extra : new int[]{0, 3}) {
            TerminalLayout layout = new TerminalLayout(extra, true);
            int top = TerminalLayout.GRID_Y + layout.itemRows() * TerminalLayout.CELL;
            assertEquals(top, layout.panelY());
            int[][] boxes = boxes(layout);
            for (int i = 0; i < boxes.length; i++) {
                int[] box = boxes[i];
                assertTrue(box[1] >= top, "below the item grid: " + i);
                assertTrue(box[1] + box[3] <= layout.statusY() - 2, "above the status line: " + i);
                assertTrue(box[0] >= TerminalLayout.GRID_X - 1, "inside the left edge: " + i);
                assertTrue(box[0] + box[2] <= TerminalLayout.WIDTH - 7, "inside the right edge: " + i);
                for (int j = i + 1; j < boxes.length; j++) {
                    assertTrue(!overlap(box, boxes[j]), "boxes " + i + " and " + j + " overlap");
                }
            }
        }
    }

    /**
     * No size makes the panel taller than the window, fill takes every row that fits, and tall adds its rows only when
     * they fit (B09 item 21c).
     */
    @Test
    void sizesNeverPassTheWindow() {
        for (boolean panel : new boolean[]{false, true}) {
            for (int window = TerminalLayout.BASE_HEIGHT; window <= 1200; window++) {
                for (TerminalSize size : TerminalSize.values()) {
                    TerminalLayout layout = TerminalLayout.of(size, window, panel);
                    assertTrue(layout.height() <= window, size + " in a window " + window + " high");
                }
                TerminalLayout fill = TerminalLayout.of(TerminalSize.FILL, window, panel);
                assertTrue(fill.height() + TerminalLayout.CELL > window, "fill leaves no room for a row: " + window);
                assertEquals(TerminalLayout.BASE_HEIGHT, TerminalLayout.of(TerminalSize.SMALL, window, panel).height());
            }
        }
        assertEquals(0, TerminalLayout.extraRows(TerminalSize.TALL, 240, false), "no room for tall rows");
        assertEquals(2, TerminalLayout.extraRows(TerminalSize.TALL, 280, false), "room for two of the three");
        assertEquals(3, TerminalLayout.extraRows(TerminalSize.TALL, 294, false));
        assertEquals(3, TerminalLayout.extraRows(TerminalSize.TALL, 1000, true), "tall stays three rows");
        assertEquals(8, TerminalLayout.extraRows(TerminalSize.FILL, 400, false));
        assertEquals(42, TerminalLayout.extraRows(TerminalSize.FILL, 1000, true));
    }

    /** In a window lower than the base layout every size gives up rows, down to one row of items. */
    @Test
    void lowWindowsShrinkTheItemGrid() {
        TerminalLayout small = TerminalLayout.of(TerminalSize.SMALL, 200, false);
        assertTrue(small.height() <= 200);
        assertEquals(3, small.itemRows());
        TerminalLayout crafting = TerminalLayout.of(TerminalSize.FILL, 210, true);
        assertTrue(crafting.height() <= 210);
        assertEquals(1, crafting.itemRows(), "the crafting panel keeps its rows; the item grid gives them up");
        for (boolean panel : new boolean[]{false, true}) {
            assertEquals(TerminalLayout.MIN_ITEM_ROWS, TerminalLayout.of(TerminalSize.TALL, 40, panel).itemRows(),
                    "never fewer than one row of items");
        }
    }

    /**
     * The view filter strip sits level with the panel's bottom, below the saved search tabs (the label tab sits at the
     * right edge), in every size.
     */
    @Test
    void filterStripSitsBesideThePanelsBottom() {
        for (int extra : new int[]{0, 3, 20}) {
            TerminalLayout layout = new TerminalLayout(extra, false);
            assertEquals(layout.height(), layout.filterStripY() + TerminalLayout.FILTER_STRIP_HEIGHT);
            assertTrue(layout.filterStripY() >= 8 + 16 * 9, "below the saved search tabs");
            for (int slot = 0; slot < TerminalLayout.FILTER_SLOTS; slot++) {
                assertTrue(layout.filterX(slot) + SLOT < 0, "outside the panel, on the left");
                assertTrue(layout.filterY(slot) + SLOT < layout.height());
            }
        }
    }

    @Test
    void requestButtonHasRoomForItsLabel() {
        TerminalLayout layout = new TerminalLayout(0, true);
        assertTrue(layout.requestWidth() >= 80, "room for \"Request missing\"");
    }

    /** The pattern panel's slots and buttons stay inside the panel's three rows and never overlap (B09 item 30). */
    @Test
    void patternPanelFitsBetweenItemGridAndStatusLine() {
        for (int extra : new int[]{0, 3}) {
            TerminalLayout layout = new TerminalLayout(extra, true);
            int top = layout.panelY();
            int[][] boxes = patternBoxes(layout);
            for (int i = 0; i < boxes.length; i++) {
                int[] box = boxes[i];
                assertTrue(box[1] >= top, "below the item grid: " + i);
                assertTrue(box[1] + box[3] <= layout.statusY() - 2, "above the status line: " + i);
                assertTrue(box[0] >= TerminalLayout.GRID_X - 1, "inside the left edge: " + i);
                assertTrue(box[0] + box[2] <= TerminalLayout.WIDTH - 7, "inside the right edge: " + i);
                for (int j = i + 1; j < boxes.length; j++) {
                    assertTrue(!overlap(box, boxes[j]), "boxes " + i + " and " + j + " overlap");
                }
            }
        }
    }

    /**
     * The pattern panel's twelve slot frames, four mode tabs, two toggles, clear, encode, x2, /2 and the three advanced
     * buttons as {x, y, width, height}.
     */
    private static int[][] patternBoxes(TerminalLayout layout) {
        List<int[]> boxes = new ArrayList<>();
        for (int slot = 0; slot < 12; slot++) {
            boxes.add(new int[]{layout.patternX(slot) - 1, layout.patternY(slot) - 1, SLOT + 2, SLOT + 2});
        }
        for (int tab = 0; tab < 4; tab++) {
            boxes.add(new int[]{layout.modeX(tab), layout.modeY(tab), TerminalLayout.BUTTON, TerminalLayout.BUTTON});
        }
        for (int toggle = 0; toggle < 2; toggle++) {
            boxes.add(
                    new int[]{layout.toggleX(toggle), layout.toggleY(), TerminalLayout.BUTTON, TerminalLayout.BUTTON});
        }
        boxes.add(new int[]{layout.patternClearX(), layout.patternClearY(), TerminalLayout.BUTTON,
                TerminalLayout.BUTTON});
        boxes.add(new int[]{TerminalLayout.ENCODE_X, layout.encodeY(), TerminalLayout.ENCODE_WIDTH,
                TerminalLayout.BUTTON});
        boxes.add(new int[]{layout.scaleX(true), layout.scaleY(), TerminalLayout.SCALE_WIDTH,
                TerminalLayout.SCALE_HEIGHT});
        boxes.add(new int[]{layout.scaleX(false), layout.scaleY(), TerminalLayout.SCALE_WIDTH,
                TerminalLayout.SCALE_HEIGHT});
        for (int row = 0; row < 3; row++) {
            boxes.add(
                    new int[]{TerminalLayout.EDIT_X, layout.editY(row), TerminalLayout.BUTTON, TerminalLayout.BUTTON});
        }
        return boxes.toArray(new int[0][]);
    }

    /** Every slot frame (one pixel round the item) and button of the panel as {x, y, width, height}. */
    private static int[][] boxes(TerminalLayout layout) {
        int[][] boxes = new int[13][];
        for (int cell = 0; cell < 9; cell++) {
            boxes[cell] = new int[]{layout.cellX(cell) - 1, layout.cellY(cell) - 1, SLOT + 2, SLOT + 2};
        }
        boxes[9] = new int[]{layout.resultX() - 1, layout.resultY() - 1, SLOT + 2, SLOT + 2};
        boxes[10] = new int[]{layout.clearX(), layout.clearY(), TerminalLayout.BUTTON, TerminalLayout.BUTTON};
        boxes[11] = new int[]{layout.arrowX(), layout.arrowY(), TerminalLayout.ARROW_WIDTH,
                TerminalLayout.ARROW_HEIGHT};
        boxes[12] = new int[]{layout.requestX(), layout.requestY(), layout.requestWidth(), TerminalLayout.BUTTON};
        return boxes;
    }

    private static boolean overlap(int[] a, int[] b) {
        return a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    }
}
