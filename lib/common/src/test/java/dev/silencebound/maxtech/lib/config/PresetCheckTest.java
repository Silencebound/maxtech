package dev.silencebound.maxtech.lib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PresetCheckTest {
    private static final JsonElement ONE = new JsonPrimitive(1);

    @Test
    void agreeingPresetsHaveNoProblems() {
        var problems = PresetCheck.problems(Set.of("preset", "a", "b"),
                Map.of("standalone", Map.of("a", ONE, "b", ONE), "pack", Map.of("b", ONE)));

        assertEquals(List.of(), problems);
    }

    @Test
    void reportsASpecPathStandaloneLacks() {
        var problems = PresetCheck.problems(Set.of("a", "b"), Map.of("standalone", Map.of("a", ONE)));

        assertEquals(List.of("standalone.json has no value for b"), problems);
    }

    @Test
    void reportsAMarkedPathAPackPresetLacks() {
        var presets = Map.of("standalone", Map.of("energy.a", ONE, "b", ONE), "pack", Map.of("b", ONE));

        assertEquals(List.of(), PresetCheck.problems(Set.of("energy.a", "b"), presets));
        assertEquals(List.of("pack.json has no value for energy.a, which every preset sets"),
                PresetCheck.problems(Set.of("energy.a", "b"), presets, path -> path.startsWith("energy.")));
    }

    @Test
    void reportsAPresetKeyNoSpecDefines() {
        var problems = PresetCheck.problems(Set.of("a"),
                Map.of("standalone", Map.of("a", ONE), "pack", Map.of("gone", ONE)));

        assertEquals(List.of("pack.json has gone, which no config value defines"), problems);
    }
}
