package dev.silencebound.maxtech.lib.client.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/** The preview's face picking and starting angles. */
class FacePickTest {
    private static final float SCALE = 20.0F;

    @Test
    void centreHitsTheFaceTurnedToTheViewer() {
        assertEquals(Direction.SOUTH, FacePick.pick(0, 0, SCALE, 0, 0));
        assertEquals(Direction.EAST, FacePick.pick(-90, 0, SCALE, 0, 0));
        assertEquals(Direction.WEST, FacePick.pick(90, 0, SCALE, 0, 0));
        assertEquals(Direction.NORTH, FacePick.pick(180, 0, SCALE, 0, 0));
        assertEquals(Direction.UP, FacePick.pick(0, 80, SCALE, 0, 0));
        assertEquals(Direction.DOWN, FacePick.pick(0, -80, SCALE, 0, 0));
    }

    @Test
    void tiltedBlockShowsTopAboveFrontAndSideBesideIt() {
        // Pitched 30 degrees: the top shows above the front, so a point near the top edge hits it.
        assertEquals(Direction.UP, FacePick.pick(0, 30, SCALE, 0, -0.6 * SCALE));
        assertEquals(Direction.SOUTH, FacePick.pick(0, 30, SCALE, 0, 0.2 * SCALE));
        // Turned 30 degrees towards its east side: the right half of the view shows east.
        assertEquals(Direction.EAST, FacePick.pick(-30, 0, SCALE, 0.55 * SCALE, 0));
        assertEquals(Direction.SOUTH, FacePick.pick(-30, 0, SCALE, -0.2 * SCALE, 0));
    }

    @Test
    void pointsBesideTheBlockMiss() {
        assertNull(FacePick.pick(0, 0, SCALE, SCALE, 0));
        assertNull(FacePick.pick(0, 30, SCALE, 0, 2 * SCALE));
    }

    /**
     * A block facing up or down starts with north at the top of the view and the side named left on the left: west
     * facing up, east facing down (seen from below).
     */
    @Test
    void verticalStartAnglesKeepNorthUpAndLeftOnTheLeft() {
        assertTopAndLeft(Direction.UP, Direction.WEST);
        assertTopAndLeft(Direction.DOWN, Direction.EAST);
    }

    private static void assertTopAndLeft(Direction facing, Direction left) {
        float yaw = FacePick.startYaw(facing);
        float pitch = FacePick.startPitch(facing);
        double[] north = FacePick.screen(yaw, pitch, Direction.NORTH);
        double[] side = FacePick.screen(yaw, pitch, left);
        assertTrue(north[1] < -0.3 && Math.abs(north[1]) > Math.abs(north[0]),
                "north points up facing " + facing + ": " + north[0] + ", " + north[1]);
        assertTrue(side[0] < -0.5, left + " points left facing " + facing + ": " + side[0]);
    }

    @Test
    void startAnglesShowTheFront() {
        for (Direction facing : Direction.values()) {
            float yaw = FacePick.startYaw(facing);
            float pitch = FacePick.startPitch(facing);
            assertEquals(facing, FacePick.pick(yaw, pitch, SCALE, 0, 0), "front of a block facing " + facing);
        }
    }
}
