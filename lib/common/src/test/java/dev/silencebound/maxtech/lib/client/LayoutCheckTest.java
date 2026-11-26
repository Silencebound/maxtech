package dev.silencebound.maxtech.lib.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.client.gui.LayoutCheck;
import dev.silencebound.maxtech.lib.client.gui.LayoutCheck.Box;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The layout check finds pieces off the screen and overlapping widgets, and the shared placement math. */
class LayoutCheckTest {
    @Test
    void aLayoutInsideTheScreenWithoutOverlapsPasses() {
        List<Box> widgets = List.of(new Box("back", 8, 200, 50, 16), new Box("next", 60, 200, 50, 16));
        assertEquals(List.of(), LayoutCheck.problems(320, 240, List.of(new Box("panel", 0, 0, 320, 240)), widgets));
    }

    @Test
    void widgetsThatShareAPixelOverlapButTouchingOnesDoNot() {
        Box a = new Box("a", 0, 0, 10, 10);
        assertTrue(a.overlaps(new Box("b", 9, 9, 10, 10)));
        assertFalse(a.overlaps(new Box("c", 10, 0, 10, 10)), "touching at the right edge");
        assertFalse(a.overlaps(new Box("d", 0, 10, 10, 10)), "touching at the bottom edge");
        assertFalse(a.overlaps(new Box("e", 2, 2, 0, 5)), "an empty box covers nothing");
        List<String> problems = LayoutCheck.problems(320, 240, List.of(),
                List.of(new Box("tab", 0, 0, 30, 14), new Box("search", 20, 4, 100, 12)));
        assertEquals(List.of("tab overlaps search"), problems);
    }

    @Test
    void piecesOffTheScreenAreReported() {
        List<String> problems = LayoutCheck.problems(320, 240, List.of(new Box("panel", 72, -10, 176, 260)),
                List.of(new Box("button", 300, 10, 30, 16)));
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).startsWith("panel "), problems.get(0));
        assertTrue(problems.get(1).startsWith("button "), problems.get(1));
    }

    @Test
    void panelsMayHoldWidgets() {
        assertEquals(List.of(), LayoutCheck.problems(320, 240, List.of(new Box("panel", 72, 37, 176, 166)),
                List.of(new Box("button", 80, 50, 40, 16))));
    }

    @Test
    void placementCentresAndKeepsTheTitleTabVisible() {
        // Room for the panel and its 12 px tab on both sides: centred.
        assertEquals(37, LayoutCheck.place(240, 166, 12));
        // Room for the tab but not twice over: the tab just fits and the bottom stays on the screen.
        assertEquals(12, LayoutCheck.place(240, 222, 12));
        assertEquals(12, LayoutCheck.place(240, 228, 12));
        // Less room than the tab: the panel stays whole, the tab gets what is left.
        assertEquals(2, LayoutCheck.place(240, 238, 12));
        assertEquals(0, LayoutCheck.place(240, 240, 12));
        // Longer than the screen: centred like vanilla, both ends cut.
        assertEquals(-10, LayoutCheck.place(240, 260, 12));
        assertEquals(72, LayoutCheck.place(320, 176, 0));
    }

    @Test
    void rowsFitTheRoomWithinBounds() {
        assertEquals(4, LayoutCheck.rows(125, 30, 3, 5));
        assertEquals(5, LayoutCheck.rows(400, 30, 3, 5));
        assertEquals(3, LayoutCheck.rows(10, 30, 3, 5));
    }
}
