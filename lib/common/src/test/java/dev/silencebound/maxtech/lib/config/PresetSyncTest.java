package dev.silencebound.maxtech.lib.config;

import dev.silencebound.maxtech.lib.api.config.PresetConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.network.UnloadedChunkMode;
import dev.silencebound.maxtech.lib.api.config.ConfigFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PresetSyncTest {
    @TempDir
    Path dir;

    private FakeConfig config;
    private PresetSync sync;
    private Path configPath;
    private Path statePath;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(dir.resolve("presets"));
        config = new FakeConfig();
        sync = new PresetSync(config, "preset", new Presets(Presets.class, "maxtech_lib"), dir.resolve("presets"));
        config.onLoad(sync::sync);
        configPath = dir.resolve("maxtech_lib-server.toml");
        statePath = dir.resolve("maxtech_lib-server-preset.json");
    }

    @Test
    void freshConfigIsBroughtToThePresetAndSavedOnce() {
        config.values.put("scheduler.ownerBudgetMicros", 300);

        sync.sync(configPath);

        assertEquals(250, config.values.get("scheduler.ownerBudgetMicros"));
        assertEquals(1, config.saves, "save() re-enters sync(); the nested call must not save again");
        assertTrue(Files.isRegularFile(statePath));
    }

    @Test
    void editSurvivesSwitchingToAnotherPreset() throws IOException {
        sync.sync(configPath);
        config.values.put("scheduler.ownerBudgetMicros", 200);
        Files.writeString(dir.resolve("presets/strict.json"),
                "{\"scheduler.ownerBudgetMicros\": 50, \"networks.allowAnchors\": false}");
        config.values.put("preset", "strict");

        sync.sync(configPath);

        assertEquals(200, config.values.get("scheduler.ownerBudgetMicros"));
        assertEquals(false, config.values.get("networks.allowAnchors"));
    }

    @Test
    void customChangesNothingAndWritesNoState() {
        config.values.put("preset", PresetConfig.CUSTOM_PRESET);
        config.values.put("scheduler.ownerBudgetMicros", 300);

        sync.sync(configPath);

        assertEquals(300, config.values.get("scheduler.ownerBudgetMicros"));
        assertEquals(0, config.saves);
        assertFalse(Files.exists(statePath));
    }

    @Test
    void unknownPresetChangesNothing() {
        config.values.put("preset", "nonexistent");
        config.values.put("scheduler.ownerBudgetMicros", 300);

        sync.sync(configPath);

        assertEquals(300, config.values.get("scheduler.ownerBudgetMicros"));
        assertFalse(Files.exists(statePath));
    }

    @Test
    void syncedClientCopyWithoutFileIsIgnored() {
        config.values.put("scheduler.ownerBudgetMicros", 300);

        sync.sync(null);

        assertEquals(300, config.values.get("scheduler.ownerBudgetMicros"));
        assertEquals(0, config.saves);
    }

    @Test
    void unreadableStateFileCountsAsNoState() throws IOException {
        Files.writeString(statePath, "{ broken");
        config.values.put("scheduler.ownerBudgetMicros", 300);

        sync.sync(configPath);

        assertEquals(250, config.values.get("scheduler.ownerBudgetMicros"));
    }

    /** Map-backed stand-in whose save() fires the load listeners, as NeoForge's reload does. */
    private final class FakeConfig implements ConfigFile {
        final Map<String, Object> defaults = new LinkedHashMap<>();
        final Map<String, Object> values = new LinkedHashMap<>();
        final List<Consumer<@Nullable Path>> listeners = new ArrayList<>();
        int saves;

        FakeConfig() {
            defaults.put("preset", "standalone");
            defaults.put("networks.unloadedChunkMode", UnloadedChunkMode.TRUNCATE);
            defaults.put("networks.allowAnchors", true);
            defaults.put("scheduler.ownerBudgetMicros", 250);
            values.putAll(defaults);
        }

        @Override
        public Set<String> paths() {
            return defaults.keySet();
        }

        @Override
        public boolean isLoaded() {
            return true;
        }

        @Override
        public Object get(String path) {
            return Objects.requireNonNull(values.get(path), path);
        }

        @Override
        public Object defaultValue(String path) {
            return Objects.requireNonNull(defaults.get(path), path);
        }

        @Override
        public void set(String path, Object value) {
            values.put(path, value);
        }

        @Override
        public void save() {
            saves++;
            for (Consumer<@Nullable Path> listener : listeners) {
                listener.accept(configPath);
            }
        }

        @Override
        public void onLoad(Consumer<@Nullable Path> listener) {
            listeners.add(listener);
        }
    }
}
