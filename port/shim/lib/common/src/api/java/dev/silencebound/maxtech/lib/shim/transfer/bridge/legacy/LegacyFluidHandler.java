package dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.shim.transfer.ResourceHandler;
import dev.silencebound.maxtech.lib.shim.transfer.TransferPreconditions;
import dev.silencebound.maxtech.lib.shim.transfer.fluid.FluidResource;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.TransactionContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import org.slf4j.Logger;

/**
 * A {@code ResourceHandler<FluidResource>} over a 1.21.1 {@link IFluidHandler}, which has no transactions. The
 * transaction state lives in a {@link LegacyLedger} shared by all wrappers on the same block: fills and
 * drains are simulated (combined) and applied on the root commit, so a rollback never touches the handler.
 *
 * <p>{@code fill} does not take a tank, so the whole handler is one ledger slot: one fluid at a time may have a
 * pending fill, and filling another fluid returns 0 until the root transaction ends (two fluids simulated separately
 * could both count the same empty tank). An indexed move only checks that the tank holds that fluid or is empty; the
 * handler picks the tank itself. If the handler changed between simulation and commit, fluid it no longer accepts is
 * lost and fluid it no longer gives was handed on anyway; both are logged.
 *
 * <p>Server or client thread only, like the wrapped handler.
 */
public final class LegacyFluidHandler implements ResourceHandler<FluidResource> {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final LegacyLedger.Registry<FluidResource> LEDGERS = new LegacyLedger.Registry<>();
    private static final int KEY = 0;

    private final IFluidHandler handler;
    private final LegacyLedger<FluidResource> ledger;
    private final Real real = new Real();

    /**
     * A wrapper for a handler found at a block: every wrapper and query on that block (any side) shares one ledger,
     * since 1.21.1 providers often return a new handler per query.
     */
    public LegacyFluidHandler(IFluidHandler handler, Level level, BlockPos pos) {
        this(handler, new LegacyLedger.Location(level, pos.asLong()));
    }

    /** A wrapper for a handler with no block; it shares its ledger only with wrappers of the same object. */
    public LegacyFluidHandler(IFluidHandler handler) {
        this(handler, handler);
    }

    LegacyFluidHandler(IFluidHandler handler, Object ledgerKey) {
        this.handler = handler;
        this.ledger = LEDGERS.of(ledgerKey);
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
        if (index != pendingTank()) {
            return FluidResource.of(stack);
        }
        FluidResource pending = ledger.pendingResource(KEY);
        return pending == null || getAmountAsLong(index) <= 0 ? FluidResource.EMPTY : pending;
    }

    @Override
    public long getAmountAsLong(int index) {
        int amount = handler.getFluidInTank(index).getAmount();
        if (index != pendingTank()) {
            return amount;
        }
        FluidResource pending = ledger.pendingResource(KEY);
        return pending == null ? amount : Math.max(0, (long) amount + ledger.pendingDelta(KEY, pending));
    }

    /** The tank that shows the pending fill: the first tank holding that fluid, else the first empty one; or -1. */
    private int pendingTank() {
        FluidResource pending = ledger.pendingResource(KEY);
        if (pending == null) {
            return -1;
        }
        int empty = -1;
        for (int tank = 0, n = handler.getTanks(); tank < n; tank++) {
            FluidStack stack = handler.getFluidInTank(tank);
            if (pending.matches(stack)) {
                return tank;
            }
            if (empty < 0 && stack.isEmpty()) {
                empty = tank;
            }
        }
        return empty;
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
        return ledger.insert(real, KEY, KEY, resource, amount, transaction);
    }

    @Override
    public int insert(FluidResource resource, int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        return ledger.insert(real, KEY, KEY, resource, amount, transaction);
    }

    @Override
    public int extract(int index, FluidResource resource, int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        if (!resource.matches(handler.getFluidInTank(index)) && ledger.pendingInsert(KEY, resource) == 0) {
            return 0;
        }
        return ledger.extract(real, KEY, KEY, resource, amount, transaction);
    }

    @Override
    public int extract(FluidResource resource, int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        return ledger.extract(real, KEY, KEY, resource, amount, transaction);
    }

    @Override
    public String toString() {
        return "LegacyFluidHandler{" + handler + "}";
    }

    /** Real, non-transactional moves on the handler, called by the ledger. */
    private final class Real implements LegacyTarget<FluidResource> {
        @Override
        public Object handler() {
            return handler;
        }

        @Override
        public boolean extractsImmediately() {
            // IFluidHandler cannot put fluid back past its own fill rules (output tanks), so draining is deferred.
            return false;
        }

        @Override
        public int simulateExtract(int slot, FluidResource resource, int amount) {
            FluidStack drained = handler.drain(resource.toStack(amount), FluidAction.SIMULATE);
            return resource.matches(drained) ? drained.getAmount() : 0;
        }

        @Override
        public int restore(int slot, FluidResource resource, int amount) {
            return 0;
        }

        @Override
        public int simulateInsert(int slot, FluidResource resource, int amount) {
            return handler.fill(resource.toStack(amount), FluidAction.SIMULATE);
        }

        @Override
        public int insert(int slot, FluidResource resource, int amount) {
            return handler.fill(resource.toStack(amount), FluidAction.EXECUTE);
        }

        @Override
        public int insertAnywhere(FluidResource resource, int amount) {
            // fill already tries every tank.
            return 0;
        }

        @Override
        public int extract(int slot, FluidResource resource, int amount) {
            FluidStack simulated = handler.drain(resource.toStack(amount), FluidAction.SIMULATE);
            if (simulated.isEmpty() || !resource.matches(simulated)) {
                return 0;
            }
            FluidStack drained = handler.drain(resource.toStack(simulated.getAmount()), FluidAction.EXECUTE);
            return resource.matches(drained) ? drained.getAmount() : 0;
        }

        @Override
        public void overflow(FluidResource resource, int amount) {
            LOGGER.error("{} mB of {} could not go back into {}; lost", amount, resource, handler);
        }
    }
}
