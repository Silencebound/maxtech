package dev.silencebound.maxtech.lib.api.config;

import java.nio.file.Path;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * A registered config file, addressed by dotted value paths ({@code networks.unloadedChunkMode}). Used by code that
 * manages values as a whole, such as presets; ordinary reads go through the suppliers {@link ConfigBuilder} returns.
 * Values are {@code Boolean}, {@code Integer}, {@code Long}, {@code Double}, {@code String} or an enum constant.
 */
public interface ConfigFile {
    /** Every value path in declaration order. */
    Set<String> paths();

    /**
     * Whether values can be read right now. False before the file is loaded and again after it is unloaded (a server
     * config when the world closes); reading a value then throws. Code outside a world, such as item tooltips, checks
     * this first.
     *
     * <p>
     * The answer is a snapshot: the file can be unloaded by the server thread or swapped by the config watcher right
     * after this returns. Callers on another thread (the client thread in singleplayer) must still be ready for a read
     * to fail and fall back to the default value.
     */
    boolean isLoaded();

    /** The current value; throws if the file is not loaded or {@code path} is unknown. */
    Object get(String path);

    /** The value the spec declares as default; available before loading. */
    Object defaultValue(String path);

    /**
     * Sets a value without writing the file. {@code value} must have the type of {@link #defaultValue(String)};
     * anything else throws {@link IllegalArgumentException}.
     */
    void set(String path, Object value);

    /** Writes the file. The loader then fires a reload, so {@link #onLoad} listeners must tolerate being re-entered. */
    void save();

    /**
     * Runs {@code listener} each time the file is loaded or reloaded, on the thread that triggers it: the server thread
     * on world load, the config watcher thread after an external edit, and whichever thread calls {@link #save()} (for
     * example the client thread when a config screen saves in singleplayer). The argument is the file's path, or
     * {@code null} when the values did not come from a file, as for a client's synced copy of a server config.
     */
    void onLoad(Consumer<@Nullable Path> listener);
}
