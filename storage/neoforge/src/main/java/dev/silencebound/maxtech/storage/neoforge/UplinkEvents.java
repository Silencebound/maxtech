package dev.silencebound.maxtech.storage.neoforge;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.tool.uplink.PickupOverflow;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.TriState;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;

/**
 * Pickup overflow for the pocket uplink. {@link ItemEntityPickupEvent.Pre} fires on every touch, before vanilla checks
 * the pickup delay and the item's target, so the handler repeats those checks unless another handler forced the pickup;
 * once the uplink stored the stack, the pickup is denied so vanilla does nothing more with it.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class UplinkEvents {
    private UplinkEvents() {
    }

    @SubscribeEvent
    static void pickup(ItemEntityPickupEvent.Pre event) {
        TriState state = event.canPickup();
        if (state.isFalse() || !(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        ItemEntity item = event.getItemEntity();
        UUID target = item.getTarget();
        if (!state.isTrue() && (item.hasPickUpDelay() || (target != null && !target.equals(player.getUUID())))) {
            return;
        }
        if (PickupOverflow.overflow(player, item) > 0) {
            event.setCanPickup(TriState.FALSE);
        }
    }
}
