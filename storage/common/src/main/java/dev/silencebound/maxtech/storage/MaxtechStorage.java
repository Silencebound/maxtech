package dev.silencebound.maxtech.storage;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.config.StorageClientConfig;
import dev.silencebound.maxtech.storage.config.StorageCommonConfig;
import dev.silencebound.maxtech.storage.config.StorageModules;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.guide.GuideConfigText;
import dev.silencebound.maxtech.storage.guide.StorageGuideData;
import dev.silencebound.maxtech.storage.registry.ModContent;
import net.minecraft.resources.Identifier;

public final class MaxtechStorage {
    public static final String MOD_ID = "maxtech_storage";

    private MaxtechStorage() {
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    /** Called once from the loader entrypoint during mod construction. */
    public static void init() {
        Platform.get();
        StorageServerConfig.register();
        StorageCommonConfig.register();
        StorageClientConfig.register();
        ModContent.init();
        GuideConfigText.register();
        StorageModules.init();
        // The guide pages are GuideME's (MGM); its <Config> values come from the server config registered here.
        StorageGuideData.register();
    }
}
