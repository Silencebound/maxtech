/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
/*
 * Modified for MaxTech Lib's 1.21.1 build (port/NOTICE-transfer.md): vendored from NeoForge 26.1.2's
 * net.neoforged.neoforge.fluids.capability.FluidResourceHandlerItemAdapter, package relocated to
 * dev.silencebound.maxtech.lib.shim.transfer.port, adapted to NeoForge 21.1's IFluidHandlerItem.
 */

package dev.silencebound.maxtech.lib.shim.transfer.port;

import dev.silencebound.maxtech.lib.shim.transfer.ResourceHandler;
import dev.silencebound.maxtech.lib.shim.transfer.access.ItemAccess;
import dev.silencebound.maxtech.lib.shim.transfer.fluid.FluidResource;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;

/**
 * Presents an item's {@code ResourceHandler<FluidResource>} as a legacy {@link IFluidHandlerItem}; the container is
 * the item access's current contents.
 */
public class FluidResourceHandlerItemAdapter extends FluidResourceHandlerAdapter implements IFluidHandlerItem {
    private final ItemAccess itemAccess;

    public FluidResourceHandlerItemAdapter(ResourceHandler<FluidResource> handler, ItemAccess itemAccess) {
        super(handler);
        this.itemAccess = itemAccess;
    }

    @Override
    public ItemStack getContainer() {
        return itemAccess.getResource().toStack(itemAccess.getAmount());
    }
}
