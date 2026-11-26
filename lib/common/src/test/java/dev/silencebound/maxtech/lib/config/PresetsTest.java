package dev.silencebound.maxtech.lib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PresetsTest {
    private static final Presets LIB = new Presets(Presets.class, "maxtech_lib");

    @TempDir
    Path overrides;

    @Test
    void builtInPresetsLoadAndCoverTheSamePaths() throws IOException {
        var standalone = LIB.load("standalone", overrides).orElseThrow();
        var pack = LIB.load("pack", overrides).orElseThrow();

        assertEquals(standalone.keySet(), pack.keySet());
    }

    @Test
    void overrideFileLayersOnStandalone() throws IOException {
        Files.writeString(overrides.resolve("myserver.json"), "{\"sync.listUpdatesPerSecond\": 8}");

        var preset = LIB.load("myserver", overrides).orElseThrow();

        assertEquals(new JsonPrimitive(8), preset.get("sync.listUpdatesPerSecond"));
        assertEquals(LIB.base().get("networks.unloadedChunkMode"), preset.get("networks.unloadedChunkMode"));
    }

    @Test
    void overrideReplacesBuiltInPresetOfTheSameName() throws IOException {
        Files.writeString(overrides.resolve("pack.json"), "{\"networks.allowAnchors\": false}");

        assertEquals(new JsonPrimitive(false), LIB.load("pack", overrides).orElseThrow().get("networks.allowAnchors"));
    }

    @Test
    void unknownOrInvalidNamesAreEmpty() throws IOException {
        assertTrue(LIB.load("nope", overrides).isEmpty());
        assertTrue(LIB.load("../secrets", overrides).isEmpty());
    }

    @Test
    void malformedJsonIsReported() throws IOException {
        Files.writeString(overrides.resolve("broken.json"), "{ not json");

        assertThrows(IOException.class, () -> LIB.load("broken", overrides));
    }

    @Test
    void eachModReadsItsOwnPresets() throws IOException {
        Presets other = new Presets(PresetsTest.class, "presets_test_mod");

        var pack = other.load("pack", overrides).orElseThrow();

        assertEquals(new JsonPrimitive(2), pack.get("device.range"));
        assertEquals(new JsonPrimitive(20), pack.get("device.cooldown"), "pack is layered on the mod's own standalone");
        assertFalse(pack.containsKey("scheduler.ownerBudgetMicros"), "Lib's presets leaked into another mod's");
        assertFalse(LIB.base().containsKey("device.cooldown"), "another mod's presets leaked into Lib's");
    }

    @Test
    void missingStandaloneFailsLoudly() {
        Presets missing = new Presets(PresetsTest.class, "no_such_mod");

        assertThrows(IllegalStateException.class, missing::base);
    }
}
