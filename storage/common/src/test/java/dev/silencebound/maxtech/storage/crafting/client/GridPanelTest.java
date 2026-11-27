package dev.silencebound.maxtech.storage.crafting.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.silencebound.maxtech.lib.api.client.gui.LayoutCheck;
import dev.silencebound.maxtech.lib.api.client.gui.LayoutCheck.Box;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The request and jobs screens give up grid rows on a short window, so panel and title tab stay on the screen. */
class GridPanelTest {
    /** Window heights in GUI pixels: the smallest Minecraft lays out, 1366 x 768 at scale 3, 1080p at scale 4. */
    private static final int[] HEIGHTS = {240, 256, 270, 300, 480};

    @Test
    void aTallWindowKeepsAllFiveRows() {
        assertEquals(5, GridPanel.rows(480, CraftingJobsScreen.FIXED_HEIGHT));
        assertEquals(CraftingJobsScreen.HEIGHT, GridPanel.panelHeight(480, CraftingJobsScreen.FIXED_HEIGHT));
        assertEquals(CraftRequestScreen.HEIGHT, GridPanel.panelHeight(480, CraftRequestScreen.FIXED_HEIGHT));
    }

    @Test
    void aShortWindowTakesRowsAwayButNeverBelowThree() {
        assertEquals(4, GridPanel.rows(240, CraftingJobsScreen.FIXED_HEIGHT));
        assertEquals(4, GridPanel.rows(240, CraftRequestScreen.FIXED_HEIGHT));
        assertEquals(GridPanel.MIN_ROWS, GridPanel.rows(100, CraftingJobsScreen.FIXED_HEIGHT));
    }

    @Test
    void bothScreensFitWithTheirTitleTab() {
        for (int height : HEIGHTS) {
            fits(CraftingJobsScreen.WIDTH, GridPanel.panelHeight(height, CraftingJobsScreen.FIXED_HEIGHT), height);
            fits(CraftRequestScreen.WIDTH, GridPanel.panelHeight(height, CraftRequestScreen.FIXED_HEIGHT), height);
        }
    }

    private static void fits(int width, int panelHeight, int screenHeight) {
        int screenWidth = LayoutCheck.MIN_GUI_WIDTH;
        int left = (screenWidth - width) / 2;
        int top = LayoutCheck.place(screenHeight, panelHeight, AccentFrame.TAB_HEIGHT);
        List<Box> panels = List.of(new Box("panel", left, top, width, panelHeight),
                new Box("title tab", left + 6, top - AccentFrame.TAB_HEIGHT + 1, 60, AccentFrame.TAB_HEIGHT));
        assertEquals(List.of(), LayoutCheck.problems(screenWidth, screenHeight, panels, List.of()),
                "panel " + width + "x" + panelHeight + " on a window " + screenHeight + " high");
    }
}
