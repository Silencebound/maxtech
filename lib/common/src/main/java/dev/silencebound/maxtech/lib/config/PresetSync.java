package dev.silencebound.maxtech.lib.config;

import dev.silencebound.maxtech.lib.api.config.PresetConfig;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.config.ConfigFile;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Applies the configured preset to a loaded config file. What the preset last wrote is kept in a state file next to the
 * config ({@code <config>-preset.json}); {@link PresetApplier} uses it to tell untouched values from edited ones.
 */
final class PresetSync {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String STATE_SUFFIX = "-preset.json";

    private final ConfigFile file;
    private final String presetPath;
    private final Presets presets;
    private final Path overrideDirectory;
    // save() fires a reload that re-enters sync() on the same thread; the nested call has nothing left to do.
    private boolean syncing;

    PresetSync(ConfigFile file, String presetPath, Presets presets, Path overrideDirectory) {
        this.file = file;
        this.presetPath = presetPath;
        this.presets = presets;
        this.overrideDirectory = overrideDirectory;
    }

    synchronized void sync(@Nullable Path configPath) {
        // A client's synced copy of the server config has no file; the server has already applied the preset.
        if (configPath == null || syncing) {
            return;
        }
        syncing = true;
        try {
            apply(configPath);
        } catch (IOException e) {
            LOGGER.error("Could not apply the config preset to {}", configPath, e);
        } finally {
            syncing = false;
        }
    }

    private void apply(Path configPath) throws IOException {
        String name = (String) file.get(presetPath);
        if (PresetConfig.CUSTOM_PRESET.equals(name)) {
            return;
        }
        Optional<Map<String, JsonElement>> preset = presets.load(name, overrideDirectory);
        if (preset.isEmpty()) {
            LOGGER.warn("Unknown config preset '{}'; leaving values as they are. Built-in presets are standalone, pack"
                    + " and custom; others go in {}", name, overrideDirectory);
            return;
        }
        Map<String, Object> target = typed(preset.get(), "preset " + name);
        Map<String, Object> current = new LinkedHashMap<>();
        for (String path : target.keySet()) {
            current.put(path, file.get(path));
        }
        Path statePath = statePath(configPath);
        PresetApplier.Result result = PresetApplier.apply(target, current, readState(statePath));
        if (!result.changes().isEmpty()) {
            result.changes().forEach(file::set);
            file.save();
            LOGGER.info("Config preset '{}' set {}", name, result.changes().keySet());
        }
        writeState(statePath, name, result.applied());
    }

    /** Converts JSON to typed values, skipping (with a warning) paths the config lacks and values of the wrong type. */
    private Map<String, Object> typed(Map<String, JsonElement> json, String source) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            String path = entry.getKey();
            if (path.equals(presetPath) || !file.paths().contains(path)) {
                LOGGER.warn("Ignoring '{}' in {}: not a config value", path, source);
                continue;
            }
            Optional<Object> value = PresetValues.fromJson(entry.getValue(), file.defaultValue(path));
            if (value.isEmpty()) {
                LOGGER.warn("Ignoring '{}' in {}: {} does not fit the value's type", path, source, entry.getValue());
                continue;
            }
            values.put(path, value.get());
        }
        return values;
    }

    private Map<String, Object> readState(Path statePath) {
        if (!Files.isRegularFile(statePath)) {
            return Map.of();
        }
        try (Reader reader = Files.newBufferedReader(statePath, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root.isJsonObject() && root.getAsJsonObject().get("values") instanceof JsonObject values) {
                Map<String, JsonElement> json = new LinkedHashMap<>();
                values.entrySet().forEach(entry -> json.put(entry.getKey(), entry.getValue()));
                return typed(json, statePath.toString());
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Ignoring unreadable preset state {}; untouched values will be reset to the preset", statePath,
                    e);
        }
        return Map.of();
    }

    private static void writeState(Path statePath, String preset, Map<String, Object> applied) throws IOException {
        JsonObject values = new JsonObject();
        applied.forEach((path, value) -> values.add(path, PresetValues.toJson(value)));
        JsonObject root = new JsonObject();
        root.addProperty("preset", preset);
        root.add("values", values);
        try (Writer writer = Files.newBufferedWriter(statePath, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(root, writer);
        }
    }

    private static Path statePath(Path configPath) {
        String fileName = configPath.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return configPath.resolveSibling(base + STATE_SUFFIX);
    }
}
