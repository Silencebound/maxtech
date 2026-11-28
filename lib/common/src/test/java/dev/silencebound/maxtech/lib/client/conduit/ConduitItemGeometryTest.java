package dev.silencebound.maxtech.lib.client.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/** A shell conduit's item piece ({@link ConduitItemGeometry}): a straight run across the block, both ends closed. */
class ConduitItemGeometryTest {
    private static final Identifier GLASS = Identifier.fromNamespaceAndPath("test", "block/glass");
    private static final int BORDER = 0x2C3238;
    private static final int STRIPE = 0x16181A;
    private static final double EPS = 1.0E-4;
    /** The lab tube's look: half a block thick, 1 px frame bars, a tier stripe. */
    private static final ConduitLook.Shell LAB = ConduitLook.shell(GLASS).withBorderColour(BORDER).withBorder(1)
            .withFrameBars().withTierStripe(STRIPE);

    /**
     * 6 glass panes (4 sides, 2 ends), 4 long bars of 6 faces, 4 end bars of 4 faces at each end, 4 stripes on each
     * side pane and each end pane; the halves joined at the centre, so nothing is split there.
     */
    @Test
    void labTubePieceHasOneFacePerPart() {
        List<ConduitQuad> piece = ConduitItemGeometry.shellPiece(LAB, type(0.5F));
        long glass = piece.stream().filter(q -> Objects.equals(GLASS, q.sprite())).count();
        long bars = piece.stream().filter(q -> q.argb() == (0xFF000000 | BORDER)).count();
        long stripes = piece.stream().filter(q -> q.argb() == (0xFF000000 | STRIPE)).count();
        assertEquals(6, glass, "glass");
        assertEquals(4 * 6 + 2 * 4 * 4, bars, "bar faces");
        assertEquals(4 * 4 + 2 * 4, stripes, "stripes");
        assertEquals(glass + bars + stripes, piece.size(), "nothing else");
    }

    @Test
    void pieceSpansTheBlockAndIsMirrorSymmetric() {
        List<ConduitQuad> piece = ConduitItemGeometry.shellPiece(LAB, type(0.5F));
        double min = 16;
        double max = 0;
        Set<String> faces = new HashSet<>();
        Set<String> mirrored = new HashSet<>();
        for (ConduitQuad quad : piece) {
            AABB box = quad.box();
            min = Math.min(min, box.minX);
            max = Math.max(max, box.maxX);
            assertTrue(box.minY >= 0 && box.maxY <= 16 && box.minZ >= 0 && box.maxZ <= 16, "inside the block");
            assertTrue(!quad.culled(), "an item face is never culled");
            faces.add(key(quad.facing(), box.minX, box.maxX, box, quad.argb()));
            Direction facing = quad.facing().getAxis() == Direction.Axis.X
                    ? quad.facing().getOpposite()
                    : quad.facing();
            mirrored.add(key(facing, 16 - box.maxX, 16 - box.minX, box, quad.argb()));
        }
        assertEquals(0, min, EPS);
        assertEquals(16, max, EPS);
        assertEquals(faces, mirrored, "east and west ends alike");
    }

    @Test
    void glassUvsAreProjectedFromWhereTheFaceEndsUp() {
        for (ConduitQuad quad : ConduitItemGeometry.shellPiece(LAB.withBarTexture(GLASS), type(0.5F))) {
            if (quad.uv() != null) {
                assertEquals(ConduitUvs.projected(quad.box(), quad.facing(), quad.uv().quarterTurns()), quad.uv());
            }
        }
    }

    /** Thinner tubes and plain bars join at the centre too: no face ends there. */
    @Test
    void noFaceEndsAtTheCentre() {
        for (ConduitLook.Shell look : List.of(LAB, LAB.withTierStripe(java.util.Optional.empty()))) {
            for (float thickness : new float[]{0.375F, 0.5F, 0.625F}) {
                for (ConduitQuad quad : ConduitItemGeometry.shellPiece(look, type(thickness))) {
                    assertTrue(quad.facing().getAxis() == Direction.Axis.X || Math.abs(quad.box().maxX - 8) > EPS,
                            () -> "split at the centre: " + quad);
                }
            }
        }
    }

    private static String key(Direction facing, double minX, double maxX, AABB box, int argb) {
        return Objects.toString(facing) + String.format(" %.3f %.3f %.3f %.3f %.3f %.3f %08x", minX, maxX, box.minY,
                box.maxY, box.minZ, box.maxZ, argb);
    }

    private static ConduitType type(float thickness) {
        return new ConduitType(Identifier.fromNamespaceAndPath("test", "net"), "test", 0x4078D8, thickness,
                () -> (ItemLike) () -> {
                    throw new UnsupportedOperationException();
                });
    }
}
