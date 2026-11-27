package dev.silencebound.maxtech.lib.client.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.conduit.ConduitColours;
import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceKey;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceMode;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitBodyKey;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitTexture;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class ConduitGeometryTest {
    private static final int EAST_WEST = ConduitConnections.bit(Direction.EAST)
            | ConduitConnections.bit(Direction.WEST);
    /** Thicknesses from a thin cable to a thick one. */
    private static final float[] THICKNESSES = {0.125F, 0.1875F, 0.25F, 0.375F, 0.5F, 0.75F};

    /** Every conduit is drawn at its type's thickness: the body's faces span exactly that width across the run. */
    @Test
    void solidBodiesFollowTheirThickness() {
        for (float thickness : THICKNESSES) {
            ConduitType type = type(thickness, 0x808080);
            List<ConduitQuad> quads = ConduitGeometry.body(ConduitLook.wire(),
                    new ConduitBodyKey(type, null, EAST_WEST, 0));
            AABB bounds = bounds(quads);
            assertEquals(thickness * 16, bounds.getYsize(), 1.0E-6, "thickness " + thickness);
            assertEquals(thickness * 16, bounds.getZsize(), 1.0E-6, "thickness " + thickness);
            assertEquals(16.0, bounds.getXsize(), 1.0E-6, "the run reaches both faces: " + thickness);
            assertEquals(8.0, bounds.getCenter().y, 1.0E-6, "centred: " + thickness);
        }
    }

    /**
     * A solid conduit is one flat colour (no rim or seam texture) with the end sprite only on the open ends, which sit
     * on the block face and are culled against a solid neighbour; a ringed arm has no end of its own.
     */
    @Test
    void solidBodiesAreFlatWithEndsOnlyAtOpenEnds() {
        ConduitType type = type(0.5F, 0x336699);
        int east = ConduitConnections.bit(Direction.EAST);
        List<ConduitQuad> open = ConduitGeometry.body(ConduitLook.wire(), new ConduitBodyKey(type, null, EAST_WEST, 0));
        int argb = ARGB.opaque(0x336699);
        for (ConduitQuad quad : open) {
            assertEquals(argb, quad.argb(), "one colour on every face");
            if (quad.texture() == ConduitTexture.END) {
                assertTrue(quad.culled(), "ends lie on the block face");
                assertEquals(Direction.Axis.X, quad.facing().getAxis());
            } else {
                assertEquals(ConduitTexture.FLAT, quad.texture());
                assertFalse(quad.culled());
            }
        }
        assertEquals(2, count(open, ConduitTexture.END), "both open ends");
        List<ConduitQuad> ringed = ConduitGeometry.body(ConduitLook.wire(),
                new ConduitBodyKey(type, null, EAST_WEST, east));
        assertEquals(1, count(ringed, ConduitTexture.END), "the ring covers the east end");
        List<ConduitQuad> dyed = ConduitGeometry.body(ConduitLook.wire(),
                new ConduitBodyKey(type, DyeColor.RED, EAST_WEST, 0));
        assertEquals(ConduitColours.argb(0x336699, DyeColor.RED), dyed.getFirst().argb(), "dye pulls the colour");
    }

    /** A cable's end carries its wire core as an overlay in its own colour, which dyes leave alone. */
    @Test
    void overlaysFollowTheirEnds() {
        ConduitType type = type(0.5F, 0x112233);
        ConduitLook look = ConduitLook.solid(Identifier.fromNamespaceAndPath("test", "end"),
                Identifier.fromNamespaceAndPath("test", "core"), 0x353529, 0xFF6400);
        List<ConduitQuad> quads = ConduitGeometry.body(look, new ConduitBodyKey(type, DyeColor.BLUE, EAST_WEST, 0));
        assertEquals(2, count(quads, ConduitTexture.OVERLAY));
        for (ConduitQuad quad : quads) {
            if (quad.texture() == ConduitTexture.OVERLAY) {
                assertEquals(ARGB.opaque(0xFF6400), quad.argb());
            } else {
                assertEquals(ConduitColours.argb(0x353529, DyeColor.BLUE), quad.argb(), "the look's own colour");
            }
        }
    }

    /**
     * Rings are a solid collar in the face mode's colour whose outer face is culled like a block face; a disabled face
     * draws a stub without its face towards the core.
     */
    @Test
    void ringsTakeTheModeColour() {
        ConduitType type = type(0.5F, 0x4078D8);
        ConduitFaceKey key = new ConduitFaceKey(type, Direction.EAST, ConduitFaceMode.INSERT);
        int insert = ConduitColours.faceArgb(ConduitFaceMode.INSERT);
        List<ConduitQuad> ring = ConduitGeometry.face(ConduitLook.wire(), key);
        assertEquals(6, ring.size(), "a solid collar");
        for (ConduitQuad quad : ring) {
            assertEquals(insert, quad.argb());
            assertEquals(quad.facing() == Direction.EAST, quad.culled(), quad.toString());
        }
        ConduitFaceKey stub = new ConduitFaceKey(type, Direction.EAST, ConduitFaceMode.DISABLED);
        List<ConduitQuad> stubQuads = ConduitGeometry.face(ConduitLook.wire(), stub);
        assertEquals(5, stubQuads.size(), "a stub, without its face towards the core");
        for (ConduitQuad quad : stubQuads) {
            assertEquals(ConduitColours.faceArgb(ConduitFaceMode.DISABLED), quad.argb());
            assertFalse(quad.culled());
        }
    }

    private static int count(List<ConduitQuad> quads, ConduitTexture texture) {
        int n = 0;
        for (ConduitQuad quad : quads) {
            if (quad.texture() == texture) {
                n++;
            }
        }
        return n;
    }

    private static AABB bounds(List<ConduitQuad> quads) {
        AABB bounds = quads.getFirst().box();
        for (ConduitQuad quad : quads) {
            bounds = bounds.minmax(quad.box());
        }
        return bounds;
    }

    private static ConduitType type(float thickness, int colour) {
        return new ConduitType(Identifier.fromNamespaceAndPath("test", "net"), "test", colour, thickness,
                () -> (ItemLike) () -> {
                    throw new UnsupportedOperationException();
                });
    }
}
