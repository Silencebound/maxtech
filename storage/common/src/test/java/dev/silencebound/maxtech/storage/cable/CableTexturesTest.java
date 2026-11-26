package dev.silencebound.maxtech.storage.cable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import javax.imageio.ImageIO;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/**
 * The sprites the cable model draws one texel per pixel ({@link CableGeometry}): the glass sleeve is translucent with
 * its edges in rows 6 and 9, the core cells are closed exactly on the sides {@link CableGeometry#cell} says, and the
 * lane and the covered sleeve are opaque, so only the glass lands in the translucent layer.
 */
class CableTexturesTest {
    private static BufferedImage texture(String name) throws IOException {
        String path = "/assets/maxtech_storage/textures/block/cable/" + name + ".png";
        try (InputStream in = CableTexturesTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing " + path);
            BufferedImage image = ImageIO.read(in);
            assertNotNull(image, "unreadable " + path);
            assertEquals(16, image.getWidth(), name + " width");
            assertEquals(16, image.getHeight(), name + " height");
            return image;
        }
    }

    private static int alpha(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) >>> 24;
    }

    @Test
    void glassSleeveIsTranslucentWithEdgesInRowsSixAndNine() throws IOException {
        BufferedImage sleeve = texture("sleeve");
        for (int x = 0; x < 16; x++) {
            int edge = alpha(sleeve, x, 6);
            int clear = alpha(sleeve, x, 7);
            assertEquals(edge, alpha(sleeve, x, 9), "edges alike at column " + x);
            assertEquals(clear, alpha(sleeve, x, 8), "middle alike at column " + x);
            assertTrue(edge > clear && clear > 0 && edge < 255, "translucent, edges stronger: " + edge + ", " + clear);
        }
    }

    /** Every cell is closed (edge alpha) on the sides whose open bit is clear, and on its corners. */
    @Test
    void coreCellsAreClosedWhereNoArmLeaves() throws IOException {
        for (String name : new String[]{"sleeve_core", "covered_core"}) {
            BufferedImage cells = texture(name);
            int edge = cells.getRGB(0, 0);
            // The middle of the last cell, open all round.
            int fill = cells.getRGB(13, 13);
            assertTrue(edge != fill, name + ": edge and fill alike");
            for (int open = 0; open < 16; open++) {
                int cell = CableGeometry.cell(open);
                int x0 = cell % CableGeometry.CELL * CableGeometry.CELL;
                int y0 = cell / CableGeometry.CELL * CableGeometry.CELL;
                for (int j = 0; j < CableGeometry.CELL; j++) {
                    for (int i = 0; i < CableGeometry.CELL; i++) {
                        boolean closed = (i == 0 && (open & CableGeometry.OPEN_U_MIN) == 0)
                                || (i == 3 && (open & CableGeometry.OPEN_U_MAX) == 0)
                                || (j == 0 && (open & CableGeometry.OPEN_V_MIN) == 0)
                                || (j == 3 && (open & CableGeometry.OPEN_V_MAX) == 0)
                                || ((i == 0 || i == 3) && (j == 0 || j == 3));
                        assertEquals(closed ? edge : fill, cells.getRGB(x0 + i, y0 + j),
                                name + " cell " + open + " texel " + i + "," + j);
                    }
                }
            }
        }
    }

    /**
     * A covered core's cells (look K1) keep the plain cells' edges and carry four pips in their 2 x 2 middle, as many
     * lit (lighter than the sleeve) as the tier and the rest unlit (darker), in every cell; all opaque.
     */
    @Test
    void coveredCoresShowTheTierAsPips() throws IOException {
        BufferedImage plain = texture("covered_core");
        int[][] pips = {{1, 1}, {2, 1}, {1, 2}, {2, 2}};
        for (int tier = 1; tier <= 4; tier++) {
            BufferedImage image = texture("covered_core_tier_" + tier);
            for (int cell = 0; cell < 16; cell++) {
                int x0 = cell % CableGeometry.CELL * CableGeometry.CELL;
                int y0 = cell / CableGeometry.CELL * CableGeometry.CELL;
                int fill = plain.getRGB(x0 + 1, y0 + 1) & 0xFF;
                for (int k = 0; k < pips.length; k++) {
                    int pip = image.getRGB(x0 + pips[k][0], y0 + pips[k][1]);
                    assertEquals(255, pip >>> 24, "opaque");
                    boolean lit = (pip & 0xFF) > fill;
                    assertEquals(k < tier, lit, "tier " + tier + " cell " + cell + " pip " + k);
                }
                for (int j = 0; j < CableGeometry.CELL; j++) {
                    for (int i = 0; i < CableGeometry.CELL; i++) {
                        boolean middle = i >= 1 && i <= 2 && j >= 1 && j <= 2;
                        if (!middle) {
                            assertEquals(plain.getRGB(x0 + i, y0 + j), image.getRGB(x0 + i, y0 + j),
                                    "tier " + tier + " cell " + cell + " edge " + i + "," + j);
                        }
                    }
                }
            }
        }
    }

    /**
     * Every face of a glass cable's lane core shows its tier as that many lit pips (look K1 on every look, owner
     * decision 2026-10-15): read through the faces' own UVs from each tier's lane core sprite, the lit pips are the
     * first in lighting order, lighter than the unlit rest, and all are opaque.
     */
    @Test
    void everyTierShowsThatManyPipsOnEveryLaneCoreFace() throws IOException {
        int arms = 1 << Direction.EAST.get3DDataValue() | 1 << Direction.UP.get3DDataValue();
        List<CableGeometry.Face> faces = CableGeometry.body(CableContent.THICKNESS, arms, 0, true).stream()
                .filter(f -> f.texture() == CableGeometry.Texture.LANE_CORE).toList();
        assertEquals(4, faces.size(), "a lane core face on each closed side");
        int unlit = texture("lane_core_tier_1").getRGB(CableGeometry.PIPS[3][0], CableGeometry.PIPS[3][1]) & 0xFF;
        for (int tier = 1; tier <= 4; tier++) {
            BufferedImage image = texture("lane_core_tier_" + tier);
            for (CableGeometry.Face face : faces) {
                int lit = 0;
                for (int v = (int) face.v0(); v < face.v1(); v++) {
                    for (int u = (int) face.u0(); u < face.u1(); u++) {
                        int texel = image.getRGB(u, v);
                        assertEquals(255, texel >>> 24, "opaque");
                        if ((texel & 0xFF) > unlit) {
                            lit++;
                        }
                    }
                }
                assertEquals(tier, lit, "tier " + tier + " pips on " + face);
            }
            for (int k = 0; k < CableGeometry.PIPS.length; k++) {
                int pip = image.getRGB(CableGeometry.PIPS[k][0], CableGeometry.PIPS[k][1]) & 0xFF;
                assertEquals(k < tier, pip > unlit, "tier " + tier + " pip " + k + " lit in order");
            }
        }
    }

    /** The lanes and the covered sleeve are opaque; the fiber lane's dots sit the same whichever way u runs. */
    @Test
    void lanesAndCoveredSleeveAreOpaque() throws IOException {
        for (String name : new String[]{"lane", "fiber_lane", "covered"}) {
            BufferedImage image = texture(name);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    assertEquals(255, alpha(image, x, y), name + " at " + x + "," + y);
                }
            }
        }
        BufferedImage fiber = texture("fiber_lane");
        int dots = 0;
        for (int x = 0; x < 16; x++) {
            assertEquals(fiber.getRGB(x, 7), fiber.getRGB(15 - x, 7), "fiber lane mirrored at column " + x);
            if (fiber.getRGB(x, 7) != fiber.getRGB(3, 7)) {
                dots++;
            }
        }
        assertEquals(4, dots, "two 2 px dots per block");
    }
}
