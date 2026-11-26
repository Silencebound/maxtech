package dev.silencebound.maxtech.storage.guide;

import dev.silencebound.maxtech.lib.api.guide.GuideConfigValues;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;

/**
 * What Storage's guide pages read from the game: its server config for {@code <Config key="..." />}, the value in
 * effect on the server the player is on. Lib's comparison-table columns, screen previews and live values went with
 * Lib's guide (MGM item 3).
 */
public final class StorageGuideData {
    private StorageGuideData() {
    }

    /** Registers the config source; during mod construction, on both sides. */
    public static void register() {
        GuideConfigValues.register(MaxtechStorage.MOD_ID, StorageServerConfig.guideSource());
    }
}
