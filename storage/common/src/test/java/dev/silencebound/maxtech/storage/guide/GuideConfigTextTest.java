package dev.silencebound.maxtech.storage.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.platform.ConfigFile;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The {@code <Config>} tag's key parsing, value lookup and formatting, without a game. */
class GuideConfigTextTest {
    @Test
    void numbersAreGroupedAndFollowedByTheUnit() {
        assertEquals("140,000 MU", GuideConfigText.text(140_000L, "MU").getString());
        assertEquals("64 channels", GuideConfigText.text(64, "channels").getString());
        assertEquals("1,024", GuideConfigText.text(1024, "").getString());
        assertEquals("-2,500", GuideConfigText.text(-2500, "").getString());
        assertEquals("0.25 %", GuideConfigText.text(0.25, "%").getString());
        assertEquals("2", GuideConfigText.text(2.0, "").getString());
        assertEquals("12,000", GuideConfigText.text(12_000.0, "").getString());
        assertEquals("ALWAYS", GuideConfigText.text("ALWAYS", "").getString());
    }

    @Test
    void booleansUseLangKeys() {
        assertEquals(GuideConfigText.ON_KEY, GuideConfigText.text(true, "").getString());
        assertEquals(GuideConfigText.OFF_KEY, GuideConfigText.text(false, "").getString());
    }

    @Test
    void keysNameStoragePathsOnly() {
        assertEquals(Optional.of("network.channels.adHoc"), GuideConfigText.path("network.channels.adHoc"));
        assertEquals(Optional.of("network.channels.adHoc"),
                GuideConfigText.path("maxtech_storage:network.channels.adHoc"));
        assertEquals(Optional.empty(), GuideConfigText.path("maxtech_lib:energy.feRate"));
        assertEquals(Optional.empty(), GuideConfigText.path(""));
    }

    @Test
    void valuesComeFromTheFileWhileLoadedAndTheDefaultOtherwise() {
        FakeFile file = new FakeFile();
        file.defaults.put("preset", "standalone");
        file.defaults.put("network.channels.adHoc", 8);
        file.values.put("network.channels.adHoc", 12);

        GuideConfigText.Value unloaded = GuideConfigText.value(file, "network.channels.adHoc").orElseThrow();
        assertEquals(8, unloaded.current());
        assertFalse(unloaded.changed());

        file.loaded = true;
        GuideConfigText.Value loaded = GuideConfigText.value(file, "network.channels.adHoc").orElseThrow();
        assertEquals(12, loaded.current());
        assertEquals(8, loaded.defaultValue());
        assertTrue(loaded.changed());

        assertEquals(Optional.empty(), GuideConfigText.value(file, "network.channels.nope"));
        assertEquals(Optional.empty(), GuideConfigText.value(file, "preset"));
        assertEquals(Optional.empty(), GuideConfigText.value(null, "network.channels.adHoc"));
    }

    @Test
    void aFileUnloadedMidReadFallsBackToTheDefault() {
        FakeFile file = new FakeFile();
        file.defaults.put("controller.bufferMu", 100_000L);
        file.loaded = true;
        file.failReads = true;
        assertEquals(100_000L, GuideConfigText.value(file, "controller.bufferMu").orElseThrow().current());
    }

    private static final class FakeFile implements ConfigFile {
        final Map<String, Object> defaults = new LinkedHashMap<>();
        final Map<String, Object> values = new LinkedHashMap<>();
        boolean loaded;
        boolean failReads;

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
            if (failReads) {
                throw new IllegalStateException("unloaded");
            }
            Object value = values.containsKey(path) ? values.get(path) : defaults.get(path);
            return Objects.requireNonNull(value, path);
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
        }

        @Override
        public void onLoad(Consumer<@Nullable Path> listener) {
        }
    }
}
