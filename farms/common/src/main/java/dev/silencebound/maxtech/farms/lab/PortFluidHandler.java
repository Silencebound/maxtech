package dev.silencebound.maxtech.farms.lab;

import dev.silencebound.maxtech.farms.hub.LabHubBlockEntity;
import java.util.Objects;
// PLATFORM-LEAK: NeoForge's transfer api is the fluid capability other mods push into (root CLAUDE.md: transfers).
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.fluid.FluidResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.fluid.FluidStacksResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * A fluid port as other mods see it: one slot showing the lab hub's tank, which takes what is pushed in (in the
 * caller's transaction, counting only what the tank took) and gives nothing back. Without a lab network or a hub it
 * shows an empty slot of no capacity and takes nothing. Server thread only.
 */
public final class PortFluidHandler implements ResourceHandler<FluidResource> {
    private final FluidPortBlockEntity port;

    PortFluidHandler(FluidPortBlockEntity port) {
        this.port = port;
    }

    private @Nullable FluidStacksResourceHandler tank() {
        LabNetwork lab = port.labOrNull();
        LabHubBlockEntity hub = lab == null ? null : lab.hub().orElse(null);
        return hub == null ? null : hub.storage().tank();
    }

    @Override
    public int size() {
        return 1;
    }

    @Override
    public FluidResource getResource(int index) {
        Objects.checkIndex(index, size());
        FluidStacksResourceHandler tank = tank();
        return tank == null ? FluidResource.EMPTY : tank.getResource(0);
    }

    @Override
    public long getAmountAsLong(int index) {
        Objects.checkIndex(index, size());
        FluidStacksResourceHandler tank = tank();
        return tank == null ? 0 : tank.getAmountAsLong(0);
    }

    @Override
    public long getCapacityAsLong(int index, FluidResource resource) {
        Objects.checkIndex(index, size());
        FluidStacksResourceHandler tank = tank();
        return tank == null ? 0 : tank.getCapacityAsLong(0, resource);
    }

    @Override
    public boolean isValid(int index, FluidResource resource) {
        Objects.checkIndex(index, size());
        FluidStacksResourceHandler tank = tank();
        return tank != null && tank.isValid(0, resource);
    }

    @Override
    public int insert(int index, FluidResource resource, int amount, TransactionContext transaction) {
        Objects.checkIndex(index, size());
        LabNetwork lab = port.labOrNull();
        return lab == null ? 0 : lab.insertNutrient(resource, amount, transaction);
    }

    /** A port never gives fluid back. */
    @Override
    public int extract(int index, FluidResource resource, int amount, TransactionContext transaction) {
        Objects.checkIndex(index, size());
        return 0;
    }
}
