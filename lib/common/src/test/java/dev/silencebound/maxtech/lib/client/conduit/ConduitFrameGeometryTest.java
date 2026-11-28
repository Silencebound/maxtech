package dev.silencebound.maxtech.lib.client.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitContents;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceKey;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceMode;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.ConduitShapes;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitBodyKey;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitLayer;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The frame of a {@link ConduitLook.Shell#frameBars} shell: its bars, collars and the glass set in between them. */
class ConduitFrameGeometryTest {
    private static final double EPS = 1.0E-6;
    private static final ConduitLook.Shell FRAMED = ConduitLook
            .shell(Identifier.fromNamespaceAndPath("test", "block/glass")).withBorder(1).withFrameBars();
    // 6 px wide: the outer box is 5 to 11, the glass 6 to 10.
    private static final ConduitType TYPE = new ConduitType(Identifier.fromNamespaceAndPath("test", "net"), "test",
            0x4078D8, 0.375F, () -> (ItemLike) () -> {
                throw new UnsupportedOperationException();
            });
    private static final double LOW = 5;
    private static final double HIGH = 11;
    private static final double BAR = 1;

    /**
     * Every edge of the tube's outer box has a bar flush on it, all along: a straight run's four long edges, an open
     * end's long edges and end face, all twelve of a junction's core plus each arm's four, up to a ring's collar.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // name, arms, rings, bars
            "straight, we, '', 4", "straight y, du, '', 4", "cap, e, '', 8", "single, '', '', 12", "L, es, '', 20",
            "T, wes, '', 24", "crossing, nswe, '', 28", "3D corner, ues, '', 24", "six-way, dunswe, '', 36",
            "ringed straight, we, e, 4", "ringed cap, e, e, 8", "ringed L, es, s, 20"})
    void everyOuterEdgeHasABar(String name, String arms, String rings, int bars) {
        int armMask = mask(arms);
        List<AABB> solids = solids(body(armMask, mask(rings)));
        assertEquals(bars, solids.stream().filter(ConduitFrameGeometryTest::isBar).count(), name + ": bars");
        for (Edge edge : expectedEdges(armMask, mask(rings))) {
            for (double at = edge.from; at <= edge.to + EPS; at += 0.25) {
                Vec3 point = edge.point(at);
                assertTrue(solids.stream().anyMatch(box -> barOn(box, edge, point)),
                        name + ": no bar on " + edge + " at " + at);
            }
        }
    }

    /** A tube's bars end on the block face exactly where the next tube's start, with no end faces between them. */
    @Test
    void barsOfNeighboursLineUp() {
        List<ConduitQuad> west = body(mask("en"), 0);
        List<ConduitQuad> east = shifted(body(mask("w"), 0), 16);
        List<AABB> westEnds = touching(solids(west), Direction.Axis.X, 16);
        List<AABB> eastEnds = touching(solids(east), Direction.Axis.X, 16);
        assertEquals(4, westEnds.size());
        assertEquals(4, eastEnds.size());
        for (AABB end : westEnds) {
            assertTrue(eastEnds.stream().anyMatch(other -> sameSection(end, other, Direction.Axis.X)),
                    "no bar goes on from " + end);
        }
        for (ConduitQuad quad : concat(west, east)) {
            assertFalse(
                    quad.facing().getAxis() == Direction.Axis.X
                            && Math.abs(ConduitGeometry.bound(quad.box(), quad.facing()) - 16) < EPS,
                    "face on the joint: " + quad);
        }
    }

    /** No two bars or collar slabs share space, and no two opaque faces lie on each other, for any arms and rings. */
    @Test
    void barsNeverOverlapOrFight() {
        for (int arms = 0; arms < 64; arms++) {
            for (int rings : new int[]{0, arms}) {
                List<ConduitQuad> quads = body(arms, rings);
                List<AABB> solids = solids(quads);
                for (int i = 0; i < solids.size(); i++) {
                    for (int j = i + 1; j < solids.size(); j++) {
                        AABB a = solids.get(i);
                        AABB b = solids.get(j);
                        assertFalse(a.deflate(EPS).intersects(b.deflate(EPS)),
                                arms + "/" + rings + ": " + a + " overlaps " + b);
                    }
                }
                List<ConduitQuad> opaque = quads.stream().filter(q -> q.layer() != ConduitLayer.TRANSLUCENT).toList();
                for (int i = 0; i < opaque.size(); i++) {
                    for (int j = i + 1; j < opaque.size(); j++) {
                        assertFalse(onEachOther(opaque.get(i), opaque.get(j)),
                                arms + "/" + rings + ": " + opaque.get(i) + " fights " + opaque.get(j));
                    }
                }
            }
        }
    }

    /**
     * A face against another bar, on a joint or against a ring is left out, and every other face is drawn, those
     * looking onto the glass included.
     */
    @Test
    void hiddenFacesAreCulledAndTheRestDrawn() {
        for (int arms = 0; arms < 64; arms++) {
            for (int rings : new int[]{0, arms}) {
                List<ConduitQuad> quads = body(arms, rings);
                List<AABB> solids = solids(quads);
                for (ConduitQuad quad : quads) {
                    if (quad.layer() == ConduitLayer.TRANSLUCENT) {
                        continue;
                    }
                    Vec3 out = centre(quad)
                            .add(new Vec3(quad.facing().getStepX(), quad.facing().getStepY(), quad.facing().getStepZ())
                                    .scale(0.001));
                    assertFalse(solids.stream().anyMatch(box -> box.contains(out)),
                            arms + "/" + rings + ": hidden face drawn " + quad);
                    assertFalse(Math.abs(out.get(quad.facing().getAxis()) - 8) > 8 - 0.01,
                            arms + "/" + rings + ": face on the block face " + quad);
                }
            }
        }
        // A straight run's bars show all four long faces, the two inner ones looking onto the glass.
        List<ConduitQuad> straight = body(mask("we"), 0);
        assertEquals(16, straight.stream().filter(q -> q.layer() != ConduitLayer.TRANSLUCENT).count());
    }

    /**
     * The glass lies one bar in from the outer box and spans exactly between the bars: it never reaches into a bar, and
     * no ray from inside the tube gets out without passing glass, a bar, a collar or a ring, except through the joint
     * to the next tube.
     */
    @Test
    void glassClosesTheFrame() {
        Random random = new Random(26);
        for (int arms = 0; arms < 64; arms++) {
            for (int rings : new int[]{0, arms}) {
                List<ConduitQuad> quads = new ArrayList<>(body(arms, rings));
                List<AABB> solids = solids(quads);
                for (ConduitQuad quad : quads) {
                    if (quad.layer() != ConduitLayer.TRANSLUCENT) {
                        continue;
                    }
                    double plane = ConduitGeometry.bound(quad.box(), quad.facing());
                    double expected = quad.facing().getAxisDirection() == Direction.AxisDirection.POSITIVE
                            ? HIGH - BAR
                            : LOW + BAR;
                    assertEquals(expected, plane, EPS, arms + "/" + rings + ": glass not one bar in " + quad);
                    AABB pane = flat(quad).deflate(EPS);
                    for (AABB box : solids) {
                        assertFalse(pane.intersects(box.deflate(EPS)),
                                arms + "/" + rings + ": glass in a bar " + quad + " vs " + box);
                    }
                }
                for (Direction side : Direction.values()) {
                    if (ConduitConnections.isConnected(rings, side)) {
                        quads.addAll(
                                ConduitGeometry.face(FRAMED, new ConduitFaceKey(TYPE, side, ConduitFaceMode.BOTH)));
                    }
                }
                for (int ray = 0; ray < 400; ray++) {
                    Vec3 from = new Vec3(LOW + BAR + 0.05 + random.nextDouble() * 3.9,
                            LOW + BAR + 0.05 + random.nextDouble() * 3.9, LOW + BAR + 0.05 + random.nextDouble() * 3.9);
                    Vec3 dir = new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian())
                            .normalize();
                    assertTrue(enclosed(quads, arms, rings, from, dir),
                            arms + "/" + rings + ": ray from " + from + " along " + dir + " gets out");
                }
            }
        }
    }

    /** The contents sit inside the set-in glass, {@code inset} px in from it. */
    @Test
    void contentsFitInsideTheGlass() {
        ConduitContentsGeometry.Paint paint = new ConduitContentsGeometry.Paint(
                Identifier.fromNamespaceAndPath("test", "block/still"),
                Identifier.fromNamespaceAndPath("test", "block/flow"), 0xFF3F76E4, 0);
        int packed = ConduitContents.pack(5, 4, Direction.WEST, true);
        for (int arms = 0; arms < 64; arms++) {
            List<ConduitQuad> contents = ConduitContentsGeometry.quads(FRAMED, TYPE.thickness(), arms, 0, packed,
                    paint);
            assertFalse(contents.isEmpty());
            for (ConduitQuad quad : contents) {
                for (Direction.Axis axis : Direction.Axis.values()) {
                    AABB box = quad.box();
                    if (box.min(axis) < LOW - EPS || box.max(axis) > HIGH + EPS) {
                        continue; // along an arm
                    }
                    // The lane sits just in front of the contents, still inside the glass.
                    assertTrue(box.min(axis) >= LOW + BAR + 0.1 && box.max(axis) <= HIGH - BAR - 0.1,
                            arms + ": contents outside the glass " + quad);
                }
            }
        }
    }

    /** A ringed arm's collar wraps the arm all round from where the rim band starts to the ring. */
    @Test
    void ringCollarsAreClosed() {
        List<AABB> solids = solids(body(mask("we"), mask("e")));
        double from = ConduitShapes.armBeforeRing(TYPE.thickness(), Direction.EAST).maxX - FRAMED.rim();
        double to = ConduitShapes.armBeforeRing(TYPE.thickness(), Direction.EAST).maxX;
        for (double x = from + 0.05; x < to; x += 0.25) {
            for (double t = LOW - 0.01; t <= HIGH + 0.01; t += 0.25) {
                for (Vec3 point : List.of(new Vec3(x, HIGH + 0.01, t), new Vec3(x, LOW - 0.01, t),
                        new Vec3(x, t, HIGH + 0.01), new Vec3(x, t, LOW - 0.01))) {
                    assertTrue(solids.stream().anyMatch(box -> box.contains(point)), "collar open at " + point);
                }
            }
        }
    }

    private record Edge(Direction.Axis axis, Direction first, Direction second, double from, double to) {
        Vec3 point(double at) {
            return Vec3.ZERO.with(axis, at).with(first.getAxis(), bound(first)).with(second.getAxis(), bound(second));
        }

        private static double bound(Direction face) {
            return face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? HIGH : LOW;
        }
    }

    /** The edges of the outer box, from the shipping design: see {@link #everyOuterEdgeHasABar}. */
    private static List<Edge> expectedEdges(int arms, int rings) {
        List<Edge> edges = new ArrayList<>();
        Direction.Axis straight = ConduitGeometry.straightAxis(arms);
        int count = Integer.bitCount(arms);
        for (Direction.Axis axis : Direction.Axis.values()) {
            double from = LOW;
            double to = HIGH;
            boolean along;
            if (straight != null) {
                along = axis == straight;
            } else if (count == 1) {
                along = axis == ConduitFrameGeometry.onlyArm(arms).getAxis();
            } else {
                along = true;
            }
            if (straight != null || count == 1) {
                Direction negative = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.NEGATIVE);
                if (along && ConduitConnections.isConnected(arms, negative)) {
                    from = end(negative, rings);
                }
                if (along && ConduitConnections.isConnected(arms, negative.getOpposite())) {
                    to = end(negative.getOpposite(), rings);
                }
            }
            if (along) {
                addCorners(edges, axis, from, to);
            }
        }
        if (count == 1) {
            Direction open = ConduitFrameGeometry.onlyArm(arms).getOpposite();
            for (Direction.Axis axis : Direction.Axis.values()) {
                if (axis == open.getAxis()) {
                    continue;
                }
                for (Direction beside : Direction.values()) {
                    if (beside.getAxis() != axis && beside.getAxis() != open.getAxis()) {
                        edges.add(edge(axis, open, beside, LOW, HIGH));
                    }
                }
            }
        } else if (straight == null) {
            for (Direction side : Direction.values()) {
                if (ConduitConnections.isConnected(arms, side)) {
                    double end = end(side, rings);
                    boolean positive = side.getAxisDirection() == Direction.AxisDirection.POSITIVE;
                    addCorners(edges, side.getAxis(), positive ? HIGH : end, positive ? end : LOW);
                }
            }
        }
        return edges;
    }

    /** Where an arm's bars must reach: the block face, or the ring (a collar covers its last stretch). */
    private static double end(Direction side, int rings) {
        AABB arm = ConduitConnections.isConnected(rings, side)
                ? ConduitShapes.armBeforeRing(TYPE.thickness(), side)
                : ConduitShapes.arm(TYPE.thickness(), side);
        return ConduitGeometry.bound(arm, side);
    }

    private static void addCorners(List<Edge> edges, Direction.Axis axis, double from, double to) {
        for (Direction first : Direction.values()) {
            for (Direction second : Direction.values()) {
                if (first.getAxis() != axis && second.getAxis() != axis
                        && first.getAxis().compareTo(second.getAxis()) < 0) {
                    edges.add(edge(axis, first, second, from, to));
                }
            }
        }
    }

    private static Edge edge(Direction.Axis axis, Direction a, Direction b, double from, double to) {
        return a.getAxis().compareTo(b.getAxis()) < 0 ? new Edge(axis, a, b, from, to) : new Edge(axis, b, a, from, to);
    }

    /** Whether {@code box} is a bar on {@code edge} (or the collar over it) covering {@code point}. */
    private static boolean barOn(AABB box, Edge edge, Vec3 point) {
        if (!box.inflate(EPS).contains(point)) {
            return false;
        }
        if (!isBar(box)) {
            return true; // a collar slab
        }
        for (Direction face : new Direction[]{edge.first, edge.second}) {
            if (Math.abs(ConduitGeometry.bound(box, face) - point.get(face.getAxis())) > EPS) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBar(AABB box) {
        int square = 0;
        for (Direction.Axis axis : Direction.Axis.values()) {
            if (Math.abs(box.max(axis) - box.min(axis) - BAR) < EPS) {
                square++;
            }
        }
        return square >= 2;
    }

    private static boolean enclosed(List<ConduitQuad> quads, int arms, int rings, Vec3 from, Vec3 dir) {
        double exit = Double.MAX_VALUE;
        Direction exitFace = Direction.DOWN;
        for (Direction face : Direction.values()) {
            double d = dir.get(face.getAxis()) * face.getAxisDirection().getStep();
            if (d > EPS) {
                double wall = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 16 : 0;
                double t = (wall - from.get(face.getAxis())) / dir.get(face.getAxis());
                if (t < exit) {
                    exit = t;
                    exitFace = face;
                }
            }
        }
        for (ConduitQuad quad : quads) {
            Direction.Axis normal = quad.facing().getAxis();
            if (Math.abs(dir.get(normal)) < EPS) {
                continue;
            }
            double t = (ConduitGeometry.bound(quad.box(), quad.facing()) - from.get(normal)) / dir.get(normal);
            if (t < 0 || t > exit) {
                continue;
            }
            Vec3 hit = from.add(dir.scale(t));
            boolean inside = true;
            for (Direction.Axis axis : Direction.Axis.values()) {
                if (axis != normal
                        && (hit.get(axis) < quad.box().min(axis) - EPS || hit.get(axis) > quad.box().max(axis) + EPS)) {
                    inside = false;
                }
            }
            if (inside) {
                return true;
            }
        }
        // Out through the joint to the next tube.
        return ConduitConnections.isConnected(arms, exitFace) && !ConduitConnections.isConnected(rings, exitFace);
    }

    private static boolean onEachOther(ConduitQuad a, ConduitQuad b) {
        if (a.facing() != b.facing() || Math
                .abs(ConduitGeometry.bound(a.box(), a.facing()) - ConduitGeometry.bound(b.box(), b.facing())) > EPS) {
            return false;
        }
        for (Direction.Axis axis : Direction.Axis.values()) {
            if (axis != a.facing().getAxis() && (Math.min(a.box().max(axis), b.box().max(axis))
                    - Math.max(a.box().min(axis), b.box().min(axis)) < EPS)) {
                return false;
            }
        }
        return true;
    }

    private static AABB flat(ConduitQuad quad) {
        double plane = ConduitGeometry.bound(quad.box(), quad.facing());
        Direction.Axis normal = quad.facing().getAxis();
        // A sliver either side of the plane, so a pane lying on a bar's face counts as inside it.
        return ConduitGeometry.with(quad.box(), normal, plane - 0.01, plane + 0.01);
    }

    private static Vec3 centre(ConduitQuad quad) {
        return quad.box().getCenter().with(quad.facing().getAxis(), ConduitGeometry.bound(quad.box(), quad.facing()));
    }

    private static List<AABB> touching(List<AABB> solids, Direction.Axis axis, double plane) {
        return solids.stream()
                .filter(box -> Math.abs(box.min(axis) - plane) < EPS || Math.abs(box.max(axis) - plane) < EPS).toList();
    }

    private static boolean sameSection(AABB a, AABB b, Direction.Axis along) {
        for (Direction.Axis axis : Direction.Axis.values()) {
            if (axis != along
                    && (Math.abs(a.min(axis) - b.min(axis)) > EPS || Math.abs(a.max(axis) - b.max(axis)) > EPS)) {
                return false;
            }
        }
        return true;
    }

    private static List<ConduitQuad> shifted(List<ConduitQuad> quads, double x) {
        List<ConduitQuad> out = new ArrayList<>();
        for (ConduitQuad quad : quads) {
            out.add(new ConduitQuad(quad.box().move(x, 0, 0), quad.facing(), quad.texture(), quad.argb(), quad.culled(),
                    quad.layer(), quad.sprite(), quad.uv(), quad.lightEmission()));
        }
        return out;
    }

    private static List<ConduitQuad> concat(List<ConduitQuad> a, List<ConduitQuad> b) {
        List<ConduitQuad> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /** The distinct opaque boxes (bars and collar slabs) of {@code quads}. */
    private static List<AABB> solids(List<ConduitQuad> quads) {
        Set<AABB> boxes = new LinkedHashSet<>();
        for (ConduitQuad quad : quads) {
            if (quad.layer() != ConduitLayer.TRANSLUCENT) {
                boxes.add(quad.box());
            }
        }
        return List.copyOf(boxes);
    }

    private static List<ConduitQuad> body(int arms, int rings) {
        return ConduitGeometry.body(FRAMED, new ConduitBodyKey(TYPE, null, arms, rings));
    }

    private static int mask(String sides) {
        int mask = 0;
        for (int i = 0; i < sides.length(); i++) {
            mask |= ConduitConnections.bit(switch (sides.charAt(i)) {
                case 'd' -> Direction.DOWN;
                case 'u' -> Direction.UP;
                case 'n' -> Direction.NORTH;
                case 's' -> Direction.SOUTH;
                case 'w' -> Direction.WEST;
                case 'e' -> Direction.EAST;
                default -> throw new IllegalArgumentException(sides);
            });
        }
        return mask;
    }
}
