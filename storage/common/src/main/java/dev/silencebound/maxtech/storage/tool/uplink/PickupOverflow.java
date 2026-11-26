package dev.silencebound.maxtech.storage.tool.uplink;

import dev.silencebound.maxtech.lib.api.carried.CarriedItemMatcher;
import dev.silencebound.maxtech.lib.api.carried.CarriedItems;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.storage.controller.module.DimensionBridge;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
// PLATFORM-LEAK: the store runs in a NeoForge transaction, like every Storage transfer (PRD 7.2).
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Pickup overflow (M6e): when a player touches an item on the ground that their inventory has no room for at all, the
 * first active pocket uplink they carry whose filter takes it stores the stack in its bound network instead. An
 * inventory with room for part of the stack takes that part the vanilla way; the rest overflows on the next touch, once
 * the inventory is full. Needs deposit access on the network; across dimensions each store pays the bridge's surcharge.
 * Called from the loader's item pickup event, before vanilla's own pickup. Server thread.
 */
public final class PickupOverflow {
    /** An uplink whose filter takes the context stack. */
    private static final CarriedItemMatcher<ItemStack> ACCEPTS = (player, candidate,
            stack) -> candidate.getItem() instanceof PocketUplinkItem
                    && PocketUplinkItem.settings(candidate).accepts(stack);

    private PickupOverflow() {
    }

    /**
     * Stores as much of {@code item}'s stack as the network takes when {@code player}'s inventory is full; returns the
     * count stored, 0 when nothing overflowed. A stack stored whole discards the entity.
     */
    public static int overflow(ServerPlayer player, ItemEntity item) {
        ItemStack stack = item.getItem();
        if (stack.isEmpty() || player.isSpectator() || hasRoom(player.getInventory(), stack)) {
            return 0;
        }
        ItemStack uplink = uplinkFor(player, stack);
        if (uplink.isEmpty()) {
            return 0;
        }
        NetworkState network = UplinkReach.network(player, uplink, AccessLevel.DEPOSIT);
        if (network == null) {
            return 0;
        }
        int count = stack.getCount();
        boolean crossing = UplinkReach.crossing(player, uplink);
        long stored;
        try (Transaction transaction = Transaction.openRoot()) {
            stored = network.insert(ItemKey.of(stack), count, transaction, player);
            if (stored > 0 && crossing && !DimensionBridge.pay(network, transaction)) {
                stored = 0;
            } else {
                transaction.commit();
            }
        }
        if (stored <= 0) {
            return 0;
        }
        int moved = (int) Math.min(stored, count);
        player.take(item, moved);
        stack.shrink(moved);
        if (stack.isEmpty()) {
            item.discard();
        }
        return moved;
    }

    /** Whether {@code inventory} would take at least one of {@code stack}; vanilla's own room checks. */
    static boolean hasRoom(Inventory inventory, ItemStack stack) {
        return inventory.getFreeSlot() >= 0 || inventory.getSlotWithRemainingSpace(stack) >= 0;
    }

    /**
     * The first active uplink {@code player} carries (main slots, then the offhand, then accessory slots of other mods,
     * {@link CarriedItems}) whose filter takes {@code stack}; empty for none.
     */
    public static ItemStack uplinkFor(Player player, ItemStack stack) {
        return CarriedItems.find(player, ACCEPTS, stack);
    }
}
