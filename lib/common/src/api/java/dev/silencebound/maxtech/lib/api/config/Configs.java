package dev.silencebound.maxtech.lib.api.config;

import org.jetbrains.annotations.ApiStatus;

/**
 * Where a mod gets its config files from Lib. A server config with presets comes from {@link #presetConfig}; a plain
 * client or common file is declared on {@link #builder} and added with {@link #register}. Call during mod construction;
 * Lib sets this up in its own construction, which runs before every mod that depends on it.
 */
public final class Configs {
    private static volatile Factory factory = new Factory() {
        @Override
        public PresetConfig presetConfig(String modId, Class<?> resourceAnchor) {
            throw notReady();
        }

        @Override
        public ConfigBuilder builder(String modId) {
            throw notReady();
        }

        @Override
        public ConfigFile register(String modId, ConfigType type, ConfigBuilder builder) {
            throw notReady();
        }
    };

    private Configs() {
    }

    /**
     * A preset config for {@code modId}, reading the built-in {@code standalone} preset and declaring the
     * {@code preset} value. Throws if the jar has no {@code <modid>/presets/standalone.json}.
     *
     * @param resourceAnchor
     *            any class from the mod's own jar, used to find its preset resources
     */
    public static PresetConfig presetConfig(String modId, Class<?> resourceAnchor) {
        return factory.presetConfig(modId, resourceAnchor);
    }

    /**
     * A builder for a config file of {@code modId} without presets; values get {@code <modid>.configuration.<path>}.
     */
    public static ConfigBuilder builder(String modId) {
        return factory.builder(modId);
    }

    /** Registers the file declared on {@code builder} as {@code modId}'s config of {@code type}. */
    public static ConfigFile register(String modId, ConfigType type, ConfigBuilder builder) {
        return factory.register(modId, type, builder);
    }

    /** Sets where the methods above get their files; Lib calls it once during init. */
    @ApiStatus.Internal
    public static void setFactory(Factory source) {
        factory = source;
    }

    private static IllegalStateException notReady() {
        return new IllegalStateException("MaxTech Lib has not set up configs yet");
    }

    /** Lib's side of {@link Configs}. */
    @ApiStatus.Internal
    public interface Factory {
        /** See {@link Configs#presetConfig}. */
        PresetConfig presetConfig(String modId, Class<?> resourceAnchor);

        /** See {@link Configs#builder}. */
        ConfigBuilder builder(String modId);

        /** See {@link Configs#register}. */
        ConfigFile register(String modId, ConfigType type, ConfigBuilder builder);
    }
}
