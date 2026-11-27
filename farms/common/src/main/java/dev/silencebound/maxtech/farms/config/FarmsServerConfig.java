package dev.silencebound.maxtech.farms.config;

import dev.silencebound.maxtech.farms.MaxtechFarms;
import dev.silencebound.maxtech.lib.api.config.ConfigBuilder;
import dev.silencebound.maxtech.lib.api.config.Configs;
import dev.silencebound.maxtech.lib.api.config.PresetConfig;
import java.util.List;
import java.util.function.Supplier;

/**
 * Farms' server config: balance and behaviour, synced to clients. Defaults are the built-in {@code standalone} preset
 * in {@code maxtech_farms/presets/}; {@code pack} holds what modpacks change. Values are declared on {@code BUILDER} in
 * the static initialiser, before {@link #register()}. FM1 adds them with the features that read them.
 */
public final class FarmsServerConfig {
    private static final PresetConfig CONFIG = Configs.presetConfig(MaxtechFarms.MOD_ID, FarmsServerConfig.class);
    @SuppressWarnings("unused") // values arrive with the FM1 features that read them
    private static final ConfigBuilder BUILDER = CONFIG.builder();

    /** The configured preset name ({@code preset}). */
    public static final Supplier<String> PRESET = CONFIG.preset();

    private FarmsServerConfig() {
    }

    /** Where the built-in presets and the declared values disagree; empty when they agree. */
    public static List<String> presetProblems() {
        return CONFIG.presetProblems();
    }

    /** Registers the file; called once from {@link MaxtechFarms#init()}. */
    public static void register() {
        CONFIG.register();
    }
}
