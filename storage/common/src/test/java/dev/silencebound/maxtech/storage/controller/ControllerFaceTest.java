package dev.silencebound.maxtech.storage.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/**
 * The controller face's connected-texture mask and glow phase (M7R item 7), and the committed sprites against the
 * design's pixel rules (design 1 "Runic sigil" in the family style 2a, MTX item 2).
 */
class ControllerFaceTest {
    /** One quarter of the sigil, pixels 1 to 7 from the top left; mirrored both ways it fills pixels 1 to 14. */
    private static final String[] QUAD = {"##..#..", "#...#..", "..###.#", "....#.#", "###.#..", "..#...#", "..#.###"};
    /** The 2a frame on open edges. */
    private static final int FRAME = 0x1a1d21;
    private static final int PLATE = 0x1c2026;
    private static final int PLATE2 = 0x22272e;
    private static final String FOLDER = "/assets/maxtech_storage/textures/block/controller/face/";

    private static int bit(Direction side) {
        return ControllerFace.bit(side.getStepX(), side.getStepY(), side.getStepZ());
    }

    private static int bit(Direction side, Direction face) {
        return ControllerFace.bit(side.getStepX() + face.getStepX(), side.getStepY() + face.getStepY(),
                side.getStepZ() + face.getStepZ());
    }

    private static boolean groove(int x, int y) {
        int mx = Math.min(x, 15 - x);
        int my = Math.min(y, 15 - y);
        return mx >= 1 && my >= 1 && QUAD[my - 1].charAt(mx - 1) == '#';
    }

    private static boolean onEdge(int x, int y, int mask) {
        return (y == 0 && (mask & ControllerFace.TOP) != 0) || (x == 15 && (mask & ControllerFace.RIGHT) != 0)
                || (y == 15 && (mask & ControllerFace.BOTTOM) != 0) || (x == 0 && (mask & ControllerFace.LEFT) != 0);
    }

    /** The two middle pixels of a connected (not open) edge, where the sigil lines meet the neighbour's. */
    private static boolean joint(int x, int y, int mask) {
        boolean middleX = x == 7 || x == 8;
        boolean middleY = y == 7 || y == 8;
        return (y == 0 && middleX && (mask & ControllerFace.TOP) == 0)
                || (y == 15 && middleX && (mask & ControllerFace.BOTTOM) == 0)
                || (x == 0 && middleY && (mask & ControllerFace.LEFT) == 0)
                || (x == 15 && middleY && (mask & ControllerFace.RIGHT) == 0);
    }

    /** The 8 px accent line, cut into an open bottom edge. */
    private static boolean accentLine(int x, int y, int mask) {
        return y == 15 && x >= 4 && x <= 11 && (mask & ControllerFace.BOTTOM) != 0;
    }

    private static BufferedImage sprite(String name) throws IOException {
        try (InputStream in = ControllerFaceTest.class.getResourceAsStream(FOLDER + name + ".png")) {
            assertNotNull(in, name);
            return ImageIO.read(in);
        }
    }

    @Test
    void loneBlockFramesEveryEdge() {
        for (Direction face : Direction.values()) {
            assertEquals(ControllerFace.ALL, ControllerFace.edges(face, 0), face.getName());
        }
    }

    /** All 16 neighbour combinations in each face's plane: the frame stays only on the sides without a neighbour. */
    @Test
    void frameOnlyOnOpenSides() {
        for (Direction face : Direction.values()) {
            for (int present = 0; present < 16; present++) {
                int occupancy = 0;
                for (int i = 0; i < 4; i++) {
                    if ((present & (1 << i)) != 0) {
                        occupancy |= bit(ControllerFace.edge(face, i));
                    }
                }
                // Blocks behind the face, and diagonal ones that cover no neighbour's face, change nothing.
                occupancy |= bit(face.getOpposite());
                assertEquals(ControllerFace.ALL & ~present, ControllerFace.edges(face, occupancy),
                        face.getName() + " neighbours " + present);
            }
        }
    }

    /** A neighbour whose own face on this side is covered by a controller block is an inner corner: the frame stays. */
    @Test
    void coveredNeighbourKeepsTheFrame() {
        for (Direction face : Direction.values()) {
            for (int i = 0; i < 4; i++) {
                Direction side = ControllerFace.edge(face, i);
                int open = ControllerFace.edges(face, bit(side));
                assertEquals(0, open & (1 << i), face.getName() + " joined to the " + side.getName());
                int covered = ControllerFace.edges(face, bit(side) | bit(side, face));
                assertEquals(1 << i, covered & (1 << i), face.getName() + " inner corner to the " + side.getName());
            }
        }
    }

    /**
     * The edge table matches the full-face UVs of vanilla's {@code FaceBakery.defaultFaceUV}: edges lie in the face's
     * plane, right x down points into the block (u right, v down seen from outside), and sides have their top up.
     */
    @Test
    void edgesFollowTheTextureOrientation() {
        for (Direction face : Direction.values()) {
            Direction top = ControllerFace.edge(face, 0);
            Direction right = ControllerFace.edge(face, 1);
            Direction bottom = ControllerFace.edge(face, 2);
            Direction left = ControllerFace.edge(face, 3);
            assertEquals(top.getOpposite(), bottom, face.getName());
            assertEquals(right.getOpposite(), left, face.getName());
            assertTrue(top.getAxis() != face.getAxis() && right.getAxis() != face.getAxis(), face.getName());
            int cx = right.getStepY() * bottom.getStepZ() - right.getStepZ() * bottom.getStepY();
            int cy = right.getStepZ() * bottom.getStepX() - right.getStepX() * bottom.getStepZ();
            int cz = right.getStepX() * bottom.getStepY() - right.getStepY() * bottom.getStepX();
            Direction opposite = face.getOpposite();
            assertEquals(opposite.getStepX(), cx, face.getName());
            assertEquals(opposite.getStepY(), cy, face.getName());
            assertEquals(opposite.getStepZ(), cz, face.getName());
            if (face.getAxis().isHorizontal()) {
                assertEquals(Direction.UP, top, face.getName());
            }
        }
        assertEquals(Direction.NORTH, ControllerFace.edge(Direction.UP, 0));
        assertEquals(Direction.SOUTH, ControllerFace.edge(Direction.DOWN, 0));
    }

