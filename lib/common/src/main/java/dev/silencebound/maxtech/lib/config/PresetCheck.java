package dev.silencebound.maxtech.lib.config;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Compares a config spec with the built-in presets. {@code standalone} must give every spec path a value, because it
 * supplies the defaults; every preset, {@code pack} included, may only name paths the spec defines. A pack preset may
 * leave a path out, since presets are layered on {@code standalone} (ADR-0003), except paths the caller marks as needed
 * in every preset (the energy keys, MLE item 5), so a pack maker finds each of them in every file.
 */
final class PresetCheck {
    private PresetCheck() {
    }

    /** {@link #problems(Set, Map, Predicate)} with no path needed in every preset. */
    static List<String> problems(Set<String> specPaths, Map<String, Map<String, JsonElement>> presets) {
        return problems(specPaths, presets, path -> false);
    }

    /**
     * Lists the disagreements between a spec and its presets.
     *
     * @param specPaths
     *            every dotted path the spec declares
     * @param presets
     *            the built-in presets by name, including {@code standalone}
     * @param inEveryPreset
     *            spec paths every built-in preset must set, not only {@code standalone}
     * @return one message per disagreement; empty when there is none
     */
    static List<String> problems(Set<String> specPaths, Map<String, Map<String, JsonElement>> presets,
            Predicate<String> inEveryPreset) {
        List<String> problems = new ArrayList<>();
        Map<String, JsonElement> base = presets.get(Presets.BASE);
        if (base == null) {
            problems.add(Presets.BASE + ".json is missing");
        }
        for (String path : specPaths) {
            if (path.equals(PresetConfig.PRESET_PATH)) {
                continue;
            }
            if (base != null && !base.containsKey(path)) {
                problems.add(Presets.BASE + ".json has no value for " + path);
            }
            if (inEveryPreset.test(path)) {
                for (Map.Entry<String, Map<String, JsonElement>> preset : presets.entrySet()) {
                    if (!preset.getKey().equals(Presets.BASE) && !preset.getValue().containsKey(path)) {
                        problems.add(preset.getKey() + ".json has no value for " + path + ", which every preset sets");
                    }
                }
            }
        }
        for (Map.Entry<String, Map<String, JsonElement>> preset : presets.entrySet()) {
            for (String key : preset.getValue().keySet()) {
                if (!specPaths.contains(key)) {
                    problems.add(preset.getKey() + ".json has " + key + ", which no config value defines");
                }
            }
        }
        return problems;
    }
}
