package dev.silencebound.maxtech.storage.neoforge;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cell.StorageCellItem;
import dev.silencebound.maxtech.storage.controller.recovery.CellRecovery;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.item.ItemExpireEvent;

/**
 * The two ways a storage cell's item entity goes for good that {@code Item#onDestroyed} does not see (M7C item 20):
 * despawning and falling out of the world. Either hands the cell to {@link CellRecovery}, which moves its contents into
 * its network's recovery bin.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class RecoveryEvents {
    private RecoveryEvents() {
    }

    /**
     * Last, so other mods' extra life counts: the item despawns when its age reaches the lifespan plus the extra life,
     * as {@code ItemEntity.tick} works it out right after the event.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void expire(ItemExpireEvent event) {
        ItemEntity entity = event.getEntity();
        if (!(entity.getItem().getItem() instanceof StorageCellItem)
                || !(entity.level() instanceof ServerLevel level)) {
            return;
        }
        int life = Mth.clamp(entity.lifespan + event.getExtraLife(), 0, Short.MAX_VALUE - 1);
        if (entity.getAge() >= life) {
            CellRecovery.lost(level, entity.getItem());
        }
    }

    /** {@code Entity.checkBelowWorld} discards an entity 64 blocks under the world's floor. */
    @SubscribeEvent
    static void leave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof ItemEntity entity && entity.getItem().getItem() instanceof StorageCellItem
                && event.getLevel() instanceof ServerLevel level
                && entity.getRemovalReason() == Entity.RemovalReason.DISCARDED
                && entity.getY() < level.getMinY() - 64) {
            CellRecovery.lost(level, entity.getItem());
        }
    }
}
