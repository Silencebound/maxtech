package dev.silencebound.maxtech.lib.api.config;

import dev.silencebound.maxtech.lib.api.guide.GuideConfigValues;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A mod's server config whose defaults come from presets. Built-in presets ship in the mod's jar at
 * {@code <modid>/presets/<name>.json} ({@code standalone} is required and holds every default; {@code pack} is the
 * other built-in one); server owners override them or add their own in {@code config/<modid>/presets/}. The first value
 * in the file is {@code preset}; untouched values follow it, edited ones are kept, and {@link #CUSTOM_PRESET} never
 * changes anything. Made with {@link Configs#presetConfig}; values are declared on {@link #builder()} before
 * {@link #register()}.
 */
public interface PresetConfig {
    /** The preset name that switches preset handling off. */
    String CUSTOM_PRESET = "custom";

    /** Declares this config's values; paths must match the keys in the preset JSON. */
    ConfigBuilder builder();

    /** The configured preset name. */
    Supplier<String> preset();

    /**
     * Registers the file and its preset handling. Call once, after every value is declared and during mod construction.
     * Listeners added to the returned file with {@link ConfigFile#onLoad} run after the preset has been applied.
     */
    ConfigFile register();

    /**
     * Where the built-in presets and the declared spec disagree: a spec path {@code standalone.json} lacks, or a preset
     * key no spec path matches. Empty when they agree. Needs {@link #register()} to have run.
     */
    List<String> presetProblems();

    /**
     * {@link #presetProblems()}, and also every spec path {@code inEveryPreset} accepts that a built-in preset other
     * than {@code standalone} lacks.
     */
    List<String> presetProblems(Predicate<String> inEveryPreset);

    /**
     * This config's values for the guide's {@code <Config>} tag: every declared path but {@code preset}; the value in
     * effect while the file is loaded (on a client in a world, the server's synced copy), else the default. Register it
     * with {@code GuideConfigValues.register(modId, config.guideSource())}. Any thread; reads the file only when asked.
     */
    GuideConfigValues.Source guideSource();

    /** The {@code standalone} preset's boolean for {@code path}; throws if the preset lacks it. */
    boolean booleanDefault(String path);

    /** The {@code standalone} preset's int for {@code path}; throws if the preset lacks it. */
    int intDefault(String path);

    /** The {@code standalone} preset's long for {@code path}; throws if the preset lacks it. */
    long longDefault(String path);

    /** The {@code standalone} preset's double for {@code path}; throws if the preset lacks it. */
    double doubleDefault(String path);

    /** The {@code standalone} preset's string for {@code path}; throws if the preset lacks it. */
    String stringDefault(String path);

    /** The {@code standalone} preset's constant of {@code type} for {@code path}; throws if the preset lacks it. */
    <E extends Enum<E>> E enumDefault(String path, Class<E> type);
}
