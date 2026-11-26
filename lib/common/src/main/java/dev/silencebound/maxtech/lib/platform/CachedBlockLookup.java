package dev.silencebound.maxtech.lib.platform;

import org.jspecify.annotations.Nullable;

/** A block lookup bound to one position and side (see {@link BlockLookup#cache}). Server thread only. */
public interface CachedBlockLookup<T> {
    /**
     * The capability there now, or {@code null} if there is none or the position is not loaded (the cache refreshes
     * when the chunk loads). Throws {@link IllegalStateException} if called from its own invalidation listener or after
     * its {@code isValid} check returned false.
     */
    @Nullable
    T find();
}
