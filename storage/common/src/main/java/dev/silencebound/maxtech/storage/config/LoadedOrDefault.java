package dev.silencebound.maxtech.storage.config;

import dev.silencebound.maxtech.lib.api.config.ConfigBuilder;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A config builder whose values read as their default while the file is not loaded, instead of throwing as NeoForge's
 * do (issue #333). Block entities read server config when they load (a controller's stats and buffer), and a client
 * without a world loads them too: GuideME's scenes and the guide's web export place structures into a level of their
 * own. A client in a world reads the values the server sent it, so only reads outside a world see the defaults.
 *
 * <p>
 * Values are read through the wrapped builder's suppliers while {@code loaded} answers true; a file that unloads
 * between that check and the read (singleplayer's server closing the world) also gives the default, as in
 * {@link StorageServerConfig#loadedOr}. Declare on the server thread before registering; reads from any thread.
 */
final class LoadedOrDefault implements ConfigBuilder {
    private final ConfigBuilder inner;
    private final BooleanSupplier loaded;

    /**
     * @param inner
     *            the builder that declares the values in the file
     * @param loaded
     *            whether the file is loaded now; read on every value read
     */
    LoadedOrDefault(ConfigBuilder inner, BooleanSupplier loaded) {
        this.inner = inner;
        this.loaded = loaded;
    }

    @Override
    public ConfigBuilder comment(String... lines) {
        inner.comment(lines);
        return this;
    }

    @Override
    public ConfigBuilder worldRestart() {
        inner.worldRestart();
        return this;
    }

    @Override
    public ConfigBuilder gameRestart() {
        inner.gameRestart();
        return this;
    }

    @Override
    public ConfigBuilder push(String section) {
        inner.push(section);
        return this;
    }

    @Override
    public ConfigBuilder pop() {
        inner.pop();
        return this;
    }

    @Override
    public BooleanSupplier define(String path, boolean defaultValue) {
        BooleanSupplier value = inner.define(path, defaultValue);
        return () -> {
            if (loaded.getAsBoolean()) {
                try {
                    return value.getAsBoolean();
                } catch (IllegalStateException | NullPointerException e) {
                    return defaultValue;
                }
            }
            return defaultValue;
        };
    }

    @Override
    public IntSupplier defineInRange(String path, int defaultValue, int min, int max) {
        IntSupplier value = inner.defineInRange(path, defaultValue, min, max);
        return () -> {
            if (loaded.getAsBoolean()) {
                try {
                    return value.getAsInt();
                } catch (IllegalStateException | NullPointerException e) {
                    return defaultValue;
                }
            }
            return defaultValue;
        };
    }

    @Override
    public LongSupplier defineInRange(String path, long defaultValue, long min, long max) {
        LongSupplier value = inner.defineInRange(path, defaultValue, min, max);
        return () -> {
            if (loaded.getAsBoolean()) {
                try {
                    return value.getAsLong();
                } catch (IllegalStateException | NullPointerException e) {
                    return defaultValue;
                }
            }
            return defaultValue;
        };
    }

    @Override
    public DoubleSupplier defineInRange(String path, double defaultValue, double min, double max) {
        DoubleSupplier value = inner.defineInRange(path, defaultValue, min, max);
        return () -> {
            if (loaded.getAsBoolean()) {
                try {
                    return value.getAsDouble();
                } catch (IllegalStateException | NullPointerException e) {
                    return defaultValue;
                }
            }
            return defaultValue;
        };
    }

    @Override
    public Supplier<String> define(String path, String defaultValue) {
        return orDefault(inner.define(path, defaultValue), defaultValue);
    }

    @Override
    public Supplier<String> define(String path, String defaultValue, Predicate<String> valid) {
        return orDefault(inner.define(path, defaultValue, valid), defaultValue);
    }

    @Override
    public <E extends Enum<E>> Supplier<E> defineEnum(String path, E defaultValue) {
        return orDefault(inner.defineEnum(path, defaultValue), defaultValue);
    }

    private <T> Supplier<T> orDefault(Supplier<T> value, T defaultValue) {
        return () -> {
            if (loaded.getAsBoolean()) {
                try {
                    return value.get();
                } catch (IllegalStateException | NullPointerException e) {
                    return defaultValue;
                }
            }
            return defaultValue;
        };
    }
}
