package dev.silencebound.maxtech.lib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.guide.GuideConfigValues;
import dev.silencebound.maxtech.lib.api.config.ConfigFile;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** A preset config's values as guide pages read them ({@link LibPresetConfig#guideSource()}). */
class GuideConfigSourceTest {
    private static final class FakeFile implements ConfigFile {
        final Map<String, Object> defaults = new LinkedHashMap<>();
        final Map<String, Object> values = new LinkedHashMap<>();
        boolean loaded = true;
        boolean failing;

        FakeFile() {
            defaults.put("preset", "standalone");
            defaults.put("controller.inputPerTick", 1000L);
            defaults.put("claims.enabled", true);
            values.putAll(defaults);
        }

        @Override
        public Set<String> paths() {
            return defaults.keySet();
        }

        @Override
        public boolean isLoaded() {
            return loaded;
        }

        @Override
        public Object get(String path) {
            if (failing) {
                throw new IllegalStateException("unloaded meanwhile");
            }
            return Objects.requireNonNull(values.get(path));
        }

        @Override
        public Object defaultValue(String path) {
            return Objects.requireNonNull(defaults.get(path));
        }

        @Override
        public void set(String path, Object value) {
            values.put(path, value);
        }

        @Override
        public void save() {
        }

        @Override
        public void onLoad(Consumer<@Nullable Path> listener) {
        }
    }

    @Test
    void valuesInEffectAreMarkedWhenTheyDifferFromTheDefault() {
        FakeFile file = new FakeFile();
        GuideConfigValues.Source source = LibPresetConfig.guideSource(() -> file);
        assertEquals(Set.of("controller.inputPerTick", "claims.enabled"), source.keys(), "the preset name is no value");
        GuideConfigValues.Value plain = source.value("controller.inputPerTick").orElseThrow();
        assertEquals(1000L, plain.current());
        assertFalse(plain.changed());
        file.set("controller.inputPerTick", 500L);
        GuideConfigValues.Value changed = source.value("controller.inputPerTick").orElseThrow();
        assertEquals(500L, changed.current());
        assertEquals(1000L, changed.defaultValue());
        assertTrue(changed.changed());
        assertEquals(Optional.empty(), source.value("preset"));
        assertEquals(Optional.empty(), source.value("nope"));
    }

    @Test
    void withoutALoadedFileTheDefaultShows() {
        FakeFile file = new FakeFile();
        file.set("controller.inputPerTick", 500L);
        GuideConfigValues.Source source = LibPresetConfig.guideSource(() -> file);
        file.loaded = false;
        assertEquals(1000L, source.value("controller.inputPerTick").orElseThrow().current(), "no world");
        file.loaded = true;
        file.failing = true;
        assertEquals(1000L, source.value("controller.inputPerTick").orElseThrow().current(), "unloaded meanwhile");
        GuideConfigValues.Source unregistered = LibPresetConfig.guideSource(() -> null);
        assertEquals(Set.of(), unregistered.keys());
        assertEquals(Optional.empty(), unregistered.value("claims.enabled"));
    }
}
