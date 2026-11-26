package dev.silencebound.maxtech.lib.platform;

/**
 * Server config holds balance and behaviour and is synced to clients; client config holds visuals only. Common config
 * loads at startup on both sides, before datapacks, so it is the only place for switches that recipes or registration
 * read (content toggles); keep it to those.
 */
public enum ConfigType {
    SERVER, CLIENT, COMMON
}
