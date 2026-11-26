package dev.silencebound.maxtech.storage.logic.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DiagramFitTest {
    private static final double MIN = 0.5;
    private static final double MAX = 1.0;

    @Test
    void smallGraphShowsAtFullSizeNotMagnified() {
        assertEquals(1.0, DiagramFit.scale(100, 40, 220, 90, MIN, MAX, 3));
    }

    @Test
    void largerGraphShrinksToWholeWindowPixels() {
        // Fits at 0.55; at GUI scale 3 the next whole-pixel step down is 1/3, but the minimum rounds up to 2/3.
        assertEquals(2.0 / 3, DiagramFit.scale(400, 50, 220, 90, MIN, MAX, 3), 1e-9);
        // At GUI scale 4: 0.55 snaps down to 0.5.
        assertEquals(0.5, DiagramFit.scale(400, 50, 220, 90, MIN, MAX, 4), 1e-9);
        // Fits at 0.8: GUI scale 4 gives 0.75, GUI scale 2 gives 0.5.
        assertEquals(0.75, DiagramFit.scale(250, 50, 200, 90, MIN, MAX, 4), 1e-9);
        assertEquals(0.5, DiagramFit.scale(250, 50, 200, 90, MIN, MAX, 2), 1e-9);
    }

    @Test
    void hugeGraphStopsAtTheMinimumAndPans() {
        double scale = DiagramFit.scale(2000, 600, 220, 90, MIN, MAX, 2);
        assertEquals(0.5, scale, 1e-9);
        assertTrue(DiagramFit.pans(2000, 220, scale));
        assertTrue(DiagramFit.pans(600, 90, scale));
        assertEquals(780, DiagramFit.maxPan(2000, 220, scale), 1e-9);
        // GUI scale 1 has no whole-pixel step below 1: the minimum is 1.
        assertEquals(1.0, DiagramFit.scale(2000, 600, 220, 90, MIN, MAX, 1), 1e-9);
    }

    @Test
    void heightCanBeTheLimit() {
        assertEquals(0.5, DiagramFit.scale(100, 180, 220, 90, MIN, MAX, 2), 1e-9);
    }

    @Test
    void emptyGraphUsesTheLargestScale() {
        assertEquals(1.0, DiagramFit.scale(0, 0, 220, 90, MIN, MAX, 3));
    }

    @Test
    void fittingContentIsCentredAndIgnoresPan() {
        // Content from x 10, 100 long at scale 1 in a view at 50, 200 wide: 50 px each side, so canvas 0 is at 90.
        assertEquals(90, DiagramFit.origin(50, 200, 10, 100, 1.0, 0), 1e-9);
        assertEquals(90, DiagramFit.origin(50, 200, 10, 100, 1.0, 37), 1e-9);
        assertFalse(DiagramFit.pans(100, 200, 1.0));
        assertEquals(0, DiagramFit.maxPan(100, 200, 1.0), 1e-9);
    }

    @Test
    void longContentStartsAtTheViewAndPansWithinBounds() {
        // Content from x -20, 600 long at scale 0.5 = 300 px in a 200 px view: pan 0 to 100.
        assertEquals(60, DiagramFit.origin(50, 200, -20, 600, 0.5, 0), 1e-9);
        assertEquals(10, DiagramFit.origin(50, 200, -20, 600, 0.5, 50), 1e-9);
        assertEquals(-40, DiagramFit.origin(50, 200, -20, 600, 0.5, 500), 1e-9);
        assertEquals(60, DiagramFit.origin(50, 200, -20, 600, 0.5, -10), 1e-9);
        assertEquals(100, DiagramFit.clampPan(500, 600, 200, 0.5), 1e-9);
        assertEquals(0, DiagramFit.clampPan(-5, 600, 200, 0.5), 1e-9);
    }

    @Test
    void screenPointsMapBackToCanvasUnits() {
        double origin = DiagramFit.origin(50, 200, -20, 600, 0.5, 50);
        // Canvas x -20 (the content's start) is 50 px before the view after panning 50.
        assertEquals(-20, DiagramFit.toCanvas(0, origin, 0.5), 1e-9);
        // The view's left edge shows the content's start plus the 50 px pan, 100 units at scale 0.5.
        assertEquals(80, DiagramFit.toCanvas(50, origin, 0.5), 1e-9);
    }
}
