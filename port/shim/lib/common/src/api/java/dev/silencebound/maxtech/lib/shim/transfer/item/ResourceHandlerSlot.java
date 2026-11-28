/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
/*
 * Modified for MaxTech Lib's 1.21.1 build (port/NOTICE-transfer.md): vendored from NeoForge 26.1.2, package
 * relocated to dev.silencebound.maxtech.lib.shim.transfer, adapted to Minecraft 1.21.1.
 */

package dev.silencebound.maxtech.lib.shim.transfer.item;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import dev.silencebound.maxtech.lib.shim.transfer.IndexModifier;
import dev.silencebound.maxtech.lib.shim.transfer.ResourceHandler;
import dev.silencebound.maxtech.lib.shim.transfer.StacksResourceHandler;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.Transaction;
import dev.silencebound.maxtech.lib.shim.transfer.port.StackCopySlot;

/**
 * Base implementation for a {@link Slot} backed by a {@code ResourceHandler<ItemResource>}.
 * Requires the handler to expose a {@linkplain IndexModifier direct mutation function},
 * such as {@link StacksResourceHandler#set}.
 */
public class ResourceHandlerSlot extends StackCopySlot {
    private final ResourceHandler<ItemResource> handler;
    private final IndexModifier<ItemResource> slotModifier;

    public ResourceHandlerSlot(ResourceHandler<ItemResource> handler, IndexModifier<ItemResource> slotModifier, int handlerSlot, int xPosition, int yPosition) {
        super(handlerSlot, xPosition, yPosition);
        this.handler = handler;
        this.slotModifier = slotModifier;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        if (stack.isEmpty())
            return false;
        // Use isValid as a reasonable estimate.
        // We can't try to insert as we don't want to check the current contents to allow swapping.
        // This method is left for mods to override if this is not sufficient.
        return handler.isValid(this.getSlotIndex(), ItemResource.of(stack));
    }

    @Override
    protected ItemStack getStackCopy() {
        return handler.getResource(this.getSlotIndex()).toStack(handler.getAmountAsInt(this.getSlotIndex()));
    }

    @Override
    protected void setStackCopy(ItemStack stack) {
        slotModifier.set(this.getSlotIndex(), ItemResource.of(stack), stack.getCount());
    }

    @Override
    public void onQuickCraft(ItemStack oldStackIn, ItemStack newStackIn) {}

    @Override
    public int getMaxStackSize() {
        return handler.getCapacityAsInt(this.getSlotIndex(), ItemResource.EMPTY);
    }

    @Override
    public int getMaxStackSize(ItemStack stack) {
        return handler.getCapacityAsInt(this.getSlotIndex(), ItemResource.of(stack));
    }

    @Override
    public boolean mayPickup(Player player) {
        var resource = handler.getResource(this.getSlotIndex());
        if (resource.isEmpty()) {
            return false;
        }
        try (var tx = Transaction.openRoot()) {
            // Simulated extraction
            return handler.extract(this.getSlotIndex(), resource, 1, tx) == 1;
        }
    }

    public ResourceHandler<ItemResource> getResourceHandler() {
        return handler;
    }

    @Override
    public boolean isSameInventory(Slot other) {
        return other instanceof ResourceHandlerSlot rhs && rhs.handler == this.handler;
    }
}
