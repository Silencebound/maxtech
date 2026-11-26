package dev.silencebound.maxtech.lib.highlight;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;

/**
 * The highlights a client shows: one {@link HighlightRequest} per channel with the tick it ends at. Requests arrive
 * from the server ({@link Highlights}) or from client code ({@link #show}); the renderer reads {@link #active()} each
 * frame. Time is the client's own tick counter, advanced by the renderer's glue. Holds no client-only types; only the
 * client fills it. Client thread.
 */
public final class HighlightStore {
    /** Most channels shown at once; showing another drops the oldest. */
    public static final int MAX_CHANNELS = 64;
    private static final HighlightStore CLIENT = new HighlightStore();

    private final Map<Identifier, Active> channels = new LinkedHashMap<>();
    private final List<Active> active = new ArrayList<>();
    private long now;

    /** A request being shown until tick {@code endTick}. */
    public record Active(HighlightRequest request, long endTick) {
    }

    /** The client's store. */
    public static HighlightStore client() {
        return CLIENT;
    }

    /** Shows {@code request} on its channel from now, replacing what the channel showed, or clears the channel. */
    public void show(HighlightRequest request) {
        channels.remove(request.channel());
        if (!request.clears()) {
            if (channels.size() >= MAX_CHANNELS) {
                Iterator<Identifier> oldest = channels.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
            channels.put(request.channel(), new Active(request, now + request.durationTicks()));
        }
        refresh();
    }

    /** Removes what {@code channel} shows. */
    public void clear(Identifier channel) {
        if (channels.remove(channel) != null) {
            refresh();
        }
    }

    /** Removes everything, as on leaving a world. */
    public void clearAll() {
        channels.clear();
        refresh();
    }

    /** Advances the clock by one tick and drops requests whose time is up. */
    public void tick() {
        now++;
        boolean changed = channels.values().removeIf(entry -> entry.endTick() <= now);
        if (changed) {
            refresh();
        }
    }

    /** What is shown now, oldest channel first; do not change it. */
    public List<Active> active() {
        return Collections.unmodifiableList(active);
    }

    public boolean isEmpty() {
        return active.isEmpty();
    }

    private void refresh() {
        active.clear();
        active.addAll(channels.values());
    }
}
