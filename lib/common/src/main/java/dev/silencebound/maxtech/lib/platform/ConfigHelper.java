package dev.silencebound.maxtech.lib.platform;

import java.nio.file.Path;

public interface ConfigHelper {
    /**
     * A builder for one config file of {@code modId}; the id prefixes the translation keys of its values and sections
     * ({@link ConfigBuilder}).
     */
    ConfigBuilder builder(String modId);

    /**
     * Registers the spec built by {@code builder} as the {@code type} config file of {@code modId}. Call during mod
     * construction. Reading a value before its file is loaded throws {@link IllegalStateException}; server config only
     * loads with a world, so never read it from registration or common setup.
     */
    ConfigFile register(String modId, ConfigType type, ConfigBuilder builder);

    /** The game's config directory ({@code config/}), for files a mod manages next to its config specs. */
    Path configDirectory();
}
