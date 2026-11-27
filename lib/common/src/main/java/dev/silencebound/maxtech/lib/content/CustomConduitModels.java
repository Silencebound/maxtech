package dev.silencebound.maxtech.lib.content;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;

/**
 * The addon conduits whose client model comes from an addon hook ({@code Conduits.Builder#customModel}), by block id,
 * with whether that model is translucent. Filled during parallel mod construction; Lib's client reads it once the
 * blocks are registered.
 */
public final class CustomConduitModels {
    private static final Map<Identifier, Boolean> BLOCKS = new ConcurrentHashMap<>();

    private CustomConduitModels() {
    }

    /** Marks conduit block {@code block} as drawn by an addon hook. Call during mod construction. */
    public static void add(Identifier block, boolean translucent) {
        BLOCKS.put(block, translucent);
    }

    /** Every marked block, with whether its model is translucent. */
    public static Map<Identifier, Boolean> all() {
        return Map.copyOf(BLOCKS);
    }
}
