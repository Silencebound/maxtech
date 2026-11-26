package dev.silencebound.maxtech.storage.display;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

/**
 * Who can see which screen, without polling: a screen registers with its chunk, and the players whose clients hold that
 * chunk (the server's view distance, i.e. render distance) are its viewers. The list starts from the chunk map when the
 * first screen of a chunk registers and then follows NeoForge's chunk sent and unwatch events, which Storage's glue
 * hands to {@link #watch} and {@link #unwatch}. Only chunks with screens are kept, so a server without screens pays one
 * map lookup per chunk event. Server thread.
 */
public final class DisplayViewers {
    /** A screen that wants to hear when its chunk gains or loses a viewer. */
    public interface Screen {
        /** A player started or stopped seeing the screen's chunk. */
        void viewersChanged(boolean added);

        /** Whether the screen is gone (broken or unloaded); gone screens are dropped on the next event. */
        boolean isGone();
    }

    private static final class Watch {
        final ObjectArrayList<ServerPlayer> players = new ObjectArrayList<>();
        final ObjectArrayList<Screen> screens = new ObjectArrayList<>(1);
    }

    private static final Reference2ObjectOpenHashMap<ServerLevel, Long2ObjectOpenHashMap<Watch>> LEVELS = new Reference2ObjectOpenHashMap<>();

    private DisplayViewers() {
    }

    /** Forgets every level; when the server stops. */
    static void clear() {
        for (Long2ObjectOpenHashMap<Watch> chunks : LEVELS.values()) {
            for (Watch watch : chunks.values()) {
                watch.players.clear();
            }
        }
        LEVELS.clear();
    }

    /**
     * Starts telling {@code screen} about the viewers of chunk {@code chunk} ({@link ChunkPos#pack()}) and returns
     * them: a live list, kept up to date while the screen stays registered and emptied once the chunk is forgotten, so
     * a screen asks whether anyone sees it without a lookup. Read only.
     */
    public static List<ServerPlayer> register(ServerLevel level, long chunk, Screen screen) {
        Long2ObjectOpenHashMap<Watch> chunks = LEVELS.computeIfAbsent(level, l -> new Long2ObjectOpenHashMap<>());
        Watch watch = chunks.get(chunk);
        if (watch == null) {
            watch = new Watch();
            watch.players.addAll(level.getChunkSource().chunkMap.getPlayers(ChunkPos.unpack(chunk), false));
            chunks.put(chunk, watch);
        }
        if (!watch.screens.contains(screen)) {
            watch.screens.add(screen);
        }
        return watch.players;
    }

    /** Stops telling {@code screen}; the chunk is forgotten once it has no screens. */
    public static void unregister(ServerLevel level, long chunk, Screen screen) {
        Long2ObjectOpenHashMap<Watch> chunks = LEVELS.get(level);
        Watch watch = chunks == null ? null : chunks.get(chunk);
        if (chunks != null && watch != null) {
            watch.screens.remove(screen);
            if (watch.screens.isEmpty()) {
                forget(chunks, chunk, watch);
            }
        }
    }

    /** The players who can see chunk {@code chunk}; empty when none or when no screen is registered there. */
    public static List<ServerPlayer> players(ServerLevel level, long chunk) {
        Long2ObjectOpenHashMap<Watch> chunks = LEVELS.get(level);
        Watch watch = chunks == null ? null : chunks.get(chunk);
        return watch == null ? List.of() : watch.players;
    }

    /** {@code player}'s client now has chunk {@code chunk} of {@code level} (NeoForge's chunk sent event). */
    public static void watch(ServerLevel level, ServerPlayer player, long chunk) {
        Watch watch = watchOf(level, chunk);
        if (watch != null && !watch.players.contains(player)) {
            watch.players.add(player);
            notify(level, chunk, watch, true);
        }
    }

    /** {@code player}'s client dropped chunk {@code chunk} of {@code level} (NeoForge's unwatch event). */
    public static void unwatch(ServerLevel level, ServerPlayer player, long chunk) {
        Watch watch = watchOf(level, chunk);
        if (watch != null && watch.players.remove(player)) {
            notify(level, chunk, watch, false);
        }
    }

    /**
     * Takes {@code player} off the viewer lists of every level but {@code keep} (null for all): after a dimension
     * change, whose unwatch events may already name the new level, and on logout. Walks every watched chunk; both
     * events are rare.
     */
    public static void forget(ServerPlayer player, @Nullable ServerLevel keep) {
        for (Reference2ObjectMap.Entry<ServerLevel, Long2ObjectOpenHashMap<Watch>> level : LEVELS
                .reference2ObjectEntrySet()) {
            if (keep != null && level.getKey().dimension().equals(keep.dimension())) {
                continue;
            }
            LongArrayList dropped = new LongArrayList();
            for (Long2ObjectMap.Entry<Watch> chunk : level.getValue().long2ObjectEntrySet()) {
                if (chunk.getValue().players.remove(player)) {
                    dropped.add(chunk.getLongKey());
                }
            }
            for (int i = 0; i < dropped.size(); i++) {
                Watch watch = level.getValue().get(dropped.getLong(i));
                if (watch != null) {
                    notify(level.getKey(), dropped.getLong(i), watch, false);
                }
            }
        }
    }

    private static @Nullable Watch watchOf(ServerLevel level, long chunk) {
        Long2ObjectOpenHashMap<Watch> chunks = LEVELS.get(level);
        return chunks == null ? null : chunks.get(chunk);
    }

    private static void notify(ServerLevel level, long chunk, Watch watch, boolean added) {
        for (int i = watch.screens.size() - 1; i >= 0; i--) {
            Screen screen = watch.screens.get(i);
            if (screen.isGone()) {
                watch.screens.remove(i);
            } else {
                screen.viewersChanged(added);
            }
        }
        Long2ObjectOpenHashMap<Watch> chunks = LEVELS.get(level);
        if (watch.screens.isEmpty() && chunks != null) {
            forget(chunks, chunk, watch);
        }
    }

    /** Drops a chunk without screens; its list is emptied for screens that still hold it ({@link #register}). */
    private static void forget(Long2ObjectOpenHashMap<Watch> chunks, long chunk, Watch watch) {
        chunks.remove(chunk);
        watch.players.clear();
    }
}
