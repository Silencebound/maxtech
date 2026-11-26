package dev.silencebound.maxtech.storage.controller.module;

import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

/**
 * The chunks network anchors keep loaded, by owner (a controller core's position) and by team, under a per-team cap
 * ({@code devices.networkAnchor.chunks}). The tickets themselves are the loader's (NeoForge's ticket controller,
 * installed by the neoforge glue through {@link #install}); this class only decides which chunks each core holds and
 * diffs against what it held. The loader saves the tickets, and its load-time callback hands them back through
 * {@link #seed}, so chunks a core no longer wants after a restart are released on its first update. Server thread only.
 */
public final class AnchorChunks {
    /** Forces or releases one chunk for an owner block; the loader's ticket controller. */
    @FunctionalInterface
    public interface Tickets {
        /** Adds ({@code add}) or removes the ticket {@code owner} holds on chunk ({@code x}, {@code z}). */
        void force(ServerLevel level, BlockPos owner, int x, int z, boolean add);
    }

    /** No loader glue (unit tests): nothing is loaded, the bookkeeping still runs. */
    private static Tickets tickets = (level, owner, x, z, add) -> {
    };

    /** Per level: core position -> chunks it holds. Weak, so a stopped server's levels are dropped. */
    private static final Map<ServerLevel, Long2ObjectOpenHashMap<Held>> HELD = new WeakHashMap<>();
    /**
     * Ticks after a level's saved tickets came back before those no core claimed are released: twice the controller's
     * load window, so every anchored core that loads with its chunks has updated by then.
     */
    static final int SWEEP_TICKS = 200;
    /** Levels whose seeded tickets wait for the sweep: the game time it is due, or MIN_VALUE until the first tick. */
    private static final Map<ServerLevel, Long> SWEEPS = new WeakHashMap<>();
    private static Runnable recheck = () -> {
    };
    private static volatile boolean recheckPending;
    /** Per server: team -> chunks its cores hold together. */
    private static final Map<MinecraftServer, Object2IntOpenHashMap<UUID>> TEAMS = new WeakHashMap<>();

    /** What one core holds: its team (unknown for a seeded entry) and its chunks. */
    private static final class Held {
        @Nullable
        UUID team;
        final LongOpenHashSet chunks = new LongOpenHashSet();
    }

    private AnchorChunks() {
    }

    /** Installs the loader's ticket controller; called once by the neoforge glue when it registers it. */
    public static void install(Tickets loader) {
        tickets = loader;
    }

    /** The per-team chunk cap now; 0 turns anchors off. */
    public static int cap() {
        return StorageServerConfig.ANCHOR_CHUNKS.getAsInt();
    }

    /**
     * Records chunks the loader kept for {@code owner} across a restart, before any core loads. Their team is counted
     * once the core updates; until then they count for nobody.
     */
    public static void seed(ServerLevel level, BlockPos owner, LongSet chunks) {
        Held held = HELD.computeIfAbsent(level, l -> new Long2ObjectOpenHashMap<>()).computeIfAbsent(owner.asLong(),
                pos -> new Held());
        held.chunks.addAll(chunks);
        SWEEPS.putIfAbsent(level, Long.MIN_VALUE);
    }

    /**
     * Installs what re-evaluates every loaded anchor (the controller's), run on the server thread after a config
     * reload; set once during mod construction.
     */
    public static void onRecheck(Runnable recheck) {
        AnchorChunks.recheck = recheck;
    }

    /** A config the anchors read changed (any thread): every anchor looks again on the next server tick. */
    public static void configChanged() {
        recheckPending = true;
    }

    /**
     * Once per server tick, from the loader glue: re-evaluates the anchors after a config change, and,
     * {@link #SWEEP_TICKS} after a level's saved tickets came back, releases those no loaded anchored core claimed (its
     * core was removed while the server was off, or no longer has an anchor). A flag check and an empty-map check
     * otherwise.
     */
    public static void serverTick() {
        if (recheckPending) {
            recheckPending = false;
            recheck.run();
        }
        if (SWEEPS.isEmpty()) {
            return;
        }
        ServerLevel[] levels = SWEEPS.keySet().toArray(new ServerLevel[0]);
        for (ServerLevel level : levels) {
            long due = SWEEPS.getOrDefault(level, Long.MIN_VALUE);
            long now = level.getGameTime();
            if (due == Long.MIN_VALUE) {
                SWEEPS.put(level, now + SWEEP_TICKS);
            } else if (now >= due) {
                SWEEPS.remove(level);
                releaseUnclaimed(level);
            }
        }
    }

