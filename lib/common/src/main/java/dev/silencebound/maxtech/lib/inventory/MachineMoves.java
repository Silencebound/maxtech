package dev.silencebound.maxtech.lib.inventory;

import java.util.List;
import java.util.Objects;
import net.minecraft.world.item.ItemStack;
// PLATFORM-LEAK: a machine's own moves take part in NeoForge transfer transactions (ADR-0002, ADR-0019).
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.Transaction;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * The all-or-nothing item moves behind {@code lib.neoforge.api.inventory.Handlers#insertAll} and {@code #extractAll},
 * here in common so Lib's machine core can use them without the api naming NeoForge types.
 */
public final class MachineMoves {
    private MachineMoves() {
    }

    /** See {@code Handlers#insertAll}. */
    public static boolean insertAll(ResourceHandler<ItemResource> handler, int from, int to, List<ItemStack> stacks,
            TransactionContext transaction) {
        Objects.checkFromToIndex(from, to, handler.size());
        try (Transaction nested = Transaction.open(transaction)) {
            for (int i = 0; i < stacks.size(); i++) {
                ItemStack stack = stacks.get(i);
                if (stack.isEmpty()) {
                    continue;
                }
                ItemResource resource = ItemResource.of(stack);
                int left = stack.getCount();
                left -= insertInto(handler, from, to, resource, left, true, nested);
                left -= insertInto(handler, from, to, resource, left, false, nested);
                if (left > 0) {
                    return false;
                }
            }
            nested.commit();
            return true;
        }
    }

    /** See {@code Handlers#extractAll}. */
    public static boolean extractAll(ResourceHandler<ItemResource> handler, int from, int to, List<ItemStack> stacks,
            TransactionContext transaction) {
        Objects.checkFromToIndex(from, to, handler.size());
        try (Transaction nested = Transaction.open(transaction)) {
            for (int i = 0; i < stacks.size(); i++) {
                ItemStack stack = stacks.get(i);
                if (stack.isEmpty()) {
                    continue;
                }
                ItemResource resource = ItemResource.of(stack);
                int left = stack.getCount();
                for (int slot = from; slot < to && left > 0; slot++) {
                    if (resource.equals(handler.getResource(slot))) {
                        left -= handler.extract(slot, resource, left, nested);
                    }
                }
                if (left > 0) {
                    return false;
                }
            }
            nested.commit();
            return true;
        }
    }

    /**
     * Puts up to {@code amount} of {@code resource} into the slots {@code from} to {@code to}, first onto slots holding
     * it, then into empty ones; returns how much went in. Part of {@code transaction}, not nested. Allocates nothing.
     */
    public static int insert(ResourceHandler<ItemResource> handler, int from, int to, ItemResource resource, int amount,
            TransactionContext transaction) {
        int moved = insertInto(handler, from, to, resource, amount, true, transaction);
        return moved + insertInto(handler, from, to, resource, amount - moved, false, transaction);
    }

    /** Inserts into the range's slots that already hold {@code resource} ({@code stacking}) or are empty. */
    private static int insertInto(ResourceHandler<ItemResource> handler, int from, int to, ItemResource resource,
            int amount, boolean stacking, TransactionContext transaction) {
        int moved = 0;
        for (int slot = from; slot < to && moved < amount; slot++) {
            ItemResource held = handler.getResource(slot);
            if (stacking ? resource.equals(held) : held.isEmpty()) {
                moved += handler.insert(slot, resource, amount - moved, transaction);
            }
        }
        return moved;
    }
}
