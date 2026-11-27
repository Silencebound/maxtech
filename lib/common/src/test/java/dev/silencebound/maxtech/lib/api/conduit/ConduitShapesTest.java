package dev.silencebound.maxtech.lib.api.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

class ConduitShapesTest {
    // Thin, odd, half-block and nearly full-block widths.
    private static final float[] THICKNESSES = {0.25F, 0.1875F, 0.5F, 0.875F};

    /** The core is centred and exactly {@code thickness} of a block wide on every axis. */
    @Test
    void coreIsCentredAtTheThickness() {
        for (float thickness : THICKNESSES) {
            AABB core = ConduitShapes.core(thickness);
            for (Direction.Axis axis : Direction.Axis.values()) {
                assertEquals(thickness * 16, core.max(axis) - core.min(axis), 1.0E-9, thickness + " " + axis);
                assertEquals(8.0, (core.min(axis) + core.max(axis)) / 2, 1.0E-9, thickness + " " + axis);
            }
        }
        assertEquals(new AABB(4, 4, 4, 12, 12, 12), ConduitShapes.core(0.5F));
    }

    /**
     * A conduit's arm towards a face and a same-sized neighbour's arm towards the shared face have the same
     * cross-section and both reach the face, so a run is straight; a thinner arm lies inside a thicker one's face.
     */
    @Test
    void armsMeetTheNeighboursArmAtTheSharedFace() {
        for (float thickness : THICKNESSES) {
            for (Direction side : Direction.values()) {
                AABB here = ConduitShapes.arm(thickness, side);
                AABB there = ConduitShapes.arm(thickness, side.getOpposite());
                Direction.Axis axis = side.getAxis();
                for (Direction.Axis across : Direction.Axis.values()) {
                    if (across != axis) {
                        assertEquals(here.min(across), there.min(across), 1.0E-9, thickness + " " + side);
                        assertEquals(here.max(across), there.max(across), 1.0E-9, thickness + " " + side);
                    }
                }
                boolean positive = side.getAxisDirection() == Direction.AxisDirection.POSITIVE;
                assertEquals(positive ? 16.0 : 0.0, positive ? here.max(axis) : here.min(axis));
                assertEquals(positive ? 0.0 : 16.0, positive ? there.min(axis) : there.max(axis));
                AABB thick = ConduitShapes.arm(0.875F, side);
                for (Direction.Axis across : Direction.Axis.values()) {
                    if (across != axis) {
                        assertTrue(here.min(across) >= thick.min(across) && here.max(across) <= thick.max(across),
                                "a thin arm meets a thick one inside its face: " + thickness + " " + side);
                    }
                }
            }
        }
    }

    @Test
    void shapeFollowsTheThickness() {
        AABB bare = ConduitShapes.shape(0.25F, 0, 0).bounds();
        assertEquals(new AABB(0.375, 0.375, 0.375, 0.625, 0.625, 0.625), bare);
        AABB wide = ConduitShapes.shape(0.75F, 0, 0).bounds();
        assertEquals(new AABB(0.125, 0.125, 0.125, 0.875, 0.875, 0.875), wide);
        AABB withArm = ConduitShapes.shape(0.25F, ConduitConnections.bit(Direction.EAST), 0).bounds();
        assertEquals(1.0, withArm.maxX, 1.0E-9, "the arm reaches the east face");
        assertEquals(0.375, withArm.minX, 1.0E-9, "no arm to the west");
        assertEquals(0.625, withArm.maxY, 1.0E-9, "the arm keeps the core's cross-section");
    }

