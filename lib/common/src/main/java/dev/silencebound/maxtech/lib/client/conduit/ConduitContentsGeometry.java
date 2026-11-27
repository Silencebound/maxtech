package dev.silencebound.maxtech.lib.client.conduit;

import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitContents;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.ConduitShapes;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitBodyKey;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitLayer;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitTexture;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitUv;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * The faces of a shell conduit's contents ({@link ConduitContents}), in pixels, before they become baked quads. Pure
 * geometry: the loader's conduit model looks the fluid's sprites, tint and light up and hands them in as a
 * {@link Paint}, and caches the result per {@link #key}.
 *
 * <p>
 * The contents are opaque boxes {@link ConduitLook.Shell#inset} px inside the glass: one for the core and one per arm,
 * an arm reaching to the arm's end. A horizontal piece fills from the bottom to the fill level; a vertical piece (an
 * arm up or down, or a core with one) is a full column whenever there is any fill. Faces between two pieces of the same
 * height are left out.
 *
 * <p>
 * Flow: with an upstream side, every face along the flow shows the flowing sprite turned so its +v runs downstream
 * ({@link ConduitUvs#flowTurns}; vanilla's flowing sprites move towards +v). An arm flows away from the core, the
 * upstream arm towards it; the core flows away from the upstream side, and its faces across that direction flow along
 * the first downstream arm (in {@link Direction} order) that runs along them. A face the flow runs into, and every face
 * without an upstream side, shows the still sprite. The flowing sprite is drawn at half density, as vanilla draws
 * flowing fluid.
 *
 * <p>
 * The second lane is a strip {@link ConduitLook.Shell#laneWidth} px square in cross-section, in the inner (+, +) corner
 * of each run (the top south corner of an east-west run, the south east corner of a vertical one), reaching
 * {@value #LANE_LIFT} px past the contents so it shows in front of them, and flowing opposite to the piece it runs
 * along. Where the runs of a bend or branch meet, the strips meet in a corner cube at the core's (+, +, +) corner. A
 * conduit without arms has no run and no lane.
 */
public final class ConduitContentsGeometry {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final double EPSILON = 1.0E-4;
    /** How far the lane reaches past the contents, in pixels, so its faces never fight with theirs. */
    static final double LANE_LIFT = 0.02;
    /** The flowing sprite is drawn at half density, as vanilla's fluid renderer draws it. */
    private static final float FLOWING_SCALE = 0.5F;

    private ConduitContentsGeometry() {
    }

    /**
     * How a fluid's contents are drawn, looked up from the fluid on the client.
     *
     * @param still
     *            the still sprite, by atlas id
     * @param flowing
     *            the flowing sprite, by atlas id
     * @param argb
     *            the tint, opaque
     * @param light
     *            the light level of the quads, 0 to 15
     */
    public record Paint(Identifier still, Identifier flowing, int argb, int light) {
    }

    /**
     * The key the contents part of {@code key} is cached under: only the type, arms, rings and packed contents shape
     * it, so the dye and render value are dropped and conduits that differ only there share one part.
     */
    public static ConduitBodyKey key(ConduitBodyKey key) {
        if (key.colour() == null && key.renderValue() == 0) {
            return key;
        }
        return new ConduitBodyKey(key.type(), null, key.arms(), key.rings(), 0, key.contents());
    }

    /** Whether packed contents draw anything: a fluid and a fill above 0. */
    public static boolean drawn(int packed) {
        return ConduitContents.packedFluidId(packed) >= 0 && ConduitContents.packedFill(packed) > 0;
    }

    /**
     * The contents of a shell conduit of {@code thickness} with {@code arms} and {@code rings} (as in
     * {@link ConduitBodyKey}), {@code packed} as {@link ConduitContents#packed()}, in {@code paint}. Empty when
     * {@code packed} draws nothing or the inset leaves no room.
     */
    @SuppressWarnings("TooManyParameters") // the cache key's parts, unpacked; kept primitive
    public static List<ConduitQuad> quads(ConduitLook.Shell look, float thickness, int arms, int rings, int packed,
            Paint paint) {
        List<ConduitQuad> out = new ArrayList<>();
        int fill = ConduitContents.packedFill(packed);
        if (!drawn(packed)) {
            return out;
        }
        AABB core = ConduitShapes.core(thickness);
        double inset = look.inset();
        if (2 * inset >= ConduitShapes.width(thickness) - EPSILON) {
            return out;
        }
        AABB inner = core.deflate(inset);
        double top = inner.minY + (inner.maxY - inner.minY) * fill / ConduitContents.FULL;
        Optional<Direction> upstreamSide = ConduitContents.packedUpstream(packed);
        Direction upstream = upstreamSide.isPresent() ? upstreamSide.get() : null;
        boolean vertical = ConduitConnections.isConnected(arms, Direction.UP)
                || ConduitConnections.isConnected(arms, Direction.DOWN);

        // The core: its faces towards arms of its own height are left out.
        AABB coreBox = vertical ? inner : with(inner, Direction.Axis.Y, inner.minY, top);
        for (Direction face : DIRECTIONS) {
            boolean armSameHeight = ConduitConnections.isConnected(arms, face)
                    && (face.getAxis() == Direction.Axis.Y || !vertical);
            if (!armSameHeight) {
                Direction flow = upstream == null ? null : coreFlow(face, arms, upstream);
                out.add(contentsFace(coreBox, face, flow, paint));
            }
        }
        for (Direction side : DIRECTIONS) {
            if (!ConduitConnections.isConnected(arms, side)) {
                continue;
            }
            AABB arm = armBox(inner, thickness, side, rings);
            if (side.getAxis() != Direction.Axis.Y) {
                arm = with(arm, Direction.Axis.Y, arm.minY, top);
            }
            Direction flow = upstream == null ? null : armFlow(side, upstream);
            for (Direction face : DIRECTIONS) {
                if (face != side.getOpposite()) {
                    out.add(contentsFace(arm, face, flow, paint));
                }
            }
        }
        double width = Math.min(look.laneWidth(), inner.maxX - inner.minX);
        if (ConduitContents.packedLane(packed) && width >= EPSILON && arms != 0) {
            addLane(out, inner, thickness, arms, rings, upstream, width, Math.min(LANE_LIFT, inset), paint);
        }
        return out;
    }

    /** The direction an arm on {@code side} flows: towards the core from upstream, else away from it. */
    static Direction armFlow(Direction side, Direction upstream) {
        return side == upstream ? side.getOpposite() : side;
    }

    /**
     * The direction the core's face {@code face} flows: away from the upstream side, or across it along the first
     * downstream arm that runs along the face; null where neither does.
     */
    static @Nullable Direction coreFlow(Direction face, int arms, Direction upstream) {
        Direction main = upstream.getOpposite();
        if (main.getAxis() != face.getAxis()) {
            return main;
        }
        for (Direction side : DIRECTIONS) {
            if (side != upstream && side.getAxis() != face.getAxis() && ConduitConnections.isConnected(arms, side)) {
                return side;
            }
        }
        return null;
    }

    /**
     * The contents of the arm on {@code side}: the inner cross-section, from the core's inner face to the arm's end.
     */
    private static AABB armBox(AABB inner, float thickness, Direction side, int rings) {
        AABB arm = ConduitConnections.isConnected(rings, side)
                ? ConduitShapes.armBeforeRing(thickness, side)
                : ConduitShapes.arm(thickness, side);
        Direction.Axis axis = side.getAxis();
        return side.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? with(inner, axis, inner.max(axis), arm.max(axis))
                : with(inner, axis, arm.min(axis), inner.min(axis));
    }

    /**
     * The lane: a strip along each arm's run from the arm's end to the corner cube, the corner cube itself, each face
     * left out where it meets another part of the lane.
     */
    @SuppressWarnings("TooManyParameters") // one lane, laid out from the contents' measures; kept primitive
    private static void addLane(List<ConduitQuad> out, AABB inner, float thickness, int arms, int rings,
            @Nullable Direction upstream, double width, double lift, Paint paint) {
        AABB corner = new AABB(inner.maxX - width, inner.maxY - width, inner.maxZ - width, inner.maxX + lift,
                inner.maxY + lift, inner.maxZ + lift);
        for (Direction side : DIRECTIONS) {
            if (!ConduitConnections.isConnected(arms, side)) {
                continue;
            }
            Direction.Axis axis = side.getAxis();
            AABB end = armBox(inner, thickness, side, rings);
            AABB strip = side.getAxisDirection() == Direction.AxisDirection.POSITIVE
                    ? with(corner, axis, corner.max(axis), end.max(axis))
                    : with(corner, axis, end.min(axis), corner.min(axis));
            if (strip.max(axis) - strip.min(axis) < EPSILON) {
                continue;
            }
            Direction flow = upstream == null ? null : armFlow(side, upstream).getOpposite();
            for (Direction face : DIRECTIONS) {
                // The face towards the core meets the corner cube.
                if (face != side.getOpposite()) {
                    out.add(contentsFace(strip, face, flow, paint));
                }
            }
        }
        for (Direction face : DIRECTIONS) {
            if (!ConduitConnections.isConnected(arms, face)) {
                Direction flow = upstream == null ? null : coreFlow(face, arms, upstream);
                out.add(contentsFace(corner, face, flow == null ? null : flow.getOpposite(), paint));
            }
        }
    }

    /**
     * {@code face} of {@code box}: the flowing sprite running along {@code flow}, or the still sprite where there is no
     * flow or it runs into the face.
     */
    private static ConduitQuad contentsFace(AABB box, Direction face, @Nullable Direction flow, Paint paint) {
        AABB plane = face(box, face);
        OptionalInt turns = flow == null ? OptionalInt.empty() : ConduitUvs.flowTurns(face, flow);
        if (turns.isPresent()) {
            ConduitUv uv = scaled(ConduitUvs.projected(plane, face, turns.getAsInt()));
            return new ConduitQuad(plane, face, ConduitTexture.FLAT, paint.argb(), false, ConduitLayer.CUTOUT,
                    paint.flowing(), uv, paint.light());
        }
        return new ConduitQuad(plane, face, ConduitTexture.FLAT, paint.argb(), false, ConduitLayer.CUTOUT,
                paint.still(), ConduitUvs.projected(plane, face), paint.light());
    }

    private static ConduitUv scaled(ConduitUv uv) {
        return new ConduitUv(uv.u0() * FLOWING_SCALE, uv.v0() * FLOWING_SCALE, uv.u1() * FLOWING_SCALE,
                uv.v1() * FLOWING_SCALE, uv.quarterTurns());
    }

    /** The pane of {@code box} flat on its face {@code face}. */
    private static AABB face(AABB box, Direction face) {
        Direction.Axis axis = face.getAxis();
        double plane = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? box.max(axis) : box.min(axis);
        return with(box, axis, plane, plane);
    }

    private static AABB with(AABB box, Direction.Axis axis, double min, double max) {
        return switch (axis) {
            case X -> new AABB(min, box.minY, box.minZ, max, box.maxY, box.maxZ);
            case Y -> new AABB(box.minX, min, box.minZ, box.maxX, max, box.maxZ);
            case Z -> new AABB(box.minX, box.minY, min, box.maxX, box.maxY, max);
        };
    }
}
