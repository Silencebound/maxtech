package dev.silencebound.maxtech.storage.crafting.client;

import dev.silencebound.maxtech.lib.api.client.gui.LayoutCheck;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;

/**
 * How the request and jobs screens fit a short window: their tile grid gives up rows (five down to three) so the panel
 * and its title tab stay on the screen; the rest of the panel keeps its size. Pure arithmetic, so JUnit covers it.
 */
final class GridPanel {
    static final int MAX_ROWS = 5;
    static final int MIN_ROWS = 3;

    private GridPanel() {
    }

    /** Tile rows on a screen {@code screenHeight} tall for a panel whose parts besides the grid take {@code fixed}. */
    static int rows(int screenHeight, int fixed) {
        return LayoutCheck.rows(screenHeight - AccentFrame.TAB_HEIGHT - fixed - 2, TileGridWidget.TILE_HEIGHT, MIN_ROWS,
                MAX_ROWS);
    }

    /** The grid's height for {@code rows} tile rows, its frame included. */
    static int gridHeight(int rows) {
        return rows * TileGridWidget.TILE_HEIGHT + 2;
    }

    /** The panel's height on a screen {@code screenHeight} tall. */
    static int panelHeight(int screenHeight, int fixed) {
        return fixed + gridHeight(rows(screenHeight, fixed));
    }
}
