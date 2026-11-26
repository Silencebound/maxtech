package dev.silencebound.maxtech.storage.controller.module;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import org.jspecify.annotations.Nullable;

/**
 * A ledger module's book (M6e area G, per player since M6f F9): how much of each key each player (or automation) put
 * into and took out of the network, per in-game day, for the last {@code keepDays} days. One row per player, key and
 * day, at most {@code maxRows} rows in all; when a new row would pass the cap the oldest day goes first, and on the
 * current day alone further new rows are only counted in {@link #unrecorded()}. Saved whole through {@link #codec}: per
 * day the players once, the keys with an index into them, and two long arrays; a save from before players were recorded
 * loads with every row under {@link #AUTOMATION}. Server thread.
 *
 * @param <K>
 *            the key type (the network's stack keys in game, strings in tests)
 */
public final class LedgerBook<K> {
    /** Ticks per in-game day; days count from game time, which /time set does not move. */
    public static final long TICKS_PER_DAY = 24_000L;
    /** The "player" of moves no player made: ports, buses, crafting, and every row of a save from before F9. */
    public static final UUID AUTOMATION = new UUID(0L, 0L);

    private final ObjectArrayList<Day<K>> days = new ObjectArrayList<>();
    private int rows;
    private long unrecorded;

    /** One day's rows: player to key to {in, out}. */
    private record Day<K>(long day, Object2ObjectOpenHashMap<UUID, Object2ObjectOpenHashMap<K, long[]>> players) {
        int size() {
            int size = 0;
            for (Object2ObjectOpenHashMap<K, long[]> keys : players.values()) {
                size += keys.size();
            }
            return size;
        }
    }

    /** A key's totals over the kept days, for one player or ({@link #AUTOMATION}) automation or, summed, everyone. */
    public record Row<K>(UUID player, K key, long in, long out) {
        /** In and out together, for sorting by how busy a key was. */
        public long volume() {
            return in + out;
        }
    }

    /**
     * Adds {@code delta} of {@code key} for {@code player} ({@link #AUTOMATION} for none) on {@code day}: positive went
     * in, negative came out. Days older than {@code keepDays} are dropped first. A day earlier than the newest one kept
     * (a world whose time went back) counts on the newest day.
     */
    public void record(UUID player, K key, long delta, long day, int keepDays, int maxRows) {
        if (delta == 0) {
            return;
        }
        Day<K> today = today(day, keepDays);
        Object2ObjectOpenHashMap<K, long[]> keys = today.players().get(player);
        long[] row = keys == null ? null : keys.get(key);
        if (row == null) {
            if (maxRows <= 0) {
                unrecorded++;
                return;
            }
            while (rows >= maxRows && days.size() > 1) {
                drop(0);
            }
            if (rows >= maxRows) {
                unrecorded++;
                return;
            }
            if (keys == null) {
                keys = new Object2ObjectOpenHashMap<>();
                today.players().put(player, keys);
            }
            row = new long[2];
            keys.put(key, row);
            rows++;
        }
        if (delta > 0) {
            row[0] += delta;
        } else {
            row[1] -= delta;
        }
    }

    /** The day {@code day}'s rows, made when new; drops the days that fell out of the window. */
    private Day<K> today(long day, int keepDays) {
        expire(day, keepDays);
        if (!days.isEmpty() && days.get(days.size() - 1).day() >= day) {
            return days.get(days.size() - 1);
        }
        Day<K> created = new Day<>(day, new Object2ObjectOpenHashMap<>());
        days.add(created);
        return created;
    }

    private void drop(int index) {
        rows -= days.remove(index).size();
    }

    /**
     * Drops the days older than {@code keepDays} before {@code day}, for a ledger read on a day with no changes yet.
     */
    public void expire(long day, int keepDays) {
        long first = day - Math.max(1, keepDays) + 1;
        while (!days.isEmpty() && days.get(0).day() < first) {
            drop(0);
        }
    }

