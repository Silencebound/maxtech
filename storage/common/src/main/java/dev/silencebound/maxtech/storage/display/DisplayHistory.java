package dev.silencebound.maxtech.storage.display;

import dev.silencebound.maxtech.storage.network.NetworkState;
import it.unimi.dsi.fastutil.longs.LongList;
import org.jspecify.annotations.Nullable;

/**
 * Past values of a screen's source, kept on the server (M6c2: the controller's telemetry recorder). A screen asks it
 * once when a player starts seeing it, so the player's graph starts filled instead of empty; after that the screen
 * sends live values and the client keeps its own rolling window. Without a registered history (M6c) graphs start empty.
 *
 * <p>
 * Server thread. Register once during mod construction with {@link #register}; a later call replaces the earlier one.
 */
@FunctionalInterface
public interface DisplayHistory {
    /**
     * Adds up to {@code samples} past values of {@code settings}' source on {@code network} to {@code out}, oldest
     * first, {@code ticksPerSample} ticks apart and ending at the latest recorded sample; adds nothing when the network
     * has no record of that source. Values use the units the screen sends ({@link DisplaySourceKind}).
     */
    void fill(NetworkState network, DisplaySettings settings, int samples, int ticksPerSample, LongList out);

    /**
     * Called on every run of a screen players can see, so the history keeps recording {@code settings}' source while
     * the screen is watched (a screen showing an unchanging count would otherwise never ask again). Returns the ticks
     * within which it wants the next call, or 0 when it needs none. Must not allocate. The default needs nothing.
     */
    default int watched(NetworkState network, DisplaySettings settings) {
        return 0;
    }

    /** Makes {@code history} the one screens ask; null removes it. */
    static void register(@Nullable DisplayHistory history) {
        Holder.history = history;
    }

    /** The registered history, or null. */
    static @Nullable DisplayHistory current() {
        return Holder.history;
    }

    /** Keeps the registered history; interfaces cannot hold mutable fields. */
    final class Holder {
        private static volatile @Nullable DisplayHistory history;

        private Holder() {
        }
    }
}
