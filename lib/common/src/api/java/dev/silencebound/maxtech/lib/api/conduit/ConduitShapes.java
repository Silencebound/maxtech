package dev.silencebound.maxtech.lib.api.conduit;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.ApiStatus;

/**
 * The boxes of one conduit block (ADR-0012): a square core centred in the block, {@code thickness} of a block wide, and
 * an arm of the same cross-section from the core to each connected face. Every conduit's core sits at the block centre,
 * so a conduit's arm meets its neighbour's arm across the shared face whatever their sizes; a thinner arm simply ends
 * inside the thicker one's face.
 *
 * <p>
 * The conduit model is built from these boxes as well, so what you see is what you hit. Stubs lie inside their arm's
 * box; connector rings ({@link #ring}) sit at the block face and are a little wider than the arm, so the hit shape
 * includes them. All boxes are in pixels (0 to 16); {@code thickness} is a fraction of a block, as
 * {@code ConduitType.thickness()} gives it.
 * <p>
 * Experimental (Lib 0.14.0, moved from {@code lib.conduit} for Farms, FM1) until Farms, its first addon user, settles
 * it.
 */
@ApiStatus.Experimental
public final class ConduitShapes {
    private static final double BLOCK = 16.0;
    /** How far a ring stands proud of its arm on each side, in pixels. */
    private static final double RING_LIP = 1.0;
    /**
     * Built hit shapes by {@link #shapeKey}. Joining voxel shapes is dear (it was most of the server time of placing a
     * 10,000-cable network) and a block's shape is rebuilt on every connection change, while only a few hundred
     * combinations occur. VoxelShapes are immutable, so blocks share them. Read on the server and the client thread.
     */
    private static final Long2ObjectOpenHashMap<VoxelShape> SHAPES = new Long2ObjectOpenHashMap<>();

    private ConduitShapes() {
    }

    /** Width of a conduit of {@code thickness} in pixels. */
    public static double width(float thickness) {
        return thickness * BLOCK;
    }

    /**
     * Hit shape of a conduit of {@code thickness}: its core, an arm per side in {@code arms} and a stub per side in
     * {@code stubs}, so a disabled face can still be targeted. No rings.
     */
    public static VoxelShape shape(float thickness, int arms, int stubs) {
        return shape(thickness, arms, stubs, 0);
    }

    /**
     * Hit shape of a conduit of {@code thickness} as {@link #shape(float, int, int)}, plus a {@link #ring} per side in
     * {@code rings}, which stands out of the arm.
     */
    public static VoxelShape shape(float thickness, int arms, int stubs, int rings) {
        // A stub only shows where there is no arm.
        long key = shapeKey(thickness, arms, stubs & ~arms, rings);
        synchronized (SHAPES) {
            VoxelShape cached = SHAPES.get(key);
            if (cached != null) {
                return cached;
            }
        }
        VoxelShape built = build(thickness, arms, stubs, rings);
        synchronized (SHAPES) {
            SHAPES.put(key, built);
        }
        return built;
    }

    /** The thickness's bits and the three six-bit side masks in one long. */
    private static long shapeKey(float thickness, int arms, int stubs, int rings) {
        int sides = (arms & 0x3F) | (stubs & 0x3F) << 6 | (rings & 0x3F) << 12;
        return (long) Float.floatToIntBits(thickness) << 32 | sides;
    }

    private static VoxelShape build(float thickness, int arms, int stubs, int rings) {
        VoxelShape shape = toShape(core(thickness));
        for (Direction side : Direction.values()) {
            if (ConduitConnections.isConnected(arms, side)) {
                shape = Shapes.or(shape, toShape(arm(thickness, side)));
            } else if (ConduitConnections.isConnected(stubs, side)) {
                shape = Shapes.or(shape, toShape(stub(thickness, side)));
            }
            if (ConduitConnections.isConnected(rings, side)) {
                shape = Shapes.or(shape, toShape(ring(thickness, side)));
            }
        }
        return shape.optimize();
    }

    /** The core cube of a conduit of {@code thickness}, centred in the block. */
    public static AABB core(float thickness) {
        double min = (BLOCK - width(thickness)) / 2;
        double max = min + width(thickness);
        return new AABB(min, min, min, max, max, max);
    }

    /** The arm from the core to the {@code side} face of the block; it ends on the block's face. */
    public static AABB arm(float thickness, Direction side) {
        return atFace(side, width(thickness), 0, armLength(thickness));
    }

