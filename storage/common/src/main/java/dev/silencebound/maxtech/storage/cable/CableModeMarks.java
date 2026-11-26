package dev.silencebound.maxtech.storage.cable;

import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceMode;
import dev.silencebound.maxtech.lib.client.conduit.ConduitLabels;
import dev.silencebound.maxtech.lib.conduit.ConduitColours;
import dev.silencebound.maxtech.lib.conduit.ConduitShapes;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/**
 * The connection mode marks a storage cable shows while the player holds a wrench (owner decision 2026-10-09; the cable
 * model draws no connector rings since #103): a one-pixel band around the arm where it meets a block, in Lib's face
 * colours (green both ways, blue insert, orange extract). A disabled face keeps the model's grey stub and gets no mark.
 * With Lib's {@code conduitColorLabels} on, the mode's glyph follows the band (an arrow into or out of the block, a
 * diamond for both), so the mode does not rest on colour alone. Pure geometry, safe from any thread.
 */
public final class CableModeMarks {
    /** How long the band runs along the arm from the block face, in pixels. */
    static final double DEPTH = 1.0;
    /** How far the band stands proud of the arm, in pixels: just enough not to fight with the arm's faces. */
    static final double LIP = 0.05;
    private static final double BLOCK = 16.0;

    private CableModeMarks() {
    }

    /**
     * Where the mode's glyph starts while Lib's {@code conduitColorLabels} is on, in pixels from the block face: just
     * past the band ({@link ConduitLabels#addModeMark}).
     */
    public static final double GLYPH_FROM = DEPTH + 0.25;
    /** Space between the mode glyph and a dye letter on the same arm, in pixels. */
    private static final double LETTER_GAP = 0.15;

    /**
     * How much of an arm of a cable {@code thickness} thick, measured from the core, a dye letter may use without
     * running into the mode glyph, in pixels: every storage cable draws its letter there, as its core faces carry the
     * tier pips.
     */
    public static double letterSpan(float thickness) {
        double armLength = (BLOCK - ConduitShapes.width(thickness)) / 2;
        return armLength - ConduitLabels.modeMarkEnd(thickness, GLYPH_FROM) - LETTER_GAP;
    }

    /** Whether a face in {@code mode} gets a mark; a disabled face does not. */
    public static boolean marked(ConduitFaceMode mode) {
        return mode != ConduitFaceMode.DISABLED;
    }

    /** The mark's colour for {@code mode}, as the connector rings had it. */
    public static int argb(ConduitFaceMode mode) {
        return ConduitColours.faceArgb(mode);
    }

    /**
     * The band at the {@code side} end of the arm of a cable of {@code thickness}, in block units (0 to 1): the last
     * {@link #DEPTH} pixels of the arm, {@link #LIP} wider on each side. Its faces across the arm are not drawn.
     */
    public static AABB band(float thickness, Direction side) {
        AABB arm = ConduitShapes.arm(thickness, side);
        double minX = arm.minX - LIP;
        double minY = arm.minY - LIP;
        double minZ = arm.minZ - LIP;
        double maxX = arm.maxX + LIP;
        double maxY = arm.maxY + LIP;
        double maxZ = arm.maxZ + LIP;
        switch (side) {
            case DOWN -> {
                minY = 0;
                maxY = DEPTH;
            }
            case UP -> {
                minY = BLOCK - DEPTH;
                maxY = BLOCK;
            }
            case NORTH -> {
                minZ = 0;
                maxZ = DEPTH;
            }
            case SOUTH -> {
                minZ = BLOCK - DEPTH;
                maxZ = BLOCK;
            }
            case WEST -> {
                minX = 0;
                maxX = DEPTH;
            }
            case EAST -> {
                minX = BLOCK - DEPTH;
                maxX = BLOCK;
            }
        }
        return new AABB(minX / BLOCK, minY / BLOCK, minZ / BLOCK, maxX / BLOCK, maxY / BLOCK, maxZ / BLOCK);
    }
}
