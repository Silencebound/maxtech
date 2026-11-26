package dev.silencebound.maxtech.storage.controller.recovery;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkState;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.player.Player;
// PLATFORM-LEAK: recovering moves items into the network in a NeoForge transfer transaction (CLAUDE.md).
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * A network's recovery bin (M7C item 20), kept by its controller and saved with it: what void overflow destroyed and
 * what was lost with a destroyed storage cell, for {@code recovery.keepTicks} after it went. Each row is one key and
 * what went of it within a quarter of the keep time, so a farm voiding every tick fills one row per key and quarter
 * instead of one per tick, and each row expires the keep time after its first loss. At most {@code recovery.maxEntries}
 * rows; past that the oldest are dropped. Expired rows are dropped lazily, whenever the bin is added to or read, so
 * nothing ticks.
 *
 * <p>
 * Times are game times, which stop while the server is off, so a restart within the window keeps the rows.
 * {@link #recover} puts a row back into the network in one transaction and takes out of the row exactly what the
 * network took. Server thread only.
 */
public final class RecoveryBin {
    /**
     * One row as saved and sent: {@code id} names it in this session only (it is not saved), {@code created} is the
     * game time of its first loss.
     */
    public record Row(int id, StackKey key, long amount, long created) {
        public static final Codec<Row> CODEC = RecordCodecBuilder.create(instance -> instance
                .group(StackKey.CODEC.fieldOf("key").forGetter(Row::key),
                        Codec.LONG.fieldOf("amount").forGetter(Row::amount),
                        Codec.LONG.fieldOf("created").forGetter(Row::created))
                .apply(instance, (key, amount, created) -> new Row(0, key, amount, created)));
        public static final StreamCodec<RegistryFriendlyByteBuf, Row> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Row::id, StackKey.STREAM_CODEC, Row::key, ByteBufCodecs.VAR_LONG, Row::amount,
                ByteBufCodecs.VAR_LONG, Row::created, Row::new);
    }

    private static final class Batch {
        final int id;
        final StackKey key;
        final long created;
        long amount;
        boolean gone;

        Batch(int id, StackKey key, long amount, long created) {
            this.id = id;
            this.key = key;
            this.amount = amount;
            this.created = created;
        }
    }

    /** Oldest first. */
    private final ObjectArrayList<Batch> batches = new ObjectArrayList<>();
    /** The newest row of each key, which a loss of that key within its quarter adds to. */
    private final Object2ObjectOpenHashMap<StackKey, Batch> newest = new Object2ObjectOpenHashMap<>();
    private int nextId = 1;
    private int version;
    /** Whether the controller was told of a change since the last {@link #saved()}. */
    private boolean marked;

    /**
     * Keeps {@code amount} of {@code key}, lost at game time {@code now}. Returns true when the caller has not yet been
     * told to save since the last save (whatever changed the bin before, an expiry included), so it marks its block
     * entity changed once per save rather than once per loss. Does nothing (false) while {@code recovery.keepTicks} is
     * 0. A map lookup and, for a new row, one small object.
     */
    public boolean add(StackKey key, long amount, long now) {
        int keep = StorageServerConfig.RECOVERY_KEEP_TICKS.getAsInt();
        if (keep <= 0 || amount <= 0) {
            return false;
        }
        expire(now, keep);
        Batch batch = newest.get(key);
        if (batch != null && now - batch.created < Math.max(1, keep / 4)) {
            long sum = batch.amount + amount;
            batch.amount = sum < 0 ? Long.MAX_VALUE : sum;
        } else {
            batch = new Batch(nextId++, key, amount, now);
            batches.add(batch);
            newest.put(key, batch);
            int cap = StorageServerConfig.RECOVERY_MAX_ENTRIES.getAsInt();
            if (batches.size() > cap) {
                drop(batches.size() - cap);
            }
        }
        version++;
        boolean tell = !marked;
        marked = true;
        return tell;
    }

    private void changed() {
        version++;
    }

    /** Drops the rows whose time is up. */
    private void expire(long now, int keep) {
        int count = 0;
        while (count < batches.size() && batches.get(count).created + keep <= now) {
            count++;
        }
        if (count > 0) {
            drop(count);
            changed();
        }
    }

    /** Drops the {@code count} oldest rows. */
    private void drop(int count) {
        for (int i = 0; i < count; i++) {
            forget(batches.get(i));
        }
        batches.removeElements(0, count);
    }

    // Identity: the map's row for the key may be a newer one.
    @SuppressWarnings("ReferenceEquality")
    private void forget(Batch batch) {
        batch.gone = true;
        if (newest.get(batch.key) == batch) {
            newest.remove(batch.key);
        }
    }

    /** The rows still in time at {@code now}, newest first. */
    public List<Row> rows(long now) {
        expire(now, StorageServerConfig.RECOVERY_KEEP_TICKS.getAsInt());
        List<Row> rows = new ArrayList<>(batches.size());
        for (int i = batches.size() - 1; i >= 0; i--) {
            Batch batch = batches.get(i);
            rows.add(new Row(batch.id, batch.key, batch.amount, batch.created));
        }
        return rows;
    }

    /** The game time {@code row} expires at with the config now. */
    public static long expiry(Row row) {
        return row.created() + StorageServerConfig.RECOVERY_KEEP_TICKS.getAsInt();
    }

    /** {@code ticks} as a short duration: "45s", "3m 20s", "2h 5m"; under a second shows as "1s". */
    public static String duration(long ticks) {
        long seconds = Math.max(1, (ticks + 19) / 20);
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m " + seconds % 60 + "s";
        }
        return minutes / 60 + "h " + minutes % 60 + "m";
    }

    /** Drops the rows whose time is up at {@code now}; changes {@link #version} when it dropped any. No allocation. */
    public void prune(long now) {
        expire(now, StorageServerConfig.RECOVERY_KEEP_TICKS.getAsInt());
    }

    /** Whether the bin holds no row (expired ones included until the next read). */
    public boolean isEmpty() {
        return batches.isEmpty();
    }

    /** Changes with every add, recovery and expiry; the controller screen resends the rows when it moved. */
    public int version() {
        return version;
    }

    /**
     * Puts the row {@code id} back into {@code network} for {@code player} in one transaction: only what the network
     * takes, which then leaves the row; the rest stays. Returns what moved, 0 for a row gone meanwhile. The caller has
     * checked {@link #mayUse}. What a void overflow cell takes in again is destroyed and comes back to the bin during
     * the commit, so nothing is lost either way.
     */
    public long recover(int id, NetworkState network, Player player, long now) {
        expire(now, StorageServerConfig.RECOVERY_KEEP_TICKS.getAsInt());
        Batch batch = byId(id);
        if (batch == null) {
            return 0;
        }
        long moved;
        try (Transaction root = Transaction.openRoot()) {
            moved = network.insert(batch.key, batch.amount, root, player);
            root.commit();
        }
        // The commit may have added a voided part to this row, or dropped it for the cap: a dropped row stays dropped.
        if (moved > 0 && !batch.gone) {
            batch.amount -= Math.min(moved, batch.amount);
            if (batch.amount == 0) {
                forget(batch);
                batches.remove(batch);
            }
            changed();
        }
        return moved;
    }

    private @Nullable Batch byId(int id) {
        for (int i = 0; i < batches.size(); i++) {
            if (batches.get(i).id == id) {
                return batches.get(i);
            }
        }
        return null;
    }

    /**
     * Whether {@code player} may recover from the bin of a network owned by {@code owner}: an admin (game master), the
     * owner, or, on a network nobody owns, whoever has full access to it.
     */
    public static boolean mayUse(Player player, OwnerRecord owner, @Nullable NetworkState network) {
        if (player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            return true;
        }
        if (owner.isOwned()) {
            return owner.isOwner(player.getUUID());
        }
        return network != null && network.accessLevel(player) == AccessLevel.FULL;
    }

    /** The rows to save, oldest first; clears the unsaved mark. */
    public List<Row> saved() {
        marked = false;
        List<Row> rows = new ArrayList<>(batches.size());
        for (int i = 0; i < batches.size(); i++) {
            Batch batch = batches.get(i);
            rows.add(new Row(0, batch.key, batch.amount, batch.created));
        }
        return rows;
    }

    /** Replaces the rows with saved ones (oldest first). */
    public void load(List<Row> rows) {
        for (int i = 0; i < batches.size(); i++) {
            batches.get(i).gone = true;
        }
        batches.clear();
        newest.clear();
        for (Row row : rows) {
            if (row.amount() > 0) {
                Batch batch = new Batch(nextId++, row.key(), row.amount(), row.created());
                batches.add(batch);
                newest.put(row.key(), batch);
            }
        }
        version++;
    }
}
