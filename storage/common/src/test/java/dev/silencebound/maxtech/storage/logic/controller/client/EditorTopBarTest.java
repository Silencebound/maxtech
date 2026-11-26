package dev.silencebound.maxtech.storage.logic.controller.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The node editor's top bar (owner screenshot 2026-10-09: with History, Simulate and Revert added, the bar ran off the
 * left edge and hid the name box). Widths are the English labels at six units per character plus padding, as the screen
 * measures them.
 */
class EditorTopBarTest {
    private static final int GAP = 2;
    private static final int NAME_MIN = 100;
    private static final int NAME_MAX = 170;
    private static final int STATUS_MIN = 90;
    /** Undo, Redo, Save, Back. */
    private static final int[] PRIMARY = {width("Undo"), width("Redo"), width("Save"), width("Back")};
    /** History/Settings, Simulate/Act for real, Revert/Sure? Revert: each as wide as its longer label. */
    private static final int[] SECONDARY = {width("Settings"), width("Act for real"), width("Sure? Revert")};
    private static final int MORE = width("More ▾");
    private static final int EDIT_NOW = width("Edit now");

    private static int width(String label) {
        return Math.max(30, label.length() * 6 + 8);
    }

    private static EditorTopBar.Layout layout(int width) {
        return EditorTopBar.layout(width, GAP, NAME_MIN, NAME_MAX, STATUS_MIN, PRIMARY, SECONDARY, MORE, EDIT_NOW);
    }

    /** The parts on the bar as {x, width} pairs: name, status, the inline buttons or More, the primary buttons. */
    private static List<int[]> parts(EditorTopBar.Layout layout) {
        List<int[]> parts = new ArrayList<>();
        parts.add(new int[]{layout.nameX(), layout.nameWidth()});
        parts.add(new int[]{layout.statusX(), layout.statusWidth()});
        if (layout.menu()) {
            parts.add(new int[]{layout.moreX(), MORE});
        } else {
            for (int i = 0; i < SECONDARY.length; i++) {
                parts.add(new int[]{layout.secondaryX().get(i), SECONDARY[i]});
            }
        }
        for (int i = 0; i < PRIMARY.length; i++) {
            parts.add(new int[]{layout.primaryX().get(i), PRIMARY[i]});
        }
        return parts;
    }

    private static void assertFits(int width) {
        EditorTopBar.Layout layout = layout(width);
        List<int[]> parts = parts(layout);
        for (int i = 0; i < parts.size(); i++) {
            int[] part = parts.get(i);
            assertTrue(part[0] >= 0, "part " + i + " starts at " + part[0] + " in a bar " + width + " wide");
            assertTrue(part[0] + part[1] <= width, "part " + i + " ends past the edge of a bar " + width + " wide");
            for (int j = i + 1; j < parts.size(); j++) {
                int[] other = parts.get(j);
                boolean apart = part[0] + part[1] <= other[0] || other[0] + other[1] <= part[0];
                assertTrue(apart || part[1] == 0 || other[1] == 0,
                        "parts " + i + " and " + j + " overlap in a bar " + width + " wide");
            }
        }
        for (int i = 1; i < PRIMARY.length; i++) {
            assertTrue(layout.primaryX().get(i) > layout.primaryX().get(i - 1), "primaries keep their order");
        }
        if (layout.menu()) {
            assertTrue(layout.menuX() >= 0 && layout.menuX() + layout.menuWidth() <= width,
                    "the open menu stays in a bar " + width + " wide");
            for (int w : SECONDARY) {
                assertTrue(layout.menuWidth() >= w, "every menu row is wide enough for its label");
            }
        }
    }

    @Test
    void aSmallWindowMovesTheSecondaryButtonsIntoTheMenu() {
        // The smallest window at an automatic GUI scale is 320 wide; the screen's design width is 400.
        for (int width : new int[]{320, 400}) {
            EditorTopBar.Layout layout = layout(width);
            assertTrue(layout.menu(), "History, Simulate and Revert go into More at " + width);
            assertTrue(layout.nameWidth() >= EditorTopBar.NAME_FLOOR, "the name box keeps room at " + width);
            assertTrue(layout.statusWidth() > 0, "the status keeps room at " + width);
            assertTrue(layout.moreX() < layout.primaryX().get(0), "More sits left of the primary buttons");
            assertFits(width);
        }
    }

    @Test
    void aWideWindowKeepsEveryButtonOnTheBar() {
        EditorTopBar.Layout layout = layout(900);
        assertFalse(layout.menu(), "everything fits on a wide bar");
        assertEquals(NAME_MAX, layout.nameWidth(), "the name box grows to its widest");
        assertEquals(900 - GAP, layout.primaryX().get(3) + PRIMARY[3], "Back ends one gap from the right edge");
        assertTrue(layout.secondaryX().get(2) + SECONDARY[2] <= layout.primaryX().get(0),
                "the secondary buttons sit left of the primary ones");
        assertFits(900);
    }

    @Test
    void everyBarWidthFits() {
        for (int width = 300; width <= 1400; width++) {
            assertFits(width);
        }
    }

    @Test
    void theEditNowButtonSharesTheStatusRoom() {
        for (int width : new int[]{320, 400, 900}) {
            EditorTopBar.Layout layout = layout(width);
            assertTrue(layout.editNowX() >= layout.statusX(), "edit now never covers the name box at " + width);
            assertTrue(
                    layout.editNowX() + EDIT_NOW + GAP <= (layout.menu() ? layout.moreX() : layout.secondaryX().get(0)),
                    "edit now ends before the buttons at " + width);
            assertTrue(layout.statusX() + layout.statusRoomBesideEditNow(GAP) <= layout.editNowX(),
                    "the status stops before edit now at " + width);
        }
    }

    @Test
    void aBarTooNarrowForTheButtonsStillStartsAtZeroOrLater() {
        for (int width = 0; width < 300; width++) {
            EditorTopBar.Layout layout = layout(width);
            assertTrue(layout.nameX() >= 0 && layout.statusX() >= 0 && layout.moreX() >= 0, "at " + width);
            for (int i = 0; i < PRIMARY.length; i++) {
                assertTrue(layout.primaryX().get(i) >= 0, "primary " + i + " at " + width);
            }
            assertTrue(layout.menuX() >= 0, "the menu at " + width);
        }
    }
}