    /**
     * Adds every row of {@code other} to this book on its own day (M7R item 3: a merged controller's ledger, or an old
     * ledger module's moved into its controller): amounts of a row both books have add up. Days of {@code other} older
     * than {@code keepDays} before {@code today} are left out; a new row past {@code maxRows} is only counted in
     * {@link #unrecorded()}, as are the rows {@code other} could not record. {@code other} is not changed.
     */
    public void merge(LedgerBook<K> other, long today, int keepDays, int maxRows) {
        expire(today, keepDays);
        long first = today - Math.max(1, keepDays) + 1;
        for (int d = 0; d < other.days.size(); d++) {
            Day<K> source = other.days.get(d);
            if (source.day() < first) {
                continue;
            }
            Day<K> into = dayOf(source.day());
            for (Object2ObjectMap.Entry<UUID, Object2ObjectOpenHashMap<K, long[]>> who : Object2ObjectMaps
                    .fastIterable(source.players())) {
                for (Object2ObjectMap.Entry<K, long[]> entry : Object2ObjectMaps.fastIterable(who.getValue())) {
                    Object2ObjectOpenHashMap<K, long[]> keys = into.players().get(who.getKey());
                    long[] row = keys == null ? null : keys.get(entry.getKey());
                    if (row == null) {
                        if (rows >= maxRows) {
                            unrecorded++;
                            continue;
                        }
                        if (keys == null) {
                            keys = new Object2ObjectOpenHashMap<>();
                            into.players().put(who.getKey(), keys);
                        }
                        row = new long[2];
                        keys.put(entry.getKey(), row);
                        rows++;
                    }
                    row[0] += entry.getValue()[0];
                    row[1] += entry.getValue()[1];
                }
            }
        }
        unrecorded += other.unrecorded;
        // A day made for rows that found no room goes again.
        for (int i = days.size() - 1; i >= 0; i--) {
            if (days.get(i).players().isEmpty()) {
                days.remove(i);
            }
        }
    }

    /** The kept day {@code day}, made in its place (oldest first) when new. */
    private Day<K> dayOf(long day) {
        int at = days.size();
        while (at > 0 && days.get(at - 1).day() > day) {
            at--;
        }
        if (at > 0 && days.get(at - 1).day() == day) {
            return days.get(at - 1);
        }
        Day<K> created = new Day<>(day, new Object2ObjectOpenHashMap<>());
        days.add(at, created);
        return created;
    }

    /** Rows kept now, over all days and players. */
    public int rows() {
        return rows;
    }

    /** Days with at least one row. */
    public int days() {
        return days.size();
    }

    /** Changes that found no row (the cap was reached on the current day alone). */
    public long unrecorded() {
        return unrecorded;
    }

    /** {@code player}'s {in, out} of {@code key} on {@code day}; zeros for none. */
    public long[] dayTotals(UUID player, K key, long day) {
        for (int i = 0; i < days.size(); i++) {
            if (days.get(i).day() == day) {
                Object2ObjectOpenHashMap<K, long[]> keys = days.get(i).players().get(player);
                long[] row = keys == null ? null : keys.get(key);
                return row == null ? new long[2] : row.clone();
            }
        }
        return new long[2];
    }

    /**
     * Totals over the kept days, busiest first, at most {@code limit}: {@code player}'s rows, or with null every key
     * summed over all players (those rows carry {@link #AUTOMATION} as their player).
     */
    public List<Row<K>> totals(int limit, @Nullable UUID player) {
        Object2ObjectOpenHashMap<K, long[]> sums = new Object2ObjectOpenHashMap<>();
        for (int i = 0; i < days.size(); i++) {
            for (Object2ObjectMap.Entry<UUID, Object2ObjectOpenHashMap<K, long[]>> who : Object2ObjectMaps
                    .fastIterable(days.get(i).players())) {
                if (player != null && !player.equals(who.getKey())) {
                    continue;
                }
                for (Object2ObjectMap.Entry<K, long[]> entry : Object2ObjectMaps.fastIterable(who.getValue())) {
                    long[] sum = sums.computeIfAbsent(entry.getKey(), k -> new long[2]);
                    sum[0] += entry.getValue()[0];
                    sum[1] += entry.getValue()[1];
                }
            }
        }
        UUID label = player == null ? AUTOMATION : player;
        List<Row<K>> out = new ArrayList<>(sums.size());
        for (Object2ObjectMap.Entry<K, long[]> entry : Object2ObjectMaps.fastIterable(sums)) {
            out.add(new Row<>(label, entry.getKey(), entry.getValue()[0], entry.getValue()[1]));
        }
        out.sort(Comparator.comparingLong((Row<K> row) -> row.volume()).reversed());
        return out.size() > limit ? List.copyOf(out.subList(0, Math.max(0, limit))) : out;
    }

    /** Every player with a row in the kept days ({@link #AUTOMATION} included), busiest first. */
    public List<UUID> players() {
        Object2LongOpenHashMap<UUID> volume = new Object2LongOpenHashMap<>();
        for (int i = 0; i < days.size(); i++) {
            for (Object2ObjectMap.Entry<UUID, Object2ObjectOpenHashMap<K, long[]>> who : Object2ObjectMaps
                    .fastIterable(days.get(i).players())) {
                long sum = 0;
                for (long[] row : who.getValue().values()) {
                    sum += row[0] + row[1];
                }
                volume.addTo(who.getKey(), sum);
            }
        }
        List<UUID> out = new ArrayList<>(volume.keySet());
        out.sort(Comparator.comparingLong(volume::getLong).reversed());
        return out;
    }

