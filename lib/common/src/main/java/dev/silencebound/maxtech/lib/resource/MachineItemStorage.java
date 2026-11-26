package dev.silencebound.maxtech.lib.resource;

import java.util.function.Predicate;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
// Machine slots are NeoForge item handlers, so pipes, hoppers and other mods use them directly (ADR-0002).
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * One group of machine slots (the inputs or the outputs). Changes that come through a transaction are reported once it
 * commits, direct {@link #set} calls at once; either way {@code onChange} runs, never during a simulation. Slots take
 * any item unless the storage was made with a rule (e.g. a machine accepting only its recipe inputs). Storage's
 * machines use it.
 */
public final class MachineItemStorage extends ItemStacksResourceHandler {
    private final int slots;
    private final Runnable onChange;
    private final @Nullable Predicate<ItemStack> accepts;
    private final int limit;

    public MachineItemStorage(int size, Runnable onChange) {
        this(size, onChange, null, Integer.MAX_VALUE);
    }

    /**
     * Slots that take only what {@code accepts} allows (if given), at most {@code limit} items each. The rule is asked
     * with a one-item stack on insertion only.
     */
    public MachineItemStorage(int size, Runnable onChange, @Nullable Predicate<ItemStack> accepts, int limit) {
        super(size);
        if (limit < 1) {
            throw new IllegalArgumentException("Slot limit must be at least 1, was " + limit);
        }
        this.slots = size;
        this.onChange = onChange;
        this.accepts = accepts;
        this.limit = limit;
    }

    /** Loads saved stacks; a save with another slot count (the category changed) keeps what fits. */
    @Override
    public void deserialize(ValueInput input) {
        super.deserialize(input);
        if (size() != slots) {
            NonNullList<ItemStack> resized = NonNullList.withSize(slots, ItemStack.EMPTY);
            for (int i = 0; i < Math.min(slots, size()); i++) {
                resized.set(i, stacks.get(i));
            }
            setStacks(resized);
        }
    }

    /** The live stack in {@code index}; read only, never modify or keep it. Recipe matching reads slots this way. */
    public ItemStack stack(int index) {
        return stacks.get(index);
    }

    /**
     * Moves the whole stack in {@code index} into {@code target} if all of it fits there, filling matching stacks
     * first; returns whether it moved. Nothing moves when only part would fit.
     */
    public boolean moveTo(int index, MachineItemStorage target) {
        ItemStack stack = stacks.get(index);
        if (stack.isEmpty()) {
            return false;
        }
        ItemResource resource = ItemResource.of(stack);
        int count = stack.getCount();
        // Closing without a commit rolls a partial insert back. Server thread, outside any other transaction.
        try (Transaction transaction = Transaction.openRoot()) {
            if (ResourceHandlerUtil.insertStacking(target, resource, count, transaction) < count) {
                return false;
            }
            transaction.commit();
        }
        set(index, ItemResource.EMPTY, 0);
        return true;
    }

    /** Removes {@code count} items from {@code index}; the caller has checked they are there. */
    public void shrink(int index, int count) {
        ItemStack stack = stacks.get(index);
        int remaining = stack.getCount() - count;
        set(index, getResourceFrom(remaining > 0 ? stack : ItemStack.EMPTY), remaining);
    }

    @Override
    public boolean isValid(int index, ItemResource resource) {
        Predicate<ItemStack> rule = accepts;
        return rule == null || rule.test(resource.toStack(1));
    }

    @Override
    protected int getCapacity(int index, ItemResource resource) {
        return Math.min(limit, super.getCapacity(index, resource));
    }

    @Override
    protected void onContentsChanged(int index, ItemStack previousContents) {
        onChange.run();
    }
}
