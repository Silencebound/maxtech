package dev.silencebound.maxtech.lib.client.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceKey;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceMode;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitBodyKey;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitLayer;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Pins every quad {@link ConduitGeometry} lays out for bodies (all arm masks, some ring masks, dyed and plain, with and
 * without labels) and face decorations, so moving or refactoring the geometry cannot change how a conduit looks. On a
 * mismatch the full dump is written to {@code build/test-work/conduit-geometry-snapshot.txt} for a diff against the old
 * code.
 */
class ConduitGeometrySnapshotTest {
    private static final String SHA256 = "ae09c1a0629957a16572cec81f6d441cef439dbfd79574b6372c34a078802503";

    @Test
    void geometryIsUnchanged() throws IOException, NoSuchAlgorithmException {
        StringBuilder dump = new StringBuilder();
        ConduitLook overlaid = ConduitLook.solid(Identifier.fromNamespaceAndPath("test", "end"),
                Identifier.fromNamespaceAndPath("test", "core"), 0x353529, 0xFF6400);
        for (float thickness : new float[]{0.25F, 0.5F}) {
            ConduitType type = type(thickness);
            for (ConduitLook look : List.of(ConduitLook.wire(), overlaid)) {
                dump.append("look ").append(look).append('\n');
                for (@Nullable
                DyeColor colour : new @Nullable DyeColor[]{null, DyeColor.RED}) {
                    for (int arms = 0; arms < 64; arms++) {
                        for (int rings : new int[]{0, arms, arms & 0b010101}) {
                            for (boolean labels : new boolean[]{false, true}) {
                                ConduitBodyKey key = new ConduitBodyKey(type, colour, arms, rings);
                                dump.append("body ").append(thickness).append(' ').append(colour).append(' ')
                                        .append(arms).append(' ').append(rings).append(' ').append(labels).append('\n');
                                append(dump, ConduitGeometry.body(look, key, labels));
                            }
                        }
                    }
                }
            }
            for (Direction side : Direction.values()) {
                for (ConduitFaceMode mode : ConduitFaceMode.values()) {
                    for (boolean labels : new boolean[]{false, true}) {
                        ConduitFaceKey key = new ConduitFaceKey(type, side, mode);
                        dump.append("face ").append(thickness).append(' ').append(side).append(' ').append(mode)
                                .append(' ').append(labels).append('\n');
                        append(dump, ConduitGeometry.face(ConduitLook.wire(), key, labels));
                    }
                }
            }
        }
        byte[] bytes = dump.toString().getBytes(StandardCharsets.UTF_8);
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!SHA256.equals(actual)) {
            Path out = Path.of("conduit-geometry-snapshot.txt");
            Files.write(out, bytes);
        }
        assertEquals(SHA256, actual, "conduit geometry changed; dump in build/test-work/conduit-geometry-snapshot.txt");
    }

    private static void append(StringBuilder dump, List<ConduitQuad> quads) {
        for (ConduitQuad quad : quads) {
            // Lib's own conduits keep the layer their sprite picks.
            assertEquals(ConduitLayer.AUTO, quad.layer(), quad.toString());
            AABB box = quad.box();
            dump.append(String.format(Locale.ROOT, "  %s %s %s %s %s %s %s %s %08x %b\n", box.minX, box.minY, box.minZ,
                    box.maxX, box.maxY, box.maxZ, quad.facing(), quad.texture(), quad.argb(), quad.culled()));
        }
    }

    private static ConduitType type(float thickness) {
        return new ConduitType(Identifier.fromNamespaceAndPath("test", "net"), "test", 0x4078D8, thickness,
                () -> (ItemLike) () -> {
                    throw new UnsupportedOperationException();
                });
    }
}
