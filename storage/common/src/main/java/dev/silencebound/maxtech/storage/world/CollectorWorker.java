package dev.silencebound.maxtech.storage.world;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
// PLATFORM-LEAK: every store is a network transfer in a NeoForge transaction (PRD 7.2).
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The collector (PRD 7.2, Devices): pulls item entities within {@code world.collector.radius} blocks of the device into
 * its network, each entity's stack one operation, everything when the filter is blank and else what it names. It never
 * looks for entities on its own: {@link CollectorIndex} wakes it when an item entity enters the level in range
 * (dropped, spawned or loaded with its chunk), and placing or loading the device looks once. While items it may take
 * are still in range (the network was full, or out of energy) it runs again every
 * {@code world.collector.intervalTicks}. Items that appear within {@link #DRIFT_MARGIN} blocks beyond its range wake it
 * too, and while any it may take are there (carried by water, pushed, or still falling) it looks again every
 * {@link #DRIFT_RECHECK_TICKS} ticks, so a stream of mob drops that lands outside the range and flows in is collected.
 * With none left in range or margin it sleeps until the next one appears.
 */
public final class CollectorWorker extends WorldWorker {
    /**
     * Blocks beyond the range in which an item entity keeps the collector checking: how far an item may land from the
     * range and still drift into it. A performance bound, not balance: a larger margin only costs more rechecks.
     */
    public static final int DRIFT_MARGIN = 16;
    /** Ticks between looks while items it may take are only in the margin; a performance bound, not balance. */
    static final int DRIFT_RECHECK_TICKS = 20;

    CollectorWorker(Host host) {
        super(host);
    }

    @Override
    public WorldKind kind() {
        return WorldKind.COLLECTOR;
    }

    /** Whether the device is gone; the index drops it then. */
    boolean isGone() {
        return host.removed().getAsBoolean();
    }

    @Override
    public void start(ServerLevel level) {
        super.start(level);
        CollectorIndex.add(level, host.pos().get(), this);
    }

    /** No neighbour matters to a collector. */
    @Override
    public void neighbourChanged() {
    }

    /** From the index: an item entity is entering the level near the collector; wakes it within range or margin. */
    void itemNearby(ItemEntity item) {
        BlockPos pos = host.pos().get();
        int radius = radius() + DRIFT_MARGIN;
        if (Math.abs(item.getX() - (pos.getX() + 0.5)) <= radius + 0.5
                && Math.abs(item.getY() - (pos.getY() + 0.5)) <= radius + 0.5
                && Math.abs(item.getZ() - (pos.getZ() + 0.5)) <= radius + 0.5) {
            wake();
        }
    }

    private static int radius() {
        return Math.min(CollectorIndex.MAX_RADIUS, StorageServerConfig.COLLECTOR_RADIUS.getAsInt());
    }

    /** The box items are taken from: {@link #radius()} blocks around the device's block. */
    AABB range() {
        return new AABB(host.pos().get()).inflate(radius());
    }

    @Override
    protected int work(ServerLevel level, StorageTransfer transfer, long gameTime) {
        if (!transfer.canOperate()) {
            return Math.max(2, transfer.retryTicks());
        }
        // Runs only when an item appeared near or one is still waiting there, never as an idle poll.
        AABB range = range();
        List<ItemEntity> items = level.getEntities(EntityType.ITEM, range.inflate(DRIFT_MARGIN), ItemEntity::isAlive);
        boolean left = false;
        boolean drifting = false;
        for (int i = 0; i < items.size(); i++) {
            ItemEntity item = items.get(i);
            ItemStack stack = item.getItem();
            if (stack.isEmpty() || (!filter().isBlank() && !filter().matches(stack))) {
                continue;
            }
            if (!range.intersects(item.getBoundingBox())) {
                drifting = true;
                continue;
            }
            if (!store(item, stack, transfer)) {
                left = true;
                if (!transfer.canOperate()) {
                    return Math.max(2, transfer.retryTicks());
                }
            }
        }
        if (left) {
            return StorageServerConfig.COLLECTOR_INTERVAL.getAsInt();
        }
        return drifting ? DRIFT_RECHECK_TICKS : StorageTask.WAIT;
    }

    /** One operation for {@code item}'s stack; true when all of it went in. */
    private static boolean store(ItemEntity item, ItemStack stack, StorageTransfer transfer) {
        long moved;
        try (Transaction transaction = Transaction.openRoot()) {
            moved = transfer.insert(ItemKey.of(stack), stack.getCount(), transaction);
            transaction.commit();
        }
        if (moved <= 0) {
            return false;
        }
        ItemStack rest = stack.copyWithCount(stack.getCount() - (int) moved);
        if (rest.isEmpty()) {
            item.discard();
            return true;
        }
        item.setItem(rest);
        return false;
    }
}
