package dev.silencebound.maxtech.lib.neoforge;

import dev.silencebound.maxtech.lib.api.inventory.FluidTank;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import dev.silencebound.maxtech.lib.inventory.LibFluidTank;
import dev.silencebound.maxtech.lib.inventory.LibItemSlots;
import dev.silencebound.maxtech.lib.neoforge.api.inventory.Handlers;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;

/** {@link Handlers} over the handlers Lib's inventories already have in common. */
final class LibHandlerBridge implements Handlers.Bridge {
    @Override
    public ResourceHandler<ItemResource> items(ItemSlots slots) {
        if (!(slots instanceof LibItemSlots lib)) {
            throw new IllegalArgumentException("Not Lib's slots: " + slots.getClass().getName());
        }
        return lib.automation();
    }

    @Override
    public ResourceHandler<FluidResource> fluids(FluidTank tank) {
        if (!(tank instanceof LibFluidTank lib)) {
            throw new IllegalArgumentException("Not Lib's tank: " + tank.getClass().getName());
        }
        return lib.handler();
    }
}
