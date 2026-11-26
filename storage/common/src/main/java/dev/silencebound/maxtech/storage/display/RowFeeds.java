package dev.silencebound.maxtech.storage.display;

import dev.silencebound.maxtech.storage.display.alarm.AlarmFeed;
import dev.silencebound.maxtech.storage.display.material.MaterialFeed;
import dev.silencebound.maxtech.storage.display.production.ProductionFeed;
import dev.silencebound.maxtech.storage.display.task.TaskFeed;
import org.jspecify.annotations.Nullable;

/** Makes the {@link RowFeed} of a list screen's source; the one place a list source is added. Server thread. */
public final class RowFeeds {
    private RowFeeds() {
    }

    /** A new feed for a screen showing {@code source}, or null when the source is no list. */
    static @Nullable RowFeed create(DisplaySourceKind source, RowFeed.Screen screen) {
        return switch (source) {
            case TASK_BOARD -> new TaskFeed(screen);
            case ALARMS -> new AlarmFeed(screen);
            case MATERIAL_LIST -> new MaterialFeed(screen);
            case PRODUCTION -> new ProductionFeed(screen);
            default -> null;
        };
    }
}
