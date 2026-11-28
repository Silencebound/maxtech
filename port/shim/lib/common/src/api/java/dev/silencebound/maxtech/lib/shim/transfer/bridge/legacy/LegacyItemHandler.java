package dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.shim.transfer.ResourceHandler;
import dev.silencebound.maxtech.lib.shim.transfer.TransferPreconditions;
import dev.silencebound.maxtech.lib.shim.transfer.item.ItemResource;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.SnapshotJournal;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.TransactionContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.slf4j.Logger;

/**
 * A {@code ResourceHandler<ItemResource>} over a 1.21.1 {@link IItemHandler}, which has no transactions. Moves are
 * simulated on the real handler and applied when the root transaction commits, so a rollback never touches the
 * handler.
 *
 * <p>To keep the simulation exact, a transaction may change one slot of the handler: once a slot has a pending move,
 * moves on other slots return 0 until the root transaction ends, and a slot only accepts moves of the resource it
 * already has pending. Repeated moves on that slot are simulated as one combined move. A handler whose simulation
 * promises more than it then accepts would lose or duplicate items on commit; that is logged as an error.
 *
 * <p>Server or client thread only, like the wrapped handler.
 */
public final class LegacyItemHandler implements ResourceHandler<ItemResource> {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final IItemHandler handler;
    private final Pending pending = new Pending();

    public LegacyItemHandler(IItemHandler handler) {
        this.handler = handler;
    }

    /** {@return the wrapped 1.21.1 handler} */
    public IItemHandler handler() {
        return handler;
    }

    @Override
    public int size() {
        return handler.getSlots();
    }

    @Override
    public ItemResource getResource(int index) {
        ItemStack stack = handler.getStackInSlot(index);
        if (pending.delta != 0 && pending.slot == index) {
            return stack.getCount() + pending.delta <= 0 ? ItemResource.EMPTY : pending.resource;
        }
        return ItemResource.of(stack);
    }

    @Override
    public long getAmountAsLong(int index) {
        int count = handler.getStackInSlot(index).getCount();
        if (pending.delta != 0 && pending.slot == index) {
            return Math.max(0, count + pending.delta);
        }
        return count;
    }

    @Override
    public long getCapacityAsLong(int index, ItemResource resource) {
        int limit = handler.getSlotLimit(index);
        // Same as ItemStackHandler's stack limit: the slot limit, capped by the item's own max stack size.
        return resource.isEmpty() ? limit : Math.min(limit, resource.getMaxStackSize());
    }

    @Override
    public boolean isValid(int index, ItemResource resource) {
        return !resource.isEmpty() && handler.isItemValid(index, resource.toStack());
    }

    @Override
    public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        if (amount == 0 || !pending.allows(index, resource)) {
            return 0;
        }
        int delta = pending.delta;
        int accepted;
        if (delta >= 0) {
            int combined = LegacyMath.add(delta, amount);
            accepted = simulateInsert(index, resource, combined) - delta;
        } else {
            // Putting back what this transaction took out always fits; anything beyond goes on top of the real stack.
            int back = Math.min(amount, -delta);
            int rest = amount - back;
            accepted = back + (rest > 0 ? simulateInsert(index, resource, rest) : 0);
        }
        if (accepted <= 0) {
            return 0;
        }
        pending.set(index, resource, delta + accepted, transaction);
        return accepted;
    }

    @Override
    public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        if (amount == 0 || !pending.allows(index, resource)) {
            return 0;
        }
        int delta = pending.delta;
        int accepted;
        if (delta <= 0) {
            int combined = LegacyMath.add(-delta, amount);
            accepted = simulateExtract(index, resource, combined) + delta;
        } else {
            // Taking back what this transaction put in always works; anything beyond comes from the real stack.
            int back = Math.min(amount, delta);
            int rest = amount - back;
            accepted = back + (rest > 0 ? simulateExtract(index, resource, rest) : 0);
        }
        if (accepted <= 0) {
            return 0;
        }
        pending.set(index, resource, delta - accepted, transaction);
        return accepted;
    }

    private int simulateInsert(int index, ItemResource resource, int amount) {
        ItemStack remainder = handler.insertItem(index, resource.toStack(amount), true);
        return amount - remainder.getCount();
    }

    private int simulateExtract(int index, ItemResource resource, int amount) {
        if (!resource.matches(handler.getStackInSlot(index))) {
            return 0;
        }
        ItemStack extracted = handler.extractItem(index, amount, true);
        return resource.matches(extracted) ? extracted.getCount() : 0;
    }

    @Override
    public String toString() {
        return "LegacyItemHandler{" + handler + "}";
    }

    private record State(int slot, ItemResource resource, int delta) {
    }

    /** The one pending slot change of the current root transaction. */
    private final class Pending extends SnapshotJournal<State> {
        int slot = -1;
        ItemResource resource = ItemResource.EMPTY;
        int delta;

        boolean allows(int index, ItemResource candidate) {
            return delta == 0 || (slot == index && resource.equals(candidate));
        }

        void set(int index, ItemResource newResource, int newDelta, TransactionContext transaction) {
            updateSnapshots(transaction);
            slot = index;
            resource = newResource;
            delta = newDelta;
        }

        @Override
        protected State createSnapshot() {
            return new State(slot, resource, delta);
        }

        @Override
        protected void revertToSnapshot(State snapshot) {
            slot = snapshot.slot();
            resource = snapshot.resource();
            delta = snapshot.delta();
        }

        @Override
        protected void onRootCommit(State originalState) {
            int index = slot;
            ItemResource moved = resource;
            int change = delta;
            slot = -1;
            resource = ItemResource.EMPTY;
            delta = 0;
            if (change > 0) {
                ItemStack remainder = handler.insertItem(index, moved.toStack(change), false);
                if (!remainder.isEmpty()) {
                    remainder = ItemHandlerHelper.insertItemStacked(handler, remainder, false);
                }
                if (!remainder.isEmpty()) {
                    LOGGER.error("{} accepted {} x {} in simulation but not on commit; {} lost", handler, change,
                            moved, remainder.getCount());
                }
            } else if (change < 0) {
                int left = -change;
                while (left > 0 && moved.matches(handler.getStackInSlot(index))) {
                    ItemStack extracted = handler.extractItem(index, left, false);
                    if (extracted.isEmpty()) {
                        break;
                    }
                    left -= extracted.getCount();
                }
                if (left > 0) {
                    LOGGER.error("{} gave {} x {} in simulation but not on commit; {} duplicated", handler, -change,
                            moved, left);
                }
            }
        }
    }
}
