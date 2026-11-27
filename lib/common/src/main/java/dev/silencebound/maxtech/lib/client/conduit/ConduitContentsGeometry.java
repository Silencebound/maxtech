package dev.silencebound.maxtech.lib.client.conduit;

import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitContents;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.ConduitShapes;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitBodyKey;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitLayer;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitTexture;
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
 * geometry: the resource's sprites, tint and light come from its kind's look, resolved once into a {@link Paint}. The
 * model caches the result per {@link #key}.
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
 * without an upstream side, shows the still sprite. Both sprites are drawn block-projected at full size (one sprite per
 * block, {@link ConduitUvs}), so the flowing sprite runs on without a seam along a run of any length; vanilla draws
 * flowing fluid at half density, which would jump at every block boundary here.
 *
 * <p>
 * The second lane is a strip {@link ConduitLook.Shell#laneWidth} px square in cross-section, in the inner (+, +) corner
 * of each run (the top south corner of an east-west run, the south east corner of a vertical one), reaching
 * {@value #LANE_LIFT} px past the contents so it shows in front of them, whatever the inset, and flowing opposite to
 * the piece it runs along. On a horizontal piece it lies on the fluid's surface, no wider than the fill is high. Where
 * the runs of a bend or branch meet, the strips meet in a corner cube at the core's (+, +, +) corner, at the core's own
 * top; on a vertical bend, whose core is a full column, the corner reaches down to the horizontal arms' surface. A
 * conduit without arms has no run and no lane.
 */
public final class ConduitContentsGeometry {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final double EPSILON = 1.0E-4;
    /**
     * How far the lane reaches past the contents, in pixels, so its faces never fight with theirs. Independent of the
     * inset: at an inset below this the lane pokes through the glass by the difference, too little to see, where a lift
     * clamped to the inset would be 0 at inset 0 and fight.
     */
    static final double LANE_LIFT = 0.02;

    private ConduitContentsGeometry() {
    }

    /**
     * How a resource's contents are drawn, resolved from its kind's look on the client ({@link ConduitLooks}).
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

    /** Whether packed contents draw anything: a resource and a fill above 0. */
    public static boolean drawn(int packed) {
        return ConduitContents.packedLookIndex(packed) >= 0 && ConduitContents.packedFill(packed) > 0;
    }

    /**
     * The contents of a shell conduit of {@code thickness} with {@code arms} and {@code rings} (as in
     * {@link ConduitBodyKey}), {@code packed} as {@link ConduitContents#pack}, in {@code paint}. Empty when
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
            addLane(out, inner, thickness, arms, rings, upstream, vertical, top, width, paint);
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
     * left out where it meets another part of the lane. Horizontal strips lie on the fluid's surface at {@code top}, no
     * wider than the fill is high; the corner sits at the core's top, reaching down to that surface when the core is a
     * full column.
     */
    @SuppressWarnings("TooManyParameters") // one lane, laid out from the contents' measures; kept primitive
    private static void addLane(List<ConduitQuad> out, AABB inner, float thickness, int arms, int rings,
            @Nullable Direction upstream, boolean vertical, double top, double width, Paint paint) {
        double flatWidth = Math.min(width, top - inner.minY);
        // A vertical piece's lane keeps its width; a horizontal one's shrinks with the fill.
        double side = vertical ? width : flatWidth;
        double coreTop = vertical ? inner.maxY : top;
        boolean flatArms = (arms
                & ~(ConduitConnections.bit(Direction.UP) | ConduitConnections.bit(Direction.DOWN))) != 0;
        double cornerBottom = vertical && flatArms ? Math.min(coreTop - side, top - flatWidth) : coreTop - side;
        AABB corner = new AABB(inner.maxX - side, cornerBottom, inner.maxZ - side, inner.maxX + LANE_LIFT,
                coreTop + LANE_LIFT, inner.maxZ + LANE_LIFT);
        AABB flat = with(corner, Direction.Axis.Y, top - flatWidth, top + LANE_LIFT);
        int covered = 0;
        for (Direction armSide : DIRECTIONS) {
            if (!ConduitConnections.isConnected(arms, armSide)) {
                continue;
            }
            Direction.Axis axis = armSide.getAxis();
            AABB base = axis == Direction.Axis.Y ? corner : flat;
            AABB end = armBox(inner, thickness, armSide, rings);
            AABB strip = armSide.getAxisDirection() == Direction.AxisDirection.POSITIVE
                    ? with(base, axis, corner.max(axis), end.max(axis))
                    : with(base, axis, end.min(axis), corner.min(axis));
            if (strip.max(axis) - strip.min(axis) < EPSILON) {
                continue;
            }
            if (axis == Direction.Axis.Y
                    || (Math.abs(base.minY - corner.minY) < EPSILON && Math.abs(base.maxY - corner.maxY) < EPSILON)) {
                covered |= ConduitConnections.bit(armSide);
            }
            Direction flow = upstream == null ? null : armFlow(armSide, upstream).getOpposite();
            for (Direction face : DIRECTIONS) {
                // The face towards the core meets the corner cube.
                if (face != armSide.getOpposite()) {
                    out.add(contentsFace(strip, face, flow, paint));
                }
            }
        }
        for (Direction face : DIRECTIONS) {
            // A corner face is left out where a strip as tall as the corner meets it.
            if (!ConduitConnections.isConnected(covered, face)) {
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
            return new ConduitQuad(plane, face, ConduitTexture.FLAT, paint.argb(), false, ConduitLayer.CUTOUT,
                    paint.flowing(), ConduitUvs.projected(plane, face, turns.getAsInt()), paint.light());
        }
        return new ConduitQuad(plane, face, ConduitTexture.FLAT, paint.argb(), false, ConduitLayer.CUTOUT,
                paint.still(), ConduitUvs.projected(plane, face), paint.light());
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