    /**
     * The saved form of one day: its players once, its keys with each one's player as an index into them, and the in
     * and out amounts in two parallel arrays. A day without players (saved before F9) is all automation.
     */
    private record SavedDay<K>(long day, List<UUID> players, IntList who, List<K> keys, LongList in, LongList out) {
    }

    private static final Codec<LongList> LONGS = Codec.LONG_STREAM.xmap(stream -> LongArrayList.wrap(stream.toArray()),
            LongList::longStream);
    private static final Codec<IntList> INTS = Codec.INT_STREAM.xmap(stream -> IntArrayList.wrap(stream.toArray()),
            IntList::intStream);

    /** How a book is saved, with {@code keyCodec} for the keys. */
    public static <K> Codec<LedgerBook<K>> codec(Codec<K> keyCodec) {
        Codec<SavedDay<K>> day = RecordCodecBuilder.create(instance -> instance
                .group(Codec.LONG.fieldOf("day").forGetter(SavedDay::day),
                        UUIDUtil.CODEC.listOf().optionalFieldOf("players", List.of()).forGetter(SavedDay::players),
                        INTS.optionalFieldOf("who", IntList.of()).forGetter(SavedDay::who),
                        keyCodec.listOf().fieldOf("keys").forGetter(SavedDay::keys),
                        LONGS.fieldOf("in").forGetter(SavedDay::in), LONGS.fieldOf("out").forGetter(SavedDay::out))
                .apply(instance, SavedDay::new));
        return RecordCodecBuilder.create(instance -> instance
                .group(day.listOf().fieldOf("days").forGetter(LedgerBook::saved),
                        Codec.LONG.optionalFieldOf("unrecorded", 0L).forGetter(LedgerBook::unrecorded))
                .apply(instance, LedgerBook::load));
    }

    private List<SavedDay<K>> saved() {
        List<SavedDay<K>> out = new ArrayList<>(days.size());
        for (int i = 0; i < days.size(); i++) {
            Day<K> day = days.get(i);
            int size = day.size();
            List<UUID> players = new ArrayList<>(day.players().size());
            IntArrayList who = new IntArrayList(size);
            List<K> keys = new ArrayList<>(size);
            LongArrayList in = new LongArrayList(size);
            LongArrayList outs = new LongArrayList(size);
            for (Object2ObjectMap.Entry<UUID, Object2ObjectOpenHashMap<K, long[]>> player : Object2ObjectMaps
                    .fastIterable(day.players())) {
                int index = players.size();
                players.add(player.getKey());
                for (Object2ObjectMap.Entry<K, long[]> entry : Object2ObjectMaps.fastIterable(player.getValue())) {
                    who.add(index);
                    keys.add(entry.getKey());
                    in.add(entry.getValue()[0]);
                    outs.add(entry.getValue()[1]);
                }
            }
            out.add(new SavedDay<>(day.day(), players, who, keys, in, outs));
        }
        return out;
    }

    private static <K> LedgerBook<K> load(List<SavedDay<K>> saved, long unrecorded) {
        LedgerBook<K> book = new LedgerBook<>();
        for (SavedDay<K> day : saved) {
            int size = Math.min(day.keys().size(), Math.min(day.in().size(), day.out().size()));
            Object2ObjectOpenHashMap<UUID, Object2ObjectOpenHashMap<K, long[]>> players = new Object2ObjectOpenHashMap<>();
            int count = 0;
            for (int i = 0; i < size; i++) {
                int index = i < day.who().size() ? day.who().getInt(i) : -1;
                UUID player = index >= 0 && index < day.players().size() ? day.players().get(index) : AUTOMATION;
                if (players.computeIfAbsent(player, p -> new Object2ObjectOpenHashMap<>()).put(day.keys().get(i),
                        new long[]{day.in().getLong(i), day.out().getLong(i)}) == null) {
                    count++;
                }
            }
            // Saved oldest first; a hand-edited save out of order is put back in order here.
            int at = book.days.size();
            while (at > 0 && book.days.get(at - 1).day() > day.day()) {
                at--;
            }
            book.days.add(at, new Day<>(day.day(), players));
            book.rows += count;
        }
        book.unrecorded = unrecorded;
        return book;
    }
}
