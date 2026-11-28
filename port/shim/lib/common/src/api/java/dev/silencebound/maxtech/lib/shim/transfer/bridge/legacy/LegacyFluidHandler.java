package dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.shim.transfer.ResourceHandler;
import dev.silencebound.maxtech.lib.shim.transfer.TransferPreconditions;
import dev.silencebound.maxtech.lib.shim.transfer.fluid.FluidResource;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.SnapshotJournal;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.TransactionContext;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import org.slf4j.Logger;

/**
 * A {@code ResourceHandler<FluidResource>} over a 1.21.1 {@link IFluidHandler}, which has no transactions. Moves are
 * simulated on the real handler and applied when the root transaction commits, so a rollback never touches the
 * handler.
 *
 * <p>{@code fill} and {@code drain} do not take a tank, so the pending change is per fluid, not per tank: a
 * transaction may move one fluid, repeated moves are simulated as one combined move, and moves of another fluid
 * return 0 until the root transaction ends. An indexed move only checks that the tank holds that fluid or is empty;
 * the handler picks the tank itself. The pending amount shows on the tank of the first indexed move. A handler whose
 * simulation promises more than it then accepts would lose or duplicate fluid on commit; that is logged as an error.
 *
 * <p>Server or client thread only, like the wrapped handler.
 */
public final class LegacyFluidHandler implements ResourceHandler<FluidResource> {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final IFluidHandler handler;
    private final Pending pending = new Pending();

    public LegacyFluidHandler(IFluidHandler handler) {
        this.handler = handler;
    }

    /** {@return the wrapped 1.21.1 handler} */
    public IFluidHandler handler() {
        return handler;
    }

    @Override
    public int size() {
        return handler.getTanks();
    }

    @Override
    public FluidResource getResource(int index) {
        FluidStack stack = handler.getFluidInTank(index);
        if (pending.delta != 0 && pending.tank == index) {
            return stack.getAmount() + pending.delta <= 0 ? FluidResource.EMPTY : pending.resource;
        }
        return FluidResource.of(stack);
    }

    @Override
    public long getAmountAsLong(int index) {
        int amount = handler.getFluidInTank(index).getAmount();
        if (pending.delta != 0 && pending.tank == index) {
            return Math.max(0, amount + pending.delta);
        }
        return amount;
    }

    @Override
    public long getCapacityAsLong(int index, FluidResource resource) {
        return handler.getTankCapacity(index);
    }

    @Override
    public boolean isValid(int index, FluidResource resource) {
        return !resource.isEmpty() && handler.isFluidValid(index, resource.toStack(1));
    }

    @Override
    public int insert(int index, FluidResource resource, int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        FluidStack inTank = handler.getFluidInTank(index);
        if (!inTank.isEmpty() && !resource.matches(inTank)) {
            return 0;
        }
        return fill(index, resource, amount, transaction);
    }

    @Override
    public int insert(FluidResource resource, int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        return fill(-1, resource, amount, transaction);
    }

    @Override
    public int extract(int index, FluidResource resource, int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        if (!resource.matches(handler.getFluidInTank(index)) && !(pending.tank == index && pending.delta > 0)) {
            return 0;
        }
        return drain(index, resource, amount, transaction);
    }

    @Override
    public int extract(FluidResource resource, int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        return drain(-1, resource, amount, transaction);
    }

    private int fill(int tank, FluidResource resource, int amount, TransactionContext transaction) {
        if (amount == 0 || !pending.allows(resource)) {
            return 0;
        }
        int delta = pending.delta;
        int accepted;
        if (delta >= 0) {
            accepted = simulateFill(resource, LegacyMath.add(delta, amount)) - delta;
        } else {
            // Putting back what this transaction drained always fits; anything beyond goes on top of the real tanks.
            int back = Math.min(amount, -delta);
            int rest = amount - back;
            accepted = back + (rest > 0 ? simulateFill(resource, rest) : 0);
        }
        if (accepted <= 0) {
            return 0;
        }
        pending.set(tank, resource, delta + accepted, transaction);
        return accepted;
    }

    private int drain(int tank, FluidResource resource, int amount, TransactionContext transaction) {
        if (amount == 0 || !pending.allows(resource)) {
            return 0;
        }
        int delta = pending.delta;
        int accepted;
        if (delta <= 0) {
            accepted = simulateDrain(resource, LegacyMath.add(-delta, amount)) + delta;
        } else {
            // Taking back what this transaction filled always works; anything beyond comes from the real tanks.
            int back = Math.min(amount, delta);
            int rest = amount - back;
            accepted = back + (rest > 0 ? simulateDrain(resource, rest) : 0);
        }
        if (accepted <= 0) {
            return 0;
        }
        pending.set(tank, resource, delta - accepted, transaction);
        return accepted;
    }

    private int simulateFill(FluidResource resource, int amount) {
        return handler.fill(resource.toStack(amount), FluidAction.SIMULATE);
    }

    private int simulateDrain(FluidResource resource, int amount) {
        FluidStack drained = handler.drain(resource.toStack(amount), FluidAction.SIMULATE);
        return resource.matches(drained) ? drained.getAmount() : 0;
    }

    @Override
    public String toString() {
        return "LegacyFluidHandler{" + handler + "}";
    }

    private record State(int tank, FluidResource resource, int delta) {
    }

    /** The one pending fluid change of the current root transaction. */
    private final class Pending extends SnapshotJournal<State> {
        int tank = -1;
        FluidResource resource = FluidResource.EMPTY;
        int delta;

        boolean allows(FluidResource candidate) {
            return delta == 0 || resource.equals(candidate);
        }

        void set(int index, FluidResource newResource, int newDelta, TransactionContext transaction) {
            updateSnapshots(transaction);
            if (delta == 0 || tank < 0) {
                tank = index;
            }
            resource = newResource;
            delta = newDelta;
        }

        @Override
        protected State createSnapshot() {
            return new State(tank, resource, delta);
        }

        @Override
        protected void revertToSnapshot(State snapshot) {
            tank = snapshot.tank();
            resource = snapshot.resource();
            delta = snapshot.delta();
        }

        @Override
        protected void onRootCommit(State originalState) {
            FluidResource moved = resource;
            int change = delta;
            tank = -1;
            resource = FluidResource.EMPTY;
            delta = 0;
            if (change > 0) {
                int filled = handler.fill(moved.toStack(change), FluidAction.EXECUTE);
                if (filled < change) {
                    LOGGER.error("{} accepted {} mB of {} in simulation but not on commit; {} mB lost", handler, change,
                            moved, change - filled);
                }
            } else if (change < 0) {
                FluidStack drained = handler.drain(moved.toStack(-change), FluidAction.EXECUTE);
                int got = moved.matches(drained) ? drained.getAmount() : 0;
                if (got < -change) {
                    LOGGER.error("{} gave {} mB of {} in simulation but not on commit; {} mB duplicated", handler,
                            -change, moved, -change - got);
                }
            }
        }
    }
}
