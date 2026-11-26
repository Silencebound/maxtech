package dev.silencebound.maxtech.storage.machine;

import dev.silencebound.maxtech.lib.resource.MachineItemStorage;
// PLATFORM-LEAK: machines expose NeoForge's item transfer handler on every side (ADR-0002).
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.TransferPreconditions;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * What hoppers and pipes see of a Storage machine on any side (PRD 7.2: every machine is hopper- and pipe-friendly):
 * the catalyst slots, then the inputs, then the outputs. Insertion goes only into catalysts and inputs, each by its own
 * rule (masks only into the mask slot), extraction only from outputs. Indices that refuse insertion report no capacity,
 * so a hopper sees the outputs as full. Holds no state.
 */
final class MachineSides implements ResourceHandler<ItemResource> {
    private final MachineItemStorage catalysts;
    private final MachineItemStorage inputs;
    private final MachineItemStorage outputs;

    MachineSides(MachineItemStorage catalysts, MachineItemStorage inputs, MachineItemStorage outputs) {
        this.catalysts = catalysts;
        this.inputs = inputs;
        this.outputs = outputs;
    }

    @Override
    public int size() {
        return catalysts.size() + inputs.size() + outputs.size();
    }

    private MachineItemStorage group(int index) {
        if (index < catalysts.size()) {
            return catalysts;
        }
        return index < catalysts.size() + inputs.size() ? inputs : outputs;
    }

    private int local(int index) {
        if (index < catalysts.size()) {
            return index;
        }
        int afterCatalysts = index - catalysts.size();
        return afterCatalysts < inputs.size() ? afterCatalysts : afterCatalysts - inputs.size();
    }

    @Override
    public ItemResource getResource(int index) {
        return group(index).getResource(local(index));
    }

    @Override
    public long getAmountAsLong(int index) {
        return group(index).getAmountAsLong(local(index));
    }

    @Override
    public long getCapacityAsLong(int index, ItemResource resource) {
        MachineItemStorage group = group(index);
        return group == outputs ? 0 : group.getCapacityAsLong(local(index), resource);
    }

    @Override
    public boolean isValid(int index, ItemResource resource) {
        MachineItemStorage group = group(index);
        return group != outputs && group.isValid(local(index), resource);
    }

    @Override
    public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        MachineItemStorage group = group(index);
        return group == outputs ? 0 : group.insert(local(index), resource, amount, transaction);
    }

    @Override
    public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        MachineItemStorage group = group(index);
        return group == outputs ? group.extract(local(index), resource, amount, transaction) : 0;
    }
}
