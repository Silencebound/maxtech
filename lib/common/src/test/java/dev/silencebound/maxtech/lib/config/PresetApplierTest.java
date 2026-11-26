package dev.silencebound.maxtech.lib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PresetApplierTest {
    @Test
    void firstRunWritesEveryDifferingValue() {
        var result = PresetApplier.apply(Map.of("a", 2, "b", true), Map.of("a", 1, "b", true), Map.of());

        assertEquals(Map.of("a", 2), result.changes());
        assertEquals(Map.of("a", 2, "b", true), result.applied());
    }

    @Test
    void untouchedValueFollowsNewPresetDefault() {
        var result = PresetApplier.apply(Map.of("a", 3), Map.of("a", 2), Map.of("a", 2));

        assertEquals(Map.of("a", 3), result.changes());
        assertEquals(Map.of("a", 3), result.applied());
    }

    @Test
    void editedValueIsKeptAndOldPresetValueRemembered() {
        var result = PresetApplier.apply(Map.of("a", 3), Map.of("a", 7), Map.of("a", 2));

        assertEquals(Map.of(), result.changes());
        assertEquals(Map.of("a", 2), result.applied());
    }

    @Test
    void revertingAnEditHandsTheValueBackToThePreset() {
        var result = PresetApplier.apply(Map.of("a", 3), Map.of("a", 2), Map.of("a", 2));

        assertEquals(Map.of("a", 3), result.changes());
    }

    @Test
    void pathsNotInThePresetAreForgotten() {
        var result = PresetApplier.apply(Map.of("a", 1), Map.of("a", 1, "gone", 5), Map.of("a", 1, "gone", 5));

        assertEquals(Map.of("a", 1), result.applied());
    }
}
