package dev.silencebound.maxtech.farms.hub;

import java.util.Objects;
// PLATFORM-LEAK: NeoForge's item transfer type; other mods' hoppers and pipes reach the hub through it.
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * The lab hub's items as other mods see them, on every side: index 0 is the fuel slot, which takes furnace fuel and
 * gives back only what is not fuel (an empty bucket left by lava); indices 1 to {@link HubStorage#OUTPUT_SLOTS} are the
 * output buffer, which only gives. Every move goes to the real handlers in the caller's transaction. Server thread
 * only.
 */
public final class HubItemHandler implements ResourceHandler<ItemResource> {
    private final ItemStacksResourceHandler fuel;
    private final ItemStacksResourceHandler output;

    HubItemHandler(ItemStacksResourceHandler fuel, ItemStacksResourceHandler output) {
        this.fuel = fuel;
        this.output = output;
    }

    @Override
    public int size() {
        return 1 + output.size();
    }

    @Override
    public ItemResource getResource(int index) {
        Objects.checkIndex(index, size());
        return index == 0 ? fuel.getResource(0) : output.getResource(index - 1);
    }

    @Override
    public long getAmountAsLong(int index) {
        Objects.checkIndex(index, size());
        return index == 0 ? fuel.getAmountAsLong(0) : output.getAmountAsLong(index - 1);
    }

    /** The fuel slot's capacity for fuel; output slots report 0 so nothing tries to fill them. */
    @Override
    public long getCapacityAsLong(int index, ItemResource resource) {
        Objects.checkIndex(index, size());
        return index == 0 ? fuel.getCapacityAsLong(0, resource) : 0;
    }

    @Override
    public boolean isValid(int index, ItemResource resource) {
        Objects.checkIndex(index, size());
        return index == 0 && fuel.isValid(0, resource);
    }

    @Override
    public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
        Objects.checkIndex(index, size());
        return index == 0 ? fuel.insert(0, resource, amount, transaction) : 0;
    }

    @Override
    public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
        Objects.checkIndex(index, size());
        if (index == 0) {
            // Fuel stays to burn; only a spent remainder leaves.
            return fuel.isValid(0, resource) ? 0 : fuel.extract(0, resource, amount, transaction);
        }
        return output.extract(index - 1, resource, amount, transaction);
    }
}
