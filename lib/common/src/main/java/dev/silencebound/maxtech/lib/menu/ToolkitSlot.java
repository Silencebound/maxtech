package dev.silencebound.maxtech.lib.menu;

import dev.silencebound.maxtech.lib.api.inventory.SlotRole;
import dev.silencebound.maxtech.lib.inventory.LibItemSlots;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
// PLATFORM-LEAK: a menu slot over Lib's NeoForge slots (ADR-0002).
import net.neoforged.neoforge.transfer.item.ResourceHandlerSlot;

/**
 * A menu slot over one slot of a {@link LibItemSlots}, following its role: inputs take what their filter accepts and
 * give back, outputs only give, display slots do neither. Reads and writes go to the live slots (vanilla sync fills a
 * client menu's empty mirror the same way). Clicks go through vanilla's slot logic on copies of the stack, which write
 * the result back whole; shift-clicks go through {@link MenuTransfer} instead.
 */
public final class ToolkitSlot extends ResourceHandlerSlot {
    private final LibItemSlots slots;
    private final int slotIndex;

    ToolkitSlot(LibItemSlots slots, int index, int x, int y) {
        super(slots, slots::set, index, x, y);
        this.slots = slots;
        this.slotIndex = index;
    }

    /** The inventory this slot shows. */
    public LibItemSlots slots() {
        return slots;
    }

    /** The slot's index in {@link #slots()}. */
    public int index() {
        return slotIndex;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return slots.role(slotIndex) == SlotRole.INPUT && slots.accepts(slotIndex, stack);
    }

    @Override
    public boolean mayPickup(Player player) {
        return slots.role(slotIndex) != SlotRole.DISPLAY && !slots.stack(slotIndex).isEmpty();
    }
}
