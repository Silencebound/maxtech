package dev.silencebound.maxtech.storage.controller;

import net.minecraft.core.Direction;

/**
 * The controller face's connected-texture rules (M7R item 7), shared by the client model and the tests: which of a
 * face's four edges draw the 1 px frame, and how far the face's glow lags behind the multiblock's centre.
 *
 * <p>
 * Edges are numbered as the face's texture shows them with the full-face UVs vanilla's {@code FaceBakery.defaultFaceUV}
 * gives a cube: bit {@link #TOP} 1, {@link #RIGHT} 2, {@link #BOTTOM} 4, {@link #LEFT} 8. A set bit means the edge is
 * drawn: the block next to it in the face's plane is no controller block, or it is one whose own face on this side is
 * covered by another controller block (an inner corner). Touching controller blocks always belong to one multiblock
 * (they merge), so a controller neighbour is enough. Pure and allocation-free; safe from chunk meshing threads.
 *
 * <p>
 * Neighbours come as a 27-bit occupancy cube: bit {@link #bit(int, int, int)} is set where the block at that offset
 * (each -1 to 1) from this one is a controller block.
 */
public final class ControllerFace {
    public static final int TOP = 1;
    public static final int RIGHT = 2;
    public static final int BOTTOM = 4;
    public static final int LEFT = 8;
    /** Every edge drawn: a face with no controller neighbours, as on a lone block. */
    public static final int ALL = 15;
    /** Phase offsets of the glow strips; the strips have two frames per offset. */
    public static final int PHASES = 8;
    /** Phase steps per block of distance: a wave crest moves a quarter period per block outward. */
    private static final int STEPS_PER_BLOCK = 2;
    /** The centre offset's range per axis, in half blocks. */
    private static final int MAX_HALF_BLOCKS = 127;

    /**
     * Per face (by {@link Direction#get3DDataValue()}): the directions of the texture's top, right, bottom and left
     * edge.
     */
    private static final Direction[][] EDGES = {
            // DOWN: u = x, v = 16 - z, so the texture's top is south.
            {Direction.SOUTH, Direction.EAST, Direction.NORTH, Direction.WEST},
            // UP: u = x, v = z.
            {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST},
            // NORTH: u = 16 - x, v = 16 - y.
            {Direction.UP, Direction.WEST, Direction.DOWN, Direction.EAST},
            // SOUTH: u = x.
            {Direction.UP, Direction.EAST, Direction.DOWN, Direction.WEST},
            // WEST: u = z.
            {Direction.UP, Direction.SOUTH, Direction.DOWN, Direction.NORTH},
            // EAST: u = 16 - z.
            {Direction.UP, Direction.NORTH, Direction.DOWN, Direction.SOUTH}};

    private ControllerFace() {
    }

    /** The occupancy bit of the block at offset ({@code dx}, {@code dy}, {@code dz}), each -1 to 1. */
    public static int bit(int dx, int dy, int dz) {
        return 1 << ((dx + 1) + 3 * (dy + 1) + 9 * (dz + 1));
    }

    /** The texture edge {@code index} (0 top, 1 right, 2 bottom, 3 left) of {@code face}, as a world direction. */
    public static Direction edge(Direction face, int index) {
        return EDGES[face.get3DDataValue()][index];
    }

    /** The edges of {@code face} that draw the frame, given the {@code occupancy} cube around the block. */
    public static int edges(Direction face, int occupancy) {
        int mask = 0;
        Direction[] sides = EDGES[face.get3DDataValue()];
        for (int i = 0; i < 4; i++) {
            Direction side = sides[i];
            boolean neighbour = (occupancy & bit(side.getStepX(), side.getStepY(), side.getStepZ())) != 0;
            boolean covered = (occupancy & bit(side.getStepX() + face.getStepX(), side.getStepY() + face.getStepY(),
                    side.getStepZ() + face.getStepZ())) != 0;
            if (!neighbour || covered) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    /**
     * Packs the offset from a block's centre to its multiblock's centre, in half blocks per axis (clamped to +-127),
     * for the block entity to sync; 0 is a lone block.
     */
    public static int packCentre(int dx2, int dy2, int dz2) {
        return (clamp(dx2) & 0xFF) << 16 | (clamp(dy2) & 0xFF) << 8 | (clamp(dz2) & 0xFF);
    }

    /** One axis of {@link #packCentre}: 0 x, 1 y, 2 z, in half blocks. */
    public static int centreAxis(int packed, int axis) {
        return (byte) (packed >>> (16 - 8 * axis));
    }

    /**
     * The glow phase offset (0 to {@link #PHASES} - 1) of {@code face} on a block whose multiblock centre lies at the
     * packed offset {@code centre}: its face centre's distance from the multiblock's centre, in steps of half a block.
     * Faces further out start their wave later, so crests roll outward through the machine on the one atlas clock.
     */
    public static int phase(int centre, Direction face) {
        // In half blocks: face centre (half a block along the normal) minus the multiblock centre.
        int x = face.getStepX() - centreAxis(centre, 0);
        int y = face.getStepY() - centreAxis(centre, 1);
        int z = face.getStepZ() - centreAxis(centre, 2);
        double blocks = Math.sqrt(x * x + y * y + z * z) / 2.0;
        return (int) Math.round(blocks * STEPS_PER_BLOCK) % PHASES;
    }

    private static int clamp(int halfBlocks) {
        return Math.clamp(halfBlocks, -MAX_HALF_BLOCKS, MAX_HALF_BLOCKS);
    }
}
