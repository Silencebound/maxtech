package dev.silencebound.maxtech.storage.cable;

import dev.silencebound.maxtech.lib.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.conduit.ConduitShapes;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/**
 * The faces of a storage cable's body, in pixels, before Storage's cable model bakes them (PRD 7.2, 3D models; look
 * "J", owner decision 2026-10-15). A body is a sleeve, a core cube at the cable's thickness plus an arm from each
 * connected core face to the block face, as Lib's conduit shapes lay them out, so the model and the hit shape agree. A
 * glass body also has one lane inside the sleeve: the same core and arms at half the thickness, centred. No face of a
 * body lies in the plane of another face it overlaps, so nothing z-fights.
 *
 * <p>
 * Every texture is drawn one texel per block pixel. An arm side shows {@link Texture#SLEEVE} or {@link Texture#LANE}
 * running along the arm: the face's own UVs, unrotated where the face's u axis is the arm's axis, turned 90 degrees
 * with u and v swapped where its v axis is (north and south: u = x, v = y; east and west: u = z, v = y; up and down: u
 * = x, v = z). Across the arm the UVs are the face's own pixels too, so the sleeve sprite carries its edges in rows 6
 * and 9 and the lane fills rows 7 and 8. A sleeve core face ({@link Texture#SLEEVE_CORE}), and the closed end of an
 * opaque sleeve ({@link Texture#SLEEVE_END}, always cell 0), shows one 4 x 4 cell of a core sprite: the cell whose open
 * edges are the face's edges where an arm or a part's arm leaves the core ({@link #cell}), so the sleeve's edges run on
 * round corners and junctions. A glass sleeve has no ends: it is open where it meets the block face, and the next cable
 * or the device closes it. The lane's core faces ({@link Texture#LANE_CORE}) show their default UVs, the 2 x 2 middle
 * of a sprite that holds the tier's pips there, so every look shows its tier by shape (owner decision 2026-10-15)
 * without faces of their own. Pure geometry without client classes, unit-tested.
 */
public final class CableGeometry {
    /** Size of one {@link Texture#SLEEVE_CORE} cell, in texels; the sprite holds 4 x 4 of them. */
    public static final int CELL = 4;
    /** {@link #cell} bits: the face's edge at its lowest u, highest u, lowest v and highest v is open. */
    public static final int OPEN_U_MIN = 1;
    public static final int OPEN_U_MAX = 2;
    public static final int OPEN_V_MIN = 4;
    public static final int OPEN_V_MAX = 8;

    /**
     * The texels of a {@link Texture#LANE_CORE} face's tier pips, {u, v} in lighting order: top left, top right, bottom
     * left, bottom right; a tier lights the first that many.
     */
    static final int[][] PIPS = {{7, 7}, {8, 7}, {7, 8}, {8, 8}};

    private static final Direction[] DIRECTIONS = Direction.values();
    private static final double EPSILON = 1.0E-4;

    private CableGeometry() {
    }

    /** Which sprite a face is drawn with. */
    public enum Texture {
        /** The sleeve along an arm: edges in rows 6 and 9. */
        SLEEVE,
        /**
         * A sleeve core face: one cell of the core sprite ({@link #cell}); a covered cable's core sprite also carries
         * its tier pips.
         */
        SLEEVE_CORE,
        /** An opaque sleeve's closed arm end: cell 0 of the core sprite, without tier pips. */
        SLEEVE_END,
        /** The lane inside a glass sleeve, along an arm and at its ends; tinted with the line colour. */
        LANE,
        /**
         * A face of the lane's core: the lane's sprite with the tier pips in the face's 2 x 2 texels ({@link #PIPS}),
         * tinted and lit like the lane.
         */
        LANE_CORE
    }

    /**
     * One face: the {@code facing} side of {@code box} drawn with {@code texture} over the UV rectangle {@code u0, v0}
     * to {@code u1, v1} (pixels), turned 90 degrees when {@code rotated}, culled against a solid neighbour when
     * {@code culled}.
     */
    public record Face(AABB box, Direction facing, Texture texture, float u0, float v0, float u1, float v1,
            boolean rotated, boolean culled) {
    }

    /** The lane's thickness inside a sleeve {@code thickness} thick: half of it. */
    public static float laneThickness(float thickness) {
        return thickness / 2;
    }

