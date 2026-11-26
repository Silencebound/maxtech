package dev.silencebound.maxtech.storage.display.production;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.iface.InterfaceDevice;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.ItemMoveListener;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.port.PortDevice;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * A network's production stats (M7C item 18): items per minute of each crafting CPU (items its jobs brought back), each
 * stock keeper (items its finished jobs asked for) and each machine pool (what the ports and interfaces sharing a label
 * moved), with a short history, and the items coming into and going out of the network most. The top lists and the
 * pools listen to the network's committed moves ({@link NetworkState#listenItemMoves}, the stream the ledger and logic
 * read); a CPU's and a keeper's items never pass a device transfer, so they are counted at events that already exist:
 * where the network counts a job's outputs and where a keeper hears its job end. A count costs a few map updates,
 * nothing polls, and the minute buckets only roll when something is counted or read. Made on first use of a network
 * ({@link #of}), by a display showing the stats, or the controller's overview, and gone with the network; a merge or
 * split starts it again, like the recorder's history. Only items count (fluids have another unit), in and out added up
 * for pools. A group appears with its first count and goes away after {@link #BUCKETS} idle minutes. Server thread
 * only.
 */
public final class ProductionStats implements ItemMoveListener {
    /** Minutes the history keeps, one bucket per minute (the last one is the minute not over yet). */
    public static final int BUCKETS = 16;
    /** Most groups of one kind; further ones are not counted until a group goes away. */
    public static final int MAX_GROUPS = 16;
    /** Items listed in each of the top lists. */
    public static final int TOP = 5;

    /** What a group is. */
    public enum Kind {
        CPU, STOCK_KEEPER, POOL
    }

    /**
     * One group's reading.
     *
     * @param label
     *            the device's label (a pool's name), empty for none
     * @param pos
     *            the device's node position, 0 for a pool
     * @param perMinute
     *            items moved per minute now
     * @param history
     *            finished minutes, oldest first, as items per minute
     */
    public record Line(Kind kind, String label, long pos, long perMinute, LongList history) {
    }

    /** One item of a top list and its items per minute. */
    public record Top(ItemKey item, long perMinute) {
    }

    private static final Map<NetworkState, ProductionStats> ALL = new WeakHashMap<>();

    private static final class Group {
        final Kind kind;
        final long pos;
        final RateRing ring = new RateRing(RateRing.MINUTE, BUCKETS);
        String label = "";
        boolean dropped;

        Group(Kind kind, long pos) {
            this.kind = kind;
            this.pos = pos;
        }
    }

    private final ServerLevel level;
    private final KeyRates<StackKey> in = new KeyRates<>(RateRing.MINUTE);
    private final KeyRates<StackKey> out = new KeyRates<>(RateRing.MINUTE);
    private final Long2ObjectOpenHashMap<Group> cpus = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Group> keepers = new Long2ObjectOpenHashMap<>();
    /** Pools by label text as written; spellings that differ in case point to one group. */
    private final Object2ObjectOpenHashMap<String, Group> pools = new Object2ObjectOpenHashMap<>();
    private int poolCount;

    private ProductionStats(ServerLevel level) {
        this.level = level;
    }

    /** The stats of {@code network}, listening to its moves from now on when they are new. */
    public static ProductionStats of(NetworkState network) {
        ProductionStats stats = ALL.get(network);
        if (stats == null) {
            stats = new ProductionStats(network.level());
            ALL.put(network, stats);
            network.listenItemMoves(null, stats);
        }
        return stats;
    }

    @Override
    public void itemsMoved(StackKey key, long amount, @Nullable NetworkDevice device) {
        if (amount == 0 || !(key instanceof ItemKey)) {
            return;
        }
        long now = level.getGameTime();
        long moved = Math.abs(amount);
        if (amount > 0) {
            in.add(key, moved, now);
        } else {
            out.add(key, moved, now);
        }
        // Ports and interfaces sharing a label are a machine pool; no other device's moves make a group.
        if (device instanceof PortDevice || device instanceof InterfaceDevice) {
            DeviceLabel label = device.label();
            Group group = label.isEmpty() ? null : pool(label);
            if (group != null) {
                group.ring.add(moved, now);
            }
        }
    }

    /**
     * A crafting CPU's job brought {@code amount} items back (called where the network counts a job's outputs, which is
     * the CPU's only event: its items never pass a device transfer).
     */
    public void crafted(long cpuPos, long amount) {
        count(cpus, Kind.CPU, cpuPos, amount);
    }

    /** A stock keeper's job ended done having asked for {@code amount} items (called where the keeper hears it). */
    public void stocked(long keeperPos, long amount) {
        count(keepers, Kind.STOCK_KEEPER, keeperPos, amount);
    }

    private void count(Long2ObjectOpenHashMap<Group> map, Kind kind, long pos, long amount) {
        if (amount <= 0) {
            return;
        }
        Group group = map.get(pos);
        if (group == null) {
            if (map.size() >= MAX_GROUPS) {
                return;
            }
            group = new Group(kind, pos);
            map.put(pos, group);
        }
        group.ring.add(amount, level.getGameTime());
    }

    /** The label of the device at {@code pos} now, empty without one or when its chunk is not loaded. */
    private String labelAt(long pos) {
        BlockPos at = BlockPos.of(pos);
        if (level.hasChunkAt(at) && level.getBlockEntity(at) instanceof NetworkDevice device) {
            return device.label().text();
        }
        return "";
    }

    private @Nullable Group pool(DeviceLabel label) {
        Group group = pools.get(label.text());
        if (group != null) {
            return group;
        }
        // A spelling not seen yet: find the pool by its lower-case key (allocates, once per spelling).
        String key = label.key();
        for (Group known : pools.values()) {
            if (known.label.toLowerCase(Locale.ROOT).equals(key)) {
                pools.put(label.text(), known);
                return known;
            }
        }
        if (poolCount >= MAX_GROUPS) {
            return null;
        }
        group = new Group(Kind.POOL, 0);
        group.label = label.text();
        pools.put(label.text(), group);
        poolCount++;
        return group;
    }

    /** All groups with their readings, the busiest first; drops the groups that did nothing for the whole history. */
    public List<Line> groups(long now) {
        List<Line> lines = new ArrayList<>();
        prune(cpus, now);
        prune(keepers, now);
        prunePools(now);
        add(lines, cpus.values(), now);
        add(lines, keepers.values(), now);
        add(lines, new LinkedHashSet<>(pools.values()), now);
        lines.sort((a, b) -> Long.compare(b.perMinute(), a.perMinute()));
        return lines;
    }

    private void add(List<Line> lines, Iterable<Group> groups, long now) {
        for (Group group : groups) {
            long[] history = new long[BUCKETS - 1];
            int count = group.ring.history(now, history);
            lines.add(new Line(group.kind, group.kind == Kind.POOL ? group.label : labelAt(group.pos), group.pos,
                    group.ring.perMinute(now), new LongArrayList(history, 0, count)));
        }
    }

    private static void prune(Long2ObjectOpenHashMap<Group> map, long now) {
        map.values().removeIf(group -> group.ring.idle(now));
    }

    private void prunePools(long now) {
        pools.values().removeIf(group -> {
            if (group.ring.idle(now)) {
                if (!group.dropped) {
                    group.dropped = true;
                    poolCount--;
                }
                return true;
            }
            return false;
        });
    }

    /** The items coming into the network most, the highest first, at most {@link #TOP}. */
    public List<Top> topIn(long now) {
        return top(in, now);
    }

    /** The items going out of the network most, the highest first, at most {@link #TOP}. */
    public List<Top> topOut(long now) {
        return top(out, now);
    }

    /** Items per minute coming into the network now, all items together. */
    public long totalIn(long now) {
        return in.totalPerMinute(now);
    }

    /** Items per minute going out of the network now, all items together. */
    public long totalOut(long now) {
        return out.totalPerMinute(now);
    }

    private static List<Top> top(KeyRates<StackKey> rates, long now) {
        StackKey[] keys = new StackKey[TOP];
        long[] values = new long[TOP];
        int count = rates.top(now, keys, values);
        List<Top> tops = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            tops.add(new Top((ItemKey) keys[i], values[i]));
        }
        return tops;
    }

    /** How many groups of any kind are counted now; for tests. */
    public int groupCount() {
        return cpus.size() + keepers.size() + new HashSet<>(pools.values()).size();
    }
}
