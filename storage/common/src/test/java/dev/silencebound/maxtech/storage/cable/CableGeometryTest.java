package dev.silencebound.maxtech.storage.cable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class CableGeometryTest {
    private static final float THICKNESS = CableContent.THICKNESS;
    private static final double EPSILON = 1.0E-6;

    private static int mask(Direction... sides) {
        int mask = 0;
        for (Direction side : sides) {
            mask |= ConduitConnections.bit(side);
        }
        return mask;
    }

    private static List<CableGeometry.Face> of(List<CableGeometry.Face> faces, CableGeometry.Texture texture) {
        return faces.stream().filter(f -> f.texture() == texture).toList();
    }

    /** The plane coordinate of a face: the box's side it lies on. */
    private static double plane(CableGeometry.Face face) {
        Direction.Axis axis = face.facing().getAxis();
        return face.facing().getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? face.box().max(axis)
                : face.box().min(axis);
    }

    /** Whether two faces lie in one plane and overlap there with a positive area: they would z-fight. */
    private static boolean coplanarOverlap(CableGeometry.Face a, CableGeometry.Face b) {
        Direction.Axis axis = a.facing().getAxis();
        if (b.facing().getAxis() != axis || Math.abs(plane(a) - plane(b)) > EPSILON) {
            return false;
        }
        for (Direction.Axis other : Direction.Axis.values()) {
            if (other == axis) {
                continue;
            }
            double min = Math.max(a.box().min(other), b.box().min(other));
            double max = Math.min(a.box().max(other), b.box().max(other));
            if (max - min <= EPSILON) {
                return false;
            }
        }
        return true;
    }

    private static void assertNoCoplanarFaces(List<CableGeometry.Face> faces, String what) {
        for (int i = 0; i < faces.size(); i++) {
            for (int j = i + 1; j < faces.size(); j++) {
                if (coplanarOverlap(faces.get(i), faces.get(j))) {
                    fail(what + ": " + faces.get(i) + " lies on " + faces.get(j));
                }
            }
        }
    }

    /**
     * No face of a body or a part's arm lies on another, for every arm mask, glass and covered, with and without a part
     * on any free side: the old look drew a lanes quad on every casing quad, which flickered. The tier pips on the
     * lane's core are texels of its faces, not faces of their own, so they cannot lie on anything either.
     */
    @Test
    void noFaceLiesOnAnother() {
        for (int arms = 0; arms < 64; arms++) {
            for (boolean glass : new boolean[]{true, false}) {
                assertNoCoplanarFaces(CableGeometry.body(THICKNESS, arms, 0, glass), "arms " + arms);
                for (Direction side : Direction.values()) {
                    int part = mask(side);
                    if ((arms & part) != 0) {
                        continue;
                    }
                    List<CableGeometry.Face> faces = new ArrayList<>(CableGeometry.body(THICKNESS, arms, part, glass));
                    faces.addAll(CableGeometry.connector(THICKNESS, side, 2, glass));
                    assertNoCoplanarFaces(faces, "arms " + arms + " with a part " + side);
                }
            }
        }
    }

    /**
     * A glass body's lane core has a {@link CableGeometry.Texture#LANE_CORE} face on every side no arm leaves, each
     * showing exactly the four pip texels, unturned; a covered body has none (its sleeve carries the pips).
     */
    @Test
    void laneCoreFacesShowThePipTexels() {
        for (int arms = 0; arms < 64; arms++) {
            List<CableGeometry.Face> cores = of(CableGeometry.body(THICKNESS, arms, 0, true),
                    CableGeometry.Texture.LANE_CORE);
            assertEquals(6 - Integer.bitCount(arms), cores.size(), "lane core faces with arms " + arms);
            for (CableGeometry.Face face : cores) {
                assertFalse(face.rotated(), face.toString());
                assertEquals(CableGeometry.PIPS[0][0], face.u0(), EPSILON, face.toString());
                assertEquals(CableGeometry.PIPS[3][0] + 1, face.u1(), EPSILON, face.toString());
                assertEquals(CableGeometry.PIPS[0][1], face.v0(), EPSILON, face.toString());
                assertEquals(CableGeometry.PIPS[3][1] + 1, face.v1(), EPSILON, face.toString());
            }
        }
        assertTrue(of(CableGeometry.body(THICKNESS, 0, 0, false), CableGeometry.Texture.LANE_CORE).isEmpty(),
                "a lane core in a covered cable");
    }

    /** The lane is a centred box half the sleeve's thickness, inside the sleeve on every axis across its arms. */
    @Test
    void laneRunsInsideTheSleeve() {
        int arms = mask(Direction.EAST, Direction.WEST, Direction.UP);
        List<CableGeometry.Face> faces = CableGeometry.body(THICKNESS, arms, 0, true);
        List<CableGeometry.Face> lane = of(faces, CableGeometry.Texture.LANE);
        assertFalse(lane.isEmpty());
        for (CableGeometry.Face face : lane) {
            for (Direction.Axis axis : Direction.Axis.values()) {
                double size = face.box().max(axis) - face.box().min(axis);
                if (size < 3) {
                    assertEquals(7, face.box().min(axis), EPSILON, face + " across " + axis);
                    assertEquals(9, face.box().max(axis), EPSILON, face + " across " + axis);
                }
            }
        }
        for (CableGeometry.Face face : of(faces, CableGeometry.Texture.SLEEVE)) {
            for (Direction.Axis axis : Direction.Axis.values()) {
                if (face.box().max(axis) - face.box().min(axis) < 5) {
                    assertEquals(6, face.box().min(axis), EPSILON, face + " across " + axis);
                    assertEquals(10, face.box().max(axis), EPSILON, face + " across " + axis);
                }
            }
        }
    }

    /** A covered body has no lane; its arm ends are closed cells, culled against solid neighbours. */
    @Test
    void coveredSleeveIsClosedWithoutALane() {
        List<CableGeometry.Face> faces = CableGeometry.body(THICKNESS, mask(Direction.EAST), 0, false);
        assertTrue(of(faces, CableGeometry.Texture.LANE).isEmpty(), "a lane in a covered cable");
        CableGeometry.Face end = faces.stream().filter(f -> f.facing() == Direction.EAST && f.box().maxX == 16)
                .findFirst().orElseThrow();
        assertEquals(CableGeometry.Texture.SLEEVE_END, end.texture(), "an end shows no tier pips");
        assertTrue(end.culled());
        assertEquals(0, end.u0(), "cell 0, closed all round");
        assertEquals(0, end.v0());
        // Every other face off the arm sides is a core face, which carries the tier pips (look K1).
        List<CableGeometry.Face> cores = of(faces, CableGeometry.Texture.SLEEVE_CORE);
        assertEquals(5, cores.size(), "core faces but the one towards the arm");
        for (CableGeometry.Face core : cores) {
            assertEquals(4, core.box().getXsize(), EPSILON, "on the core: " + core);
            assertEquals(4, core.box().getZsize(), EPSILON, "on the core: " + core);
        }
    }

    /** A glass sleeve is open at the block face; the lane ends there, culled against a solid neighbour. */
    @Test
    void glassSleeveIsOpenAndTheLaneEndsAtTheBlockFace() {
        List<CableGeometry.Face> faces = CableGeometry.body(THICKNESS, mask(Direction.EAST), 0, true);
        List<CableGeometry.Face> ends = faces.stream()
                .filter(f -> f.facing() == Direction.EAST && Math.abs(f.box().maxX - 16) < EPSILON).toList();
        assertEquals(1, ends.size(), "one end at the block face: " + ends);
        assertEquals(CableGeometry.Texture.LANE, ends.get(0).texture());
        assertTrue(ends.get(0).culled());
    }

    /** Along an arm and across it the UVs are the face's own pixels, one texel per pixel; turned where u is across. */
    @Test
    void armTexturesRunAlongTheArmOneTexelPerPixel() {
        List<CableGeometry.Face> faces = CableGeometry.body(THICKNESS,
                mask(Direction.EAST, Direction.UP, Direction.SOUTH), 0, true);
        for (CableGeometry.Face face : faces) {
            Direction.Axis axis = longest(face);
            boolean laneCore = face.box().getXsize() < 2.5 && face.box().getYsize() < 2.5
                    && face.box().getZsize() < 2.5;
            // Core cells, the lane's core and its ends are not stripes.
            if (face.texture() == CableGeometry.Texture.SLEEVE_CORE || laneCore || face.facing().getAxis() == axis) {
                continue;
            }
            boolean uAlong = CableGeometry.uAxis(face.facing()) == axis;
            assertEquals(!uAlong, face.rotated(), face + " turned the wrong way");
            double width = face.texture() == CableGeometry.Texture.SLEEVE ? 4 : 2;
            assertEquals(width, Math.abs(face.v1() - face.v0()), EPSILON, face + " across");
            assertEquals(8, (face.v0() + face.v1()) / 2, EPSILON, face + " centred across");
        }
        CableGeometry.Face top = faces.stream().filter(
                f -> f.texture() == CableGeometry.Texture.SLEEVE && f.facing() == Direction.UP && f.box().minX > 9)
                .findFirst().orElseThrow();
        assertEquals(10, top.u0(), EPSILON);
        assertEquals(16, top.u1(), EPSILON);
    }

    /**
     * A sleeve core face's cell is open exactly towards the arms and parts in its plane: on the top of an east-west run
     * with a part north, open west, east and north, closed south.
     */
    @Test
    void coreCellsOpenTowardsArmsAndParts() {
        List<CableGeometry.Face> faces = CableGeometry.body(THICKNESS, mask(Direction.EAST, Direction.WEST),
                mask(Direction.NORTH), true);
        CableGeometry.Face top = faces.stream()
                .filter(f -> f.texture() == CableGeometry.Texture.SLEEVE_CORE && f.facing() == Direction.UP).findFirst()
                .orElseThrow();
        int open = CableGeometry.OPEN_U_MIN | CableGeometry.OPEN_U_MAX | CableGeometry.OPEN_V_MIN;
        int cell = CableGeometry.cell(open);
        assertEquals(cell % CableGeometry.CELL * CableGeometry.CELL, top.u0(), EPSILON);
        assertEquals(cell / CableGeometry.CELL * CableGeometry.CELL, top.v0(), EPSILON);
        assertEquals(CableGeometry.CELL, top.u1() - top.u0(), EPSILON);
        // No core face towards an arm or a part: the sleeve opens into them.
        assertEquals(3, of(faces, CableGeometry.Texture.SLEEVE_CORE).size(), "core faces up, down and south");
    }

    /** {@link CableGeometry#edge} agrees with where {@link CableGeometry#defaultUv} puts each half of a face. */
    @Test
    void cellEdgesMatchTheDefaultUvs() {
        AABB box = new AABB(6, 6, 6, 10, 10, 10);
        int[] bits = {CableGeometry.OPEN_U_MIN, CableGeometry.OPEN_U_MAX, CableGeometry.OPEN_V_MIN,
                CableGeometry.OPEN_V_MAX};
        for (Direction face : Direction.values()) {
            float[] whole = CableGeometry.defaultUv(box, face);
            for (int bit : bits) {
                Direction towards = CableGeometry.edge(face, bit);
                assertTrue(towards.getAxis() != face.getAxis(), face + " edge " + bit);
                float[] half = CableGeometry.defaultUv(half(box, towards), face);
                boolean ok = switch (bit) {
                    case CableGeometry.OPEN_U_MIN -> half[0] == whole[0] && half[2] < whole[2];
                    case CableGeometry.OPEN_U_MAX -> half[2] == whole[2] && half[0] > whole[0];
                    case CableGeometry.OPEN_V_MIN -> half[1] == whole[1] && half[3] < whole[3];
                    default -> half[3] == whole[3] && half[1] > whole[1];
                };
                assertTrue(ok, face + " edge " + bit + " is not " + towards);
            }
        }
    }

    /** The arm to a part has the sleeve's and the lane's four sides but no ends. */
    @Test
    void connectorHasSidesButNoEnds() {
        List<CableGeometry.Face> faces = CableGeometry.connector(THICKNESS, Direction.NORTH, 2, true);
        assertEquals(8, faces.size(), "four sleeve sides and four lane sides");
        for (CableGeometry.Face face : faces) {
            assertTrue(face.facing().getAxis() != Direction.Axis.Z, "an end face " + face);
            assertEquals(2, face.box().minZ, EPSILON, "starts at the part's back");
        }
        assertEquals(4, CableGeometry.connector(THICKNESS, Direction.NORTH, 2, false).size(), "covered: sleeve only");
        assertTrue(CableGeometry.connector(THICKNESS, Direction.NORTH, 7, true).isEmpty(), "a part reaching the core");
    }

    @Test
    void anIsolatedCableIsACoreWithALaneCore() {
        List<CableGeometry.Face> faces = CableGeometry.body(THICKNESS, 0, 0, true);
        assertEquals(12, faces.size());
        assertEquals(6, of(faces, CableGeometry.Texture.SLEEVE_CORE).size());
        assertEquals(6, of(faces, CableGeometry.Texture.LANE_CORE).size());
    }

    private static AABB half(AABB box, Direction side) {
        double centre = (box.min(side.getAxis()) + box.max(side.getAxis())) / 2;
        return switch (side) {
            case DOWN -> box.setMaxY(centre);
            case UP -> box.setMinY(centre);
            case NORTH -> box.setMaxZ(centre);
            case SOUTH -> box.setMinZ(centre);
            case WEST -> box.setMaxX(centre);
            case EAST -> box.setMinX(centre);
        };
    }

    private static Direction.Axis longest(CableGeometry.Face face) {
        double x = face.box().maxX - face.box().minX;
        double y = face.box().maxY - face.box().minY;
        double z = face.box().maxZ - face.box().minZ;
        return x >= y && x >= z ? Direction.Axis.X : y >= z ? Direction.Axis.Y : Direction.Axis.Z;
    }
}
