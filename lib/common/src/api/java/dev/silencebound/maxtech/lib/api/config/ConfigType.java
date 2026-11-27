package dev.silencebound.maxtech.lib.api.config;

/**
 * Server config holds balance and behaviour and is synced to clients; client config holds visuals only. Common config
 * loads at startup on both sides, before datapacks, so it is the only place for switches that recipes or registration
 * read (content toggles); keep it to those.
 */
public enum ConfigType {
    /** Per world, synced to clients: balance and behaviour. */
    SERVER,
    /** Per player's game: visuals only. */
    CLIENT,
    /** Loaded at startup on both sides: switches that registration or recipes read. */
    COMMON
}
