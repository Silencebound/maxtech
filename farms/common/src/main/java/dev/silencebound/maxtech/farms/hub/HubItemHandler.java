package dev.silencebound.maxtech.farms.hub;

import java.util.Objects;
// PLATFORM-LEAK: NeoForge's item transfer type; other mods' hoppers and pipes reach the hub through it.
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * The lab hub's items as other mods see them, on every side: Lib's handler of the hub's slots without the sampler
 * charging slot, so index 0 is the fuel slot (takes furnace fuel, gives nothing back) and indices 1 to
 * {@link HubStorage#OUTPUT_SLOTS} the output buffer (only gives). Every call goes to Lib's handler in the caller's
 * transaction. Server thread only.
 */
public final class HubItemHandler implements ResourceHandler<ItemResource> {
    private final ResourceHandler<ItemResource> slots;

    HubItemHandler(ResourceHandler<ItemResource> slots) {
        this.slots = slots;
    }

    @Override
    public int size() {
        return HubStorage.SAMPLER;
    }

    @Override
    public ItemResource getResource(int index) {
        return slots.getResource(Objects.checkIndex(index, size()));
    }

    @Override
    public long getAmountAsLong(int index) {
        return slots.getAmountAsLong(Objects.checkIndex(index, size()));
    }

    @Override
    public long getCapacityAsLong(int index, ItemResource resource) {
        return slots.getCapacityAsLong(Objects.checkIndex(index, size()), resource);
    }

    @Override
    public boolean isValid(int index, ItemResource resource) {
        return slots.isValid(Objects.checkIndex(index, size()), resource);
    }

    @Override
    public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
        return slots.insert(Objects.checkIndex(index, size()), resource, amount, transaction);
    }

    @Override
    public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
        return slots.extract(Objects.checkIndex(index, size()), resource, amount, transaction);
    }
}