    /**
     * The body of a cable {@code thickness} thick whose arms are {@code arms} and whose faces in {@code parts} carry a
     * part (its arm, {@link #connector}, belongs to the part). A {@code glass} body is an open sleeve with the lane
     * inside it; otherwise the sleeve is closed at its ends and has no lane.
     */
    public static List<Face> body(float thickness, int arms, int parts, boolean glass) {
        List<Face> out = new ArrayList<>();
        int leaving = arms | parts;
        sleeveOrLane(out, thickness, arms, leaving, Texture.SLEEVE, !glass);
        if (glass) {
            sleeveOrLane(out, laneThickness(thickness), arms, leaving, Texture.LANE, true);
        }
        return out;
    }

    /**
     * Adds a core cube {@code thickness} thick with a face on every side outside {@code leaving} and an arm on every
     * side in {@code arms}; the arms' ends on the block face are drawn when {@code ends}.
     */
    private static void sleeveOrLane(List<Face> out, float thickness, int arms, int leaving, Texture along,
            boolean ends) {
        AABB core = ConduitShapes.core(thickness);
        for (Direction side : DIRECTIONS) {
            if (ConduitConnections.isConnected(leaving, side)) {
                continue;
            }
            out.add(along == Texture.LANE
                    ? plain(core, side, Texture.LANE_CORE, false)
                    : coreCell(core, side, leaving));
        }
        for (Direction side : DIRECTIONS) {
            if (!ConduitConnections.isConnected(arms, side)) {
                continue;
            }
            AABB arm = ConduitShapes.arm(thickness, side);
            if (arm.max(side.getAxis()) - arm.min(side.getAxis()) < EPSILON) {
                continue;
            }
            for (Direction face : DIRECTIONS) {
                // The inner end lies on the core, which leaves that face out.
                if (face == side.getOpposite()) {
                    continue;
                }
                if (face == side) {
                    if (ends) {
                        out.add(along == Texture.LANE
                                ? plain(arm, face, Texture.LANE, true)
                                : cellFace(arm, face, 0, Texture.SLEEVE_END, true));
                    }
                } else {
                    out.add(striped(arm, face, side.getAxis(), along));
                }
            }
        }
    }

    /**
     * The arm from the core of a cable {@code thickness} thick to a part on {@code side} whose back lies {@code depth}
     * px inside the block face: the sleeve's four sides and, on a {@code glass} cable, the lane's. Its ends are left
     * out: the inner one opens into the core, which draws no face there, and the outer one lies behind the part, where
     * a face would z-fight with the part's back. Empty when the part reaches the core.
     */
    public static List<Face> connector(float thickness, Direction side, double depth, boolean glass) {
        List<Face> out = new ArrayList<>();
        connectorSides(out, thickness, side, depth, Texture.SLEEVE);
        if (glass) {
            connectorSides(out, laneThickness(thickness), side, depth, Texture.LANE);
        }
        return out;
    }

    private static void connectorSides(List<Face> out, float thickness, Direction side, double depth, Texture texture) {
        double armLength = (16 - ConduitShapes.width(thickness)) / 2;
        if (depth >= armLength - EPSILON) {
            return;
        }
        AABB arm = ConduitShapes.atFace(side, ConduitShapes.width(thickness), depth, armLength);
        for (Direction face : DIRECTIONS) {
            if (face.getAxis() != side.getAxis()) {
                out.add(striped(arm, face, side.getAxis(), texture));
            }
        }
    }

    /**
     * The {@code face} side of {@code box} with a texture that runs along {@code axis}, one texel per pixel both ways:
     * the face's own UVs, with u and v swapped and the texture turned where the face's u axis lies across the arm.
     */
    static Face striped(AABB box, Direction face, Direction.Axis axis, Texture texture) {
        float[] uv = defaultUv(box, face);
        boolean rotated = uAxis(face) != axis;
        return rotated
                ? new Face(box, face, texture, uv[1], uv[0], uv[3], uv[2], true, false)
                : new Face(box, face, texture, uv[0], uv[1], uv[2], uv[3], false, false);
    }