    /**
     * The short stub a disabled face draws instead of its arm: the inner half of the arm, slightly thinner, so it lies
     * inside the arm's box.
     */
    public static AABB stub(float thickness, Direction side) {
        double length = armLength(thickness);
        return atFace(side, width(thickness) * 3 / 4, length / 2, length);
    }

    /**
     * The connector ring at the block end of an arm: a collar up to a pixel deep that stands {@value #RING_LIP} px
     * proud of the arm on every side, at most a block wide. The model ends the arm at {@link #armBeforeRing}, so the
     * two never overlap along the arm.
     */
    public static AABB ring(float thickness, Direction side) {
        return atFace(side, collarWidth(thickness, 2 * RING_LIP), 0, ringDepth(thickness));
    }

    /** The part of the arm between the core and its {@link #ring}. */
    public static AABB armBeforeRing(float thickness, Direction side) {
        return atFace(side, width(thickness), ringDepth(thickness), armLength(thickness));
    }

    /** Width of a collar {@code extra} px wider than the arm, capped at the block. */
    private static double collarWidth(float thickness, double extra) {
        return Math.min(BLOCK, width(thickness) + extra);
    }

    private static double ringDepth(float thickness) {
        return Math.min(1.0, armLength(thickness) / 2);
    }

    /** Length of every arm: from the core's face to the block's face. */
    private static double armLength(float thickness) {
        return (BLOCK - width(thickness)) / 2;
    }

    /**
     * A box centred across {@code side}'s axis, {@code across} px wide, running from {@code near} to {@code far} px
     * inwards from the {@code side} face of the block.
     */
    public static AABB atFace(Direction side, double across, double near, double far) {
        double min = (BLOCK - across) / 2;
        double max = min + across;
        return switch (side) {
            case DOWN -> new AABB(min, near, min, max, far, max);
            case UP -> new AABB(min, BLOCK - far, min, max, BLOCK - near, max);
            case NORTH -> new AABB(min, min, near, max, max, far);
            case SOUTH -> new AABB(min, min, BLOCK - far, max, max, BLOCK - near);
            case WEST -> new AABB(near, min, min, far, max, max);
            case EAST -> new AABB(BLOCK - far, min, min, BLOCK - near, max, max);
        };
    }

    /**
     * The face of a conduit that a point inside its hit shape belongs to: the side of the arm (from {@code arms}) or
     * stub (from {@code stubs}) whose box strictly contains {@code pixels}, otherwise {@code clicked}, the side of the
     * core that was hit. Points exactly on a boundary count as the core, so a click on the core's face next to an arm
     * still picks the clicked side. {@code pixels} is block-local (0 to 16) and should lie just inside the box that was
     * hit, not on its surface.
     */
    public static Direction faceAt(float thickness, int arms, int stubs, Vec3 pixels, Direction clicked) {
        return faceAt(thickness, arms, stubs, 0, pixels, clicked);
    }

    /**
     * As {@link #faceAt(float, int, int, Vec3, Direction)}, where a point in the {@link #ring} of a side in
     * {@code rings} also belongs to that side.
     */
    public static Direction faceAt(float thickness, int arms, int stubs, int rings, Vec3 pixels, Direction clicked) {
        for (Direction side : Direction.values()) {
            if (ConduitConnections.isConnected(arms, side)) {
                if (strictlyContains(arm(thickness, side), pixels)) {
                    return side;
                }
            } else if (ConduitConnections.isConnected(stubs, side) && strictlyContains(stub(thickness, side), pixels)) {
                return side;
            }
            if (ConduitConnections.isConnected(rings, side) && strictlyContains(ring(thickness, side), pixels)) {
                return side;
            }
        }
        return clicked;
    }

    private static boolean strictlyContains(AABB box, Vec3 point) {
        return point.x > box.minX && point.x < box.maxX && point.y > box.minY && point.y < box.maxY
                && point.z > box.minZ && point.z < box.maxZ;
    }

    private static VoxelShape toShape(AABB pixels) {
        return Shapes.box(pixels.minX / BLOCK, pixels.minY / BLOCK, pixels.minZ / BLOCK, pixels.maxX / BLOCK,
                pixels.maxY / BLOCK, pixels.maxZ / BLOCK);
    }
}
