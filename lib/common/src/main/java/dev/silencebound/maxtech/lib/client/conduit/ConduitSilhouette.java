package dev.silencebound.maxtech.lib.client.conduit;

import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.ConduitShapes;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Where a {@link dev.silencebound.maxtech.lib.api.conduit.ConduitLook.Shell} conduit draws its border lines and rims,
 * worked out from its arms and rings. Pure and thread-safe; the model calls it once per body key and caches the result.
 *
 * <p>
 * The glass is the core cube plus one arm per connected side ({@link ConduitShapes#arm}, or
 * {@link ConduitShapes#armBeforeRing} for a ringed one). Every box has the same cross-section, so along each axis there
 * are four lines, one per corner of that cross-section, and each piece along the axis either has an edge there or not:
 * <ul>
 * <li>an arm along the axis always has: its four long edges are outer edges;
 * <li>the core has one where neither face beside the corner has an arm; with an arm on one face the core's face runs on
 * flat into the arm's, and with arms on both the corner is an inner crease, which shows no line.
 * </ul>
 * Pieces that touch along a line merge into one {@link Line}. An arm without a ring ends on the block face, where the
 * next tube's glass goes on, so a line reaches the face and continues in the next block; nothing is drawn across the
 * joint. A ringed arm's lines stop {@code rim} px before the ring, where its {@link Band} starts. A conduit with a
 * single arm ends in the open on the far side: the four edges of that end face are {@link Kind#RIM} lines. A conduit
 * with no arms has a border on all twelve edges.
 * <p>
 * A caged outline ({@link ConduitLook.Shell#junctionCage}, for bends and branches) gives the core a line on all twelve
 * edges, inner creases included, and breaks every line at the core's faces: each arm's lines and each of the core's are
 * separate {@link Line}s that meet end to end there. The geometry draws a core line only on the faces no arm leaves
 * from.
 */
public final class ConduitSilhouette {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final double BLOCK = 16.0;
    private static final double EPSILON = 1.0E-6;

    private ConduitSilhouette() {
    }

    /** What a line is drawn as. */
    public enum Kind {
        /** A border line, {@code border} px wide on each face. */
        BORDER,
        /** An edge of an open end, {@code rim} px wide on each face. */
        RIM
    }

    /**
     * A line along an edge of the glass.
     *
     * @param axis
     *            the axis the line runs along
     * @param first
     *            one face the edge borders, the one that comes first in {@link Direction}
     * @param second
     *            the other face it borders
     * @param from
     *            where it starts along {@code axis}, in pixels
     * @param to
     *            where it ends along {@code axis}, in pixels, more than {@code from}
     * @param kind
     *            how it is drawn
     */
    public record Line(Direction.Axis axis, Direction first, Direction second, double from, double to, Kind kind) {
    }

    /**
     * The rim band around the side faces of the ringed arm on {@code side}, from {@code from} to {@code to} px along
     * its axis, ending where the ring starts.
     */
    public record Band(Direction side, double from, double to) {
    }

    /** The lines and bands of one conduit. */
    public record Outline(List<Line> lines, List<Band> bands) {
        public Outline {
            lines = List.copyOf(lines);
            bands = List.copyOf(bands);
        }
    }

    /**
     * The outline of a conduit of {@code thickness} with arms on {@code arms}, of which {@code rings} meet a block;
     * {@code rim} is the rim width in pixels, which also sets how far before a ring the lines stop.
     */
    public static Outline of(float thickness, int arms, int rings, double rim) {
        return of(thickness, arms, rings, rim, false);
    }

    /**
     * The outline as {@link #of(float, int, int, double)}; with {@code caged} the core has a line on each of its twelve
     * edges and no line runs on across a core face. Lib only cages conduits with two or more arms.
     */
    public static Outline of(float thickness, int arms, int rings, double rim, boolean caged) {
        arms &= ConduitConnections.ALL_SIDES;
        rings &= arms;
        AABB core = ConduitShapes.core(thickness);
        // The side whose core face is an open end: opposite the one arm, if there is exactly one.
        @Nullable
        Direction cap = Integer.bitCount(arms) == 1 ? single(arms).getOpposite() : null;
        List<Line> lines = new ArrayList<>();
        for (Direction.Axis axis : Direction.Axis.values()) {
            Direction negative = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.NEGATIVE);
            Direction positive = negative.getOpposite();
            for (Direction first : DIRECTIONS) {
                for (Direction second : DIRECTIONS) {
                    if (first.getAxis() == axis || second.getAxis() == axis
                            || first.getAxis().compareTo(second.getAxis()) >= 0) {
                        continue;
                    }
                    if (caged) {
                        addCaged(lines, thickness, axis, first, second, arms, rings, rim, core);
                        continue;
                    }
                    double start = Double.NaN;
                    double end = Double.NaN;
                    if (ConduitConnections.isConnected(arms, negative)) {
                        double[] span = armSpan(thickness, negative, rings, rim);
                        if (span != null) {
                            start = span[0];
                            end = span[1];
                        }
                    }
                    boolean coreEdge = !ConduitConnections.isConnected(arms, first)
                            && !ConduitConnections.isConnected(arms, second);
                    if (coreEdge && (first == cap || second == cap)) {
                        lines.add(line(axis, first, second, core.min(axis), core.max(axis), Kind.RIM));
                        coreEdge = false;
                    }
                    if (coreEdge) {
                        if (Double.isNaN(start) || end < core.min(axis) - EPSILON) {
                            start = flush(lines, axis, first, second, start, end, core.min(axis));
                        }
                        end = core.max(axis);
                    }
                    if (ConduitConnections.isConnected(arms, positive)) {
                        double[] span = armSpan(thickness, positive, rings, rim);
                        if (span != null) {
                            if (Double.isNaN(start) || end < span[0] - EPSILON) {
                                start = flush(lines, axis, first, second, start, end, span[0]);
                            }
                            end = span[1];
                        }
                    }
                    flush(lines, axis, first, second, start, end, Double.NaN);
                }
            }
        }
        List<Band> bands = new ArrayList<>();
        for (Direction side : DIRECTIONS) {
            if (ConduitConnections.isConnected(rings, side)) {
                AABB arm = ConduitShapes.armBeforeRing(thickness, side);
                Direction.Axis axis = side.getAxis();
                double width = Math.min(rim, arm.max(axis) - arm.min(axis));
                if (width > EPSILON) {
                    bands.add(side.getAxisDirection() == Direction.AxisDirection.POSITIVE
                            ? new Band(side, arm.max(axis) - width, arm.max(axis))
                            : new Band(side, arm.min(axis), arm.min(axis) + width));
                }
            }
        }
        return new Outline(lines, bands);
    }

    /** Adds the caged lines along one corner: the negative arm's, the core's and the positive arm's, unmerged. */
    @SuppressWarnings("TooManyParameters") // one corner of the outline; kept primitive like the uncaged loop
    private static void addCaged(List<Line> lines, float thickness, Direction.Axis axis, Direction first,
            Direction second, int arms, int rings, double rim, AABB core) {
        Direction negative = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.NEGATIVE);
        double @Nullable [] before = ConduitConnections.isConnected(arms, negative)
                ? armSpan(thickness, negative, rings, rim)
                : null;
        if (before != null) {
            flush(lines, axis, first, second, before[0], before[1], Double.NaN);
        }
        lines.add(line(axis, first, second, core.min(axis), core.max(axis), Kind.BORDER));
        double @Nullable [] after = ConduitConnections.isConnected(arms, negative.getOpposite())
                ? armSpan(thickness, negative.getOpposite(), rings, rim)
                : null;
        if (after != null) {
            flush(lines, axis, first, second, after[0], after[1], Double.NaN);
        }
    }

    /** Adds the open border line {@code start..end}, if any, and returns {@code next} as the start of the next one. */
    private static double flush(List<Line> lines, Direction.Axis axis, Direction first, Direction second, double start,
            double end, double next) {
        if (!Double.isNaN(start) && end - start > EPSILON) {
            lines.add(line(axis, first, second, start, end, Kind.BORDER));
        }
        return next;
    }

    private static Line line(Direction.Axis axis, Direction a, Direction b, double from, double to, Kind kind) {
        return a.compareTo(b) < 0 ? new Line(axis, a, b, from, to, kind) : new Line(axis, b, a, from, to, kind);
    }

    /**
     * Where the border lines of the arm on {@code side} run along its axis: the whole arm, or for a ringed one up to
     * its rim band. Null for an arm too short to draw.
     */
    private static double @Nullable [] armSpan(float thickness, Direction side, int rings, double rim) {
        Direction.Axis axis = side.getAxis();
        boolean ringed = ConduitConnections.isConnected(rings, side);
        AABB arm = ringed ? ConduitShapes.armBeforeRing(thickness, side) : ConduitShapes.arm(thickness, side);
        double min = arm.min(axis);
        double max = arm.max(axis);
        if (max - min < EPSILON) {
            return null;
        }
        if (ringed) {
            double width = Math.min(rim, max - min);
            if (side.getAxisDirection() == Direction.AxisDirection.POSITIVE) {
                max -= width;
            } else {
                min += width;
            }
        }
        return new double[]{Math.max(0.0, min), Math.min(BLOCK, max)};
    }

    private static Direction single(int arms) {
        for (Direction side : DIRECTIONS) {
            if (ConduitConnections.isConnected(arms, side)) {
                return side;
            }
        }
        throw new IllegalArgumentException("no arm in " + arms);
    }
}
