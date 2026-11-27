package dev.silencebound.maxtech.lib.api.conduit.model;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.ApiStatus;

/**
 * One face of a conduit model part before baking, drawn whole with its sprite. An immutable value, safe to share
 * between threads.
 *
 * @param box
 *            the box whose face this is, in pixels (0 to 16); a pane is a box flat along {@code facing}
 * @param facing
 *            which face of {@code box} this is
 * @param texture
 *            the sprite it is drawn with
 * @param argb
 *            the tint, opaque white for none
 * @param culled
 *            whether a solid neighbour on {@code facing} hides it, as for a face on the block face
 * @param layer
 *            the chunk layer it is drawn in
 */
@ApiStatus.Experimental
public record ConduitQuad(AABB box, Direction facing, ConduitTexture texture, int argb, boolean culled,
        ConduitLayer layer) {
    /** A quad whose chunk layer follows its sprite ({@link ConduitLayer#AUTO}). */
    public ConduitQuad(AABB box, Direction facing, ConduitTexture texture, int argb, boolean culled) {
        this(box, facing, texture, argb, culled, ConduitLayer.AUTO);
    }
}
