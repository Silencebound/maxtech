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
 * @param renderValue
 *            the addon's {@link dev.silencebound.maxtech.lib.api.conduit.ConduitView#renderValue()}; Lib's own looks
 *            ignore it
 * @param contents
 *            the conduit's {@link dev.silencebound.maxtech.lib.api.conduit.ConduitView#contents()},
 *            {@linkplain dev.silencebound.maxtech.lib.api.conduit.ConduitContents#pack packed} with this session's look
 *            indices and read back with {@code ConduitContents}' {@code packed*} helpers; 0 for none. The shell look
 *            draws it; the solid look ignores it
 */
@ApiStatus.Experimental
public record ConduitBodyKey(ConduitType type, @Nullable DyeColor colour, int arms, int rings, int renderValue,
        int contents) {
    /** A key with render value 0 and no contents, which is what every conduit has until its addon sets them. */
    public ConduitBodyKey(ConduitType type, @Nullable DyeColor colour, int arms, int rings) {
        this(type, colour, arms, rings, 0, 0);
    }

    /** A key with no contents. */
    public ConduitBodyKey(ConduitType type, @Nullable DyeColor colour, int arms, int rings, int renderValue) {
        this(type, colour, arms, rings, renderValue, 0);
    }
}
