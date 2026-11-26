package dev.silencebound.maxtech.lib.api.carried;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Where a carried stack sits: a hand, an inventory slot, or a slot of a {@link CarriedItemSource}. An open GUI keeps
 * one and reads the stack again on each use, since the player may move it away, and compares what it reads with what it
 * opened for. Changes made to the returned stack in place are changes to the carried item. Experimental (Lib 0.4.0):
 * may change in any Lib minor version, also after 1.0. Server thread, like the GUIs that hold one.
 */
@ApiStatus.Experimental
@FunctionalInterface
public interface CarriedSlot {
    /** A slot that never holds anything, for a client-side menu that has no slot to read. */
    CarriedSlot EMPTY = () -> ItemStack.EMPTY;

    /** The stack in this slot now; {@link ItemStack#EMPTY} when the slot is empty or gone, never null. */
    ItemStack get();

    /**
     * {@code player}'s {@code hand}. The main hand follows the selected hotbar slot, as {@link Player#getItemInHand}
     * does. Slots of the same hand of the same player are equal.
     */
    static CarriedSlot hand(Player player, InteractionHand hand) {
        return new Slots.Hand(player, hand);
    }

    /**
     * Container slot {@code index} of {@code player}'s inventory; empty for an index out of range. Slots of the same
     * index of the same player are equal.
     */
    static CarriedSlot inventory(Player player, int index) {
        return new Slots.Inventory(player, index);
    }
}
