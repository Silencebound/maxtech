package dev.silencebound.maxtech.storage.logic.controller;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player rate limits for sharing graphs ({@link GraphSharing}): when each player last exported (or wrote a card),
 * added a graph (import, paste, card, template) and asked for the template list, by server tick, keyed by UUID. An
 * entry older than the longest interval says nothing an absent entry does not, so such entries are dropped whenever the
 * map has grown past {@link #PRUNE_AT}; the map holds at most the players who shared within the last interval plus that
 * slack. A clock that went backwards (a new server) allows the request. Server thread only.
 */
final class ShareLimits {
    /** Kinds of request; each has its own last time. */
    static final int EXPORT = 0;
    static final int IMPORT = 1;
    /** Asking for the template list. */
    static final int LIST = 2;
    private static final int KINDS = 3;
    /** Entries kept before stale ones are dropped. */
    static final int PRUNE_AT = 64;

    private final Map<UUID, long[]> last = new HashMap<>();
    private final long longest;

    /** {@code longest} is the longest interval any kind uses, so older entries can go. */
    ShareLimits(long longest) {
        this.longest = longest;
    }

    /** Counts a request of {@code kind} at {@code now}; false when the last was less than {@code interval} ago. */
    boolean allow(UUID player, int kind, long interval, long now) {
        if (last.size() >= PRUNE_AT) {
            prune(now);
        }
        long[] times = last.computeIfAbsent(player, ignored -> {
            long[] fresh = new long[KINDS];
            java.util.Arrays.fill(fresh, Long.MIN_VALUE / 2);
            return fresh;
        });
        if (now >= times[kind] && now - times[kind] < interval) {
            return false;
        }
        times[kind] = now;
        return true;
    }

    /** Drops every entry whose times are all at least {@link #longest} ticks old, or in the future. */
    void prune(long now) {
        Iterator<long[]> it = last.values().iterator();
        while (it.hasNext()) {
            long[] times = it.next();
            boolean stale = true;
            for (long time : times) {
                if (time <= now && now - time < longest) {
                    stale = false;
                }
            }
            if (stale) {
                it.remove();
            }
        }
    }

    /** Players tracked now; for tests. */
    int size() {
        return last.size();
    }
}
