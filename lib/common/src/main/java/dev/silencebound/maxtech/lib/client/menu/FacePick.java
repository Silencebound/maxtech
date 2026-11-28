package dev.silencebound.maxtech.lib.client.menu;

import net.minecraft.core.Direction;
import org.jspecify.annotations.Nullable;

/**
 * The maths of a faces node's block preview: its starting angles and which face of the unit block lies under a point.
 * The preview draws the block centred, turned by {@code pitch} about the view's x axis after {@code yaw} about the
 * block's y axis (degrees), under an orthographic view along the view's z axis; a point is given in GUI pixels from the
 * preview's centre, y down, with {@code scale} GUI pixels per block. Pure arithmetic; any thread.
 */
final class FacePick {
    /** How far a drag of one GUI pixel turns the preview, in degrees. */
    static final float DEGREES_PER_PIXEL = 2.0F;
    /** The pitch the preview starts with and keeps within. */
    static final float PITCH = 30.0F;
    static final float MAX_PITCH = 89.0F;
    /** How far the preview starts turned off the front, so a side shows too. */
    private static final float YAW_OFFSET = -30.0F;

    private FacePick() {
    }

    /**
     * The yaw that shows a block facing {@code facing} (north without one) front on, turned a little to its side; for a
     * block facing up or down, with north at the top of the view.
     */
    static float startYaw(Direction facing) {
        float front = switch (facing) {
            case SOUTH, UP -> 0.0F;
            case EAST -> -90.0F;
            case WEST -> 90.0F;
            // Seen from below, a half turn keeps north at the top of the view and puts east on the left, as the
            // block's relative names have it (top towards north, left as seen from the front).
            case NORTH, DOWN -> 180.0F;
        };
        return front + YAW_OFFSET;
    }

    /** The pitch that shows the front of a block facing {@code facing}: from below for one facing down. */
    static float startPitch(Direction facing) {
        return switch (facing) {
            case UP -> 60.0F;
            case DOWN -> -60.0F;
            default -> PITCH;
        };
    }

    /**
     * The face under the point ({@code x}, {@code y}) from the preview's centre, or null when the point misses the
     * block.
     */
    static @Nullable Direction pick(float yaw, float pitch, float scale, double x, double y) {
        // The ray in view space: from the point towards the block, along -z (z points at the viewer).
        double[] origin = toModel(yaw, pitch, x / scale, -y / scale, 10.0);
        double[] direction = toModel(yaw, pitch, 0.0, 0.0, -1.0);
        double enter = Double.NEGATIVE_INFINITY;
        double exit = Double.POSITIVE_INFINITY;
        int axis = -1;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(direction[i]) < 1.0E-9) {
                if (Math.abs(origin[i]) > 0.5) {
                    return null;
                }
                continue;
            }
            double t0 = (-0.5 - origin[i]) / direction[i];
            double t1 = (0.5 - origin[i]) / direction[i];
            double near = Math.min(t0, t1);
            double far = Math.max(t0, t1);
            if (near > enter) {
                enter = near;
                axis = i;
            }
            exit = Math.min(exit, far);
        }
        if (axis < 0 || enter > exit) {
            return null;
        }
        boolean positive = direction[axis] < 0;
        return switch (axis) {
            case 0 -> positive ? Direction.EAST : Direction.WEST;
            case 1 -> positive ? Direction.UP : Direction.DOWN;
            default -> positive ? Direction.SOUTH : Direction.NORTH;
        };
    }

    /**
     * Where the outward normal of {@code face} points on screen under the angles: x right, y down (z, towards the
     * viewer, is left out).
     */
    static double[] screen(float yaw, float pitch, Direction face) {
        double x = face.getStepX();
        double y = face.getStepY();
        double z = face.getStepZ();
        double w = Math.toRadians(yaw);
        double x1 = x * Math.cos(w) + z * Math.sin(w);
        double z1 = -x * Math.sin(w) + z * Math.cos(w);
        double p = Math.toRadians(pitch);
        double y2 = y * Math.cos(p) - z1 * Math.sin(p);
        return new double[]{x1, -y2};
    }

    /** A view-space vector in block space: the inverse of yaw about y, then pitch about x. */
    private static double[] toModel(float yaw, float pitch, double x, double y, double z) {
        double p = Math.toRadians(-pitch);
        double y1 = y * Math.cos(p) - z * Math.sin(p);
        double z1 = y * Math.sin(p) + z * Math.cos(p);
        double w = Math.toRadians(-yaw);
        double x2 = x * Math.cos(w) + z1 * Math.sin(w);
        double z2 = -x * Math.sin(w) + z1 * Math.cos(w);
        return new double[]{x2, y1, z2};
    }
}
