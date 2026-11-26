package dev.silencebound.maxtech.storage.network;

/**
 * Whether channels limit storage networks ({@code network.channels.mode}, B09 item 12, owner 2026-10-18; AE2's channel
 * mode). Read by {@link Channels} when the server config loads; networks pick a change up with one recount.
 */
public enum ChannelMode {
    /** Cables, hubs, root links and networks without a controller carry their configured channels (the default). */
    LIMITED,
    /**
     * No channel limits: cables, hubs and root links carry as many devices as are routed over them, and a network
     * without a controller holds any number. Routes, zones, the zone size and the team caps stay as they are.
     */
    INFINITE
}
