package dev.silencebound.maxtech.storage.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.silencebound.maxtech.lib.platform.ConfigBuilder;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Server config values read as their defaults while the file is not loaded (issue #333): GuideME's scenes load block
 * entities, whose load reads config, on a client without a world.
 */
class LoadedOrDefaultTest {
    @Test
    void unloadedFileGivesTheDefaults() {
        ConfigBuilder builder = new LoadedOrDefault(new Unloaded(), () -> false);
        assertFalse(builder.push("a").define("flag", false).getAsBoolean());
        assertEquals(7, builder.defineInRange("int", 7, 0, 10).getAsInt());
        assertEquals(8L, builder.defineInRange("long", 8L, 0L, 10L).getAsLong());
        assertEquals(0.5, builder.defineInRange("double", 0.5, 0.0, 1.0).getAsDouble());
        assertEquals("x", builder.define("string", "x").get());
        assertEquals("y", builder.define("valid", "y", value -> true).get());
        assertEquals(TimeUnit.SECONDS, builder.defineEnum("enum", TimeUnit.SECONDS).get());
    }

    @Test
    void loadedFileGivesItsValues() {
        ConfigBuilder builder = new LoadedOrDefault(new Loaded(), () -> true);
        assertEquals(42, builder.defineInRange("int", 7, 0, 100).getAsInt());
        assertEquals(43L, builder.defineInRange("long", 8L, 0L, 100L).getAsLong());
        assertEquals(TimeUnit.DAYS, builder.defineEnum("enum", TimeUnit.SECONDS).get());
    }

    @Test
    void unloadedCheckWinsOverAReadableValue() {
        // A value that could be read is still ignored while the file is not loaded (review of #335).
        ConfigBuilder builder = new LoadedOrDefault(new Loaded(), () -> false);
        assertEquals(7, builder.defineInRange("int", 7, 0, 100).getAsInt());
        assertEquals(TimeUnit.SECONDS, builder.defineEnum("enum", TimeUnit.SECONDS).get());
    }

    @Test
    void fileUnloadedAfterTheCheckGivesTheDefaults() {
        // Singleplayer's server closing the world between the loaded check and the read.
        ConfigBuilder builder = new LoadedOrDefault(new Unloaded(), () -> true);
        assertEquals(7, builder.defineInRange("int", 7, 0, 10).getAsInt());
        assertEquals("x", builder.define("string", "x").get());
    }

    /** As NeoForge's values before the file loads: every read throws. */
    private static class Unloaded implements ConfigBuilder {
        static IllegalStateException unloaded() {
            return new IllegalStateException("Cannot get config value before config is loaded.");
        }

        @Override
        public ConfigBuilder comment(String... lines) {
            return this;
        }

        @Override
        public ConfigBuilder worldRestart() {
            return this;
        }

        @Override
        public ConfigBuilder gameRestart() {
            return this;
        }

        @Override
        public ConfigBuilder push(String section) {
            return this;
        }

        @Override
        public ConfigBuilder pop() {
            return this;
        }

        @Override
        public BooleanSupplier define(String path, boolean defaultValue) {
            return () -> {
                throw unloaded();
            };
        }

        @Override
        public IntSupplier defineInRange(String path, int defaultValue, int min, int max) {
            return () -> {
                throw unloaded();
            };
        }

        @Override
        public LongSupplier defineInRange(String path, long defaultValue, long min, long max) {
            return () -> {
                throw unloaded();
            };
        }

        @Override
        public DoubleSupplier defineInRange(String path, double defaultValue, double min, double max) {
            return () -> {
                throw unloaded();
            };
        }

        @Override
        public Supplier<String> define(String path, String defaultValue) {
            return () -> {
                throw unloaded();
            };
        }

        @Override
        public Supplier<String> define(String path, String defaultValue, Predicate<String> valid) {
            return () -> {
                throw unloaded();
            };
        }

        @Override
        public <E extends Enum<E>> Supplier<E> defineEnum(String path, E defaultValue) {
            return () -> {
                throw unloaded();
            };
        }
    }

    /** A loaded file whose values differ from their defaults. */
    private static final class Loaded extends Unloaded {
        @Override
        public IntSupplier defineInRange(String path, int defaultValue, int min, int max) {
            return () -> 42;
        }

        @Override
        public LongSupplier defineInRange(String path, long defaultValue, long min, long max) {
            return () -> 43L;
        }

        @Override
        @SuppressWarnings("unchecked") // the test passes TimeUnit only
        public <E extends Enum<E>> Supplier<E> defineEnum(String path, E defaultValue) {
            return () -> (E) TimeUnit.DAYS;
        }
    }
}
