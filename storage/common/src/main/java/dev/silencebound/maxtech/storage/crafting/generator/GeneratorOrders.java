package dev.silencebound.maxtech.storage.crafting.generator;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJobs;
import dev.silencebound.maxtech.storage.crafting.job.JobState;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.ToLongFunction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * The open orders of one {@link dev.silencebound.maxtech.storage.crafting.pattern.GeneratorSource}: units still owed
 * per crafting job, oldest order first. Shared by the generator provider (M7C item 11) and the condition switch's
 * request mode (B09 item 14), which differ only in how they make the units arrive. Mutable, server thread only.
 */
public final class GeneratorOrders {
    /** One open order, for saving. */
    record Order(UUID job, long amount) {
        static final Codec<Order> CODEC = RecordCodecBuilder
                .create(instance -> instance.group(UUIDUtil.CODEC.fieldOf("job").forGetter(Order::job),
                        Codec.LONG.fieldOf("amount").forGetter(Order::amount)).apply(instance, Order::new));
    }

    private final Object2LongLinkedOpenHashMap<UUID> orders = new Object2LongLinkedOpenHashMap<>();
    /** Units still owed on all open orders; 0 while idle. */
    private long remaining;

    /** Units still owed on all open orders. */
    public long remaining() {
        return remaining;
    }

    public boolean isEmpty() {
        return remaining <= 0;
    }

    /** What is still owed to job {@code job}; 0 without an order for it. */
    public long owed(UUID job) {
        return orders.getLong(job);
    }

    /** Adds {@code amount} (more than 0) to job {@code job}'s order. */
    public void add(UUID job, long amount) {
        orders.put(job, saturatedAdd(orders.getLong(job), amount));
        remaining = saturatedAdd(remaining, amount);
    }

    /** Withdraws job {@code job}'s order; returns whether there was one. */
    public boolean cancel(UUID job) {
        if (!orders.containsKey(job)) {
            return false;
        }
        remaining = Math.max(0, remaining - orders.removeLong(job));
        if (remaining == 0) {
            orders.clear();
        }
        return true;
    }

    /** Forgets every order. */
    public void clear() {
        orders.clear();
        remaining = 0;
    }

    /** Counts {@code moved} arrived units against the orders, oldest first. */
    public void fill(long moved) {
        long left = moved;
        ObjectIterator<Object2LongMap.Entry<UUID>> iterator = orders.object2LongEntrySet().fastIterator();
        while (iterator.hasNext() && left > 0) {
            Object2LongMap.Entry<UUID> entry = iterator.next();
            long take = Math.min(left, entry.getLongValue());
            left -= take;
            if (take >= entry.getLongValue()) {
                iterator.remove();
            } else {
                entry.setValue(entry.getLongValue() - take);
            }
        }
        remaining = Math.max(0, remaining - moved);
        if (remaining == 0) {
            orders.clear();
        }
    }

    /**
     * Lowers each order to at most {@code cap.applyAsLong(job)}, dropping it at 0: what the job itself still waits for,
     * when some of its units arrived another way. Returns whether anything changed. Allocation-free.
     */
    public boolean capTo(ToLongFunction<UUID> cap) {
        boolean changed = false;
        ObjectIterator<Object2LongMap.Entry<UUID>> iterator = orders.object2LongEntrySet().fastIterator();
        while (iterator.hasNext()) {
            Object2LongMap.Entry<UUID> entry = iterator.next();
            long most = Math.max(0, cap.applyAsLong(entry.getKey()));
            if (entry.getLongValue() > most) {
                remaining = Math.max(0, remaining - (entry.getLongValue() - most));
                changed = true;
                if (most == 0) {
                    iterator.remove();
                } else {
                    entry.setValue(most);
                }
            }
        }
        if (remaining == 0) {
            orders.clear();
        }
        return changed;
    }

    /**
     * Removes the orders whose job is gone or not running (they ended while the holder was unloaded, so their cancel
     * never reached it); returns whether any went. Once per start, not per tick.
     */
    public boolean dropEnded(ServerLevel level) {
        if (orders.isEmpty()) {
            return false;
        }
        CraftingJobs jobs = CraftingJobs.get(level);
        ObjectIterator<Object2LongMap.Entry<UUID>> iterator = orders.object2LongEntrySet().fastIterator();
        boolean dropped = false;
        while (iterator.hasNext()) {
            Object2LongMap.Entry<UUID> entry = iterator.next();
            CraftingJob job = jobs.get(entry.getKey());
            if (job == null || job.state() != JobState.RUNNING) {
                remaining = Math.max(0, remaining - entry.getLongValue());
                iterator.remove();
                dropped = true;
            }
        }
        if (dropped && remaining == 0) {
            orders.clear();
        }
        return dropped;
    }

    /** Writes the open orders under {@code key}; nothing while there are none. */
    public void save(ValueOutput output, String key) {
        if (!orders.isEmpty()) {
            List<Order> open = new ArrayList<>(orders.size());
            for (Object2LongMap.Entry<UUID> entry : orders.object2LongEntrySet()) {
                open.add(new Order(entry.getKey(), entry.getLongValue()));
            }
            output.store(key, Order.CODEC.listOf(), open);
        }
    }

    /** Replaces the orders with what {@link #save} wrote under {@code key}. */
    public void load(ValueInput input, String key) {
        clear();
        for (Order order : input.read(key, Order.CODEC.listOf()).orElse(List.of())) {
            if (order.amount() > 0) {
                add(order.job(), order.amount());
            }
        }
    }

    static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }
}
