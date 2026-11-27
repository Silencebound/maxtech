package dev.silencebound.maxtech.lib.api.conduit.model;

import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import net.minecraft.world.item.DyeColor;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * What a conduit's body looks like: equal keys render identically, so they share one baked part. It holds no position,
 * so a network of thousands of conduits needs only as many body parts as it has distinct arm and ring masks. An
 * immutable value, safe to share between threads.
 *
 * @param type
 *            the conduit's type
 * @param colour
 *            the conduit's dye, or null for an undyed one
 * @param arms
 *            sides that draw a full arm, as a {@link dev.silencebound.maxtech.lib.api.conduit.ConduitConnections} mask
 * @param rings
 *            arms that stop where their ring starts, as such a mask
 */
@ApiStatus.Experimental
public record ConduitBodyKey(ConduitType type, @Nullable DyeColor colour, int arms, int rings) {
}
