package dev.silencebound.maxtech.storage.part;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PartBoxesTest {
    private static final AABB PANEL = new AABB(2, 2, 0, 14, 14, 2);
    private static final double EPSILON = 1.0E-9;

    /** Every turned panel lies against its own face, 2 px deep, and keeps 12 px across. */
    @Test
    void turnedPanelLiesOnItsFace() {
        for (Direction face : Direction.values()) {
            AABB box = PartBoxes.turn(PANEL, face);
            Direction.Axis axis = face.getAxis();
            double outer = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 16 : 0;
            double edge = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? box.max(axis) : box.min(axis);
            assertEquals(outer, edge, EPSILON, "outer side of " + face);
            assertEquals(2, box.max(axis) - box.min(axis), EPSILON, "depth on " + face);
            for (Direction.Axis cross : Direction.Axis.values()) {
                if (cross != axis) {
                    assertEquals(2, box.min(cross), EPSILON, "across on " + face);
                    assertEquals(14, box.max(cross), EPSILON, "across on " + face);
                }
            }
        }
    }

    /** 12 x 12 panels on neighbouring faces never overlap, so two parts never z-fight at a corner. */
    @Test
    void panelsOnNeighbouringFacesDoNotOverlap() {
        for (Direction a : Direction.values()) {
            for (Direction b : Direction.values()) {
                if (a != b) {
                    assertFalse(PartBoxes.turn(PANEL, a).intersects(PartBoxes.turn(PANEL, b)), a + " and " + b);
                }
            }
        }
    }

    /** The arm behind a part runs from the part's back to the core and is absent when the part reaches the core. */
    @Test
    void armRunsFromThePartToTheCore() {
        AABB arm = PartBoxes.arm(2.0F / 16.0F, Direction.UP, 2);
        assertNotNull(arm);
        assertEquals(9, arm.minY, EPSILON);
        assertEquals(14, arm.maxY, EPSILON);
        assertEquals(2, arm.getXsize(), EPSILON);
        assertNull(PartBoxes.arm(2.0F / 16.0F, Direction.UP, 7));
    }

    @Test
    void containsIncludesTheSurface() {
        assertTrue(PartBoxes.contains(PANEL, new Vec3(2, 8, 0)));
        assertFalse(PartBoxes.contains(PANEL, new Vec3(8, 8, 2.5)));
    }
}
