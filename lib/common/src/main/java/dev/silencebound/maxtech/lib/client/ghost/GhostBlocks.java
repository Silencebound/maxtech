package dev.silencebound.maxtech.lib.client.ghost;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;

/**
 * The ghost blocks a client shows: one {@link GhostSet} per channel, kept until the channel is shown again or cleared
 * (no timeout: the code that shows a set clears it, for example when the player stops holding an item). Lib's client
 * glue draws {@link #shown()} every frame, skipping cells beyond {@code highlights.renderDistance}, and clears
 * everything on leaving a world. Showing a set is the only time anything is allocated, so callers build their cells
 * when what they show changes, never per frame. Client thread.
 */
public final class GhostBlocks {
    /** Most channels shown at once; showing another drops the oldest. */
    public static final int MAX_CHANNELS = 16;
    private static final GhostBlocks CLIENT = new GhostBlocks();

    private final Map<Identifier, GhostSet> channels = new LinkedHashMap<>();
    private final List<GhostSet> shown = new ArrayList<>();
    private final List<GhostSet> view = Collections.unmodifiableList(shown);

    /** The client's ghosts. */
    public static GhostBlocks client() {
        return CLIENT;
    }

    /** Shows {@code set} on {@code channel}, replacing what it showed; an empty set clears the channel. */
    public void show(Identifier channel, GhostSet set) {
        channels.remove(channel);
        if (!set.isEmpty()) {
            if (channels.size() >= MAX_CHANNELS) {
                channels.remove(channels.keySet().iterator().next());
            }
            channels.put(channel, set);
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

    /** What {@code channel} shows, or {@link GhostSet#EMPTY}. */
    public GhostSet get(Identifier channel) {
        return channels.getOrDefault(channel, GhostSet.EMPTY);
    }

    /** Every shown set, oldest channel first; a live view, not to be changed. */
    public List<GhostSet> shown() {
        return view;
    }

    public boolean isEmpty() {
        return shown.isEmpty();
    }

    private void refresh() {
        shown.clear();
        shown.addAll(channels.values());
    }
}
