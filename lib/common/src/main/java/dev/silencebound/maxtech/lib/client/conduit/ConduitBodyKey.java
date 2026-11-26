package dev.silencebound.maxtech.lib.client.conduit;

import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.conduit.ConduitRenderData;
import net.minecraft.world.item.DyeColor;
import org.jspecify.annotations.Nullable;

/**
 * What a conduit's body looks like: equal keys render identically, so they share one baked part. It holds no position,
 * so a network of thousands of conduits needs only as many body parts as it has distinct arm and ring masks.
 *
 * @param arms
 *            sides that draw a full arm
 * @param rings
 *            arms that stop where their ring starts
 */
public record ConduitBodyKey(ConduitType type, @Nullable DyeColor colour, int arms, int rings) {
    /** The body of {@code data}. */
    public static ConduitBodyKey of(ConduitRenderData data) {
        return new ConduitBodyKey(data.type(), data.colour(), data.arms(), data.rings());
    }
}
