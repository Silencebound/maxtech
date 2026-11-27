package dev.silencebound.maxtech.lib.inventory;

import com.google.common.primitives.Ints;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import java.util.Objects;
// PLATFORM-LEAK: a NeoForge fluid handler over Lib's tank (ADR-0002).
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.TransferPreconditions;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.fluid.FluidResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * A {@link LibFluidTank} as a NeoForge fluid handler with one index; changes go to the tank's journal, so they take
 * part in the caller's transaction. Amounts and the capacity are clamped to {@code int}. The resource of the last
 * conversion is kept, so reading an unchanged tank or moving the same fluid again allocates nothing.
 */
public final class FluidTankHandler implements ResourceHandler<FluidResource> {
    private final LibFluidTank tank;
    private @Nullable FluidKey lastKey;
    private FluidResource lastResource = FluidResource.EMPTY;

    FluidTankHandler(LibFluidTank tank) {
        this.tank = tank;
    }

    /** The tank this handler is over. */
    public LibFluidTank tank() {
        return tank;
    }

    @Override
    public int size() {
        return 1;
    }

    @Override
    public FluidResource getResource(int index) {
        Objects.checkIndex(index, 1);
        FluidKey key = tank.key();
        return key == null ? FluidResource.EMPTY : resourceOf(key);
    }

    @Override
    public long getAmountAsLong(int index) {
        Objects.checkIndex(index, 1);
        return tank.amount();
    }

    @Override
    public long getCapacityAsLong(int index, FluidResource resource) {
        Objects.checkIndex(index, 1);
        if (tank.unknown() || (!resource.isEmpty() && !isValid(index, resource))) {
            return 0;
        }
        return tank.capacity();
    }

    @Override
    public boolean isValid(int index, FluidResource resource) {
        Objects.checkIndex(index, 1);
        return !tank.unknown() && tank.accepts(keyOf(resource));
    }

    @Override
    public int insert(int index, FluidResource resource, int amount, TransactionContext transaction) {
        Objects.checkIndex(index, 1);
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        return (int) tank.insert(keyOf(resource), amount, transaction);
    }

    @Override
    public int extract(int index, FluidResource resource, int amount, TransactionContext transaction) {
        Objects.checkIndex(index, 1);
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        return Ints.saturatedCast(tank.extract(keyOf(resource), amount, transaction));
    }

    private FluidResource resourceOf(FluidKey key) {
        if (lastKey == null || !key.equals(lastKey)) {
            lastResource = FluidResource.of(key.fluid(), key.components());
            lastKey = key;
        }
        return lastResource;
    }

    private FluidKey keyOf(FluidResource resource) {
        FluidKey key = lastKey;
        if (key == null || !resource.equals(lastResource)) {
            key = FluidKey.of(resource.getFluid(), resource.getComponentsPatch());
            lastKey = key;
            lastResource = resource;
        }
        return key;
    }

    @Override
    public String toString() {
        return "FluidTankHandler[" + tank + "]";
    }
}