    @Test
    void centreRoundTrips() {
        int packed = ControllerFace.packCentre(-3, 0, 127);
        assertEquals(-3, ControllerFace.centreAxis(packed, 0));
        assertEquals(0, ControllerFace.centreAxis(packed, 1));
        assertEquals(127, ControllerFace.centreAxis(packed, 2));
        assertEquals(-127, ControllerFace.centreAxis(ControllerFace.packCentre(-400, 0, 0), 0));
        assertEquals(0, ControllerFace.packCentre(0, 0, 0));
    }

    /** A lone block's faces share one phase; along a row, faces further from the centre start later. */
    @Test
    void phaseGrowsOutward() {
        for (Direction face : Direction.values()) {
            assertEquals(1, ControllerFace.phase(0, face), face.getName());
        }
        // A row of four blocks along x: the east end block sits 1.5 blocks east of the centre (-3 half blocks).
        int end = ControllerFace.packCentre(-3, 0, 0);
        int inner = ControllerFace.packCentre(-1, 0, 0);
        assertEquals(4, ControllerFace.phase(end, Direction.EAST));
        assertTrue(ControllerFace.phase(inner, Direction.UP) < ControllerFace.phase(end, Direction.UP));
        assertTrue(ControllerFace.phase(end, Direction.UP) < ControllerFace.phase(end, Direction.EAST));
        for (int dx = -127; dx <= 127; dx++) {
            int phase = ControllerFace.phase(ControllerFace.packCentre(dx, dx, -dx), Direction.NORTH);
            assertTrue(phase >= 0 && phase < ControllerFace.PHASES, "phase " + phase);
        }
    }

    /**
     * Every plate: the sigil, the accent line on an open bottom edge and the joints on connected edges cut out, the 2a
     * frame exactly on the rest of the open edges, the darker plate everywhere else (no light grey left).
     */
    @Test
    void platesFollowTheDesign() throws IOException {
        for (int mask = 0; mask < 16; mask++) {
            BufferedImage plate = sprite("plate_" + mask);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int argb = plate.getRGB(x, y);
                    String at = "plate_" + mask + " at " + x + "," + y;
                    boolean cut = groove(x, y) || joint(x, y, mask) || accentLine(x, y, mask);
                    assertEquals(cut, (argb >>> 24) == 0, at + " cut out");
                    if (cut) {
                        continue;
                    }
                    assertEquals(0xFF, argb >>> 24, at + " opaque");
                    int rgb = argb & 0xFFFFFF;
                    if (onEdge(x, y, mask)) {
                        assertEquals(FRAME, rgb, at);
                    } else {
                        assertTrue(rgb == PLATE || rgb == PLATE2, at + " plate colour");
                    }
                }
            }
        }
    }

    /** The sigil is four-way mirrored, so it carries across the seams between blocks. */
    @Test
    void sigilIsMirrorSymmetric() {
        int grooves = 0;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                assertEquals(groove(x, y), groove(15 - x, y));
                assertEquals(groove(x, y), groove(x, 15 - y));
                grooves += groove(x, y) ? 1 : 0;
                if (x == 0 || y == 0 || x == 15 || y == 15) {
                    assertTrue(!groove(x, y), "no groove under the edge");
                }
            }
        }
        assertTrue(groove(7, 7) && groove(8, 8), "the centre glows");
        assertTrue(grooves > 0);
    }

    /**
     * Each glow phase is the same 16-frame strip started two frames later per phase; the lit pixels (sigil, accent line
     * rows, edge joints) are mirror-symmetric in every frame.
     */
    @Test
    void glowPhasesAreShiftedStrips() throws IOException {
        for (String status : new String[]{"online", "error"}) {
            BufferedImage first = sprite("glow_" + status + "_0");
            assertEquals(16 * 16, first.getHeight(), status);
            for (int phase = 0; phase < ControllerFace.PHASES; phase++) {
                BufferedImage strip = sprite("glow_" + status + "_" + phase);
                for (int frame = 0; frame < 16; frame++) {
                    int from = Math.floorMod(frame - 2 * phase, 16);
                    for (int y = 0; y < 16; y++) {
                        for (int x = 0; x < 16; x++) {
                            int argb = strip.getRGB(x, frame * 16 + y);
                            String at = status + " phase " + phase + " frame " + frame + " at " + x + "," + y;
                            assertEquals(first.getRGB(x, from * 16 + y), argb, at);
                            assertEquals(0xFF, argb >>> 24, at + " opaque");
                            if (groove(x, y) || joint(x, y, 0) || accentLine(x, y, ControllerFace.ALL)) {
                                assertEquals(argb, strip.getRGB(15 - x, frame * 16 + y), at + " mirrored");
                                assertEquals(argb, strip.getRGB(x, frame * 16 + 15 - y), at + " mirrored");
                            }
                        }
                    }
                }
            }
        }
        assertEquals(16, sprite("glow_off").getHeight());
    }
}
