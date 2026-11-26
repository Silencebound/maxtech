package dev.silencebound.maxtech.lib.api.network;

/**
 * How networks treat unloaded chunks (server config {@code networks.unloadedChunkMode}, PRD 5.2). Read on the server
 * thread; the value can change on config reload, which invalidates every graph.
 */
public enum UnloadedChunkMode {
    /**
     * Graphs contain only loaded chunks. Edges into unloaded chunks are cut and re-attached when the chunk loads, so a
     * network may temporarily split.
     */
    TRUNCATE,
    /** A network touching any unloaded chunk idles entirely until all of its chunks are loaded. */
    PAUSE
}
