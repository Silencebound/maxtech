package dev.silencebound.maxtech.storage.cable;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/**
 * Pins every face {@link CableGeometry} lays out for cable bodies (all arm masks, some part masks, glass and covered)
 * and connectors, so changes to Lib's conduit geometry cannot change how a cable looks. On a mismatch the full dump is
 * written to {@code build/test-work/cable-geometry-snapshot.txt} for a diff against the old code.
 */
class CableGeometrySnapshotTest {
    private static final String SHA256 = "14c75d8cb9aecc2118d222e93d098be6f36b680ac3066a77977a36a9dd9191bf";

    @Test
    void geometryIsUnchanged() throws IOException, NoSuchAlgorithmException {
        StringBuilder dump = new StringBuilder();
        float thickness = CableContent.THICKNESS;
        for (boolean glass : new boolean[]{false, true}) {
            for (int arms = 0; arms < 64; arms++) {
                for (int parts : new int[]{0, ~arms & 0b111111, ~arms & 0b101010}) {
                    dump.append("body ").append(arms).append(' ').append(parts).append(' ').append(glass).append('\n');
                    append(dump, CableGeometry.body(thickness, arms, parts, glass));
                }
            }
            for (Direction side : Direction.values()) {
                for (double depth : new double[]{0, 1, 2.5, 4, 8}) {
                    dump.append("connector ").append(side).append(' ').append(depth).append(' ').append(glass)
                            .append('\n');
                    append(dump, CableGeometry.connector(thickness, side, depth, glass));
                }
            }
        }
        byte[] bytes = dump.toString().getBytes(StandardCharsets.UTF_8);
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!SHA256.equals(actual)) {
            Path out = Path.of("cable-geometry-snapshot.txt");
            Files.write(out, bytes);
        }
        assertEquals(SHA256, actual, "cable geometry changed; dump in build/test-work/cable-geometry-snapshot.txt");
    }

    private static void append(StringBuilder dump, List<CableGeometry.Face> faces) {
        for (CableGeometry.Face face : faces) {
            AABB box = face.box();
            dump.append(String.format(Locale.ROOT, "  %s %s %s %s %s %s %s %s %s %s %s %s %b %b%n", box.minX, box.minY,
                    box.minZ, box.maxX, box.maxY, box.maxZ, face.facing(), face.texture(), face.u0(), face.v0(),
                    face.u1(), face.v1(), face.rotated(), face.culled()));
        }
    }
}
