package dev.silencebound.maxtech.lib.client.conduit;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/**
 * One face of a conduit model part before baking: the {@code facing} face of {@code box} (pixels, 0 to 16; a pane is a
 * box flat along {@code facing}), drawn whole with {@code texture} tinted {@code argb} (opaque white for none), and
 * culled against a solid neighbour on {@code facing} when {@code culled}.
 */
public record ConduitQuad(AABB box, Direction facing, ConduitTexture texture, int argb, boolean culled) {
}
