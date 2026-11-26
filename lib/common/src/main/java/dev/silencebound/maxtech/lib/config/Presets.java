package dev.silencebound.maxtech.lib.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Preset JSON of one mod: flat objects of dotted config paths to values. Built-in presets ship in the mod's jar under
 * {@code <modid>/presets/}; a file with the same name in the override directory ({@code config/<modid>/presets/})
 * replaces one, and new names add presets. Every preset is layered on {@code standalone}, so a pack's preset only lists
 * what it changes.
 */
final class Presets {
    static final String BASE = "standalone";
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]+");

    private final Class<?> anchor;
    private final String root;

    /**
     * @param anchor
     *            a class from the jar that ships the presets; resources are looked up through it because each mod's jar
     *            is its own module and Lib's classes cannot see another mod's resources
     * @param modId
     *            the owning mod; built-in presets live under {@code /<modId>/presets/}
     */
    Presets(Class<?> anchor, String modId) {
        this.anchor = anchor;
        this.root = "/" + modId + "/presets/";
    }

    /** The built-in {@code standalone} preset, which also supplies the spec defaults. */
    Map<String, JsonElement> base() {
        Map<String, JsonElement> base = builtin(BASE);
        if (base == null) {
            throw new IllegalStateException("Built-in preset " + root + BASE + ".json is missing from the jar");
        }
        return base;
    }

    /** The preset named {@code name}, layered on {@code standalone}; empty if no such preset exists. */
    Optional<Map<String, JsonElement>> load(String name, Path overrideDirectory) throws IOException {
        if (!NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        Map<String, JsonElement> override = read(overrideDirectory.resolve(name + ".json"));
        Map<String, JsonElement> preset = override != null ? override : builtin(name);
        if (preset == null) {
            return Optional.empty();
        }
        Map<String, JsonElement> layered = new LinkedHashMap<>(base());
        Map<String, JsonElement> baseOverride = BASE.equals(name)
                ? null
                : read(overrideDirectory.resolve(BASE + ".json"));
        if (baseOverride != null) {
            layered.putAll(baseOverride);
        }
        layered.putAll(preset);
        return Optional.of(layered);
    }

    @Nullable
    Map<String, JsonElement> builtin(String name) {
        InputStream stream = anchor.getResourceAsStream(root + name + ".json");
        if (stream == null) {
            return null;
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return parse(reader, "built-in preset " + root + name);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read built-in preset " + root + name, e);
        }
    }

    private static @Nullable Map<String, JsonElement> read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader, file.toString());
        }
    }

    private static Map<String, JsonElement> parse(Reader reader, String source) throws IOException {
        JsonElement root;
        try {
            root = JsonParser.parseReader(reader);
        } catch (RuntimeException e) {
            throw new IOException("Malformed preset JSON in " + source, e);
        }
        if (!root.isJsonObject()) {
            throw new IOException("Preset " + source + " is not a JSON object");
        }
        JsonObject object = root.getAsJsonObject();
        Map<String, JsonElement> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            values.put(entry.getKey(), entry.getValue());
        }
        return values;
    }
}
