package dev.silencebound.maxtech.lib.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.client.gui.PanView;
import dev.silencebound.maxtech.lib.client.gui.PixelScale;
import dev.silencebound.maxtech.lib.client.gui.RightAngleWire;
import dev.silencebound.maxtech.lib.client.gui.SidePanel;
import dev.silencebound.maxtech.lib.client.gui.UndoStack;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The editor toolkit's pure parts: scale snapping, panning, wire routing and hit tests, panel scrolling, undo. */
class EditorToolkitTest {
    @Test
    void scaleSnapsToWholePixelsAndKeepsTheMinimum() {
        assertEquals(4, PixelScale.pixelsPerUnit(1920, 1080, 400, 240, 2), "1080p: 4 pixels per unit");
        assertEquals(3, PixelScale.pixelsPerUnit(1280, 720, 400, 240, 2), "720p: 3, rounded down to fit");
        assertEquals(2, PixelScale.pixelsPerUnit(640, 400, 400, 240, 2), "a small window keeps the readable minimum");
        assertEquals(9, PixelScale.pixelsPerUnit(3840, 2160, 400, 240, 2), "4k");

        PixelScale scale = PixelScale.of(1920, 1080, 3, 400, 240, 2);
        assertEquals(4, scale.pixels());
        assertEquals(480, scale.width(), "the layout fills the window");
        assertEquals(270, scale.height());
        assertEquals(4f / 3f, scale.poseScale(), 1e-6, "independent of the GUI scale");
        assertEquals(30.0, scale.toUnits(40.0), 1e-9, "40 GUI pixels are 120 window pixels, 30 units");
        assertEquals(40.0, scale.toGui(30.0), 1e-9);
    }

    @Test
    void panKeepsContentInViewAndSumsSmallDrags() {
        PanView view = new PanView();
        view.setViewport(100, 20, 200, 100);
        view.setContent(0, 0, 400, 300, 20);
        assertEquals(0, view.scrollX());
        view.drag(-0.4, 0);
        view.drag(-0.4, 0);
        assertEquals(0, view.scrollX(), "less than a unit so far");
        view.drag(-0.4, 0);
        assertEquals(1, view.scrollX(), "three small drags add up to one unit");
        view.drag(-10_000, -10_000);
        assertEquals(380, view.scrollX(), "the content's right edge stays 20 units in view");
        assertEquals(280, view.scrollY());
        view.drag(10_000, 10_000);
        assertEquals(-180, view.scrollX(), "its left edge stays 20 units in view");
        assertEquals(-80, view.scrollY());

        view.centreOn(200, 150);
        assertEquals(100, view.scrollX());
        assertEquals(150, view.screenX(150), "canvas 150 is 50 right of the viewport's left edge");
        assertEquals(150.0, view.canvasX(view.screenX(150)), 1e-9);
        assertTrue(view.contains(299, 119));
        assertFalse(view.contains(300, 50));
    }

    @Test
    void wiresRouteForwardAndAroundBackwards() {
        int[] points = new int[RightAngleWire.MAX_POINTS * 2];
        int count = RightAngleWire.route(10, 20, 50, 40, 4, points);
        assertEquals(4, count);
        assertArrayEquals(new int[]{10, 20, 30, 20, 30, 40, 50, 40}, Arrays.copyOf(points, 8),
                "across, down at the middle, across");

        count = RightAngleWire.route(50, 20, 10, 40, 4, points);
        assertEquals(6, count, "a wire back to the left goes around");
        assertArrayEquals(new int[]{50, 20, 54, 20, 54, 30, 6, 30, 6, 40, 10, 40}, Arrays.copyOf(points, 12));

        count = RightAngleWire.route(10, 20, 15, 20, 4, points);
        assertEquals(6, count, "too close to turn at the middle");
    }

    @Test
    void wireHitTestFollowsTheSegments() {
        int[] points = new int[RightAngleWire.MAX_POINTS * 2];
        int count = RightAngleWire.route(10, 20, 50, 40, 4, points);
        assertTrue(RightAngleWire.near(points, count, 20, 21, 1.5), "on the first run");
        assertTrue(RightAngleWire.near(points, count, 31, 30, 1.5), "on the vertical");
        assertFalse(RightAngleWire.near(points, count, 20, 30, 1.5), "inside the corner, off the wire");
        assertFalse(RightAngleWire.near(points, count, 60, 40, 1.5), "past the end");

        int[] label = new int[2];
        count = RightAngleWire.route(10, 20, 50, 60, 4, points);
        RightAngleWire.labelPoint(points, count, label);
        assertArrayEquals(new int[]{30, 40}, label, "the middle of the longest segment, the vertical");
    }

    @Test
    void panelScrollsOnlyAsFarAsItsContent() {
        SidePanel panel = new SidePanel();
        panel.setBounds(0, 0, 80, 100);
        panel.setContentHeight(50);
        assertEquals(0, panel.maxScroll(), "fits");
        assertFalse(panel.mouseScrolled(10, 10, -1, 10), "nothing to scroll");
        panel.setContentHeight(200);
        assertEquals(200 - panel.bodyHeight(), panel.maxScroll());
        assertTrue(panel.mouseScrolled(10, 10, -1, 10));
        assertEquals(10, panel.scroll(), "wheel down scrolls down");
        assertEquals(SidePanel.TITLE_HEIGHT + 20 - 10, panel.screenY(20));
        assertFalse(panel.shows(0, 10), "the first row scrolled out");
        assertTrue(panel.shows(0, 11));
        panel.scrollBy(10_000);
        assertEquals(panel.maxScroll(), panel.scroll());
        panel.setContentHeight(10);
        assertEquals(0, panel.scroll(), "clamped when the content shrinks");
    }

    @Test
    void undoAndRedoWalkTheSnapshots() {
        UndoStack<String> stack = new UndoStack<>(2);
        assertEquals(Optional.empty(), stack.undo("a"));
        stack.record("a");
        stack.record("b");
        stack.record("c");
        assertEquals(Optional.of("c"), stack.undo("d"), "undo returns the state before the last change");
        assertEquals(Optional.of("b"), stack.undo("c"));
        assertEquals(Optional.empty(), stack.undo("b"), "capacity 2 dropped the oldest");
        assertEquals(Optional.of("c"), stack.redo("b"));
        assertEquals(Optional.of("d"), stack.redo("c"));
        assertFalse(stack.canRedo());
        stack.undo("d");
        stack.record("e");
        assertFalse(stack.canRedo(), "a new change clears redo");
    }
}
