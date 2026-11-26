package dev.silencebound.maxtech.storage.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class WallLayoutTest {
    private static LongArrayList cells(int u0, int v0, int width, int height) {
        LongArrayList cells = new LongArrayList();
        for (int v = v0; v < v0 + height; v++) {
            for (int u = u0; u < u0 + width; u++) {
                cells.add(WallLayout.pack(u, v));
            }
        }
        return cells;
    }

    /** A full rectangle within the cap is one screen anchored at its bottom-left cell, negative coordinates too. */
    @Test
    void rectangleIsOneScreen() {
        List<WallLayout.Rect> rects = WallLayout.partition(cells(-5, -2, 4, 3), 8, 8);
        assertEquals(List.of(new WallLayout.Rect(-5, -2, 4, 3)), rects);
    }

    /** A 10 x 3 group with a cap of 8 splits into 8 x 3 and 2 x 3. */
    @Test
    void capSplitsWideWalls() {
        List<WallLayout.Rect> rects = WallLayout.partition(cells(0, 0, 10, 3), 8, 8);
        assertEquals(List.of(new WallLayout.Rect(0, 0, 8, 3), new WallLayout.Rect(8, 0, 2, 3)), rects);
    }

    /**
     * An L shape (a 3-wide bottom row and a column above its left end) becomes the row and the column; every cell is
     * covered exactly once.
     */
    @Test
    void lShapeIsTwoScreens() {
        LongArrayList cells = cells(0, 0, 3, 1);
        cells.addAll(cells(0, 1, 1, 2));
        List<WallLayout.Rect> rects = WallLayout.partition(cells, 8, 8);
        assertEquals(List.of(new WallLayout.Rect(0, 0, 3, 1), new WallLayout.Rect(0, 1, 1, 2)), rects);
        LongArrayList covered = WallLayout.cells(rects);
        assertEquals(cells.size(), covered.size());
        assertEquals(new LongOpenHashSet(cells), new LongOpenHashSet(covered));
    }

    /** A lone panel is framed all round. */
    @Test
    void frameOfOnePanel() {
        assertEquals(WallLayout.ALL, WallLayout.frame(0, 0, 1, 1));
    }

    /** In a 2 x 2 wall every panel is a corner: framed on its two outer edges, never between panels. */
    @Test
    void frameOfTwoByTwo() {
        assertEquals(WallLayout.LEFT | WallLayout.BOTTOM, WallLayout.frame(0, 0, 2, 2));
        assertEquals(WallLayout.RIGHT | WallLayout.BOTTOM, WallLayout.frame(1, 0, 2, 2));
        assertEquals(WallLayout.LEFT | WallLayout.TOP, WallLayout.frame(0, 1, 2, 2));
        assertEquals(WallLayout.RIGHT | WallLayout.TOP, WallLayout.frame(1, 1, 2, 2));
    }

    /** In a 3 x 2 wall the middle column is framed only on the top or bottom; a 3 x 3's centre not at all. */
    @Test
    void frameOfThreeByTwo() {
        int[][] expected = {
                {WallLayout.LEFT | WallLayout.BOTTOM, WallLayout.BOTTOM, WallLayout.RIGHT | WallLayout.BOTTOM},
                {WallLayout.LEFT | WallLayout.TOP, WallLayout.TOP, WallLayout.RIGHT | WallLayout.TOP}};
        for (int dv = 0; dv < 2; dv++) {
            for (int du = 0; du < 3; du++) {
                assertEquals(expected[dv][du], WallLayout.frame(du, dv, 3, 2), du + "," + dv);
            }
        }
        assertEquals(0, WallLayout.frame(1, 1, 3, 3));
        assertEquals(WallLayout.LEFT | WallLayout.RIGHT | WallLayout.BOTTOM, WallLayout.frame(0, 0, 1, 2));
    }

    /** Each screen's right and up are perpendicular to its facing and to each other, and up is world up on walls. */
    @Test
    void planeAxes() {
        for (Direction facing : Direction.values()) {
            Direction right = DisplayWalls.right(facing);
            Direction up = DisplayWalls.up(facing);
            assertTrue(right.getAxis() != facing.getAxis() && up.getAxis() != facing.getAxis()
                    && right.getAxis() != up.getAxis(), facing.toString());
            // Right x up points out of the screen, towards the viewer.
            BlockPos cross = new BlockPos(right.getUnitVec3i().cross(up.getUnitVec3i()));
            assertEquals(facing.getUnitVec3i(), cross, facing.toString());
            if (facing.getAxis() != Direction.Axis.Y) {
                assertEquals(Direction.UP, up);
            }
        }
    }
}