    /**
     * Shapes are cached: the same masks give the same instance, a stub under an arm changes nothing, and different
     * thicknesses or masks never share an entry.
     */
    @Test
    void shapesAreCachedPerThicknessAndMasks() {
        int east = ConduitConnections.bit(Direction.EAST);
        int up = ConduitConnections.bit(Direction.UP);
        VoxelShape first = ConduitShapes.shape(0.25F, east, up, east);
        assertSame(first, ConduitShapes.shape(0.25F, east, up, east));
        assertSame(first, ConduitShapes.shape(0.25F, east, up | east, east), "a stub under an arm adds nothing");
        assertNotSame(first, ConduitShapes.shape(0.25F, east, 0, east));
        assertNotSame(first, ConduitShapes.shape(0.25F, east, up, 0));
        assertNotSame(first, ConduitShapes.shape(0.5F, east, up, east));
        assertEquals(0.625, ConduitShapes.shape(0.25F, up, 0, 0).bounds().maxX, 1.0E-9);
        assertEquals(1.0, ConduitShapes.shape(0.25F, up, 0, 0).bounds().maxY, 1.0E-9);
    }

    @Test
    void conduitsStayInsideTheBlock() {
        for (float thickness : THICKNESSES) {
            AABB bounds = ConduitShapes.shape(thickness, ConduitConnections.ALL_SIDES, 0).bounds();
            assertTrue(bounds.minX >= 0 && bounds.minY >= 0 && bounds.minZ >= 0, Float.toString(thickness));
            assertTrue(bounds.maxX <= 1 && bounds.maxY <= 1 && bounds.maxZ <= 1, Float.toString(thickness));
        }
    }

    /**
     * Stubs lie inside their arm and touch the core. A ring is a collar at the block face a pixel proud of the arm on
     * every side (capped at the block), and the ring's depth and the shortened arm make up the arm's length.
     */
    @Test
    void stubsLieInsideTheArmAndRingsStandProud() {
        for (float thickness : THICKNESSES) {
            for (Direction side : Direction.values()) {
                AABB arm = ConduitShapes.arm(thickness, side);
                AABB stub = ConduitShapes.stub(thickness, side);
                assertTrue(contains(arm, stub), "stub " + thickness + " " + side);
                assertTrue(stub.intersects(ConduitShapes.core(thickness).inflate(1.0E-6)), "stub touches core");
                AABB ring = ConduitShapes.ring(thickness, side);
                AABB rest = ConduitShapes.armBeforeRing(thickness, side);
                assertTrue(contains(ring.minmax(rest), arm), "ring and shortened arm cover the arm " + thickness);
                assertTrue(rest.getXsize() > 0 && rest.getYsize() > 0 && rest.getZsize() > 0, "arm volume");
                Direction.Axis axis = side.getAxis();
                assertEquals(arm.max(axis) - arm.min(axis),
                        ring.max(axis) - ring.min(axis) + rest.max(axis) - rest.min(axis), 1.0E-9,
                        "ring and shortened arm make up the arm's length " + thickness);
                for (Direction.Axis across : Direction.Axis.values()) {
                    if (across != axis) {
                        double width = ring.max(across) - ring.min(across);
                        assertEquals(Math.min(16.0, thickness * 16 + 2), width, 1.0E-9, "ring width " + thickness);
                        assertTrue(ring.min(across) >= 0 && ring.max(across) <= 16, "ring inside the block");
                    }
                }
            }
        }
    }

    /** What renders wider than the arm is also hit: rings are part of the shape. */
    @Test
    void ringsWidenTheHitShape() {
        int east = ConduitConnections.bit(Direction.EAST);
        AABB plain = ConduitShapes.shape(0.25F, east, 0).bounds();
        AABB ringed = ConduitShapes.shape(0.25F, east, 0, east).bounds();
        assertEquals(0.625, plain.maxY, 1.0E-9);
        assertEquals((8 + 3) / 16.0, ringed.maxY, 1.0E-9, "the ring stands a pixel proud of the 4 px arm");
        assertEquals(plain.getCenter().y, ringed.getCenter().y, 1.0E-9, "rings stay centred");
        Vec3 onRingRim = new Vec3(15.5, 10.5, 8.0);
        assertEquals(Direction.EAST, ConduitShapes.faceAt(0.25F, east, 0, east, onRingRim, Direction.UP),
                "the ring rim is the face");
        assertEquals(Direction.UP, ConduitShapes.faceAt(0.25F, east, 0, 0, onRingRim, Direction.UP),
                "without a ring that point is outside the conduit");
        Vec3 behindRing = new Vec3(14.5, 10.5, 8.0);
        assertEquals(Direction.UP, ConduitShapes.faceAt(0.25F, east, 0, east, behindRing, Direction.UP),
                "the ring is only a pixel deep");
    }

