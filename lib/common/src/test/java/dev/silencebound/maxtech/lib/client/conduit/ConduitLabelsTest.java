package dev.silencebound.maxtech.lib.client.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceKey;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceMode;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.conduit.ConduitRenderData;
import dev.silencebound.maxtech.lib.info.ConduitInfo;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class ConduitLabelsTest {
    private static final float CABLE = 0.25F;
    private static final int NORTH_SOUTH = ConduitConnections.bit(Direction.NORTH)
            | ConduitConnections.bit(Direction.SOUTH);

    /** Every dye has its own letter, and every letter its own glyph, so no two dyes look alike. */
    @Test
    void everyDyeHasItsOwnLetterAndGlyph() {
        Set<Character> letters = new HashSet<>();
        Set<Integer> glyphs = new HashSet<>();
        for (DyeColor colour : DyeColor.values()) {
            char letter = ConduitLabels.dyeLetter(colour);
            int glyph = ConduitLabels.letterBits(letter);
            assertNotEquals(0, glyph, "glyph for " + letter);
            assertTrue(letters.add(letter), "letter " + letter + " used twice");
            assertTrue(glyphs.add(glyph), "glyph of " + letter + " used twice");
        }
    }

    @Test
    void everyActiveModeHasItsOwnGlyph() {
        Set<Integer> glyphs = new HashSet<>();
        for (ConduitFaceMode mode : List.of(ConduitFaceMode.INSERT, ConduitFaceMode.EXTRACT, ConduitFaceMode.BOTH)) {
            int glyph = ConduitLabels.modeBits(mode);
            assertNotEquals(0, glyph, mode.name());
            assertTrue(glyphs.add(glyph), mode.name());
        }
        assertEquals(0, ConduitLabels.modeBits(ConduitFaceMode.DISABLED), "the stub's shape says disabled");
    }

    @Test
    void inkContrastsWithTheColourBelow() {
        assertEquals(ConduitLabels.DARK_INK, ConduitLabels.ink(0xFFFFFFFF));
        assertEquals(ConduitLabels.DARK_INK, ConduitLabels.ink(0xFFFED83D));
        assertEquals(ConduitLabels.LIGHT_INK, ConduitLabels.ink(0xFF1D1D21));
        assertEquals(ConduitLabels.LIGHT_INK, ConduitLabels.ink(0xFF3C44AA));
    }

    /** A north-south run shows its letter on the four open core faces, one pane per inked cell, inside each face. */
    @Test
    void letterSitsOnEveryOpenCoreFace() {
        List<ConduitQuad> quads = new ArrayList<>();
        ConduitLabels.addDyeLetter(quads, CABLE, NORTH_SOUTH, DyeColor.LIME, ConduitLabels.DARK_INK);
        int cells = Integer.bitCount(ConduitLabels.letterBits('L'));
        assertEquals(4 * cells, quads.size());
        for (ConduitQuad quad : quads) {
            assertTrue(quad.facing().getAxis() != Direction.Axis.Z, "no letter where an arm leaves: " + quad);
            AABB box = quad.box();
            // The core runs from 6 to 10 px; a pane lies just in front of its face, inside its edges.
            double plane = 8 + 2 * stepOf(quad.facing()) + ConduitLabels.LIFT * stepOf(quad.facing());
            assertEquals(plane, coordinate(box, quad.facing()), 1.0E-9, quad.toString());
            assertTrue(box.minX >= 6 - 1.0E-9 || quad.facing().getAxis() == Direction.Axis.X, quad.toString());
            assertTrue(box.maxX <= 10 + 1.0E-9 || quad.facing().getAxis() == Direction.Axis.X, quad.toString());
            assertTrue(box.minY >= 6 - 1.0E-9 || quad.facing().getAxis() == Direction.Axis.Y, quad.toString());
            assertTrue(box.maxY <= 10 + 1.0E-9 || quad.facing().getAxis() == Direction.Axis.Y, quad.toString());
            assertEquals(ConduitLabels.DARK_INK, quad.argb());
        }
    }

    /** The letter reads the right way round: L's stem on the viewer's left, its foot at the bottom. */
    @Test
    void letterIsUprightAndNotMirrored() {
        List<ConduitQuad> quads = new ArrayList<>();
        int allButEast = 0b111111 & ~ConduitConnections.bit(Direction.EAST);
        ConduitLabels.addDyeLetter(quads, CABLE, allButEast, DyeColor.LIME, ConduitLabels.DARK_INK);
        // Seen from the east, looking west, the viewer's left is south: high z.
        double maxZ = quads.stream().mapToDouble(quad -> quad.box().maxZ).max().orElseThrow();
        double minY = quads.stream().mapToDouble(quad -> quad.box().minY).min().orElseThrow();
        long stem = quads.stream().filter(quad -> quad.box().maxZ > maxZ - 1.0E-9).count();
        long foot = quads.stream().filter(quad -> quad.box().minY < minY + 1.0E-9).count();
        assertEquals(5, stem, "stem");
        assertEquals(3, foot, "foot");
    }

    /** Insert points into the block (one cell at the tip next to it), extract out of it (two cells there). */
    @Test
    void modeArrowsPointAlongTheArm() {
        assertEquals(1, cellsNearestTheBlock(ConduitFaceMode.INSERT));
        assertEquals(2, cellsNearestTheBlock(ConduitFaceMode.EXTRACT));
    }

    @Test
    void modeMarkStaysBetweenItsStartAndTheCore() {
        List<ConduitQuad> quads = new ArrayList<>();
        ConduitLabels.addModeMark(quads, CABLE, Direction.EAST, ConduitFaceMode.BOTH, 1.25, 0xFF4CB43C);
        assertEquals(4 * Integer.bitCount(ConduitLabels.modeBits(ConduitFaceMode.BOTH)), quads.size());
        double end = ConduitLabels.modeMarkEnd(CABLE, 1.25);
        for (ConduitQuad quad : quads) {
            assertTrue(quad.box().maxX <= 16 - 1.25 + 1.0E-9, quad.toString());
            assertTrue(quad.box().minX >= 16 - end - 1.0E-9, quad.toString());
            assertTrue(quad.box().minX >= 10, "on the arm, not the core: " + quad);
        }
        List<ConduitQuad> none = new ArrayList<>();
        ConduitLabels.addModeMark(none, CABLE, Direction.EAST, ConduitFaceMode.DISABLED, 1.25, 0xFF7A7A7A);
        assertEquals(List.of(), none);
    }

    @Test
    void labelledGeometryAddsMarksOnlyWhenAsked() {
        ConduitType type = type();
        ConduitBodyKey body = new ConduitBodyKey(type, DyeColor.RED, NORTH_SOUTH, 0);
        assertEquals(ConduitGeometry.body(ConduitLook.wire(), body),
                ConduitGeometry.body(ConduitLook.wire(), body, false));
        assertTrue(ConduitGeometry.body(ConduitLook.wire(), body, true).size() > ConduitGeometry
                .body(ConduitLook.wire(), body).size());
        ConduitBodyKey plain = new ConduitBodyKey(type, null, NORTH_SOUTH, 0);
        assertEquals(ConduitGeometry.body(ConduitLook.wire(), plain),
                ConduitGeometry.body(ConduitLook.wire(), plain, true), "an uncoloured conduit has no letter");
        ConduitFaceKey ring = new ConduitFaceKey(type, Direction.EAST, ConduitFaceMode.INSERT);
        assertTrue(ConduitGeometry.face(ConduitLook.wire(), ring, true).size() > ConduitGeometry
                .face(ConduitLook.wire(), ring).size());
    }

    /** The Jade text names the dye colour and each connector's mode while the option is on, and adds nothing off. */
    @Test
    void infoLinesNameTheColourAndModesWhenOn() {
        ConduitType type = type();
        ConduitRenderData data = new ConduitRenderData(type, DyeColor.LIGHT_BLUE, 0, 0,
                List.of(new ConduitFaceKey(type, Direction.NORTH, ConduitFaceMode.EXTRACT),
                        new ConduitFaceKey(type, Direction.UP, ConduitFaceMode.DISABLED)),
                null);
        assertEquals(List.of(), ConduitLabels.infoLines(data, false));

        List<Component> lines = ConduitLabels.infoLines(data, true);
        assertEquals(2, lines.size(), "colour and the one ringed face; the stub is not a mark");
        TranslatableContents colour = (TranslatableContents) lines.get(0).getContents();
        assertEquals(ConduitLabels.COLOUR_MARK_KEY, colour.getKey());
        assertEquals("A", colour.getArgs()[0]);
        assertEquals("color.minecraft.light_blue", key(colour.getArgs()[1]));
        TranslatableContents face = (TranslatableContents) lines.get(1).getContents();
        assertEquals(ConduitLabels.MODE_MARK_KEY, face.getKey());
        assertEquals(ConduitInfo.sideKey(Direction.NORTH), key(face.getArgs()[0]));
        assertEquals(ConduitInfo.faceModeKey(ConduitFaceMode.EXTRACT), key(face.getArgs()[1]));
        assertEquals(ConduitLabels.modeGlyphKey(ConduitFaceMode.EXTRACT), key(face.getArgs()[2]));
    }

    private static long cellsNearestTheBlock(ConduitFaceMode mode) {
        List<ConduitQuad> quads = new ArrayList<>();
        ConduitLabels.addModeMark(quads, CABLE, Direction.EAST, mode, 1.25, 0xFF3C6FE0);
        List<ConduitQuad> top = quads.stream().filter(quad -> quad.facing() == Direction.UP).toList();
        double maxX = top.stream().mapToDouble(quad -> quad.box().maxX).max().orElseThrow();
        return top.stream().filter(quad -> quad.box().maxX > maxX - 1.0E-9).count();
    }

    private static String key(Object component) {
        return ((TranslatableContents) ((Component) component).getContents()).getKey();
    }

    private static int stepOf(Direction direction) {
        return direction.getStepX() + direction.getStepY() + direction.getStepZ();
    }

    private static double coordinate(AABB box, Direction facing) {
        return switch (facing.getAxis()) {
            case X -> box.minX;
            case Y -> box.minY;
            case Z -> box.minZ;
        };
    }

    private static ConduitType type() {
        return new ConduitType(Identifier.fromNamespaceAndPath("test", "labels"), "basic", 0x808080, CABLE,
                ConduitLabelsTest::noItem);
    }

    // Items need a bootstrapped registry; geometry never asks for one.
    private static ItemLike noItem() {
        throw new UnsupportedOperationException();
    }
}
