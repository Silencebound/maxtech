package dev.silencebound.maxtech.storage.drive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.storage.cell.CellBytes;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * The drive's flush bays (MTX, owner 2026-10-17) as the drive model draws them: the front texture with each slot's cell
 * sprite laid over it through {@link DriveBays}' window, the way the model's copy of the front quad samples it. Every
 * occupied bay shows the cell over its whole 5 x 2 (the light at the end facing the divider in the band's colour, an
 * inset body with a lighter top row) and nothing outside the bay changes; the window never leaves the sprite.
 */
class DriveBaysTest {
    private static final int CELL_BODY = 0x737c87;
    private static final int CELL_TOP = 0xa3acb6;
    private static final int SCREEN = 0x16191d;
    private static final int[] BANDS = {CellBytes.BAND_LOW, CellBytes.BAND_HIGH, CellBytes.BAND_FULL,
            DriveBlockEntity.BAND_INACTIVE};
    /** The light per band above: the Storage accent, amber, red, off grey. */
    private static final int[] LIGHTS = {0x3fc3e0, 0xf0aa3c, 0xe05a5a, 0x2e3238};

    private static BufferedImage texture(String path, int size) throws IOException {
        String file = "/assets/maxtech_storage/textures/" + path + ".png";
        try (InputStream in = DriveBaysTest.class.getResourceAsStream(file)) {
            assertNotNull(in, "missing " + file);
            BufferedImage image = ImageIO.read(in);
            assertNotNull(image, "unreadable " + file);
            assertEquals(size, image.getWidth(), path + " width");
            assertEquals(size, image.getHeight(), path + " height");
            return image;
        }
    }

    @Test
    void emptyBaysAreScreens() throws IOException {
        BufferedImage front = texture("block/drive/front", 16);
        for (int slot = 0; slot < DriveBlockEntity.SLOTS; slot++) {
            int column = DriveBays.column(slot);
            int row = DriveBays.row(slot);
            for (int y = row; y < row + DriveBays.CELL_HEIGHT; y++) {
                for (int x = column; x < column + DriveBays.CELL_WIDTH; x++) {
                    assertEquals(0xff000000 | SCREEN, front.getRGB(x, y), "slot " + slot + " at " + x + ", " + y);
                }
            }
        }
    }

    @Test
    void occupiedBaysShowAFlushCellWithItsLightInside() throws IOException {
        for (int b = 0; b < BANDS.length; b++) {
            String path = DriveBays.cellTexture(BANDS[b]);
            BufferedImage cell = texture(path, DriveBays.SPRITE);
            for (int slot = 0; slot < DriveBlockEntity.SLOTS; slot++) {
                int column = DriveBays.column(slot);
                int row = DriveBays.row(slot);
                int light = slot % 2 == 0 ? column + DriveBays.CELL_WIDTH - 1 : column;
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        int u = (int) Math.floor(DriveBays.cellU(slot, (x + 0.5f) / 16) * DriveBays.SPRITE);
                        int v = (int) Math.floor(DriveBays.cellV(slot, (y + 0.5f) / 16) * DriveBays.SPRITE);
                        int argb = cell.getRGB(u, v);
                        String at = path + " slot " + slot + " at " + x + ", " + y;
                        boolean bay = x >= column && x < column + DriveBays.CELL_WIDTH && y >= row
                                && y < row + DriveBays.CELL_HEIGHT;
                        if (!bay) {
                            assertEquals(0, argb >>> 24, at + " draws outside its bay");
                        } else if (x == light) {
                            assertEquals(0xff000000 | LIGHTS[b], argb, at + " light");
                        } else {
                            assertEquals(0xff000000 | (y == row ? CELL_TOP : CELL_BODY), argb, at + " body");
                        }
                    }
                }
            }
        }
    }

    @Test
    void windowStaysOnTheSprite() {
        float[] edges = {0, 1};
        for (int slot = 0; slot < DriveBlockEntity.SLOTS; slot++) {
            for (float e : edges) {
                float u = DriveBays.cellU(slot, e);
                float v = DriveBays.cellV(slot, e);
                assertTrue(u >= 0 && u <= 1, "slot " + slot + " u " + u);
                assertTrue(v >= 0 && v <= 1, "slot " + slot + " v " + v);
            }
        }
    }

    @Test
    void emptySlotsDrawNoCell() {
        assertEquals("", DriveBays.cellTexture(DriveBlockEntity.BAND_EMPTY));
        assertEquals("", DriveBays.cellTexture(7));
    }
}
