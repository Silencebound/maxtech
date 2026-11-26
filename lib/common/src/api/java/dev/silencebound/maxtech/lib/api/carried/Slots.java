package dev.silencebound.maxtech.lib.api.carried;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** The vanilla {@link CarriedSlot}s; records so equal slots compare equal. Not api: use the factories. */
final class Slots {
    private Slots() {
    }

    /** A hand of a player. */
    record Hand(Player player, InteractionHand hand) implements CarriedSlot {
        @Override
        public ItemStack get() {
            return player.getItemInHand(hand);
        }
    }

    /** A slot of a player's inventory, by its container index. */
    record Inventory(Player player, int index) implements CarriedSlot {
        @Override
        public ItemStack get() {
            return index >= 0 && index < player.getInventory().getContainerSize()
                    ? player.getInventory().getItem(index)
                    : ItemStack.EMPTY;
        }
    }
}
