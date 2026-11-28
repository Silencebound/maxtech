package dev.silencebound.maxtech.lib.client.conduit;

import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.ConduitShapes;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitTexture;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * The frame of a {@link ConduitLook.Shell#frameBars} shell: solid bars on every edge of the tube's outer box and the
 * glass set in between them. Pure geometry, called by {@link ConduitGeometry} once per body key.
 *
 * <p>
 * Each bar is {@link #barWidth} px square and flush with the outer box. A straight run has four bars along its length;
 * an open end four along the tube and four around the end face; a bend, branch or single tube twelve on its core and
 * four along each arm. Bars along X reach over the core's corners and the others stop at them, so no two bars overlap.
 * An arm's bars run to the block face, where the next tube's bars go on end to end, or to the collar a ringed arm gets
 * instead of its rim band: four slabs one bar deep around the arm, just proud of the bars ({@value #PROUD} px), from
 * where the band starts to the ring. A face that lies against another bar or slab, on the block face of a connected
 * side or against a ring is left out; every other face is drawn, those looking onto the glass included.
 */
final class ConduitFrameGeometry {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final double EPSILON = 1.0E-4;
    /** How far a collar stands out of the bars, in pixels, so their outer faces never fight. */
    static final double PROUD = 0.02;
    /** Width of a tier stripe, in pixels. */
    static final double STRIPE = 1.0;

    private ConduitFrameGeometry() {
    }

    /** Width of a frame bar, {@code border} px capped at half the tube; 0 for a look without frame bars. */
    static double barWidth(ConduitLook.Shell look, float thickness) {
        return look.frameBars() ? Math.min(ConduitShapes.width(thickness) / 2, look.border()) : 0;
    }

    /**
     * The glass face {@code face} of {@code box} set in between the bars: {@code bar} px further in, and {@code bar} px
     * in from each of its edges except those across {@code keepAxis} (a run's ends, where the next glass goes on) and
     * the one on {@code keepSide} (where an open end's core runs on into its arm). {@code box} itself for no bar.
     */
    static AABB glass(AABB box, Direction face, double bar, Direction.@Nullable Axis keepAxis,
            @Nullable Direction keepSide) {
        if (bar < EPSILON) {
            return box;
        }
        Direction.Axis normal = face.getAxis();
        AABB out = face.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? ConduitGeometry.with(box, normal, box.min(normal), box.max(normal) - bar)
                : ConduitGeometry.with(box, normal, box.min(normal) + bar, box.max(normal));
        for (Direction.Axis axis : Direction.Axis.values()) {
            if (axis == normal || axis == keepAxis) {
                continue;
            }
            double min = box.min(axis) + bar;
            double max = box.max(axis) - bar;
            if (keepSide != null && keepSide.getAxis() == axis) {
                if (keepSide.getAxisDirection() == Direction.AxisDirection.POSITIVE) {
                    max = box.max(axis);
                } else {
                    min = box.min(axis);
                }
            }
            out = ConduitGeometry.with(out, axis, min, max);
        }
        return out;
    }

    /**
     * The glass of the arm on {@code side}: as {@link ConduitGeometry#glassArm}, but with frame bars it stops where a
     * ringed arm's collar starts, as the bars do.
     */
    static AABB glassArm(ConduitLook.Shell look, float thickness, Direction side, int rings) {
        if (barWidth(look, thickness) < EPSILON || !ConduitConnections.isConnected(rings, side)) {
            return ConduitGeometry.glassArm(thickness, side, rings);
        }
        return barredArm(thickness, side, rings, ConduitSilhouette.of(thickness, rings, rings, look.rim()).bands());
    }

    /** The side of a conduit's only arm; {@code arms} has exactly one. */
    static Direction onlyArm(int arms) {
        for (Direction side : DIRECTIONS) {
            if (ConduitConnections.isConnected(arms, side)) {
                return side;
            }
        }
        throw new IllegalArgumentException("no arm in " + arms);
    }

    /**
     * Adds the bars and collars of a conduit of {@code thickness} with {@code arms} and {@code rings}; returns their
     * boxes (empty for no bars).
     */
    @SuppressWarnings("TooManyParameters") // the body key's parts, unpacked; kept primitive
    static List<AABB> addFrame(List<ConduitQuad> out, ConduitLook.Shell look, float thickness, int arms, int rings,
            int argb) {
        double bar = barWidth(look, thickness);
        if (bar < EPSILON) {
            return List.of();
        }
        AABB core = ConduitShapes.core(thickness);
        List<ConduitSilhouette.Band> bands = ConduitSilhouette.of(thickness, arms, rings, look.rim()).bands();
        List<AABB> boxes = new ArrayList<>();
        Direction.Axis straight = ConduitGeometry.straightAxis(arms);
        if (straight != null) {
            Direction negative = Direction.fromAxisAndDirection(straight, Direction.AxisDirection.NEGATIVE);
            AABB box = core.minmax(barredArm(thickness, negative, rings, bands))
                    .minmax(barredArm(thickness, negative.getOpposite(), rings, bands));
            addLongBars(boxes, box, straight, bar);
        } else if (Integer.bitCount(arms) == 1) {
            Direction side = onlyArm(arms);
            AABB box = core.minmax(barredArm(thickness, side, rings, bands));
            addLongBars(boxes, box, side.getAxis(), bar);
            Direction open = side.getOpposite();
            for (Direction.Axis along : Direction.Axis.values()) {
                if (along == side.getAxis()) {
                    continue;
                }
                AABB inner = ConduitGeometry.with(box, along, box.min(along) + bar, box.max(along) - bar);
                for (Direction beside : DIRECTIONS) {
                    if (beside.getAxis() != along && beside.getAxis() != side.getAxis()) {
                        boxes.add(edgeBar(inner, open, beside, bar));
                    }
                }
            }
        } else {
            for (Direction.Axis axis : Direction.Axis.values()) {
                AABB span = axis == Direction.Axis.X
                        ? core
                        : ConduitGeometry.with(core, axis, core.min(axis) + bar, core.max(axis) - bar);
                addLongBars(boxes, span, axis, bar);
            }
            for (Direction side : DIRECTIONS) {
                if (ConduitConnections.isConnected(arms, side)) {
                    AABB arm = barredArm(thickness, side, rings, bands);
                    if (arm.max(side.getAxis()) - arm.min(side.getAxis()) > EPSILON) {
                        addLongBars(boxes, arm, side.getAxis(), bar);
                    }
                }
            }
        }
        for (ConduitSilhouette.Band band : bands) {
            addCollar(boxes, core, band, bar);
        }
        // Bars half the tube wide meet in the middle, so those between the others have no length.
        boxes.removeIf(box -> !hasArea(box, null));
        for (int i = 0; i < boxes.size(); i++) {
            AABB box = boxes.get(i);
            for (Direction face : DIRECTIONS) {
                if (ConduitConnections.isConnected(arms, face) && Math.abs(ConduitGeometry.bound(box, face)
                        - ConduitGeometry.bound(ConduitGeometry.glassArm(thickness, face, rings), face)) < EPSILON) {
                    // On the block face, where the next tube's bar goes on, or against the ring.
                    continue;
                }
                if (!covered(boxes, i, face)) {
                    out.add(new ConduitQuad(box, face, ConduitTexture.FLAT, argb, false));
                }
            }
        }
        return boxes;
    }

    /**
     * Adds the tier stripes of {@code glass}, the set-in glass faces: on each, a flat strip {@link #STRIPE} px wide (at
     * most half the face) along every edge a box of {@code frame} lies beside, as long as that box runs along the edge,
     * {@code lift} px outside the glass, opaque in {@code argb}. Stripes across the face's first in-plane axis run its
     * whole length; those along it stop at them, so none overlap.
     */
    static void addTierStripes(List<ConduitQuad> out, List<ConduitQuad> glass, List<AABB> frame, int argb,
            double lift) {
        for (ConduitQuad pane : glass) {
            AABB box = pane.box();
            Direction face = pane.facing();
            Direction.Axis normal = face.getAxis();
            double sign = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1;
            double plane = ConduitGeometry.bound(box, face);
            Direction.Axis first = null;
            Direction.Axis second = null;
            for (Direction.Axis axis : Direction.Axis.values()) {
                if (axis == normal) {
                    continue;
                }
                if (first == null) {
                    first = axis;
                } else {
                    second = axis;
                }
            }
            if (first == null || second == null) {
                continue;
            }
            // stripes at the ends of first run along second, at full length
            List<AABB> across = stripesAt(box, first, second, frame, normal, plane + sign * EPSILON);
            List<AABB> along = stripesAt(box, second, first, frame, normal, plane + sign * EPSILON);
            for (AABB stripe : across) {
                addStripe(out, stripe, face, normal, plane + sign * lift, argb);
            }
            for (AABB stripe : along) {
                double min = stripe.min(first);
                double max = stripe.max(first);
                for (AABB other : across) {
                    if (other.min(second) < stripe.max(second) - EPSILON
                            && other.max(second) > stripe.min(second) + EPSILON) {
                        if (other.min(first) <= min + EPSILON) {
                            min = Math.max(min, other.max(first));
                        } else if (other.max(first) >= max - EPSILON) {
                            max = Math.min(max, other.min(first));
                        }
                    }
                }
                if (max - min > EPSILON) {
                    addStripe(out, ConduitGeometry.with(stripe, first, min, max), face, normal, plane + sign * lift,
                            argb);
                }
            }
        }
    }

    /**
     * The stripes on {@code glass} at both ends of {@code edge} (each running along {@code length}), one per box of
     * {@code frame} just past that end at {@code probe} along {@code normal}, cut to where box and glass share
     * {@code length}.
     */
    private static List<AABB> stripesAt(AABB glass, Direction.Axis edge, Direction.Axis length, List<AABB> frame,
            Direction.Axis normal, double probe) {
        List<AABB> stripes = new ArrayList<>();
        double width = Math.min(STRIPE, (glass.max(edge) - glass.min(edge)) / 2);
        for (int end = 0; end < 2; end++) {
            double at = end == 0 ? glass.min(edge) - EPSILON : glass.max(edge) + EPSILON;
            for (AABB box : frame) {
                if (box.min(edge) >= at || box.max(edge) <= at || box.min(normal) >= probe
                        || box.max(normal) <= probe) {
                    continue;
                }
                double from = Math.max(box.min(length), glass.min(length));
                double to = Math.min(box.max(length), glass.max(length));
                if (to - from <= EPSILON) {
                    continue;
                }
                AABB stripe = ConduitGeometry.with(glass, length, from, to);
                stripes.add(end == 0
                        ? ConduitGeometry.with(stripe, edge, glass.min(edge), glass.min(edge) + width)
                        : ConduitGeometry.with(stripe, edge, glass.max(edge) - width, glass.max(edge)));
            }
        }
        return stripes;
    }

    private static void addStripe(List<ConduitQuad> out, AABB stripe, Direction face, Direction.Axis normal,
            double plane, int argb) {
        out.add(new ConduitQuad(ConduitGeometry.with(stripe, normal, plane, plane), face, ConduitTexture.FLAT, argb,
                false));
    }

    /** Whether {@code box} has extent along every axis but {@code flat} (none: every axis). */
    static boolean hasArea(AABB box, Direction.@Nullable Axis flat) {
        for (Direction.Axis axis : Direction.Axis.values()) {
            if (axis != flat && box.max(axis) - box.min(axis) < EPSILON) {
                return false;
            }
        }
        return true;
    }

    /** The arm on {@code side} as far as its bars run: to the block face, or to the start of its collar. */
    private static AABB barredArm(float thickness, Direction side, int rings, List<ConduitSilhouette.Band> bands) {
        AABB arm = ConduitGeometry.glassArm(thickness, side, rings);
        Direction.Axis axis = side.getAxis();
        for (ConduitSilhouette.Band band : bands) {
            if (band.side() == side) {
                return side.getAxisDirection() == Direction.AxisDirection.POSITIVE
                        ? ConduitGeometry.with(arm, axis, arm.min(axis), band.from())
                        : ConduitGeometry.with(arm, axis, band.to(), arm.max(axis));
            }
        }
        return arm;
    }

    /**
     * The four slabs of a ringed arm's collar along {@code band}. The pair across the first cross axis spans the whole
     * width, the other pair fits between them, so the slabs do not overlap.
     */
    private static void addCollar(List<AABB> boxes, AABB core, ConduitSilhouette.Band band, double bar) {
        Direction.Axis axis = band.side().getAxis();
        AABB range = ConduitGeometry.with(core, axis, band.from(), band.to());
        Direction.Axis first = axis == Direction.Axis.X ? Direction.Axis.Y : Direction.Axis.X;
        Direction.Axis second = axis == Direction.Axis.Z ? Direction.Axis.Y : Direction.Axis.Z;
        for (Direction face : DIRECTIONS) {
            Direction.Axis normal = face.getAxis();
            if (normal == axis) {
                continue;
            }
            AABB slab = toward(range, face, bar);
            slab = face.getAxisDirection() == Direction.AxisDirection.POSITIVE
                    ? ConduitGeometry.with(slab, normal, slab.min(normal), slab.max(normal) + PROUD)
                    : ConduitGeometry.with(slab, normal, slab.min(normal) - PROUD, slab.max(normal));
            slab = normal == first
                    ? ConduitGeometry.with(slab, second, slab.min(second) - PROUD, slab.max(second) + PROUD)
                    : ConduitGeometry.with(slab, first, slab.min(first) + bar, slab.max(first) - bar);
            boxes.add(slab);
        }
    }

    /** The four bars along {@code axis} on the long edges of {@code box}. */
    private static void addLongBars(List<AABB> boxes, AABB box, Direction.Axis axis, double bar) {
        for (Direction first : DIRECTIONS) {
            for (Direction second : DIRECTIONS) {
                if (first.getAxis() != axis && second.getAxis() != axis
                        && first.getAxis().compareTo(second.getAxis()) < 0) {
                    boxes.add(edgeBar(box, first, second, bar));
                }
            }
        }
    }

    /** The bar along {@code box}'s edge between its faces {@code first} and {@code second}. */
    private static AABB edgeBar(AABB box, Direction first, Direction second, double bar) {
        return toward(toward(box, first, bar), second, bar);
    }

    /** The slice of {@code box} {@code depth} px deep along its face {@code face}. */
    private static AABB toward(AABB box, Direction face, double depth) {
        Direction.Axis axis = face.getAxis();
        return face.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? ConduitGeometry.with(box, axis, box.max(axis) - depth, box.max(axis))
                : ConduitGeometry.with(box, axis, box.min(axis), box.min(axis) + depth);
    }

    /** Whether another box lies against the whole of face {@code face} of box {@code index}. */
    private static boolean covered(List<AABB> boxes, int index, Direction face) {
        AABB box = boxes.get(index);
        double plane = ConduitGeometry.bound(box, face);
        for (int i = 0; i < boxes.size(); i++) {
            AABB other = boxes.get(i);
            if (i == index || Math.abs(ConduitGeometry.bound(other, face.getOpposite()) - plane) > EPSILON) {
                continue;
            }
            boolean inside = true;
            for (Direction.Axis axis : Direction.Axis.values()) {
                if (axis != face.getAxis()
                        && (other.min(axis) > box.min(axis) + EPSILON || other.max(axis) < box.max(axis) - EPSILON)) {
                    inside = false;
                }
            }
            if (inside) {
                return true;
            }
        }
        return false;
    }
}
