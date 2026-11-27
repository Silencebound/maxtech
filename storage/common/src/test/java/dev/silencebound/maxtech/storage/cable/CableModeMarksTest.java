package dev.silencebound.maxtech.storage.cable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceMode;
import dev.silencebound.maxtech.lib.api.conduit.ConduitShapes;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class CableModeMarksTest {
    private static final float TIER_1 = 2.0F / 16.0F;
    private static final double EPSILON = 1e-9;

    /** Each band is one pixel long, sits at its own block face and wraps the arm just outside it. */
    @Test
    void bandWrapsTheArmEndOnePixelLong() {
        for (Direction side : Direction.values()) {
            AABB band = CableModeMarks.band(TIER_1, side);
            AABB arm = ConduitShapes.arm(TIER_1, side);
            Direction.Axis axis = side.getAxis();
            double length = band.max(axis) - band.min(axis);
            assertEquals(CableModeMarks.DEPTH / 16, length, EPSILON, side + " length");
            double face = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
            double end = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? band.max(axis) : band.min(axis);
            assertEquals(face, end, EPSILON, side + " at its face");
            for (Direction.Axis across : Direction.Axis.values()) {
                if (across == axis) {
                    continue;
                }
                assertEquals(arm.min(across) / 16 - CableModeMarks.LIP / 16, band.min(across), EPSILON, side + " min");
                assertEquals(arm.max(across) / 16 + CableModeMarks.LIP / 16, band.max(across), EPSILON, side + " max");
            }
        }
    }

    /** Every connected mode is marked in its own ring colour; a disabled face keeps only its stub. */
    @Test
    void connectedModesAreMarkedInDistinctColours() {
        assertFalse(CableModeMarks.marked(ConduitFaceMode.DISABLED));
        assertTrue(CableModeMarks.marked(ConduitFaceMode.BOTH));
        assertTrue(CableModeMarks.marked(ConduitFaceMode.INSERT));
        assertTrue(CableModeMarks.marked(ConduitFaceMode.EXTRACT));
        assertNotEquals(CableModeMarks.argb(ConduitFaceMode.BOTH), CableModeMarks.argb(ConduitFaceMode.INSERT));
        assertNotEquals(CableModeMarks.argb(ConduitFaceMode.INSERT), CableModeMarks.argb(ConduitFaceMode.EXTRACT));
        assertNotEquals(CableModeMarks.argb(ConduitFaceMode.BOTH), CableModeMarks.argb(ConduitFaceMode.EXTRACT));
    }
}
