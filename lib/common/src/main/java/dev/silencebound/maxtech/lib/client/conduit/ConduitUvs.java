package dev.silencebound.maxtech.lib.client.conduit;

import dev.silencebound.maxtech.lib.api.conduit.model.ConduitUv;
import java.util.OptionalInt;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/**
 * Block-projected sprite coordinates for conduit faces, so a sprite tiles on without a seam from one block to the next,
 * and the turns that point it along a flow.
 *
 * <p>
 * Projection: a face shows the sprite pixel at its own position in the block, one sprite pixel per model pixel, as
 * vanilla's default face UVs do ({@code FaceBakery.defaultFaceUV}). Per face, the sprite's +u and +v run along these
 * directions (v runs down the sprite):
 *
 * <pre>
 * face   +u     +v
 * DOWN   east   north
 * UP     east   south
 * NORTH  west   down
 * SOUTH  east   down
 * WEST   south  down
 * EAST   north  down
 * </pre>
 *
 * Turned by {@code k} quarters, a point shows the pixel its unturned projection gives, turned {@code k} times by
 * {@code (u, v) -> (v, 16 - u)} about the sprite's centre: the sprite turns clockwise on the face, as with a block
 * model face's {@code "rotation"}, and still tiles across blocks because a quarter turn about the centre maps the
 * 16-pixel grid onto itself.
 *
 * <p>
 * Flow: {@link #flowTurns} gives the turns after which the sprite's +v (top to bottom of the image) runs along the
 * flow, so a streak drawn running down the sprite moves with it. Pure and thread-safe.
 */
public final class ConduitUvs {
    private ConduitUvs() {
    }

    /** The unturned block-projected UVs of {@code box}'s {@code face}. */
    public static ConduitUv projected(AABB box, Direction face) {
        return projected(box, face, 0);
    }

    /**
     * The block-projected UVs of {@code box}'s {@code face} turned clockwise by {@code quarterTurns} (0 to 3), ready to
     * bake: the rectangle is the one whose corners FaceBakery hands to the face's vertices, after the turn, as the
     * projection above.
     *
     * @throws IllegalArgumentException
     *             when {@code box} reaches outside the block (0 to 16) or {@code quarterTurns} is outside 0 to 3
     */
    public static ConduitUv projected(AABB box, Direction face, int quarterTurns) {
        float x0 = (float) box.minX;
        float y0 = (float) box.minY;
        float z0 = (float) box.minZ;
        float x1 = (float) box.maxX;
        float y1 = (float) box.maxY;
        float z1 = (float) box.maxZ;
        float s = ConduitUv.SIZE;
        float[] r = switch (face) {
            case DOWN -> new float[]{x0, s - z1, x1, s - z0};
            case UP -> new float[]{x0, z0, x1, z1};
            case NORTH -> new float[]{s - x1, s - y1, s - x0, s - y0};
            case SOUTH -> new float[]{x0, s - y1, x1, s - y0};
            case WEST -> new float[]{z0, s - y1, z1, s - y0};
            case EAST -> new float[]{s - z1, s - y1, s - z0, s - y0};
        };
        if (quarterTurns < 0 || quarterTurns > 3) {
            throw new IllegalArgumentException("quarterTurns must be 0 to 3, was " + quarterTurns);
        }
        for (int i = 0; i < quarterTurns; i++) {
            // (u, v) -> (v, 16 - u) on the rectangle: u spans the old v, v spans 16 minus the old u
            r = new float[]{r[1], s - r[2], r[3], s - r[0]};
        }
        return new ConduitUv(r[0], r[1], r[2], r[3], quarterTurns);
    }

    /**
     * The clockwise quarter turns (0 to 3) after which the sprite's +v on {@code face} runs along {@code flow}; empty
     * when {@code flow} runs into or out of the face, where no turn points along it.
     */
    public static OptionalInt flowTurns(Direction face, Direction flow) {
        if (flow.getAxis() == face.getAxis()) {
            return OptionalInt.empty();
        }
        Direction u = uAxis(face);
        Direction v = vAxis(face);
        // turn k shows the pixel at (v, 16 - u) applied k times, so +v of the sprite runs along +v, -u, -v, +u
        if (flow == v) {
            return OptionalInt.of(0);
        } else if (flow == u.getOpposite()) {
            return OptionalInt.of(1);
        } else if (flow == v.getOpposite()) {
            return OptionalInt.of(2);
        }
        return OptionalInt.of(3);
    }

    /** The block direction the unturned sprite's +u runs along on {@code face}. */
    static Direction uAxis(Direction face) {
        return switch (face) {
            case DOWN, UP, SOUTH -> Direction.EAST;
            case NORTH -> Direction.WEST;
            case WEST -> Direction.SOUTH;
            case EAST -> Direction.NORTH;
        };
    }

    /** The block direction the unturned sprite's +v runs along on {@code face}. */
    static Direction vAxis(Direction face) {
        return switch (face) {
            case DOWN -> Direction.NORTH;
            case UP -> Direction.SOUTH;
            case NORTH, SOUTH, WEST, EAST -> Direction.DOWN;
        };
    }
}
