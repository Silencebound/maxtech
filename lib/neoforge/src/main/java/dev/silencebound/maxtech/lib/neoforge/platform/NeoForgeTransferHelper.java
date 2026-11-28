package dev.silencebound.maxtech.lib.neoforge.platform;

import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.neoforge.api.transfer.KindBridges;
import dev.silencebound.maxtech.lib.neoforge.transfer.BridgeLookup;
import dev.silencebound.maxtech.lib.platform.BlockLookup;
import dev.silencebound.maxtech.lib.platform.KindLookup;
import java.util.Optional;
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

    @Override
    public <K> Optional<KindLookup<K>> kind(ResourceKind<K> kind) {
        return KindBridges.get(kind).map(BridgeLookup::new);
    }
}
