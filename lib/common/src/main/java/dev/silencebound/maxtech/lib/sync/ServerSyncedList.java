package dev.silencebound.maxtech.lib.sync;

import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.platform.Platform;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongMaps;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * The server side of one synced list for one player's open menu. The menu creates it when it opens, calls {@link #open}
 * with the full contents, feeds every change through {@link #changed} (cheap: one primitive map put, later changes to
 * the same id overwrite earlier ones) and calls {@link #tick} from its {@code broadcastChanges()}, which the server
 * runs only for a player's open menu, so nothing is sent once the screen closes. Server thread.
 */
public final class ServerSyncedList<E> {
    private final SyncedListType<E> type;
    private final int containerId;
    private final IntFunction<@Nullable E> definitions;
    private final Sink<E> sink;
    private final @Nullable EntrySizer<E> sizer;
    private final Int2LongOpenHashMap pending = new Int2LongOpenHashMap();
    private final IntOpenHashSet known = new IntOpenHashSet();
    private long lastSent = Long.MIN_VALUE / 2;
    private int sentBatches;

    /** An entry definition's encoded size in bytes; {@link SyncedListType#sizer} measures with the real codec. */
    @FunctionalInterface
    public interface EntrySizer<E> {
        int bytes(E entry);
    }

    /** Where batches go: the player, or a test's recorder. */
    @FunctionalInterface
    public interface Sink<E> {
        void send(SyncedListUpdate<E> update);
    }

    /**
     * @param definitions
     *            an entry's definition by id, asked once per id the client does not know yet; null means the id is
     *            gone, and it is sent as removed
     */
    public ServerSyncedList(SyncedListType<E> type, int containerId, IntFunction<@Nullable E> definitions,
            Sink<E> sink) {
        this(type, containerId, definitions, sink, null);
    }

    /**
     * @param sizer
     *            measures definitions so no batch grows past {@link SyncedListUpdate#MAX_BYTES}; without one, batches
     *            are capped by entry count only, which suits small fixed-size entries
     */
    public ServerSyncedList(SyncedListType<E> type, int containerId, IntFunction<@Nullable E> definitions, Sink<E> sink,
            @Nullable EntrySizer<E> sizer) {
        this.type = type;
        this.containerId = containerId;
        this.definitions = definitions;
        this.sink = sink;
        this.sizer = sizer;
    }

    /** A list that sends to {@code player}, with batches capped by entry count and encoded size. */
    public static <E> ServerSyncedList<E> forPlayer(SyncedListType<E> type, ServerPlayer player, int containerId,
            IntFunction<@Nullable E> definitions) {
        return new ServerSyncedList<>(type, containerId, definitions,
                update -> Platform.get().network().sendToPlayer(player, update), type.sizer(player.registryAccess()));
    }

    /**
     * Sends the full contents now, replacing whatever the client had, and forgets pending changes. Entries with a count
     * of 0 are skipped. Split into batches of at most {@link SyncedListUpdate#MAX_ENTRIES} entries and, with a sizer,
     * {@link SyncedListUpdate#MAX_BYTES} bytes; the first one resets.
     */
    public void open(Int2LongMap contents, long gameTime) {
        pending.clear();
        known.clear();
        for (ObjectIterator<Int2LongMap.Entry> it = Int2LongMaps.fastIterator(contents); it.hasNext();) {
            Int2LongMap.Entry entry = it.next();
            if (entry.getLongValue() > 0) {
                pending.put(entry.getIntKey(), entry.getLongValue());
            }
        }
        send(true);
        lastSent = gameTime;
    }

    /** {@code id} now has {@code count} (0 if it is gone). Merged with earlier changes until the next send. */
    public void changed(int id, long count) {
        pending.put(id, Math.max(0L, count));
    }

    /** Changes waiting for the next send. */
    public int pendingCount() {
        return pending.size();
    }

    /** Batches sent so far, dumps included. */
    public int sentBatches() {
        return sentBatches;
    }

    /** {@link #tick(long, int)} at the configured rate ({@code sync.listUpdatesPerSecond}). */
    public boolean tick(long gameTime) {
        return tick(gameTime, intervalTicks(ServerConfig.LIST_UPDATES_PER_SECOND.getAsInt()));
    }

    /**
     * Sends the pending changes if there are any and at least {@code intervalTicks} ticks passed since the last send.
     * Returns whether it sent.
     */
    public boolean tick(long gameTime, int intervalTicks) {
        if (pending.isEmpty() || gameTime - lastSent < intervalTicks) {
            return false;
        }
        send(false);
        lastSent = gameTime;
        return true;
    }

    /** Ticks between two sends for {@code perSecond} sends a second, at least 1. */
    public static int intervalTicks(int perSecond) {
        return Math.max(1, Math.round(20.0F / Math.max(1, perSecond)));
    }

    private void send(boolean reset) {
        int batch = Math.min(pending.size(), SyncedListUpdate.MAX_ENTRIES);
        IntArrayList ids = new IntArrayList(batch);
        LongArrayList counts = new LongArrayList(batch);
        IntArrayList definitionIds = new IntArrayList();
        List<E> defined = new ArrayList<>();
        boolean resetNext = reset;
        int bytes = SyncedListUpdate.HEADER_BYTES;
        EntrySizer<E> measure = sizer;
        for (ObjectIterator<Int2LongMap.Entry> it = pending.int2LongEntrySet().fastIterator(); it.hasNext();) {
            Int2LongMap.Entry entry = it.next();
            int id = entry.getIntKey();
            long count = entry.getLongValue();
            @Nullable
            E definition = null;
            if (count > 0 && !known.contains(id)) {
                definition = definitions.apply(id);
                if (definition == null) {
                    count = 0;
                }
            }
            if (count == 0 && !known.remove(id)) {
                // The client never saw it, so there is nothing to remove.
                continue;
            }
            int entryBytes = SyncedListUpdate.COUNT_BYTES;
            if (definition != null && measure != null) {
                entryBytes += SyncedListUpdate.DEFINITION_ID_BYTES + measure.bytes(definition);
            }
            if (!ids.isEmpty() && bytes + entryBytes > SyncedListUpdate.MAX_BYTES) {
                emit(resetNext, definitionIds, defined, ids, counts);
                resetNext = false;
                definitionIds = new IntArrayList();
                defined = new ArrayList<>();
                ids = new IntArrayList(batch);
                counts = new LongArrayList(batch);
                bytes = SyncedListUpdate.HEADER_BYTES;
            }
            if (definition != null) {
                definitionIds.add(id);
                defined.add(definition);
                known.add(id);
            }
            ids.add(id);
            counts.add(count);
            bytes += entryBytes;
            if (ids.size() == SyncedListUpdate.MAX_ENTRIES) {
                emit(resetNext, definitionIds, defined, ids, counts);
                resetNext = false;
                definitionIds = new IntArrayList();
                defined = new ArrayList<>();
                ids = new IntArrayList(batch);
                counts = new LongArrayList(batch);
                bytes = SyncedListUpdate.HEADER_BYTES;
            }
        }
        if (!ids.isEmpty() || resetNext) {
            emit(resetNext, definitionIds, defined, ids, counts);
        }
        pending.clear();
    }

    private void emit(boolean reset, IntArrayList definitionIds, List<E> defined, IntArrayList ids,
            LongArrayList counts) {
        sink.send(type.update(containerId, reset, definitionIds.toIntArray(), defined, ids.toIntArray(),
                counts.toLongArray()));
        sentBatches++;
    }
}
