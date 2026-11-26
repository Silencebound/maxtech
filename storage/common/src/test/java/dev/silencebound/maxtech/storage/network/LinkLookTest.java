package dev.silencebound.maxtech.storage.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/**
 * The textures the front line looks are drawn with ({@link LinkLook}): every listed front carries the online line (the
 * Storage accent, x 4 to 11 in the bottom row), which items show and the world draws over for the other looks, and each
 * overlay is transparent but for that line in its look's colour, so it lands in the cutout layer and covers only the
 * line.
 */
class LinkLookTest {
    private static final int ACCENT = 0x3fc3e0;
    private static final int ERROR = 0xe05a5a;
    private static final int OFF = 0x2e3238;

    private static BufferedImage texture(String path) throws IOException {
        String file = "/assets/maxtech_storage/textures/" + path + ".png";
        try (InputStream in = LinkLookTest.class.getResourceAsStream(file)) {
            assertNotNull(in, "missing " + file);
            BufferedImage image = ImageIO.read(in);
            assertNotNull(image, "unreadable " + file);
            assertEquals(16, image.getWidth(), path + " width");
            assertEquals(16, image.getHeight(), path + " height");
            return image;
        }
    }

    private static boolean onLine(int x, int y) {
        return y == 15 && x >= 4 && x <= 11;
    }

    @Test
    void frontsCarryTheOnlineLine() throws IOException {
        for (String path : LinkLook.FRONT_TEXTURES) {
            BufferedImage front = texture(path);
            for (int x = 4; x <= 11; x++) {
                assertEquals(0xff000000 | ACCENT, front.getRGB(x, 15), path + " line at x " + x);
            }
            assertTrue(LinkLook.isFront(Identifier.fromNamespaceAndPath("maxtech_storage", path)), path);
        }
        assertFalse(LinkLook.isFront(Identifier.fromNamespaceAndPath("maxtech_storage", "block/port/side")));
        assertFalse(LinkLook.isFront(Identifier.fromNamespaceAndPath("other", "block/drive/front")));
    }

    @Test
    void overlaysAreOnlyTheLine() throws IOException {
        assertEquals("", LinkLook.ONLINE.overlayTexture(), "online draws no overlay");
        int[] colours = {ERROR, OFF};
        LinkLook[] looks = {LinkLook.NO_CHANNEL, LinkLook.OFFLINE};
        for (int i = 0; i < looks.length; i++) {
            BufferedImage overlay = texture(looks[i].overlayTexture());
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int argb = overlay.getRGB(x, y);
                    if (onLine(x, y)) {
                        assertEquals(0xff000000 | colours[i], argb, looks[i] + " line at x " + x);
                    } else {
                        assertEquals(0, argb >>> 24, looks[i] + " transparent at " + x + ", " + y);
                    }
                }
            }
        }
    }

    @Test
    void namesRoundTrip() {
        for (LinkLook look : LinkLook.values()) {
            assertEquals(look, LinkLook.byName(look.getSerializedName()));
        }
        assertEquals(LinkLook.OFFLINE, LinkLook.byName("unknown"));
    }
}
