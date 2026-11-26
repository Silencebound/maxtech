package dev.silencebound.maxtech.storage.display;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Where a screen's glass lies in the world, as the renderer draws it: its bottom-left corner, the way it faces, its
 * size in blocks and the model frame's width. The server uses it to find which row of a list screen a player clicked
 * ({@link #rowAt}): the player's view ray is met with the glass's plane, so a panel wall and a cable part work alike.
 * Immutable; any thread.
 */
public record ScreenGeometry(Vec3 corner, Direction facing, float width, float height, float bezel) {
    /** A part's screen is 12 px wide, centred on the cable's face. */
    private static final double PART_HALF = 0.375;

    /** The wall anchored at {@code anchor} (its bottom-left panel), {@code width} by {@code height} panels. */
    public static ScreenGeometry wall(BlockPos anchor, Direction facing, int width, int height) {
        // The panel model's glass sits 6 px behind the block's centre (DisplayPanelRenderer).
        return of(anchor, facing, -6.0 / 16.0, 0.5, width, height, 2F / 16F);
    }

    /** The display part on face {@code face} of the cable at {@code pos}. */
    public static ScreenGeometry part(BlockPos pos, Direction face) {
        return of(pos, face, 0.5, PART_HALF, 0.75F, 0.75F, 2F / 16F * 0.75F);
    }

    private static ScreenGeometry of(BlockPos pos, Direction facing, double along, double half, float width,
            float height, float bezel) {
        Direction right = DisplayWalls.right(facing);
        Direction up = DisplayWalls.up(facing);
        Vec3 corner = new Vec3(pos.getX() + 0.5 + facing.getStepX() * along - half * (right.getStepX() + up.getStepX()),
                pos.getY() + 0.5 + facing.getStepY() * along - half * (right.getStepY() + up.getStepY()),
                pos.getZ() + 0.5 + facing.getStepZ() * along - half * (right.getStepZ() + up.getStepZ()));
        return new ScreenGeometry(corner, facing, width, height, bezel);
    }

    /**
     * Where the ray from {@code eye} along {@code look} meets the glass, as {x, y} in blocks from its bottom-left
     * corner (x to the right, y up); null when it misses, comes from behind or is more than 16 blocks away.
     */
    public float @Nullable [] hit(Vec3 eye, Vec3 look) {
        Vec3 normal = new Vec3(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        double towards = look.dot(normal);
        if (towards >= -1.0E-6) {
            return null;
        }
        double distance = corner.subtract(eye).dot(normal) / towards;
        if (distance <= 0 || distance > 16) {
            return null;
        }
        Vec3 offset = eye.add(look.scale(distance)).subtract(corner);
        Direction right = DisplayWalls.right(facing);
        Direction up = DisplayWalls.up(facing);
        float x = (float) (offset.x * right.getStepX() + offset.y * right.getStepY() + offset.z * right.getStepZ());
        float y = (float) (offset.x * up.getStepX() + offset.y * up.getStepY() + offset.z * up.getStepZ());
        if (x < 0 || y < 0 || x > width || y > height) {
            return null;
        }
        return new float[]{x, y};
    }

    /** The list row {@code player} looks at ({@link ScreenRows#rowAt}), or -1 for none. */
    public int rowAt(Player player) {
        float[] hit = hit(player.getEyePosition(), player.getViewVector(1.0F));
        return hit == null ? -1 : ScreenRows.rowAt(width, height, bezel, hit[1]);
    }
}
