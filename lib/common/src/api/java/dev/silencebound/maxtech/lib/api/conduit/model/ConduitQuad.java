package dev.silencebound.maxtech.lib.api.conduit.model;

import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * One face of a conduit model part before baking, drawn with its sprite: whole and unturned, or the part and turn its
 * {@code uv} names. An immutable value, safe to share between threads.
 *
 * @param box
 *            the box whose face this is, in pixels (0 to 16); a pane is a box flat along {@code facing}
 * @param facing
 *            which face of {@code box} this is
 * @param texture
 *            the sprite it is drawn with, unless {@code sprite} names one
 * @param argb
 *            the tint, opaque white for none
 * @param culled
 *            whether a solid neighbour on {@code facing} hides it, as for a face on the block face
 * @param layer
 *            the chunk layer it is drawn in
 * @param sprite
 *            a sprite of a custom conduit model, by its atlas id (e.g. {@code farms:block/tube_glass}), drawn instead
 *            of {@code texture}; it must be one its model hook lists in {@code sprites()}, or the model fails when it
 *            bakes the quad. Lib's own quads name only their look's sprites this way (a shell's glass). Null for one of
 *            {@code texture}'s. Lib 0.14.0.
 * @param uv
 *            which part of the sprite the face shows and how it is turned; null for the whole sprite unturned, as all
 *            of Lib's quads except a shell's glass and contents. Lib 0.14.0.
 * @param lightEmission
 *            the light level the quad is drawn at, at least, 0 to 15: 0 takes the light around the block as usual, 15
 *            is full-bright. It lights only the quad, never the block or its neighbours. Lib's quads use it for a
 *            glowing fluid's contents (lava's 15). Lib 0.14.0.
 */
@ApiStatus.Experimental
public record ConduitQuad(AABB box, Direction facing, ConduitTexture texture, int argb, boolean culled,
        ConduitLayer layer, @Nullable Identifier sprite, @Nullable ConduitUv uv, int lightEmission) {
    /** The highest {@link #lightEmission}. */
    public static final int MAX_LIGHT = 15;

    /**
     * Checks the light level.
     *
     * @throws IllegalArgumentException
     *             when {@code lightEmission} is outside 0 to {@value #MAX_LIGHT}
     */
    public ConduitQuad {
        if (lightEmission < 0 || lightEmission > MAX_LIGHT) {
            throw new IllegalArgumentException("lightEmission must be 0 to " + MAX_LIGHT + ", was " + lightEmission);
        }
    }

    /** A quad drawn in the light around the block. */
    public ConduitQuad(AABB box, Direction facing, ConduitTexture texture, int argb, boolean culled, ConduitLayer layer,
            @Nullable Identifier sprite, @Nullable ConduitUv uv) {
        this(box, facing, texture, argb, culled, layer, sprite, uv, 0);
    }
    /** A quad showing its whole sprite unturned. */
    public ConduitQuad(AABB box, Direction facing, ConduitTexture texture, int argb, boolean culled, ConduitLayer layer,
            @Nullable Identifier sprite) {
        this(box, facing, texture, argb, culled, layer, sprite, null);
    }

    /** A quad drawn with one of {@code texture}'s sprites. */
    public ConduitQuad(AABB box, Direction facing, ConduitTexture texture, int argb, boolean culled,
            ConduitLayer layer) {
        this(box, facing, texture, argb, culled, layer, null);
    }

    /** A quad whose chunk layer follows its sprite ({@link ConduitLayer#AUTO}). */
    public ConduitQuad(AABB box, Direction facing, ConduitTexture texture, int argb, boolean culled) {
        this(box, facing, texture, argb, culled, ConduitLayer.AUTO);
    }

    /**
     * A quad of a custom conduit model drawn with {@code sprite} (see the record's {@code sprite}). Lib 0.14.0.
     */
    public static ConduitQuad ofSprite(AABB box, Direction facing, Identifier sprite, int argb, boolean culled,
            ConduitLayer layer) {
        return new ConduitQuad(box, facing, ConduitTexture.FLAT, argb, culled, layer, sprite);
    }
}