    private static Face plain(AABB box, Direction face, Texture texture, boolean culled) {
        float[] uv = defaultUv(box, face);
        return new Face(box, face, texture, uv[0], uv[1], uv[2], uv[3], false, culled);
    }

    /** The sleeve core's {@code side} face, its cell open towards every side in {@code leaving} in its plane. */
    private static Face coreCell(AABB core, Direction side, int leaving) {
        int open = 0;
        if (ConduitConnections.isConnected(leaving, edge(side, OPEN_U_MIN))) {
            open |= OPEN_U_MIN;
        }
        if (ConduitConnections.isConnected(leaving, edge(side, OPEN_U_MAX))) {
            open |= OPEN_U_MAX;
        }
        if (ConduitConnections.isConnected(leaving, edge(side, OPEN_V_MIN))) {
            open |= OPEN_V_MIN;
        }
        if (ConduitConnections.isConnected(leaving, edge(side, OPEN_V_MAX))) {
            open |= OPEN_V_MAX;
        }
        return cellFace(core, side, open, Texture.SLEEVE_CORE, false);
    }

    private static Face cellFace(AABB box, Direction side, int open, Texture texture, boolean culled) {
        int u = cell(open) % CELL * CELL;
        int v = cell(open) / CELL * CELL;
        return new Face(box, side, texture, u, v, u + CELL, v + CELL, false, culled);
    }

    /**
     * The index (0 to 15, row by row) of the {@link Texture#SLEEVE_CORE} cell whose edges in {@code open} (the
     * {@code OPEN_} bits) are open: the cell is the bits themselves, so cell 0 is closed all round.
     */
    public static int cell(int open) {
        return open & 15;
    }

    /**
     * The direction the {@code bit} edge of a {@code face} side faces in the world ({@code bit} one of the
     * {@code OPEN_} constants), as {@link #defaultUv} lays the texture on the face.
     */
    public static Direction edge(Direction face, int bit) {
        boolean uMin = bit == OPEN_U_MIN;
        boolean uMax = bit == OPEN_U_MAX;
        boolean vMin = bit == OPEN_V_MIN;
        return switch (face) {
            case DOWN -> uMin ? Direction.WEST : uMax ? Direction.EAST : vMin ? Direction.SOUTH : Direction.NORTH;
            case UP -> uMin ? Direction.WEST : uMax ? Direction.EAST : vMin ? Direction.NORTH : Direction.SOUTH;
            case NORTH -> uMin ? Direction.EAST : uMax ? Direction.WEST : vMin ? Direction.UP : Direction.DOWN;
            case SOUTH -> uMin ? Direction.WEST : uMax ? Direction.EAST : vMin ? Direction.UP : Direction.DOWN;
            case WEST -> uMin ? Direction.NORTH : uMax ? Direction.SOUTH : vMin ? Direction.UP : Direction.DOWN;
            case EAST -> uMin ? Direction.SOUTH : uMax ? Direction.NORTH : vMin ? Direction.UP : Direction.DOWN;
        };
    }

    /** The axis a face's texture u runs along. */
    static Direction.Axis uAxis(Direction face) {
        return switch (face) {
            case NORTH, SOUTH, UP, DOWN -> Direction.Axis.X;
            case EAST, WEST -> Direction.Axis.Z;
        };
    }

    /**
     * The UVs Minecraft gives a face of an element without explicit ones ({@code FaceBakery.defaultFaceUV}): the part
     * of the texture the face covers in world space.
     */
    public static float[] defaultUv(AABB box, Direction face) {
        float x0 = (float) box.minX;
        float y0 = (float) box.minY;
        float z0 = (float) box.minZ;
        float x1 = (float) box.maxX;
        float y1 = (float) box.maxY;
        float z1 = (float) box.maxZ;
        return switch (face) {
            case DOWN -> new float[]{x0, 16 - z1, x1, 16 - z0};
            case UP -> new float[]{x0, z0, x1, z1};
            case NORTH -> new float[]{16 - x1, 16 - y1, 16 - x0, 16 - y0};
            case SOUTH -> new float[]{x0, 16 - y1, x1, 16 - y0};
            case WEST -> new float[]{z0, 16 - y1, z1, 16 - y0};
            case EAST -> new float[]{16 - z1, 16 - y1, 16 - z0, 16 - y0};
        };
    }
}
