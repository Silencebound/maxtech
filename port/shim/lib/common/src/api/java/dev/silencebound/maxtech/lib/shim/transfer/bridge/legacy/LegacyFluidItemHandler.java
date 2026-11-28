package dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy;

import dev.silencebound.maxtech.lib.shim.transfer.ResourceHandler;
import dev.silencebound.maxtech.lib.shim.transfer.TransferPreconditions;
import dev.silencebound.maxtech.lib.shim.transfer.access.ItemAccess;
import dev.silencebound.maxtech.lib.shim.transfer.fluid.FluidResource;
import dev.silencebound.maxtech.lib.shim.transfer.item.ItemResource;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.Transaction;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.TransactionContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;
import org.jspecify.annotations.Nullable;

/**
 * The fluid handler of one item of an {@link ItemAccess}, over the item's 1.21.1 {@link IFluidHandlerItem}. Each move
 * runs on a fresh one-item copy of the accessed item, then exchanges that item for the resulting container through the
 * access, inside the caller's transaction; the real item only changes when the access commits.
 *
 * <p>Server or client thread only.
 */
public final class LegacyFluidItemHandler implements ResourceHandler<FluidResource> {
    private final ItemAccess access;

    public LegacyFluidItemHandler(ItemAccess access) {
        this.access = access;
    }

    /** The 1.21.1 handler of a one-item copy of the accessed item, or null when it has none. */
    private @Nullable IFluidHandlerItem view() {
        ItemResource item = access.getResource();
        if (item.isEmpty() || access.getAmount() <= 0) {
            return null;
        }
        return item.toStack().getCapability(Capabilities.FluidHandler.ITEM);
    }

    @Override
    public int size() {
        IFluidHandlerItem view = view();
        return view == null ? 0 : view.getTanks();
    }

    @Override
    public FluidResource getResource(int index) {
        IFluidHandlerItem view = view();
        return view == null || index >= view.getTanks() ? FluidResource.EMPTY
                : FluidResource.of(view.getFluidInTank(index));
    }

    @Override
    public long getAmountAsLong(int index) {
        IFluidHandlerItem view = view();
        return view == null || index >= view.getTanks() ? 0 : view.getFluidInTank(index).getAmount();
    }

    @Override
    public long getCapacityAsLong(int index, FluidResource resource) {
        IFluidHandlerItem view = view();
        return view == null || index >= view.getTanks() ? 0 : view.getTankCapacity(index);
    }

    @Override
    public boolean isValid(int index, FluidResource resource) {
        IFluidHandlerItem view = view();
        return view != null && index < view.getTanks() && !resource.isEmpty()
                && view.isFluidValid(index, resource.toStack(1));
    }

    @Override
    public int insert(int index, FluidResource resource, int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        IFluidHandlerItem view = view();
        if (amount == 0 || view == null || index >= view.getTanks()) {
            return 0;
        }
        FluidStack inTank = view.getFluidInTank(index);
        if (!inTank.isEmpty() && !resource.matches(inTank)) {
            return 0;
        }
        return fill(view, resource, amount, transaction);
    }

    @Override
    public int insert(FluidResource resource, int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        IFluidHandlerItem view = view();
        return amount == 0 || view == null ? 0 : fill(view, resource, amount, transaction);
    }

    @Override
    public int extract(int index, FluidResource resource, int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        IFluidHandlerItem view = view();
        if (amount == 0 || view == null || index >= view.getTanks()
                || !resource.matches(view.getFluidInTank(index))) {
            return 0;
        }
        return drain(view, resource, amount, transaction);
    }

    @Override
    public int extract(FluidResource resource, int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        IFluidHandlerItem view = view();
        return amount == 0 || view == null ? 0 : drain(view, resource, amount, transaction);
    }

    private int fill(IFluidHandlerItem view, FluidResource resource, int amount, TransactionContext transaction) {
        int filled = view.fill(resource.toStack(amount), FluidAction.EXECUTE);
        return filled > 0 && replaceItem(view.getContainer(), transaction) ? filled : 0;
    }

    private int drain(IFluidHandlerItem view, FluidResource resource, int amount, TransactionContext transaction) {
        FluidStack drained = view.drain(resource.toStack(amount), FluidAction.EXECUTE);
        if (drained.isEmpty() || !resource.matches(drained)) {
            return 0;
        }
        return replaceItem(view.getContainer(), transaction) ? drained.getAmount() : 0;
    }

    /** Swaps one accessed item for the container the copy turned into; false (and no change) if the access refuses. */
    private boolean replaceItem(ItemStack container, TransactionContext transaction) {
        ItemResource current = access.getResource();
        try (Transaction swap = Transaction.open(transaction)) {
            if (access.extract(current, 1, swap) != 1) {
                return false;
            }
            if (!container.isEmpty()) {
                int count = container.getCount();
                if (access.insert(ItemResource.of(container), count, swap) != count) {
                    return false;
                }
            }
            swap.commit();
            return true;
        }
    }

    @Override
    public String toString() {
        return "LegacyFluidItemHandler{" + access + "}";
    }
}
