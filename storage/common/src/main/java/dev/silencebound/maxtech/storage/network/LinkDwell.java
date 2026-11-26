package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.platform.Platform;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.server.level.ServerLevel;

/**
 * How long a network must stay offline before its devices' front lines show it ({@link LinkLook}), and the wait for
 * networks paused for unloaded chunks, which do not tick: each pause is listed with its deadline and checked at the end
 * of its level's tick, so a chunk edge that loads and unloads in quick turns re-meshes nothing. The level tick returns
 * after one size check while nothing is paused. Server thread.
 */
final class LinkDwell {
    /**
     * Ticks a loss of power, of the controller in charge or a pause must last before fronts show offline: a second,
     * long enough to ride out energy hovering at the threshold and a standby controller taking over. A look, not a
     * balance number, so not in the config.
     */
    static final int TICKS = 20;

    private static final ObjectArrayList<NetworkState> PAUSED = new ObjectArrayList<>(0);
    private static final ObjectArrayList<ServerLevel> LEVELS = new ObjectArrayList<>(0);
    private static final LongArrayList DEADLINES = new LongArrayList(0);

    private LinkDwell() {
    }

    /** Hooks the check into the level tick; called once from {@link NetworkContent#init()}. */
    static void init() {
        Platform.get().lifecycle().onServerLevelTickEnd(LinkDwell::tick);
        Platform.get().lifecycle().onServerStopped(server -> {
            PAUSED.clear();
            LEVELS.clear();
            DEADLINES.clear();
        });
    }

    /** {@code state} was paused: it shows offline in {@link #TICKS} unless it resumes first. */
    static void paused(NetworkState state, ServerLevel level) {
        resumed(state);
        PAUSED.add(state);
        LEVELS.add(level);
        DEADLINES.add(level.getGameTime() + TICKS);
    }

    /** {@code state} resumed or closed: it is no longer waited for. */
    // States are listed by identity.
    @SuppressWarnings("ReferenceEquality")
    static void resumed(NetworkState state) {
        for (int i = PAUSED.size() - 1; i >= 0; i--) {
            if (PAUSED.get(i) == state) {
                remove(i);
            }
        }
    }

    // Levels are compared by identity.
    @SuppressWarnings("ReferenceEquality")
    private static void tick(ServerLevel level) {
        if (PAUSED.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        for (int i = PAUSED.size() - 1; i >= 0; i--) {
            if (LEVELS.get(i) == level && now >= DEADLINES.getLong(i)) {
                NetworkState state = PAUSED.get(i);
                remove(i);
                state.pauseOutlasted();
            }
        }
    }

    private static void remove(int index) {
        PAUSED.remove(index);
        LEVELS.remove(index);
        DEADLINES.removeLong(index);
    }
}
