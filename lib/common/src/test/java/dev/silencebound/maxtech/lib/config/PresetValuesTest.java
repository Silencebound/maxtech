package dev.silencebound.maxtech.lib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonPrimitive;
import dev.silencebound.maxtech.lib.api.network.UnloadedChunkMode;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PresetValuesTest {
    @Test
    void enumsMatchIgnoringCaseAndWriteLowerCase() {
        assertEquals(Optional.of(UnloadedChunkMode.PAUSE),
                PresetValues.fromJson(new JsonPrimitive("Pause"), UnloadedChunkMode.TRUNCATE));
        assertEquals(new JsonPrimitive("pause"), PresetValues.toJson(UnloadedChunkMode.PAUSE));
    }

    @Test
    void numbersConvertToTheDefaultsType() {
        assertEquals(Optional.of(2.0), PresetValues.fromJson(new JsonPrimitive(2), 1.0));
        assertEquals(Optional.of(64), PresetValues.fromJson(new JsonPrimitive(64), 1));
        assertEquals(Optional.of(64L), PresetValues.fromJson(new JsonPrimitive(64), 1L));
    }

    @Test
    void mismatchedTypesAreRejected() {
        assertTrue(PresetValues.fromJson(new JsonPrimitive(1.5), 1).isEmpty());
        assertTrue(PresetValues.fromJson(new JsonPrimitive("yes"), true).isEmpty());
        assertTrue(PresetValues.fromJson(new JsonPrimitive("melt"), UnloadedChunkMode.TRUNCATE).isEmpty());
        assertTrue(PresetValues.fromJson(new JsonPrimitive(3_000_000_000L), 1).isEmpty());
    }
}
