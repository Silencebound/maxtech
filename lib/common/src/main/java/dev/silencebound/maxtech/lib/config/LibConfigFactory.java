package dev.silencebound.maxtech.lib.config;

import dev.silencebound.maxtech.lib.api.config.ConfigBuilder;
import dev.silencebound.maxtech.lib.api.config.ConfigFile;
import dev.silencebound.maxtech.lib.api.config.ConfigType;
import dev.silencebound.maxtech.lib.api.config.Configs;
import dev.silencebound.maxtech.lib.api.config.PresetConfig;
import dev.silencebound.maxtech.lib.platform.Platform;

/** Lib's side of the api's {@link Configs}: preset configs and plain files through the {@link Platform}. */
public final class LibConfigFactory implements Configs.Factory {
    private LibConfigFactory() {
    }

    /**
     * Sets up {@link Configs}; Lib's init calls it first, and unit tests that load a mod's config classes too. Unit
     * tests of a mod on the api jar alone cannot reach this class; they set a fake with {@link Configs#setFactory}.
     */
    public static void install() {
        Configs.setFactory(new LibConfigFactory());
    }

    @Override
    public PresetConfig presetConfig(String modId, Class<?> resourceAnchor) {
        return new LibPresetConfig(modId, resourceAnchor);
    }

    @Override
    public ConfigBuilder builder(String modId) {
        return Platform.get().config().builder(modId);
    }

    @Override
    public ConfigFile register(String modId, ConfigType type, ConfigBuilder builder) {
        return Platform.get().config().register(modId, type, builder);
    }
}
