package dev.silencebound.maxtech.lib.neoforge.platform;

import dev.silencebound.maxtech.lib.platform.BlockLookup;
import dev.silencebound.maxtech.lib.platform.TransferHelper;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;

final class NeoForgeTransferHelper implements TransferHelper {
    private final BlockLookup<ResourceHandler<ItemResource>> items = new NeoForgeBlockLookup<>(Capabilities.Item.BLOCK);
    private final BlockLookup<ResourceHandler<FluidResource>> fluids = new NeoForgeBlockLookup<>(
            Capabilities.Fluid.BLOCK);

    @Override
    public BlockLookup<ResourceHandler<ItemResource>> items() {
        return items;
    }

    @Override
    public BlockLookup<ResourceHandler<FluidResource>> fluids() {
        return fluids;
    }
}
