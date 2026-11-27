package dev.silencebound.maxtech.lib.inventory;

import dev.silencebound.maxtech.lib.api.inventory.SlotRole;
// PLATFORM-LEAK: a NeoForge item handler over Lib's slots (ADR-0002).
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.TransferPreconditions;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * {@link LibItemSlots} with the slots' roles applied: only {@link SlotRole#INPUT} slots take anything, and only
 * {@link SlotRole#OUTPUT} slots (and, for players, inputs too) give anything. A slot that takes nothing reports
 * capacity 0, so a hopper sees it as full and does not try. Changes go straight to the slots' journal, so they take
 * part in the caller's transaction.
 */
public final class RoleView implements ResourceHandler<ItemResource> {
    private final LibItemSlots slots;
    private final boolean player;

    RoleView(LibItemSlots slots, boolean player) {
        this.slots = slots;
        this.player = player;
    }

    /** The slots this view is over. */
    public LibItemSlots slots() {
        return slots;
    }

    /** Whether {@code index} takes anything through this view. */
    public boolean canInsert(int index) {
        return slots.role(index) == SlotRole.INPUT;
    }

    /** Whether {@code index} gives anything through this view. */
    public boolean canExtract(int index) {
        SlotRole role = slots.role(index);
        return role == SlotRole.OUTPUT || (player && role == SlotRole.INPUT);
    }

    @Override
    public int size() {
        return slots.size();
    }

    @Override
    public ItemResource getResource(int index) {
        return slots.getResource(index);
    }

    @Override
    public long getAmountAsLong(int index) {
        return slots.getAmountAsLong(index);
    }

    @Override
    public long getCapacityAsLong(int index, ItemResource resource) {
        return canInsert(index) ? slots.getCapacityAsLong(index, resource) : 0;
    }

    @Override
    public boolean isValid(int index, ItemResource resource) {
        return canInsert(index) && slots.isValid(index, resource);
    }

    @Override
    public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        return canInsert(index) ? slots.insert(index, resource, amount, transaction) : 0;
    }

    @Override
    public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        return canExtract(index) ? slots.extract(index, resource, amount, transaction) : 0;
    }

    @Override
    public String toString() {
        return (player ? "PlayerSlots" : "AutomationSlots") + "[" + slots.size() + "]";
    }
}
