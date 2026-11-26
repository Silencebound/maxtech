package dev.silencebound.maxtech.storage.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** The list screen's layout, which the painter and the server's click lookup share, and the view ray's hit. */
class ScreenRowsTest {
    @Test
    void largerWallsShowMoreRows() {
        int single = ScreenRows.fit(1, 1, 2F / 16F);
        int part = ScreenRows.fit(0.75F, 0.75F, 2F / 16F * 0.75F);
        int square = ScreenRows.fit(2, 2, 2F / 16F);
        int tall = ScreenRows.fit(3, 3, 2F / 16F);
        assertTrue(part >= 3, "a part shows " + part);
        assertTrue(single >= 4, "a panel shows " + single);
        assertTrue(square > single && tall > square, single + ", " + square + ", " + tall);
    }

    @Test
    void rowAtFindsEachShownRowAndNothingElse() {
        float bezel = 2F / 16F;
        int fit = ScreenRows.fit(2, 2, bezel);
        float step = ScreenRows.step(ScreenRows.label(2, 2));
        for (int i = 0; i < fit; i++) {
            float middle = ScreenRows.rowTop(2, 2, bezel, i) - step / 2;
            assertEquals(i, ScreenRows.rowAt(2, 2, bezel, middle), "row " + i);
        }
        // The title, below the last row and the frame are no row.
        assertEquals(-1, ScreenRows.rowAt(2, 2, bezel, ScreenRows.titleBottom(2, 2, bezel)));
        assertEquals(-1, ScreenRows.rowAt(2, 2, bezel, ScreenRows.rowTop(2, 2, bezel, fit) - step / 2));
        assertEquals(-1, ScreenRows.rowAt(2, 2, bezel, 0.01F));
    }

    @Test
    void viewRayHitsTheGlassFromTheFront() {
        // A 2 x 1 wall facing north anchored at the origin: its glass spans x 1 to -1 (right is west), y 0 to 1.
        ScreenGeometry wall = ScreenGeometry.wall(BlockPos.ZERO, Direction.NORTH, 2, 1);
        float[] hit = wall.hit(new Vec3(0.75, 0.25, -3), new Vec3(0, 0, 1));
        assertNotNull(hit);
        assertEquals(0.25F, hit[0], 1.0E-4F, "x from the left as the viewer sees it");
        assertEquals(0.25F, hit[1], 1.0E-4F);
        // From behind, beside the glass, and too far away.
        assertNull(wall.hit(new Vec3(0.5, 0.5, 3), new Vec3(0, 0, -1)));
        assertNull(wall.hit(new Vec3(-2, 0.5, -3), new Vec3(0, 0, 1)));
        assertNull(wall.hit(new Vec3(0.5, 0.5, -30), new Vec3(0, 0, 1)));
    }

    @Test
    void perMilleIsBounded() {
        assertEquals(500, ScreenRows.perMille(5, 10));
        assertEquals(1000, ScreenRows.perMille(50, 10));
        assertEquals(0, ScreenRows.perMille(-5, 10));
        assertEquals(ScreenRows.NO_FILL, ScreenRows.perMille(5, 0));
    }
}
