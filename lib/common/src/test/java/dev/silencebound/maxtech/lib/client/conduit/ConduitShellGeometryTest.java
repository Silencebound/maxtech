package dev.silencebound.maxtech.lib.client.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.conduit.ConduitColours;
import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
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

    private static boolean isGlass(ConduitQuad quad) {
        Identifier sprite = quad.sprite();
        return sprite != null && sprite.equals(GLASS);
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
