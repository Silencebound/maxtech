package dev.silencebound.maxtech.lib.platform;

import net.minecraft.resources.Identifier;

public interface CapabilityHelper {
    /**
     * Creates, or returns the existing, sided block lookup named {@code name}. Two calls with the same name return
     * lookups for the same underlying capability; {@code type} must then match.
     */
    <T> BlockLookup<T> sided(Identifier name, Class<T> type);

    /** Creates, or returns the existing, item lookup named {@code name}; same identity rules as {@link #sided}. */
    <T> ItemLookup<T> item(Identifier name, Class<T> type);
}
