package dev.silencebound.maxtech.lib.config;

import com.google.gson.JsonElement;
import dev.silencebound.maxtech.lib.api.guide.GuideConfigValues;

import dev.silencebound.maxtech.lib.platform.ConfigBuilder;
import dev.silencebound.maxtech.lib.platform.ConfigFile;
import dev.silencebound.maxtech.lib.platform.ConfigType;
import dev.silencebound.maxtech.lib.platform.Platform;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * A mod's server config whose defaults come from preset JSON (PRD 5.7, ADR-0003). Every suite mod with balance values
 * creates one during mod construction, declares its values on {@link #builder()} with defaults from the
 * {@code *Default} methods, and calls {@link #register()} once.
 *
 * <p>
 * Built-in presets ship in the mod's jar at {@code <modid>/presets/<name>.json}; server owners override them or add
 * their own in {@code config/<modid>/presets/}. The first value in the file is {@code preset}; untouched values follow
 * it, edited ones are kept, and {@code custom} never changes anything. What a preset last wrote is tracked per mod in a
 * state file next to the config file.
 */
public final class PresetConfig {
    /** The preset name that switches preset handling off. */
    public static final String CUSTOM_PRESET = "custom";
    private static final List<String> BUILT_IN = List.of(Presets.BASE, "pack");
    static final String PRESET_PATH = "preset";

    private final String modId;
    private final Presets presets;
    private final Map<String, JsonElement> defaults;
    private final ConfigBuilder builder;
    private final Supplier<String> preset;
    private volatile @Nullable ConfigFile file;

    /**
     * Reads the built-in {@code standalone} preset and declares the {@code preset} value. Throws if the jar has no
     * {@code <modid>/presets/standalone.json}.
     *
     * @param resourceAnchor
     *            any class from the mod's own jar, used to find its preset resources
     */
    public PresetConfig(String modId, Class<?> resourceAnchor) {
        this.modId = modId;
        this.builder = Platform.get().config().builder(modId);
        this.presets = new Presets(resourceAnchor, modId);
        this.defaults = presets.base();
        this.preset = builder.comment(
                "Default bundle for the values below: standalone, pack, custom, or the name of a JSON file in"
                        + " config/" + modId + "/presets/.",
                "Values you edit are kept; untouched values follow the preset, also across mod updates.",
                "custom never changes any value.").define(PRESET_PATH, Presets.BASE);
    }

    /** Declares this config's values; paths must match the keys in the preset JSON. */
    public ConfigBuilder builder() {
        return builder;
    }

    /** The configured preset name. */
    public Supplier<String> preset() {
        return preset;
    }

    /**
     * Registers the file and its preset handling. Call once, after every value is declared and during mod construction.
     * Listeners added to the returned file with {@link ConfigFile#onLoad} run after the preset has been applied.
     */
    public ConfigFile register() {
        ConfigFile file = Platform.get().config().register(modId, ConfigType.SERVER, builder);
        PresetSync sync = new PresetSync(file, PRESET_PATH, presets,
                Platform.get().config().configDirectory().resolve(modId).resolve("presets"));
        file.onLoad(sync::sync);
        this.file = file;
        return file;
    }

    /**
     * Where the built-in presets and the declared spec disagree: a spec path {@code standalone.json} lacks, or a preset
     * key no spec path matches. Empty when they agree. Needs {@link #register()} to have run.
     */
    public List<String> presetProblems() {
        return presetProblems(path -> false);
    }

    /**
     * {@link #presetProblems()}, and also every spec path {@code inEveryPreset} accepts that a built-in preset other
     * than {@code standalone} lacks (MLE item 5: energy keys appear in every preset).
     */
    public List<String> presetProblems(Predicate<String> inEveryPreset) {
        ConfigFile registered = file;
        if (registered == null) {
            throw new IllegalStateException(modId + " server config is not registered yet");
        }
        Map<String, Map<String, JsonElement>> builtin = new LinkedHashMap<>();
        for (String name : BUILT_IN) {
            Map<String, JsonElement> values = presets.builtin(name);
            if (values != null) {
                builtin.put(name, values);
            }
        }
        return PresetCheck.problems(registered.paths(), builtin, inEveryPreset);
    }

    /**
     * This config's values for the guide's {@code <Config>} tag: every declared path but {@code preset}; the value in
     * effect while the file is loaded (on a client in a world, the server's synced copy), else the default. Register it
     * with {@code GuideConfigValues.register(modId, config.guideSource())}. Any thread; reads the file only when asked.
     */
    public GuideConfigValues.Source guideSource() {
        return guideSource(() -> file);
    }

    /** {@link #guideSource()} over whatever file {@code files} gives at the time of each read; pure, for tests. */
    static GuideConfigValues.Source guideSource(Supplier<@Nullable ConfigFile> files) {
        return new GuideConfigValues.Source() {
            @Override
            public Set<String> keys() {
                ConfigFile registered = files.get();
                if (registered == null) {
                    return Set.of();
                }
                Set<String> keys = new LinkedHashSet<>(registered.paths());
                keys.remove(PRESET_PATH);
                return keys;
            }

            @Override
            @SuppressWarnings("IllegalCatch") // the file may unload between the check and the read (ConfigFile)
            public Optional<GuideConfigValues.Value> value(String key) {
                ConfigFile registered = files.get();

                if (registered == null || key.equals(PRESET_PATH) || !registered.paths().contains(key)) {
                    return Optional.empty();
                }
                Object fallback = registered.defaultValue(key);
                Object current = fallback;
                if (registered.isLoaded()) {
                    try {
                        current = registered.get(key);
                    } catch (RuntimeException e) {
                        current = fallback;
                    }
                }
                return Optional.of(new GuideConfigValues.Value(current, fallback));
            }
        };
    }

    public boolean booleanDefault(String path) {
        return base(path).getAsBoolean();
    }

    public int intDefault(String path) {
        return base(path).getAsInt();
    }

    public long longDefault(String path) {
        return base(path).getAsLong();
    }

    public double doubleDefault(String path) {
        return base(path).getAsDouble();
    }

    public String stringDefault(String path) {
        return base(path).getAsString();
    }

    public <E extends Enum<E>> E enumDefault(String path, Class<E> type) {
        return PresetValues.enumValue(type, base(path).getAsString());
    }

    /** The {@code standalone} value for {@code path}; a missing one fails startup, so no default lives in Java. */
    private JsonElement base(String path) {
        JsonElement value = defaults.get(path);
        if (value == null) {
            throw new IllegalStateException(
                    "Built-in preset " + modId + "/presets/" + Presets.BASE + ".json has no value for " + path);
        }
        return value;
    }
}
