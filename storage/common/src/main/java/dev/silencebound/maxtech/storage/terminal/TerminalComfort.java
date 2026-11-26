package dev.silencebound.maxtech.storage.terminal;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.List;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
// PLATFORM-LEAK: player transfers run in NeoForge transactions, like every Storage transfer (PRD 7.2).
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The terminal's comfort actions on the server, for any {@link TerminalSource}: deposit what the source already holds,
 * restock the hotbar, and pull a recipe's ingredients into the inventory. Each move is its own transaction, so a source
 * that refuses part (energy) moves the rest. Items only move between the source and the player; what the inventory
 * cannot take after counting room is dropped at the player, never lost. Server thread; access checks are the caller's.
 */
public final class TerminalComfort {
    /** Most sets a pull with "max" asks for, whatever the stack sizes allow. */
    static final int MAX_SETS = 64;

    private TerminalComfort() {
    }

    /**
     * Stores every stack of the main inventory (not the hotbar, armour or offhand) whose item the source already holds.
     * Returns the number of items stored.
     */
    public static long depositStored(TerminalSource source, Player player) {
        Inventory inventory = player.getInventory();
        long stored = 0;
        for (int slot = Inventory.getSelectionSize(); slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            ItemKey key = ItemKey.of(stack);
            if (available(source, key) <= 0) {
                continue;
            }
            long moved;
            try (Transaction transaction = Transaction.openRoot()) {
                moved = source.insert(key, stack.getCount(), transaction);
                transaction.commit();
            }
            if (moved > 0) {
                stack.shrink((int) moved);
                inventory.setChanged();
                stored += moved;
            }
        }
        return stored;
    }

    /** Tops every hotbar stack up to a full stack from the source. Returns the number of items moved. */
    public static long restockHotbar(TerminalSource source, Player player) {
        Inventory inventory = player.getInventory();
        long moved = 0;
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            int missing = stack.getMaxStackSize() - stack.getCount();
            if (stack.isEmpty() || missing <= 0) {
                continue;
            }
            long taken;
            try (Transaction transaction = Transaction.openRoot()) {
                taken = source.extract(ItemKey.of(stack), missing, transaction);
                transaction.commit();
            }
            if (taken > 0) {
                stack.grow((int) taken);
                inventory.setChanged();
                moved += taken;
            }
        }
        return moved;
    }

    /**
     * Pulls one set of a recipe's ingredients ({@code cells}: acceptable stacks per grid cell, from the client) into
     * the player's inventory, or with {@code maxTransfer} as many sets as the scarcest ingredient and a stack of each
     * allow. Each cell takes its first candidate the source still has enough of; a cell no candidate matches is left
     * out, so a partial set still arrives. Returns the number of items pulled.
     */
    public static long pullRecipe(TerminalSource source, Player player, List<List<ItemStack>> cells,
            boolean maxTransfer) {
        Object2IntLinkedOpenHashMap<ItemKey> perSet = new Object2IntLinkedOpenHashMap<>();
        for (List<ItemStack> candidates : cells) {
            for (ItemStack candidate : candidates) {
                if (candidate.isEmpty()) {
                    continue;
                }
                ItemKey key = ItemKey.of(candidate);
                if (available(source, key) > perSet.getInt(key)) {
                    perSet.addTo(key, 1);
                    break;
                }
            }
        }
        if (perSet.isEmpty()) {
            return 0;
        }
        int sets = 1;
        if (maxTransfer) {
            sets = MAX_SETS;
            for (Object2IntMap.Entry<ItemKey> entry : perSet.object2IntEntrySet()) {
                int each = entry.getIntValue();
                long byStock = available(source, entry.getKey()) / each;
                long byStack = entry.getKey().toStack(1).getMaxStackSize() / each;
                sets = (int) Math.min(sets, Math.max(1, Math.min(byStock, byStack)));
            }
        }
        long pulled = 0;
        for (Object2IntMap.Entry<ItemKey> entry : perSet.object2IntEntrySet()) {
            ItemKey key = entry.getKey();
            ItemStack sample = key.toStack(1);
            long wanted = Math.min((long) entry.getIntValue() * sets, room(player, sample));
            if (wanted <= 0) {
                continue;
            }
            long taken;
            try (Transaction transaction = Transaction.openRoot()) {
                taken = source.extract(key, wanted, transaction);
                transaction.commit();
            }
            if (taken > 0) {
                give(player, key, taken);
                pulled += taken;
            }
        }
        return pulled;
    }

    /** How much of {@code key} the source would hand out now, found in a transaction that is never committed. */
    static long available(TerminalSource source, ItemKey key) {
        try (Transaction probe = Transaction.openRoot()) {
            return source.extract(key, Integer.MAX_VALUE, probe);
        }
    }

    private static void give(Player player, ItemKey key, long count) {
        int max = key.toStack(1).getMaxStackSize();
        long left = count;
        while (left > 0) {
            ItemStack stack = key.toStack((int) Math.min(left, max));
            left -= stack.getCount();
            if (!player.getInventory().add(stack) && !stack.isEmpty()) {
                player.drop(stack, false);
            }
        }
    }

    /** How many of {@code sample} fit in the main inventory and hotbar. */
    public static long room(Player player, ItemStack sample) {
        Inventory inventory = player.getInventory();
        long room = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack held = inventory.getItem(i);
            if (held.isEmpty()) {
                room += sample.getMaxStackSize();
            } else if (ItemStack.isSameItemSameComponents(held, sample)) {
                room += Math.max(0, held.getMaxStackSize() - held.getCount());
            }
        }
        return room;
    }
}
