package dev.silencebound.maxtech.lib.client.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.silencebound.maxtech.lib.api.conduit.ConduitContents;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitBodyKey;
import dev.silencebound.maxtech.lib.api.conduit.model.ConduitQuad;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ItemLike;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Pins every quad a {@link ConduitLook.Shell} without frame bars lays out (body and contents, all arm masks, some ring
 * masks), so the frame-bar option cannot change how existing shells look. On a mismatch the full dump is written to
 * {@code build/test-work/conduit-shell-snapshot.txt}.
 */
class ConduitShellSnapshotTest {
    private static final String SHA256 = "01c13c53aedfb194f12f7109d20d738dab520ca906e4c41099e246de67172899";
    private static final String FRAME_SHA256 = "0edccc74ebe1cf77e59e1d6c1c3c636ec362b6f97d9640c6dd83125671949316";
    private static final Identifier GLASS = Identifier.fromNamespaceAndPath("test", "block/glass");
    private static final ConduitContentsGeometry.Paint PAINT = new ConduitContentsGeometry.Paint(
            Identifier.fromNamespaceAndPath("test", "block/still"),
            Identifier.fromNamespaceAndPath("test", "block/flow"), 0xFF3F76E4, 15);

    @Test
    void shellsWithoutFrameBarsAreUnchanged() throws IOException, NoSuchAlgorithmException {
        StringBuilder dump = new StringBuilder();
        List<ConduitLook.Shell> looks = List.of(ConduitLook.shell(GLASS),
                ConduitLook.shell(GLASS).withBorderColour(0x2C3238).withBorder(1).withRim(2).withInset(1));
        int[] contents = {0, ConduitContents.pack(5, 2, null, false), ConduitContents.pack(5, 4, Direction.WEST, true),
                ConduitContents.pack(5, 3, Direction.UP, true)};
        for (float thickness : new float[]{0.25F, 0.375F}) {
            ConduitType type = type(thickness);
            for (int l = 0; l < looks.size(); l++) {
                ConduitLook.Shell look = looks.get(l);
                for (@Nullable
                DyeColor colour : new @Nullable DyeColor[]{null, DyeColor.RED}) {
                    for (int arms = 0; arms < 64; arms++) {
                        for (int rings : new int[]{0, arms, arms & 0b010101}) {
                            for (boolean labels : new boolean[]{false, true}) {
                                dump.append("body ").append(thickness).append(' ').append(l).append(' ').append(colour)
                                        .append(' ').append(arms).append(' ').append(rings).append(' ').append(labels)
                                        .append('\n');
                                append(dump, ConduitGeometry.body(look, new ConduitBodyKey(type, colour, arms, rings),
                                        labels));
                            }
                        }
                    }
                }
                for (int arms = 0; arms < 64; arms++) {
                    for (int packed : contents) {
                        dump.append("contents ").append(thickness).append(' ').append(l).append(' ').append(arms)
                                .append(' ').append(packed).append('\n');
                        append(dump,
                                ConduitContentsGeometry.quads(look, thickness, arms, arms & 0b010101, packed, PAINT));
                    }
                }
            }
        }
        byte[] bytes = dump.toString().getBytes(StandardCharsets.UTF_8);
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!SHA256.equals(actual)) {
            Files.write(Path.of("conduit-shell-snapshot.txt"), bytes);
        }
        assertEquals(SHA256, actual, "shell geometry changed; dump in build/test-work/conduit-shell-snapshot.txt");
    }

    /**
     * Pins the bodies of frame-bar shells without a tier stripe (flat and textured bars, 1 and 2 px, all arm masks,
     * some ring masks), so the stripe option cannot change them.
     */
    @Test
    void frameBarShellsWithoutTierStripeAreUnchanged() throws IOException, NoSuchAlgorithmException {
        StringBuilder dump = new StringBuilder();
        ConduitLook.Shell framed = ConduitLook.shell(GLASS).withBorderColour(0x2C3238).withBorder(1).withFrameBars();
        List<ConduitLook.Shell> looks = List.of(framed, framed.withBorder(2),
                framed.withBarTexture(Identifier.fromNamespaceAndPath("test", "block/bar")));
        for (float thickness : new float[]{0.375F, 0.5F}) {
            ConduitType type = type(thickness);
            for (int l = 0; l < looks.size(); l++) {
                for (int arms = 0; arms < 64; arms++) {
                    for (int rings : new int[]{0, arms, arms & 0b010101}) {
                        dump.append("body ").append(thickness).append(' ').append(l).append(' ').append(arms)
                                .append(' ').append(rings).append('\n');
                        append(dump, ConduitGeometry.body(looks.get(l), new ConduitBodyKey(type, null, arms, rings)));
                    }
                }
            }
        }
        byte[] bytes = dump.toString().getBytes(StandardCharsets.UTF_8);
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(FRAME_SHA256, actual, "frame-bar shell geometry changed");
    }

    private static void append(StringBuilder dump, List<ConduitQuad> quads) {
        for (ConduitQuad quad : quads) {
            dump.append("  ").append(quad).append('\n');
        }
    }

    private static ConduitType type(float thickness) {
        return new ConduitType(Identifier.fromNamespaceAndPath("test", "net"), "test", 0x4078D8, thickness,
                () -> (ItemLike) () -> {
                    throw new UnsupportedOperationException();
                });
    }
}
