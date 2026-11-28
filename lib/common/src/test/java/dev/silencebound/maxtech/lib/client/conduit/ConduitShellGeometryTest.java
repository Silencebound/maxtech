package dev.silencebound.maxtech.lib.client.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.conduit.ConduitColours;
import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceKey;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceMode;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.ConduitShapes;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitBodyKey;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitLayer;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitTexture;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitUv;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ConduitShellGeometryTest {
    private static final Identifier GLASS = Identifier.fromNamespaceAndPath("test", "block/glass");
    private static final ConduitLook.Shell SHELL = ConduitLook.shell(GLASS);
    private static final ConduitLook.Shell CAGED = SHELL.withBorder(1).withJunctionCage();
    private static final ConduitType TYPE = new ConduitType(Identifier.fromNamespaceAndPath("test", "net"), "test",
            0x4078D8, 0.375F, () -> (ItemLike) () -> {
                throw new UnsupportedOperationException();
            });

    /**
     * Glass faces and border strips per piece: the glass is laid out like the solid body (a straight run is one box),
     * and each line has a strip on both faces beside it, each band one on all four side faces.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // name, arms, rings, glass, strips, caged
            "isolated, '', '', 6, 24, false", "cap, e, '', 9, 16, false", "straight, we, '', 4, 8, false",
            "straight y, du, '', 4, 8, false", "L, es, '', 12, 18, false", "T, wes, '', 15, 20, false",
            "crossing, nswe, '', 18, 32, false", "six-way, dunswe, '', 24, 48, false",
            "ringed straight, we, e, 4, 12, false", "ringed cap, e, e, 9, 20, false", "caged L, es, '', 12, 28, true",
            "caged T, wes, '', 15, 28, true", "caged crossing, nswe, '', 18, 24, true"})
    void quadCounts(String name, String arms, String rings, int glass, int strips, boolean caged) {
        List<ConduitQuad> quads = ConduitGeometry.body(caged ? SHELL.withJunctionCage() : SHELL,
                new ConduitBodyKey(TYPE, null, mask(arms), mask(rings)));
        int glassQuads = 0;
        int stripQuads = 0;
        for (ConduitQuad quad : quads) {
            if (isGlass(quad)) {
                glassQuads++;
            } else {
                stripQuads++;
            }
        }
        assertEquals(glass, glassQuads, name + ": glass");
        assertEquals(strips, stripQuads, name + ": strips");
    }

    /**
     * Glass is translucent, untinted and block-projected, so the sprite tiles across blocks; strips are opaque flat
     * quads in the border colour, which a dye tints, just outside the glass and never on the block face.
     */
    @Test
    void glassIsTranslucentAndStripsOpaqueOutsideIt() {
        int arms = mask("wes");
        List<ConduitQuad> quads = ConduitGeometry.body(SHELL.withBorderColour(0x204060),
                new ConduitBodyKey(TYPE, DyeColor.RED, arms, 0));
        AABB core = ConduitShapes.core(TYPE.thickness());
        int border = ConduitColours.argb(0x204060, DyeColor.RED);
        for (ConduitQuad quad : quads) {
            assertFalse(quad.culled(), quad.toString());
            if (isGlass(quad)) {
                assertEquals(ConduitLayer.TRANSLUCENT, quad.layer());
                assertEquals(0xFFFFFFFF, quad.argb(), "a dye leaves the glass alone");
                ConduitUv uv = quad.uv();
                assertTrue(uv != null, "glass has projected UVs");
                assertEquals(ConduitUvs.projected(quad.box(), quad.facing(), uv.quarterTurns()), uv);
            } else {
                assertEquals(ConduitTexture.FLAT, quad.texture());
                assertEquals(ConduitLayer.AUTO, quad.layer());
                assertEquals(border, quad.argb());
                Direction face = quad.facing();
                double plane = face.getAxisDirection() == Direction.AxisDirection.POSITIVE
                        ? quad.box().max(face.getAxis()) - core.max(face.getAxis())
                        : core.min(face.getAxis()) - quad.box().min(face.getAxis());
                assertEquals(ConduitGeometry.STRIP_OFFSET, plane, 1.0E-9, "strip outside the glass: " + quad);
            }
        }
    }

    /** A straight run's glass is one box from block face to block face, ending at a ring where there is one. */
    @Test
    void straightGlassIsOneBox() {
        List<ConduitQuad> quads = ConduitGeometry.body(SHELL, new ConduitBodyKey(TYPE, null, mask("we"), mask("e")));
        for (ConduitQuad quad : quads) {
            if (isGlass(quad)) {
                assertEquals(0.0, quad.box().minX, 1.0E-9);
                assertEquals(15.0, quad.box().maxX, 1.0E-9);
                // the sprite's rows run along the tube: 15 px of u, 6 of v
                ConduitUv uv = quad.uv();
                assertTrue(uv != null);
                assertEquals(15.0, Math.abs(uv.u1() - uv.u0()), 1.0E-6, quad.toString());
                assertEquals(6.0, Math.abs(uv.v1() - uv.v0()), 1.0E-6, quad.toString());
            }
        }
    }

    /** Border width 0 draws no lines, but the rims stay; the render value never changes the shell. */
    @Test
    void zeroBorderDrawsOnlyRims() {
        int arms = mask("e");
        List<ConduitQuad> quads = ConduitGeometry.body(SHELL.withBorder(0), new ConduitBodyKey(TYPE, null, arms, 0));
        assertEquals(9 + 8, quads.size(), "glass and the cap's rim strips");
        assertEquals(ConduitGeometry.body(SHELL, new ConduitBodyKey(TYPE, null, arms, 0)),
                ConduitGeometry.body(SHELL, new ConduitBodyKey(TYPE, null, arms, 0, 0x1234)));
        assertTrue(ConduitGeometry.body(SHELL.withRim(0).withBorder(0), new ConduitBodyKey(TYPE, null, arms, 0))
                .stream().allMatch(ConduitShellGeometryTest::isGlass));
    }

    /**
     * Strips on one face cover each point of a border once: where two meet at a corner, on the outside of a face or at
     * the inner corner of a bend or branch on the core's face, neither leaves a notch nor lies over the other.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // name, arms, face, x, y, z (the face's own axis is ignored), strips covering the point
            "isolated corner, '', u, 5.25, 0, 5.25, 1", "isolated edge, '', u, 8, 0, 5.25, 1",
            "isolated face, '', u, 8, 0, 8, 0", "cap rim corner, e, w, 0, 5.5, 5.5, 1",
            "cap rim beside border, e, u, 6, 0, 5.25, 1", "L inner corner, es, u, 10.75, 0, 10.75, 1",
            "L arm edge, es, u, 13, 0, 10.75, 1", "L inner glass, es, u, 10.25, 0, 10.25, 0",
            "T inner corner, wes, u, 5.25, 0, 10.75, 1", "crossing inner corner, nswe, u, 5.25, 0, 5.25, 1",
            "L under an up arm, esu, u, 10.75, 0, 10.75, 0"})
    void stripsCoverCornersOnce(String name, String arms, String face, double x, double y, double z, int expected) {
        Direction facing = side(face.charAt(0));
        AABB core = ConduitShapes.core(TYPE.thickness());
        AABB point = new AABB(x, y, z, x, y, z);
        int covering = 0;
        for (ConduitQuad quad : ConduitGeometry.body(SHELL, new ConduitBodyKey(TYPE, null, mask(arms), 0))) {
            if (isGlass(quad) || quad.facing() != facing || Math.abs(plane(quad, core)) > 0.1) {
                continue;
            }
            boolean inside = true;
            for (Direction.Axis axis : Direction.Axis.values()) {
                double at = point.min(axis);
                if (axis != facing.getAxis() && (at <= quad.box().min(axis) || at >= quad.box().max(axis))) {
                    inside = false;
                }
            }
            if (inside) {
                covering++;
            }
        }
        assertEquals(expected, covering, name);
    }

    /** No two strips on one plane overlap, so none fights with another under ambient occlusion. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"isolated, '', ''", "cap, e, ''", "straight, we, ''", "L, es, ''", "T, wes, ''", "crossing, nswe, ''",
            "six-way, dunswe, ''", "L up, esu, ''", "ringed cap, e, e", "ringed T, wes, s"})
    void stripsNeverOverlap(String name, String arms, String rings) {
        assertNoStripOverlaps(name,
                ConduitGeometry.body(SHELL, new ConduitBodyKey(TYPE, null, mask(arms), mask(rings))));
    }

    /**
     * A junction cage frames each face of a bend's or branch's core that no arm leaves from: the middle of each edge of
     * such a face is covered by exactly one strip on it. A face an arm leaves from has no frame, which would lie inside
     * the arm's glass. Without the cage some core edges have no strip.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"corner, es", "corner up, eu", "T, wes", "crossing, nswe", "3D corner, esu", "six-way, dunswe",
            "ringed T, wes"})
    void junctionCageFramesFreeCoreFaces(String name, String arms) {
        AABB core = ConduitShapes.core(TYPE.thickness());
        int mask = mask(arms);
        List<ConduitQuad> caged = ConduitGeometry.body(CAGED, new ConduitBodyKey(TYPE, null, mask, 0));
        List<ConduitQuad> plain = ConduitGeometry.body(SHELL.withBorder(1), new ConduitBodyKey(TYPE, null, mask, 0));
        int uncovered = 0;
        for (Direction face : Direction.values()) {
            for (Direction beside : Direction.values()) {
                if (beside.getAxis() == face.getAxis()) {
                    continue;
                }
                Direction.Axis across = beside.getAxis();
                double edge = beside.getAxisDirection() == Direction.AxisDirection.POSITIVE
                        ? core.max(across) - 0.5
                        : core.min(across) + 0.5;
                double[] point = {across == Direction.Axis.X ? edge : 8, across == Direction.Axis.Y ? edge : 8,
                        across == Direction.Axis.Z ? edge : 8};
                int expected = ConduitConnections.isConnected(mask, face) ? 0 : 1;
                assertEquals(expected, covering(caged, face, point), name + ": " + face + " beside " + beside);
                if (covering(plain, face, point) == 0) {
                    uncovered++;
                }
            }
        }
        assertTrue(uncovered > 0, name + ": without the cage some core edges have no strip");
    }

    /** The cage leaves straight runs, open ends, single tubes and block attachments alone. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"isolated, '', ''", "cap, e, ''", "straight, we, ''", "straight y, du, ''", "ringed straight, we, e",
            "ringed cap, e, e", "attached both ends, we, we"})
    void junctionCageLeavesRunsAlone(String name, String arms, String rings) {
        ConduitBodyKey key = new ConduitBodyKey(TYPE, null, mask(arms), mask(rings));
        assertEquals(ConduitGeometry.body(SHELL.withBorder(1), key), ConduitGeometry.body(CAGED, key), name);
    }

    /**
     * Caged strips never lie over one another, and an arm's lines meet the frame end to end: the arm's strip starts
     * exactly where the frame's strip on the same plane ends.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"corner, es, ''", "corner up, eu, ''", "T, wes, ''", "crossing, nswe, ''", "3D corner, esu, ''",
            "T up, weu, ''", "five-way, unswe, ''", "six-way, dunswe, ''", "ringed T, wes, s", "ringed corner, es, es"})
    void junctionCageStripsNeverOverlap(String name, String arms, String rings) {
        assertNoStripOverlaps(name,
                ConduitGeometry.body(CAGED, new ConduitBodyKey(TYPE, null, mask(arms), mask(rings))));
    }

    /**
     * Every strip of a caged junction lies on the outside of the tube: just behind it is glass, just in front of it is
     * air. A strip inside the glass (a frame on a face an arm leaves from, or one inside the core) would only ever be
     * seen through glass and look like it floats.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"corner, es", "corner up, eu", "T, wes", "crossing, nswe", "3D corner, esu", "T up, weu",
            "five-way, unswe"})
    void junctionCageStripsLieOnTheOutside(String name, String arms) {
        int mask = mask(arms);
        List<AABB> volume = new ArrayList<>();
        volume.add(ConduitShapes.core(TYPE.thickness()));
        for (Direction side : Direction.values()) {
            if (ConduitConnections.isConnected(mask, side)) {
                volume.add(ConduitShapes.arm(TYPE.thickness(), side));
            }
        }
        for (ConduitQuad quad : ConduitGeometry.body(CAGED, new ConduitBodyKey(TYPE, null, mask, 0))) {
            if (isGlass(quad)) {
                continue;
            }
            Direction facing = quad.facing();
            AABB box = quad.box();
            for (double s : new double[]{0.25, 0.75}) {
                for (double t : new double[]{0.25, 0.75}) {
                    double x = box.minX + (box.maxX - box.minX) * (facing.getAxis() == Direction.Axis.Z ? s : t);
                    double y = box.minY + (box.maxY - box.minY) * (facing.getAxis() == Direction.Axis.X ? s : t);
                    double z = box.minZ + (box.maxZ - box.minZ) * (facing.getAxis() == Direction.Axis.Y ? s : t);
                    double step = 0.05;
                    double dx = facing.getStepX() * step;
                    double dy = facing.getStepY() * step;
                    double dz = facing.getStepZ() * step;
                    assertTrue(inside(volume, x - dx, y - dy, z - dz), name + ": no glass behind " + quad);
                    assertFalse(inside(volume, x + dx, y + dy, z + dz), name + ": glass in front of " + quad);
                }
            }
        }
    }

    private static boolean inside(List<AABB> volume, double x, double y, double z) {
        for (AABB box : volume) {
            if (x > box.minX && x < box.maxX && y > box.minY && y < box.maxY && z > box.minZ && z < box.maxZ) {
                return true;
            }
        }
        return false;
    }

    /**
     * Arm strips beside a caged core start where its frame ends, with no gap: the arm's edge is covered once. An arm's
     * side facing into the crease (the way another arm leaves) has no strip.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // name, arms, face, x, y, z, strips: points on the arm just past the core
            "corner crease, es, u, 11.01, 0, 10.5, 1", "corner outer, es, u, 11.01, 0, 5.5, 1",
            "corner side into the crease, es, s, 11.01, 10.5, 0, 0", "T crease, wes, u, 4.99, 0, 10.5, 1",
            "T branch side into the crease, wen, e, 0, 5.5, 2, 0",
            "3D corner under the up arm, esu, u, 11.01, 0, 10.5, 0", "T core face, wes, u, 8, 0, 10.5, 1"})
    void junctionCageMeetsArmLines(String name, String arms, String face, double x, double y, double z, int expected) {
        List<ConduitQuad> quads = ConduitGeometry.body(CAGED, new ConduitBodyKey(TYPE, null, mask(arms), 0));
        assertEquals(expected, covering(quads, side(face.charAt(0)), new double[]{x, y, z}), name);
    }

    /**
     * No strip of a caged junction is ever seen through its glass: from 144 view directions all round, a ray from
     * points on each strip that faces the camera to the camera crosses no glass face that faces the camera. Lines seen
     * only through the neighbouring glass are what looked like floating bars in game.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"corner, es", "corner up, eu", "T, wen", "T sideways, wes", "crossing, nswe", "3D corner, esu",
            "T up, weu", "five-way, unswe", "six-way, dunswe"})
    void junctionCageStripsAreNeverSeenThroughGlass(String name, String arms) {
        List<ConduitQuad> quads = ConduitGeometry.body(CAGED, new ConduitBodyKey(TYPE, null, mask(arms), 0));
        for (int elevation = -75; elevation <= 75; elevation += 30) {
            for (int azimuth = 0; azimuth < 360; azimuth += 15) {
                double e = Math.toRadians(elevation);
                double a = Math.toRadians(azimuth);
                double[] view = {Math.cos(e) * Math.cos(a), Math.sin(e), Math.cos(e) * Math.sin(a)};
                for (ConduitQuad strip : quads) {
                    if (isGlass(strip) || !facesView(strip, view)) {
                        continue;
                    }
                    for (double[] point : samples(strip)) {
                        for (ConduitQuad glass : quads) {
                            assertFalse(isGlass(glass) && facesView(glass, view) && crosses(glass, point, view), name
                                    + ": " + strip + " seen through " + glass + " from " + elevation + "/" + azimuth);
                        }
                    }
                }
            }
        }
    }

    private static boolean facesView(ConduitQuad quad, double[] view) {
        Direction facing = quad.facing();
        return facing.getStepX() * view[0] + facing.getStepY() * view[1] + facing.getStepZ() * view[2] > 1.0E-9;
    }

    /** Points a quarter and three quarters across the quad on its plane. */
    private static List<double[]> samples(ConduitQuad quad) {
        Direction.Axis normal = quad.facing().getAxis();
        double plane = quad.facing().getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? quad.box().max(normal)
                : quad.box().min(normal);
        List<double[]> points = new ArrayList<>();
        for (double s : new double[]{0.25, 0.75}) {
            for (double t : new double[]{0.25, 0.75}) {
                double[] point = new double[3];
                boolean first = true;
                for (Direction.Axis axis : Direction.Axis.values()) {
                    double min = quad.box().min(axis);
                    double max = quad.box().max(axis);
                    double at = axis == normal ? plane : min + (max - min) * (first ? s : t);
                    if (axis != normal) {
                        first = false;
                    }
                    point[axis == Direction.Axis.X ? 0 : axis == Direction.Axis.Y ? 1 : 2] = at;
                }
                points.add(point);
            }
        }
        return points;
    }

    /** Whether the ray from {@code point} along {@code view} passes through the face {@code quad} draws. */
    private static boolean crosses(ConduitQuad quad, double[] point, double[] view) {
        Direction.Axis normal = quad.facing().getAxis();
        double plane = quad.facing().getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? quad.box().max(normal)
                : quad.box().min(normal);
        double along = normal.choose(view[0], view[1], view[2]);
        double t = (plane - normal.choose(point[0], point[1], point[2])) / along;
        if (Math.abs(along) < 1.0E-9 || t <= 1.0E-6) {
            return false;
        }
        for (Direction.Axis axis : Direction.Axis.values()) {
            double at = axis.choose(point[0], point[1], point[2]) + t * axis.choose(view[0], view[1], view[2]);
            if (axis != normal && (at <= quad.box().min(axis) || at >= quad.box().max(axis))) {
                return false;
            }
        }
        return true;
    }

    /** The strips of {@code quads} on {@code facing}'s plane of the core that cover {@code point}. */
    private static int covering(List<ConduitQuad> quads, Direction facing, double[] point) {
        AABB core = ConduitShapes.core(TYPE.thickness());
        int covering = 0;
        for (ConduitQuad quad : quads) {
            if (isGlass(quad) || quad.facing() != facing || Math.abs(plane(quad, core)) > 0.1) {
                continue;
            }
            boolean inside = true;
            for (Direction.Axis axis : Direction.Axis.values()) {
                double at = axis.choose(point[0], point[1], point[2]);
                if (axis != facing.getAxis() && (at <= quad.box().min(axis) || at >= quad.box().max(axis))) {
                    inside = false;
                }
            }
            if (inside) {
                covering++;
            }
        }
        return covering;
    }

    private static void assertNoStripOverlaps(String name, List<ConduitQuad> quads) {
        List<ConduitQuad> strips = quads.stream().filter(quad -> !isGlass(quad)).toList();
        for (int i = 0; i < strips.size(); i++) {
            for (int j = i + 1; j < strips.size(); j++) {
                ConduitQuad a = strips.get(i);
                ConduitQuad b = strips.get(j);
                Direction.Axis normal = a.facing().getAxis();
                if (a.facing() != b.facing() || Math.abs(a.box().min(normal) - b.box().min(normal)) > 1.0E-9) {
                    continue;
                }
                double area = 1;
                for (Direction.Axis axis : Direction.Axis.values()) {
                    if (axis != normal) {
                        area *= Math.max(0, Math.min(a.box().max(axis), b.box().max(axis))
                                - Math.max(a.box().min(axis), b.box().min(axis)));
                    }
                }
                assertTrue(area < 1.0E-9, name + ": " + a + " overlaps " + b);
            }
        }
    }

    /**
     * A core face beside a through run turns its sprite like the run's arms, not like a branch: on a T the top face
     * runs along the straight part, and so does the core's north face between a west-east run and an up branch.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"T, wes, u, w", "T with an up branch, weu, n, w", "crossing branch, wesu, n, w"})
    void coreGlassFollowsTheThroughRun(String name, String arms, String face, String arm) {
        Direction facing = side(face.charAt(0));
        Direction armSide = side(arm.charAt(0));
        AABB core = ConduitShapes.core(TYPE.thickness());
        Integer coreTurns = null;
        Integer armTurns = null;
        for (ConduitQuad quad : ConduitGeometry.body(SHELL, new ConduitBodyKey(TYPE, null, mask(arms), 0))) {
            ConduitUv uv = quad.uv();
            if (!isGlass(quad) || quad.facing() != facing || uv == null) {
                continue;
            }
            if (quad.box().equals(core)) {
                coreTurns = uv.quarterTurns();
            } else if (quad.box().equals(ConduitShapes.arm(TYPE.thickness(), armSide))) {
                armTurns = uv.quarterTurns();
            }
        }
        assertTrue(coreTurns != null && armTurns != null, name);
        assertEquals(armTurns % 2, coreTurns % 2, name + ": the core's rows run along the arm's");
    }

    /**
     * With labels on, a shell's marks stand in front of its strips (a dye letter beside a cap's rim, a mode glyph over
     * the rim band), and the letter is inked for the glass it sits on, whatever the border colour.
     */
    @Test
    void labelsSitInFrontOfStrips() {
        ConduitLook.Shell look = SHELL.withBorderColour(0x202020);
        AABB core = ConduitShapes.core(TYPE.thickness());
        int border = ConduitColours.argb(0x202020, DyeColor.BLACK);
        int letters = 0;
        for (ConduitQuad quad : ConduitGeometry.body(look, new ConduitBodyKey(TYPE, DyeColor.BLACK, mask("e"), 0),
                true)) {
            if (isGlass(quad) || quad.argb() == border) {
                continue;
            }
            letters++;
            assertEquals(ConduitLabels.ink(0xFFFFFFFF), quad.argb(), "ink for the glass: " + quad);
            assertEquals(ConduitGeometry.MARK_LIFT, plane(quad, core), 1.0E-9, quad.toString());
        }
        assertTrue(letters > 0, "a dyed shell shows its letter");
        assertTrue(ConduitGeometry.MARK_LIFT > ConduitGeometry.STRIP_OFFSET);
        int glyphs = 0;
        for (ConduitQuad quad : ConduitGeometry.face(look,
                new ConduitFaceKey(TYPE, Direction.EAST, ConduitFaceMode.INSERT), true)) {
            Direction.Axis normal = quad.facing().getAxis();
            if (quad.box().max(normal) - quad.box().min(normal) > 1.0E-9) {
                continue; // the ring
            }
            glyphs++;
            assertEquals(ConduitGeometry.MARK_LIFT, plane(quad, core), 1.0E-9, quad.toString());
        }
        assertTrue(glyphs > 0, "an insert ring shows its glyph");
    }

    /** How far {@code quad} lies outside the core's face it faces, in pixels. */
    private static double plane(ConduitQuad quad, AABB core) {
        Direction face = quad.facing();
        return face.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? quad.box().max(face.getAxis()) - core.max(face.getAxis())
                : core.min(face.getAxis()) - quad.box().min(face.getAxis());
    }

    private static boolean isGlass(ConduitQuad quad) {
        Identifier sprite = quad.sprite();
        return sprite != null && sprite.equals(GLASS);
    }

    private static int mask(String sides) {
        int mask = 0;
        for (int i = 0; i < sides.length(); i++) {
            mask |= ConduitConnections.bit(side(sides.charAt(i)));
        }
        return mask;
    }

    private static Direction side(char letter) {
        return switch (letter) {
            case 'd' -> Direction.DOWN;
            case 'u' -> Direction.UP;
            case 'n' -> Direction.NORTH;
            case 's' -> Direction.SOUTH;
            case 'w' -> Direction.WEST;
            case 'e' -> Direction.EAST;
            default -> throw new IllegalArgumentException(String.valueOf(letter));
        };
    }
}
