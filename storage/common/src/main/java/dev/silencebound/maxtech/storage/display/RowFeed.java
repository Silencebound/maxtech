package dev.silencebound.maxtech.storage.display;

import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageTask;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * The server side of a list screen's source ({@link DisplaySourceKind#rows()}): it builds the rows the screen shows,
 * subscribes to the events that change them and handles a click on a row. One per screen, made by {@link RowFeeds} when
 * the screen's source or settings change and dropped with them. Nothing polls: the feed tells its screen through
 * {@link Screen} when its rows may have changed, and the screen reads them on its task when someone sees it. Server
 * thread.
 */
public interface RowFeed {
    /** The screen a feed belongs to. */
    interface Screen {
        /** The settings, position and device of the screen. */
        ScreenWorker.Host host();

        /** The rows may have changed: the screen reads them again on its next run if someone sees it. */
        void rowsChanged();

        /** {@link #work} is due whether or not anyone sees the screen (a goal to look at). */
        void workDue();
    }

    /** The screen joined {@code network} or left its network (null): subscribe to its events, or stop. */
    void bind(@Nullable NetworkState network);

    /** The feed is dropped: stop listening to everything. */
    void unbind();

    /** The rows to show now; called on the screen's task while someone sees it, at most once per update interval. */
    ScreenRows rows(NetworkState network, long gameTime);

    /**
     * {@code player}, within reach, clicked shown row {@code row}; returns whether the click was handled (otherwise the
     * screen's settings open as before). Access checks are the feed's.
     */
    default boolean click(ServerPlayer player, NetworkState network, int row) {
        return false;
    }

    /** Work asked for by {@link Screen#workDue}, run on the screen's task whether or not anyone sees it. */
    default void work(NetworkState network, long gameTime) {
    }

    /**
     * Ticks after which the rows should be read again while someone sees the screen though no event said so (a time
     * shown on the screen), or {@link StorageTask#WAIT} for never.
     */
    default int refreshTicks() {
        return StorageTask.WAIT;
    }
}
