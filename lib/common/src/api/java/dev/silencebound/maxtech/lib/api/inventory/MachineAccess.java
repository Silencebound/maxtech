package dev.silencebound.maxtech.lib.api.inventory;

import java.util.List;
import java.util.Objects;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;
// PLATFORM-LEAK: a machine's own moves take part in NeoForge transfer transactions (ADR-0002).
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.Transaction;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * A machine's own access to its {@link ItemSlots} and {@link ResourceTank}s inside a NeoForge transfer transaction, so
 * one step (take the inputs, put out the results) commits or rolls back as a whole. The direct methods of the slots and
 * tanks are their own committed step and throw while a transaction is open; the handlers here do not, and like the
 * machine's own methods they ignore {@link SlotRole}s (a machine puts results into its output slots) but keep every
 * slot's filter and limit and a tank's filter and capacity.
 * <p>
 * Changes made through these handlers are journaled: a rolled-back transaction restores every stack and tank exactly,
 * and the inventory's change listener runs only when the root transaction commits, once per changed slot or tank. A
 * slot holding an {@link ItemSlots#unknown} item takes and gives nothing, an unknown tank takes nothing and reads as
 * empty, and {@link ItemSlots#overflow()} is never reached, so nothing Lib keeps for a removed mod is overwritten or
 * lost.
 * <p>
 * Server thread only, like the block entity that owns the inventories; transactions are per thread. Players and
 * automation use {@code lib.neoforge.api.inventory.Handlers} instead, which apply the roles. Experimental until the
 * machine core (Lib's {@code MachineProcess}) settles it.
 */
@ApiStatus.Experimental
public final class MachineAccess {
    private MachineAccess() {
    }

    /**
     * The machine's item handler over {@code slots}: every slot by its index, roles not applied, filters and limits
     * kept; display slots are reachable too. The same instance for the same slots. Throws
     * {@link IllegalArgumentException} for slots Lib did not build.
     */
    public static ResourceHandler<ItemResource> items(ItemSlots slots) {
        Objects.requireNonNull(slots, "slots");
        return InventoryFactory.get().machineItems(slots);
    }

    /**
     * The machine's transactional view of {@code tank}, keeping its filter and capacity. The same instance for the same
     * tank. Throws {@link IllegalArgumentException} for a tank Lib did not build.
     */
    public static <K> Tank<K> tank(ResourceTank<K> tank) {
        Objects.requireNonNull(tank, "tank");
        return InventoryFactory.get().machineTank(tank);
    }

    /**
     * Puts every stack of {@code stacks} into the slots {@code from} (inclusive) to {@code to} (exclusive) of
     * {@code handler}, each first onto slots already holding the same item and components, then into empty ones, in
     * slot order. All or nothing: runs in a transaction nested in {@code transaction} and returns false, with nothing
     * moved, if any stack does not fit whole (a filter refuses it, limits leave too little room); true once all went
     * in. Empty stacks are skipped, so an empty list returns true. The stacks themselves are not changed. Throws
     * {@link IndexOutOfBoundsException} for a range outside the handler.
     */
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

    /**
     * Takes every stack of {@code stacks} (its item, components and count) out of the slots {@code from} (inclusive) to
     * {@code to} (exclusive) of {@code handler}, from the first slot holding it on, summing over slots. All or nothing,
     * like {@link #insertAll}: false with nothing taken if any stack is short. Empty stacks are skipped. The stacks
     * themselves are not changed. Throws {@link IndexOutOfBoundsException} for a range outside the handler.
     */
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

    /**
     * A machine's transactional view of one {@link ResourceTank}: moves take part in the given transaction, keep the
     * tank's filter and capacity, and are reported to the tank's change listener when the root commits. Read the
     * contents through the tank itself. Implemented by Lib only; server thread only.
     *
     * @param <K>
     *            the key type of the tank's kind
     */
    @ApiStatus.NonExtendable
    public interface Tank<K> {
        /**
         * Puts up to {@code amount} of {@code key} in if the filter takes it and the tank is empty or holds
         * {@code key}; returns how much went in (0 for an amount of 0 or less, and while the tank is unknown or over
         * its capacity).
         */
        long insert(K key, long amount, TransactionContext transaction);

        /** Takes up to {@code amount} of {@code key} out if the tank holds it; returns how much came out. */
        long extract(K key, long amount, TransactionContext transaction);

        /**
         * Puts exactly {@code amount} of {@code key} in, or nothing: false, in a nested transaction rolled back, if
         * less fits. True for an amount of 0 or less.
         */
        boolean insertExactly(K key, long amount, TransactionContext transaction);

        /**
         * Takes exactly {@code amount} of {@code key} out, or nothing: false, in a nested transaction rolled back, if
         * the tank holds less. True for an amount of 0 or less.
         */
        boolean extractExactly(K key, long amount, TransactionContext transaction);
    }
}
