package dev.silencebound.maxtech.farms.config;

import dev.silencebound.maxtech.farms.MaxtechFarms;
import dev.silencebound.maxtech.lib.api.config.ConfigBuilder;
import dev.silencebound.maxtech.lib.api.config.ConfigFile;
import dev.silencebound.maxtech.lib.api.config.Configs;
import dev.silencebound.maxtech.lib.api.config.PresetConfig;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Farms' server config: balance and behaviour, synced to clients. Defaults are the built-in {@code standalone} preset
 * in {@code maxtech_farms/presets/}; {@code pack} holds what modpacks change. Values are declared on {@code BUILDER} in
 * the static initialiser, before {@link #register()}. FM1 adds them with the features that read them.
 */
public final class FarmsServerConfig {
    private static final PresetConfig CONFIG = Configs.presetConfig(MaxtechFarms.MOD_ID, FarmsServerConfig.class);
    private static final ConfigBuilder BUILDER = CONFIG.builder();

    /** The configured preset name ({@code preset}). */
    public static final Supplier<String> PRESET = CONFIG.preset();

    private static final LongSupplier SAMPLER_MU_PER_SAMPLE;
    private static final LongSupplier SAMPLER_CAPACITY_MU;

    private static volatile @Nullable ConfigFile file;

    static {
        BUILDER.push("sampler");
        SAMPLER_MU_PER_SAMPLE = BUILDER.comment("MU the sampler spends on one sample.").defineInRange("muPerSample",
                CONFIG.longDefault("sampler.muPerSample"), 0L, 1L << 40);
        SAMPLER_CAPACITY_MU = BUILDER.comment("MU a fully charged sampler holds.").defineInRange("capacityMu",
                CONFIG.longDefault("sampler.capacityMu"), 1L, 1L << 40);
        BUILDER.pop();
    }

    private FarmsServerConfig() {
    }

    /** MU per sample ({@code sampler.muPerSample}); the preset default while no server config is loaded. */
    public static long samplerMuPerSample() {
        return loadedOr(SAMPLER_MU_PER_SAMPLE, "sampler.muPerSample");
    }

    /** The sampler's capacity in MU ({@code sampler.capacityMu}); the preset default while none is loaded. */
    public static long samplerCapacityMu() {
        return loadedOr(SAMPLER_CAPACITY_MU, "sampler.capacityMu");
    }

    /** Where the built-in presets and the declared values disagree; empty when they agree. */
    public static List<String> presetProblems() {
        return CONFIG.presetProblems();
    }

    /** Registers the file; called once from {@link MaxtechFarms#init()}. */
    public static void register() {
        file = CONFIG.register();
    }

    private static long loadedOr(LongSupplier value, String path) {
        ConfigFile current = file;
        if (current != null && current.isLoaded()) {
            try {
                return value.getAsLong();
            } catch (IllegalStateException | NullPointerException e) {
                // Unloaded between isLoaded() and the read (ConfigFile#isLoaded); fall back to the default.
            }
        }
        return CONFIG.longDefault(path);
    }
}
