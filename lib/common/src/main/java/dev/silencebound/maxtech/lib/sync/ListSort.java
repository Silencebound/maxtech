package dev.silencebound.maxtech.lib.sync;

/** How a {@link SyncedListIndex} orders its view; ties fall back to name, then id, so the order is stable. */
public enum ListSort {
    /** Largest count first (ascending reverses it). */
    COUNT,
    /** Display name, A to Z. */
    NAME,
    /** Mod id, then name. */
    MOD;
}