    @Test
    void stubsAreHitShapes() {
        AABB bounds = ConduitShapes.shape(0.5F, 0, ConduitConnections.bit(Direction.EAST)).bounds();
        assertTrue(bounds.maxX * 16 > ConduitShapes.core(0.5F).maxX + 1.0E-6, "stub sticks out of the core");
        assertTrue(bounds.maxX < 1.0, "stub stops short of the block face");
    }

    /** A point inside an arm picks that arm's side, whatever side of the arm was clicked. */
    @Test
    void faceAtArmPicksTheArmSide() {
        int arms = ConduitConnections.bit(Direction.EAST) | ConduitConnections.bit(Direction.UP);
        for (float thickness : THICKNESSES) {
            for (Direction side : new Direction[]{Direction.EAST, Direction.UP}) {
                Vec3 inside = ConduitShapes.arm(thickness, side).getCenter();
                assertEquals(side, ConduitShapes.faceAt(thickness, arms, 0, inside, Direction.NORTH),
                        thickness + " " + side);
            }
        }
    }

    /** A disabled face is reached through its stub. */
    @Test
    void faceAtStubPicksTheStubSide() {
        int stubs = ConduitConnections.bit(Direction.WEST);
        Vec3 inside = ConduitShapes.stub(0.5F, Direction.WEST).getCenter();
        assertEquals(Direction.WEST, ConduitShapes.faceAt(0.5F, 0, stubs, inside, Direction.UP));
        // The stub only covers the inner half of the arm; beyond it lies nothing of this conduit.
        Vec3 beyond = ConduitShapes.arm(0.5F, Direction.WEST).getCenter().with(Direction.Axis.X, 0.5);
        assertEquals(Direction.UP, ConduitShapes.faceAt(0.5F, 0, stubs, beyond, Direction.UP));
    }

    /**
     * A point in the core picks the clicked side, including just inside the core's face where an arm or stub starts:
     * the core-box hit that keeps a disabled face reachable.
     */
    @Test
    void faceAtCorePicksTheClickedSide() {
        int arms = ConduitConnections.bit(Direction.EAST);
        int stubs = ConduitConnections.bit(Direction.SOUTH);
        AABB core = ConduitShapes.core(0.375F);
        for (Direction clicked : Direction.values()) {
            assertEquals(clicked, ConduitShapes.faceAt(0.375F, arms, stubs, core.getCenter(), clicked));
        }
        Vec3 nearEastFace = core.getCenter().with(Direction.Axis.X, core.maxX - 1.0E-3);
        assertEquals(Direction.UP, ConduitShapes.faceAt(0.375F, arms, stubs, nearEastFace, Direction.UP));
        Vec3 onSouthFace = core.getCenter().with(Direction.Axis.Z, core.maxZ);
        assertEquals(Direction.SOUTH, ConduitShapes.faceAt(0.375F, arms, stubs, onSouthFace, Direction.SOUTH));
        // A face without an arm or stub is only reachable through the core.
        Vec3 wouldBeWestArm = ConduitShapes.arm(0.375F, Direction.WEST).getCenter();
        assertEquals(Direction.DOWN, ConduitShapes.faceAt(0.375F, arms, stubs, wouldBeWestArm, Direction.DOWN));
    }

    private static boolean contains(AABB outer, AABB inner) {
        double e = 1.0E-9;
        return inner.minX >= outer.minX - e && inner.minY >= outer.minY - e && inner.minZ >= outer.minZ - e
                && inner.maxX <= outer.maxX + e && inner.maxY <= outer.maxY + e && inner.maxZ <= outer.maxZ + e;
    }
}
