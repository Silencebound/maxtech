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
            // name, arms, rings, glass, strips
            "isolated, '', '', 6, 24", "cap, e, '', 9, 16", "straight, we, '', 4, 8", "straight y, du, '', 4, 8",
            "L, es, '', 12, 18", "T, wes, '', 15, 20", "crossing, nswe, '', 18, 32", "six-way, dunswe, '', 24, 48",
            "ringed straight, we, e, 4, 12", "ringed cap, e, e, 9, 20"})
    void quadCounts(String name, String arms, String rings, int glass, int strips) {
        List<ConduitQuad> quads = ConduitGeometry.body(SHELL, new ConduitBodyKey(TYPE, null, mask(arms), mask(rings)));
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
        List<ConduitQuad> strips = ConduitGeometry.body(SHELL, new ConduitBodyKey(TYPE, null, mask(arms), mask(rings)))
                .stream().filter(quad -> !isGlass(quad)).toList();
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
