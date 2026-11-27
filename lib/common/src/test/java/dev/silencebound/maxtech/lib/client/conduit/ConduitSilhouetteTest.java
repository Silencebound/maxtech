package dev.silencebound.maxtech.lib.client.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitShapes;
import dev.silencebound.maxtech.lib.client.conduit.ConduitSilhouette.Band;
import dev.silencebound.maxtech.lib.client.conduit.ConduitSilhouette.Kind;
import dev.silencebound.maxtech.lib.client.conduit.ConduitSilhouette.Line;
import dev.silencebound.maxtech.lib.client.conduit.ConduitSilhouette.Outline;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ConduitSilhouetteTest {
    /** 6 px wide: the core runs from 5 to 11, an arm 5 px long, its ring 1 px deep. */
    private static final float THICKNESS = 0.375F;
    private static final double RIM = 1.5;
    private static final double EPSILON = 1.0E-6;

    /**
     * Line and band counts for each kind of piece; sides are given by their initial (d, u, n, s, w, e), rings as the
     * ringed subset of the arms.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // name, arms, rings, borders, rims, bands
            "isolated, '', '', 12, 0, 0", "cap east, e, '', 4, 4, 0", "cap up, u, '', 4, 4, 0",
            "straight x, we, '', 4, 0, 0", "straight y, du, '', 4, 0, 0", "straight z, ns, '', 4, 0, 0",
            "L xz, es, '', 9, 0, 0", "L xy, eu, '', 9, 0, 0", "L yz, us, '', 9, 0, 0", "L down north, dn, '', 9, 0, 0",
            "T, wes, '', 10, 0, 0", "T vertical, due, '', 10, 0, 0", "crossing, nswe, '', 16, 0, 0",
            "six-way, dunswe, '', 24, 0, 0", "ringed straight, we, e, 4, 0, 1", "ringed cap, e, e, 4, 4, 1",
            "ringed L, es, es, 9, 0, 2", "ringed six-way, dunswe, dunswe, 24, 0, 6"})
    void countsPerPiece(String name, String arms, String rings, int borders, int rims, int bands) {
        Outline outline = ConduitSilhouette.of(THICKNESS, mask(arms), mask(rings), RIM);
        assertEquals(borders, count(outline, Kind.BORDER), name + ": borders " + outline);
        assertEquals(rims, count(outline, Kind.RIM), name + ": rims " + outline);
        assertEquals(bands, outline.bands().size(), name + ": bands " + outline);
    }

    /** An isolated tube is a box with a border on each of its twelve edges, and no rims. */
    @Test
    void isolatedTubeHasTwelveBorders() {
        for (Line line : ConduitSilhouette.of(THICKNESS, 0, 0, RIM).lines()) {
            assertEquals(Kind.BORDER, line.kind());
            assertEquals(5.0, line.from(), EPSILON, line.toString());
            assertEquals(11.0, line.to(), EPSILON, line.toString());
        }
    }

    /** A straight run's four lines run from block face to block face, merged over core and arms. */
    @Test
    void straightRunIsFourWholeLines() {
        Outline outline = ConduitSilhouette.of(THICKNESS, mask("ns"), 0, RIM);
        for (Line line : outline.lines()) {
            assertEquals(Direction.Axis.Z, line.axis());
            assertEquals(0.0, line.from(), EPSILON);
            assertEquals(16.0, line.to(), EPSILON);
        }
    }

    /** A dead end: the lines run from the open end to the block face, and the open end's four edges are rims. */
    @Test
    void capHasRimsOnItsOpenEnd() {
        Outline outline = ConduitSilhouette.of(THICKNESS, mask("e"), 0, RIM);
        for (Line line : outline.lines()) {
            if (line.kind() == Kind.BORDER) {
                assertEquals(Direction.Axis.X, line.axis());
                assertEquals(5.0, line.from(), EPSILON);
                assertEquals(16.0, line.to(), EPSILON);
            } else {
                assertTrue(line.first() == Direction.WEST || line.second() == Direction.WEST, line.toString());
            }
        }
    }

    /**
     * A bend: the outer corner keeps its line, the inner crease has none, and the inner lines start where the crease
     * is.
     */
    @Test
    void bendHasNoLineOnItsInnerCrease() {
        Outline outline = ConduitSilhouette.of(THICKNESS, mask("es"), 0, RIM);
        assertTrue(
                outline.lines()
                        .contains(new Line(Direction.Axis.Y, Direction.NORTH, Direction.WEST, 5, 11, Kind.BORDER)),
                "outer corner: " + outline);
        for (Line line : outline.lines()) {
            boolean crease = line.axis() == Direction.Axis.Y && line.first() == Direction.SOUTH
                    && line.second() == Direction.EAST;
            assertFalse(crease, "inner crease: " + line);
        }
        assertTrue(outline.lines()
                .contains(new Line(Direction.Axis.X, Direction.UP, Direction.SOUTH, 11, 16, Kind.BORDER)));
        assertTrue(outline.lines()
                .contains(new Line(Direction.Axis.X, Direction.UP, Direction.NORTH, 5, 16, Kind.BORDER)));
    }

    /** A ringed arm's lines stop a rim's width before the ring, where its band runs up to the ring. */
    @Test
    void ringedArmEndsInABandBeforeItsRing() {
        Outline outline = ConduitSilhouette.of(THICKNESS, mask("we"), mask("e"), RIM);
        for (Line line : outline.lines()) {
            assertEquals(0.0, line.from(), EPSILON);
            assertEquals(13.5, line.to(), EPSILON);
        }
        assertEquals(List.of(new Band(Direction.EAST, 13.5, 15.0)), outline.bands());
        Outline west = ConduitSilhouette.of(THICKNESS, mask("we"), mask("w"), RIM);
        assertEquals(List.of(new Band(Direction.WEST, 1.0, 2.5)), west.bands());
    }

    /**
     * Every arm and ring mask at several thicknesses: each line lies on an outer convex edge of the glass, none lies on
     * a joint with the next tube, lines along such a joint's arm reach the block face, every outer edge is covered by a
     * line or band, and turning the conduit a quarter about Y turns its outline with it.
     */
    @Test
    void everyMaskFollowsTheRules() {
        for (float thickness : new float[]{0.125F, 0.375F, 0.5F, 0.75F}) {
            for (int arms = 0; arms < 64; arms++) {
                for (int rings = 0; rings < 64; rings++) {
                    if ((rings & ~arms) != 0) {
                        continue;
                    }
                    Outline outline = ConduitSilhouette.of(thickness, arms, rings, RIM);
                    String where = "thickness " + thickness + " arms " + arms + " rings " + rings + ": ";
                    Shape shape = new Shape(thickness, arms, rings);
                    for (Line line : outline.lines()) {
                        assertTrue(line.to() - line.from() > EPSILON, where + line);
                        assertTrue(line.first().compareTo(line.second()) < 0, where + line);
                        for (double t : samples(line.from(), line.to())) {
                            assertTrue(shape.convexEdge(line.axis(), line.first(), line.second(), t),
                                    where + "not an outer edge at " + t + ": " + line);
                        }
                    }
                    for (Direction side : Direction.values()) {
                        if (ConduitConnections.isConnected(arms, side) && !ConduitConnections.isConnected(rings, side)
                                && shape.armLength() > EPSILON) {
                            double face = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 16 : 0;
                            int reaching = 0;
                            for (Line line : outline.lines()) {
                                if (line.axis() == side.getAxis() && (Math.abs(line.from() - face) < EPSILON
                                        || Math.abs(line.to() - face) < EPSILON)) {
                                    reaching++;
                                }
                            }
                            assertEquals(4, reaching, where + "lines into the next tube on " + side);
                            for (Band band : outline.bands()) {
                                assertTrue(band.side() != side, where + "band on a joint");
                            }
                        }
                    }
                    shape.assertCovered(outline, where);
                    assertEquals(normalised(rotate(outline)),
                            normalised(ConduitSilhouette.of(thickness, rotate(arms), rotate(rings), RIM)),
                            where + "quarter turn");
                }
            }
        }
    }

    /** The glass of one conduit, with the next tube's glass beyond each joint and the rings as solid. */
    private record Shape(float thickness, int arms, int rings) {
        double armLength() {
            return (16 - ConduitShapes.width(thickness)) / 2;
        }

        List<AABB> glass() {
            List<AABB> boxes = new ArrayList<>();
            boxes.add(ConduitShapes.core(thickness));
            for (Direction side : Direction.values()) {
                if (ConduitConnections.isConnected(rings, side)) {
                    boxes.add(ConduitShapes.armBeforeRing(thickness, side));
                } else if (ConduitConnections.isConnected(arms, side)) {
                    boxes.add(ConduitShapes.arm(thickness, side));
                }
            }
            return boxes;
        }

        List<AABB> solid() {
            List<AABB> boxes = glass();
            for (Direction side : Direction.values()) {
                if (ConduitConnections.isConnected(rings, side)) {
                    boxes.add(ConduitShapes.ring(thickness, side));
                } else if (ConduitConnections.isConnected(arms, side)) {
                    // the next tube's arm beyond the joint
                    boxes.add(ConduitShapes.arm(thickness, side).move(side.getStepX() * 16.0, side.getStepY() * 16.0,
                            side.getStepZ() * 16.0));
                }
            }
            return boxes;
        }

        boolean inside(double x, double y, double z) {
            for (AABB box : solid()) {
                if (x > box.minX && x < box.maxX && y > box.minY && y < box.maxY && z > box.minZ && z < box.maxZ) {
                    return true;
                }
            }
            return false;
        }

        /** Whether the edge along {@code axis} where {@code a} and {@code b} faces meet is convex at {@code t}. */
        boolean convexEdge(Direction.Axis axis, Direction a, Direction b, double t) {
            AABB core = ConduitShapes.core(thickness);
            double[] point = new double[3];
            point[index(axis)] = t;
            point[index(a.getAxis())] = bound(core, a);
            point[index(b.getAxis())] = bound(core, b);
            int filled = 0;
            boolean innerFilled = false;
            for (int i = 0; i < 4; i++) {
                double[] p = point.clone();
                int sa = (i & 1) == 0 ? -1 : 1;
                int sb = (i & 2) == 0 ? -1 : 1;
                p[index(a.getAxis())] += sa * step(a) * 0.01;
                p[index(b.getAxis())] += sb * step(b) * 0.01;
                if (inside(p[0], p[1], p[2])) {
                    filled++;
                    innerFilled |= sa < 0 && sb < 0;
                }
            }
            return filled == 1 && innerFilled;
        }

        /** Every outer edge of the glass boxes is covered by a line or band. */
        void assertCovered(Outline outline, String where) {
            for (AABB box : glass()) {
                for (Direction.Axis axis : Direction.Axis.values()) {
                    for (Direction a : Direction.values()) {
                        for (Direction b : Direction.values()) {
                            if (a.getAxis() == axis || b.getAxis() == axis || a.compareTo(b) >= 0
                                    || a.getAxis() == b.getAxis()) {
                                continue;
                            }
                            // only edges of this box at the shared cross-section
                            AABB core = ConduitShapes.core(thickness);
                            if (Math.abs(bound(box, a) - bound(core, a)) > EPSILON
                                    || Math.abs(bound(box, b) - bound(core, b)) > EPSILON) {
                                continue;
                            }
                            for (double t : samples(box.min(axis), box.max(axis))) {
                                if (convexEdge(axis, a, b, t)) {
                                    assertTrue(covered(outline, axis, a, b, t),
                                            where + "outer edge " + axis + " " + a + "/" + b + " at " + t);
                                }
                            }
                        }
                    }
                }
            }
        }

        private static boolean covered(Outline outline, Direction.Axis axis, Direction a, Direction b, double t) {
            for (Line line : outline.lines()) {
                if (line.axis() == axis && line.first() == a && line.second() == b && t >= line.from()
                        && t <= line.to()) {
                    return true;
                }
            }
            for (Band band : outline.bands()) {
                if (band.side().getAxis() == axis && t >= band.from() && t <= band.to()) {
                    return true;
                }
            }
            return false;
        }

        private static int index(Direction.Axis axis) {
            return switch (axis) {
                case X -> 0;
                case Y -> 1;
                case Z -> 2;
            };
        }

        private static double bound(AABB box, Direction side) {
            return side.getAxisDirection() == Direction.AxisDirection.POSITIVE
                    ? box.max(side.getAxis())
                    : box.min(side.getAxis());
        }

        private static int step(Direction side) {
            return side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1;
        }
    }

    /** Points spread over {@code from..to}, never on a whole or half pixel where pieces meet. */
    private static double[] samples(double from, double to) {
        int n = 13;
        double[] t = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = from + (to - from) * (i + 0.5) / n;
        }
        return t;
    }

    private static int count(Outline outline, Kind kind) {
        int n = 0;
        for (Line line : outline.lines()) {
            if (line.kind() == kind) {
                n++;
            }
        }
        return n;
    }

    private static int mask(String sides) {
        int mask = 0;
        for (int i = 0; i < sides.length(); i++) {
            Direction side = switch (sides.charAt(i)) {
                case 'd' -> Direction.DOWN;
                case 'u' -> Direction.UP;
                case 'n' -> Direction.NORTH;
                case 's' -> Direction.SOUTH;
                case 'w' -> Direction.WEST;
                case 'e' -> Direction.EAST;
                default -> throw new IllegalArgumentException(sides);
            };
            mask |= ConduitConnections.bit(side);
        }
        return mask;
    }

    /** A quarter turn clockwise about Y, seen from above: north to east, (x, z) to (16 - z, x). */
    private static Direction rotate(Direction side) {
        return side.getAxis() == Direction.Axis.Y ? side : side.getClockWise();
    }

    private static int rotate(int mask) {
        int rotated = 0;
        for (Direction side : Direction.values()) {
            if (ConduitConnections.isConnected(mask, side)) {
                rotated |= ConduitConnections.bit(rotate(side));
            }
        }
        return rotated;
    }

    private static Outline rotate(Outline outline) {
        List<Line> lines = new ArrayList<>();
        for (Line line : outline.lines()) {
            double[] span = rotate(line.axis(), line.from(), line.to());
            Direction a = rotate(line.first());
            Direction b = rotate(line.second());
            lines.add(a.compareTo(b) < 0
                    ? new Line(rotateAxis(line.axis()), a, b, span[0], span[1], line.kind())
                    : new Line(rotateAxis(line.axis()), b, a, span[0], span[1], line.kind()));
        }
        List<Band> bands = new ArrayList<>();
        for (Band band : outline.bands()) {
            double[] span = rotate(band.side().getAxis(), band.from(), band.to());
            bands.add(new Band(rotate(band.side()), span[0], span[1]));
        }
        return new Outline(lines, bands);
    }

    private static Direction.Axis rotateAxis(Direction.Axis axis) {
        return switch (axis) {
            case X -> Direction.Axis.Z;
            case Y -> Direction.Axis.Y;
            case Z -> Direction.Axis.X;
        };
    }

    /** A span along {@code axis} after the turn: x becomes z unchanged, z becomes x mirrored. */
    private static double[] rotate(Direction.Axis axis, double from, double to) {
        return axis == Direction.Axis.Z ? new double[]{16 - to, 16 - from} : new double[]{from, to};
    }

    private static Set<String> normalised(Outline outline) {
        TreeSet<String> set = new TreeSet<>();
        for (Line line : outline.lines()) {
            set.add(String.format(Locale.ROOT, "line %s %s %s %.4f %.4f %s", line.axis(), line.first(), line.second(),
                    line.from(), line.to(), line.kind()));
        }
        for (Band band : outline.bands()) {
            set.add(String.format(Locale.ROOT, "band %s %.4f %.4f", band.side(), band.from(), band.to()));
        }
        return set;
    }
}
