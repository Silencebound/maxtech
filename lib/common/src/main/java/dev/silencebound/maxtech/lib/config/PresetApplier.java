package dev.silencebound.maxtech.lib.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decides which config values a preset rewrites. A value follows the preset while it still equals what the preset last
 * wrote (or when there is no record for it); once the owner changes it, it is left alone. Switching preset or updating
 * the mod therefore moves every untouched value to the new default and never overwrites an edit.
 */
final class PresetApplier {
    private PresetApplier() {
    }

    /**
     * @param changes
     *            values to write to the config
     * @param applied
     *            the new record of what the preset stands for per path, to persist for the next run
     */
    record Result(Map<String, Object> changes, Map<String, Object> applied) {
    }

    static Result apply(Map<String, Object> preset, Map<String, Object> current, Map<String, Object> lastApplied) {
        Map<String, Object> changes = new LinkedHashMap<>();
        Map<String, Object> applied = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : preset.entrySet()) {
            String path = entry.getKey();
            Object target = entry.getValue();
            Object value = current.get(path);
            Object last = lastApplied.get(path);
            if (last == null || last.equals(value)) {
                if (!target.equals(value)) {
                    changes.put(path, target);
                }
                applied.put(path, target);
            } else {
                // Edited by the owner: keep the edit and remember the preset value it replaced, so reverting the edit
                // hands the value back to the preset.
                applied.put(path, last);
            }
        }
        return new Result(changes, applied);
    }
}
