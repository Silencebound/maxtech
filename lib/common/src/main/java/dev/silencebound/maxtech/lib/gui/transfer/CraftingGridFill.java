package dev.silencebound.maxtech.lib.gui.transfer;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Fills a {@link CraftingGridMenu}'s grid with real items: first it empties the grid (into the ingredient source, then
 * the player's inventory, dropping only what fits nowhere), then for each cell it takes the first acceptable stack it
 * can find, from the source first and then the player's main inventory. Items only ever move, so nothing is created or
 * lost: everything taken either lands in the grid or is given back the same way. Server thread.
 */
public final class CraftingGridFill {
    private CraftingGridFill() {
    }

    /**
     * Fills {@code target}'s grid with one set of {@code cells} (or as many as a stack holds with {@code maxTransfer}).
     * Returns the number of cells filled. A cell nothing matches stays empty.
     */
    public static int fill(CraftingGridMenu target, ServerPlayer player, List<List<ItemStack>> cells,
            boolean maxTransfer) {
        if (!(target instanceof AbstractContainerMenu menu)) {
            throw new IllegalArgumentException(target + " is not a container menu");
        }
        IngredientSource source = target.ingredientSource(player);
        for (int cell = 0; cell < CraftingGridMenu.GRID_SIZE; cell++) {
            Slot slot = menu.getSlot(target.gridSlot(cell));
            if (slot.hasItem() && slot.mayPickup(player)) {
                giveBack(player, source, slot.remove(slot.getItem().getCount()));
            }
        }
        int filled = 0;
        for (int cell = 0; cell < CraftingGridMenu.GRID_SIZE && cell < cells.size(); cell++) {
            Slot slot = menu.getSlot(target.gridSlot(cell));
            if (slot.hasItem()) {
                // A slot that refused to be emptied keeps its item.
                continue;
            }
            for (ItemStack candidate : cells.get(cell)) {
                if (candidate.isEmpty() || !slot.mayPlace(candidate)) {
                    continue;
                }
                int wanted = maxTransfer ? Math.min(candidate.getMaxStackSize(), slot.getMaxStackSize(candidate)) : 1;
                ItemStack taken = take(player, source, candidate, wanted);
                if (!taken.isEmpty()) {
                    slot.set(taken);
                    filled++;
                    break;
                }
            }
        }
        menu.broadcastChanges();
        return filled;
    }

    private static ItemStack take(ServerPlayer player, IngredientSource source, ItemStack like, int wanted) {
        ItemStack taken = source.extract(like, wanted);
        if (!taken.isEmpty() && !ItemStack.isSameItemSameComponents(taken, like)) {
            // A source must hand out what was asked for; give back anything else rather than placing it.
            giveBack(player, source, taken);
            taken = ItemStack.EMPTY;
        }
        Inventory inventory = player.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE && taken.getCount() < wanted; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, like)) {
                ItemStack removed = inventory.removeItem(i, wanted - taken.getCount());
                if (taken.isEmpty()) {
                    taken = removed;
                } else {
                    taken.grow(removed.getCount());
                }
            }
        }
        return taken;
    }

    /** Returns {@code stack} to the source, then the player's inventory, and drops what fits in neither. */
    static void giveBack(ServerPlayer player, IngredientSource source, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack rest = source.insert(stack);
        if (!rest.isEmpty()) {
            // add() shrinks the stack by what it placed.
            player.getInventory().add(rest);
        }
        if (!rest.isEmpty()) {
            player.drop(rest, false);
        }
    }
}
