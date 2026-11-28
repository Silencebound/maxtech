/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
/*
 * Modified for MaxTech Lib's 1.21.1 build (port/NOTICE-transfer.md): vendored from NeoForge 26.1.2's
 * net.neoforged.neoforge.items.ItemResourceHandlerAdapter, package relocated to
 * dev.silencebound.maxtech.lib.shim.transfer.port, made public, adapted to NeoForge 21.1's IItemHandler.
 */

package dev.silencebound.maxtech.lib.shim.transfer.port;

import dev.silencebound.maxtech.lib.shim.transfer.ResourceHandler;
import dev.silencebound.maxtech.lib.shim.transfer.item.ItemResource;
import dev.silencebound.maxtech.lib.shim.transfer.item.ItemUtil;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.Transaction;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Presents a {@code ResourceHandler<ItemResource>} as a legacy {@link IItemHandler}. Every call opens its own root
 * transaction, so this wrapper cannot be used from inside a transaction.
 */
public class ItemResourceHandlerAdapter implements IItemHandler {
    private final ResourceHandler<ItemResource> handler;

    public ItemResourceHandlerAdapter(ResourceHandler<ItemResource> handler) {
        this.handler = handler;
    }

    /** {@return the wrapped handler} */
    public ResourceHandler<ItemResource> handler() {
        return handler;
    }

    @Override
    public int getSlots() {
        return handler.size();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return ItemUtil.getStack(handler, slot);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        return ItemUtil.insertItemReturnRemaining(handler, slot, stack, simulate, null);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (amount <= 0) {
            return ItemStack.EMPTY;
        }
        var resource = handler.getResource(slot);
        if (resource.isEmpty()) {
            return ItemStack.EMPTY;
        }
        // We have to limit to the max stack size, per the contract of extractItem
        amount = Math.min(amount, resource.getMaxStackSize());
        try (var tx = Transaction.openRoot()) {
            int extracted = handler.extract(slot, resource, amount, tx);
            if (!simulate) {
                tx.commit();
            }
            return resource.toStack(extracted);
        }
    }

    @Override
    public int getSlotLimit(int slot) {
        return handler.getCapacityAsInt(slot, ItemResource.EMPTY);
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return handler.isValid(slot, ItemResource.of(stack));
    }
}