    /** Releases every seeded entry of {@code level} whose core never updated it (its team is still unknown). */
    static void releaseUnclaimed(ServerLevel level) {
        Long2ObjectOpenHashMap<Held> byOwner = HELD.get(level);
        if (byOwner == null) {
            return;
        }
        LongArrayList orphans = new LongArrayList();
        for (Long2ObjectMap.Entry<Held> entry : byOwner.long2ObjectEntrySet()) {
            if (entry.getValue().team == null) {
                orphans.add(entry.getLongKey());
            }
        }
        for (int i = 0; i < orphans.size(); i++) {
            release(level, BlockPos.of(orphans.getLong(i)));
        }
    }

    /**
     * Makes the core at {@code owner} hold the first chunks of {@code wanted} (in its order) that its team's cap leaves
     * room for, releasing what it held beyond them; with {@code addOnly} it keeps everything it held and only adds
     * within the cap. Returns how many it holds now.
     */
    public static int update(ServerLevel level, BlockPos owner, UUID team, LongLinkedOpenHashSet wanted,
            boolean addOnly) {
        Long2ObjectOpenHashMap<Held> byOwner = HELD.computeIfAbsent(level, l -> new Long2ObjectOpenHashMap<>());
        Held held = byOwner.computeIfAbsent(owner.asLong(), pos -> new Held());
        Object2IntOpenHashMap<UUID> totals = TEAMS.computeIfAbsent(level.getServer(),
                server -> new Object2IntOpenHashMap<>());
        UUID before = held.team;
        if (before != null) {
            totals.addTo(before, -held.chunks.size());
        }
        int room = Math.max(0, cap() - totals.getInt(team));
        LongOpenHashSet keep = new LongOpenHashSet();
        LongIterator it = wanted.iterator();
        while (it.hasNext() && keep.size() < room) {
            keep.add(it.nextLong());
        }
        LongIterator old = held.chunks.iterator();
        while (!addOnly && old.hasNext()) {
            long chunk = old.nextLong();
            if (!keep.contains(chunk)) {
                tickets.force(level, owner, ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false);
                old.remove();
            }
        }
        LongIterator add = keep.iterator();
        while (add.hasNext() && held.chunks.size() < room) {
            long chunk = add.nextLong();
            if (held.chunks.add(chunk)) {
                tickets.force(level, owner, ChunkPos.getX(chunk), ChunkPos.getZ(chunk), true);
            }
        }
        held.team = team;
        totals.addTo(team, held.chunks.size());
        return held.chunks.size();
    }

    /** Releases every chunk the core at {@code owner} holds (the core or its last anchor broke). */
    public static void release(ServerLevel level, BlockPos owner) {
        Long2ObjectOpenHashMap<Held> byOwner = HELD.get(level);
        Held held = byOwner == null ? null : byOwner.remove(owner.asLong());
        if (held == null) {
            return;
        }
        LongIterator it = held.chunks.iterator();
        while (it.hasNext()) {
            long chunk = it.nextLong();
            tickets.force(level, owner, ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false);
        }
        UUID team = held.team;
        Object2IntOpenHashMap<UUID> totals = TEAMS.get(level.getServer());
        if (team != null && totals != null) {
            totals.addTo(team, -held.chunks.size());
        }
    }

    /** The chunks the core at {@code owner} holds; empty for none. A read-only view. */
    public static LongSet held(ServerLevel level, BlockPos owner) {
        Long2ObjectOpenHashMap<Held> byOwner = HELD.get(level);
        Held held = byOwner == null ? null : byOwner.get(owner.asLong());
        return held == null ? LongSets.EMPTY_SET : LongSets.unmodifiable(held.chunks);
    }

    /** Whether any core holds {@code chunk} in {@code level}; walks every core, so for diagnostics and tests. */
    public static boolean isHeld(ServerLevel level, long chunk) {
        Long2ObjectOpenHashMap<Held> byOwner = HELD.get(level);
        if (byOwner == null) {
            return false;
        }
        for (Held held : byOwner.values()) {
            if (held.chunks.contains(chunk)) {
                return true;
            }
        }
        return false;
    }

    /** Chunks all cores of {@code team} hold on {@code server}. */
    public static int teamTotal(MinecraftServer server, UUID team) {
        Object2IntOpenHashMap<UUID> totals = TEAMS.get(server);
        return totals == null ? 0 : totals.getInt(team);
    }
}
