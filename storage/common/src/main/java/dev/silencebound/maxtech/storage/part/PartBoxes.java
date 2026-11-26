package dev.silencebound.maxtech.storage.part;

import dev.silencebound.maxtech.lib.conduit.ConduitShapes;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Box maths for cable parts, in pixels (0 to 16): turning a box given for the north face to any face, the cable arm
 * between the core and a part, and which part a point inside the block belongs to. Pure geometry, unit-tested.
 */
public final class PartBoxes {
    private static final double BLOCK = 16.0;

    private PartBoxes() {
    }

    /**
     * {@code box}, given for a part on the north face (lying against {@code z = 0}), turned so it lies against
     * {@code face} the same way a north-facing block model is turned to face {@code face}: depth into the block maps
     * from z to the face's axis, and the box keeps its place across the face.
     */
    public static AABB turn(AABB box, Direction face) {
        double x0 = box.minX;
        double y0 = box.minY;
        double z0 = box.minZ;
        double x1 = box.maxX;
        double y1 = box.maxY;
        double z1 = box.maxZ;
        return switch (face) {
            case NORTH -> box;
            case SOUTH -> new AABB(BLOCK - x1, y0, BLOCK - z1, BLOCK - x0, y1, BLOCK - z0);
            case WEST -> new AABB(z0, y0, BLOCK - x1, z1, y1, BLOCK - x0);
            case EAST -> new AABB(BLOCK - z1, y0, x0, BLOCK - z0, y1, x1);
            case UP -> new AABB(x0, BLOCK - z1, y0, x1, BLOCK - z0, y1);
            case DOWN -> new AABB(x0, z0, BLOCK - y1, x1, z1, BLOCK - y0);
        };
    }

    /**
     * The cable arm behind a part on {@code side}: from the core of a cable {@code thickness} thick to {@code depth} px
     * inside the block face, where the part's back is; {@code null} when the part reaches the core.
     */
    public static @Nullable AABB arm(float thickness, Direction side, double depth) {
        double armLength = (BLOCK - ConduitShapes.width(thickness)) / 2;
        if (depth >= armLength) {
            return null;
        }
        return ConduitShapes.atFace(side, ConduitShapes.width(thickness), depth, armLength);
    }

    /** {@code pixels} as a shape in block units. */
    public static VoxelShape toShape(AABB pixels) {
        return Shapes.box(pixels.minX / BLOCK, pixels.minY / BLOCK, pixels.minZ / BLOCK, pixels.maxX / BLOCK,
                pixels.maxY / BLOCK, pixels.maxZ / BLOCK);
    }

    /** Whether {@code pixels} lies inside {@code box} or on its surface. */
    public static boolean contains(AABB box, Vec3 pixels) {
        return pixels.x >= box.minX && pixels.x <= box.maxX && pixels.y >= box.minY && pixels.y <= box.maxY
                && pixels.z >= box.minZ && pixels.z <= box.maxZ;
    }
}
