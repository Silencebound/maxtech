/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
/*
 * Modified for MaxTech Lib's 1.21.1 build (port/NOTICE-transfer.md): vendored from NeoForge 26.1.2's
 * net.neoforged.neoforge.fluids.capability.FluidResourceHandlerAdapter, package relocated to
 * dev.silencebound.maxtech.lib.shim.transfer.port, made public, adapted to NeoForge 21.1's IFluidHandler.
 */

package dev.silencebound.maxtech.lib.shim.transfer.port;

import dev.silencebound.maxtech.lib.shim.transfer.ResourceHandler;
import dev.silencebound.maxtech.lib.shim.transfer.ResourceHandlerUtil;
import dev.silencebound.maxtech.lib.shim.transfer.fluid.FluidResource;
import dev.silencebound.maxtech.lib.shim.transfer.fluid.FluidUtil;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.Transaction;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * Presents a {@code ResourceHandler<FluidResource>} as a legacy {@link IFluidHandler}. Every call opens its own root
 * transaction, so this wrapper cannot be used from inside a transaction.
 */
public class FluidResourceHandlerAdapter implements IFluidHandler {
    private final ResourceHandler<FluidResource> handler;

    public FluidResourceHandlerAdapter(ResourceHandler<FluidResource> handler) {
        this.handler = handler;
    }

    /** {@return the wrapped handler} */
    public ResourceHandler<FluidResource> handler() {
        return handler;
    }

    @Override
    public int getTanks() {
        return handler.size();
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        return FluidUtil.getStack(handler, tank);
    }

    @Override
    public int getTankCapacity(int tank) {
        return handler.getCapacityAsInt(tank, FluidResource.EMPTY);
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        return handler.isValid(tank, FluidResource.of(stack));
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (resource.isEmpty()) {
            return 0;
        }
        try (var tx = Transaction.openRoot()) {
            int inserted = handler.insert(FluidResource.of(resource), resource.getAmount(), tx);
            if (action.execute()) {
                tx.commit();
            }
            return inserted;
        }
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        if (resource.isEmpty()) {
            return FluidStack.EMPTY;
        }
        try (var tx = Transaction.openRoot()) {
            int extracted = handler.extract(FluidResource.of(resource), resource.getAmount(), tx);
            if (action.execute()) {
                tx.commit();
            }
            return extracted == 0 ? FluidStack.EMPTY : resource.copyWithAmount(extracted);
        }
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        if (maxDrain <= 0) {
            return FluidStack.EMPTY;
        }
        try (var tx = Transaction.openRoot()) {
            var extracted = ResourceHandlerUtil.extractFirst(handler, fr -> true, maxDrain, tx);
            if (action.execute()) {
                tx.commit();
            }
            return extracted == null ? FluidStack.EMPTY : extracted.resource().toStack(extracted.amount());
        }
    }
}
