package dev.silencebound.maxtech.lib.api.conduit.model;

import org.jetbrains.annotations.ApiStatus;

/**
 * Which part of its sprite a {@link ConduitQuad} shows, and turned how far: the {@code uv} and {@code rotation} of a
 * block model face. An immutable value, safe to share between threads. Lib 0.14.0.
 *
 * <p>
 * The rectangle is in sprite pixels (0 to 16 on each axis, whatever the sprite's real size). Its corners are numbered
 * as for a block model face's {@code "uv"}: 0 {@code (u0, v0)}, 1 {@code (u0, v1)}, 2 {@code (u1, v1)}, 3
 * {@code (u1, v0)}, so top left, bottom left, bottom right, top right of the sprite. The face's four vertices are
 * numbered the same way round, seen from outside the face, from the one at its top left as vanilla walks the face, and
 * vertex {@code i} shows corner {@code (i + quarterTurns) % 4}: the sprite turns clockwise, seen from outside the face,
 * by {@code quarterTurns} quarters, as a face's {@code "rotation"} of {@code 90 * quarterTurns} does. {@code u0 > u1}
 * or {@code v0 > v1} mirrors the sprite. {@code -0.0F} is stored as {@code 0.0F}, so equal rectangles are equal values.
 * A quad without a {@code ConduitUv} shows the whole sprite unturned.
 *
 * @param u0
 *            u of the first corner, 0 to 16
 * @param v0
 *            v of the first corner, 0 to 16
 * @param u1
 *            u of the opposite corner, 0 to 16
 * @param v1
 *            v of the opposite corner, 0 to 16
 * @param quarterTurns
 *            clockwise quarter turns of the sprite, 0 to 3
 */
@ApiStatus.Experimental
public record ConduitUv(float u0, float v0, float u1, float v1, int quarterTurns) {
    /** The highest sprite coordinate: one sprite is 16 pixels wide and high. */
    public static final float SIZE = 16.0F;

    /**
     * Checks the ranges.
     *
     * @throws IllegalArgumentException
     *             when a coordinate is outside 0 to 16 (or not a number) or {@code quarterTurns} outside 0 to 3
     */
    public ConduitUv {
        requireCoordinate("u0", u0);
        requireCoordinate("v0", v0);
        requireCoordinate("u1", u1);
        requireCoordinate("v1", v1);
        if (quarterTurns < 0 || quarterTurns > 3) {
            throw new IllegalArgumentException("quarterTurns must be 0 to 3, was " + quarterTurns);
        }
        // -0.0F == 0.0F, so this only replaces a negative zero, which record equality would tell apart
        u0 = u0 == 0.0F ? 0.0F : u0;
        v0 = v0 == 0.0F ? 0.0F : v0;
        u1 = u1 == 0.0F ? 0.0F : u1;
        v1 = v1 == 0.0F ? 0.0F : v1;
    }

    /** The rectangle {@code (u0, v0)} to {@code (u1, v1)} unturned. */
    public ConduitUv(float u0, float v0, float u1, float v1) {
        this(u0, v0, u1, v1, 0);
    }

    private static void requireCoordinate(String name, float value) {
        if (!(value >= 0.0F && value <= SIZE)) {
            throw new IllegalArgumentException(name + " must be 0 to 16, was " + value);
        }
    }
}
