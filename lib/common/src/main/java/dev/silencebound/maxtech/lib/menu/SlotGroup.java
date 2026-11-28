package dev.silencebound.maxtech.lib.menu;

import dev.silencebound.maxtech.lib.inventory.LibItemSlots;
// PLATFORM-LEAK: a group's slots as one NeoForge handler, for shift-click transfers (ADR-0002).
import net.neoforged.neoforge.transfer.RangedResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * A run of a toolkit menu's slots over one {@link LibItemSlots}: slots {@code from} to {@code to} (exclusive) of the
 * inventory, shown as menu slots from {@code firstMenuSlot} on. Immutable; one menu's thread.
 */
public final class SlotGroup {
    private final LibItemSlots slots;
    private final int from;
    private final int to;
    private final int firstMenuSlot;

    SlotGroup(LibItemSlots slots, int from, int to, int firstMenuSlot) {
        this.slots = slots;
        this.from = from;
        this.to = to;
        this.firstMenuSlot = firstMenuSlot;
    }

    /** The inventory the group shows. */
    public LibItemSlots slots() {
        return slots;
    }

    /** The menu index of the group's first slot. */
    public int firstMenuSlot() {
        return firstMenuSlot;
    }

    /** The number of slots in the group. */
    public int size() {
        return to - from;
    }

    /** The group's slots as a player may use them (roles applied), for a transfer. */
    ResourceHandler<ItemResource> playerView() {
        return RangedResourceHandler.of(slots.player(), from, to);
    }
}
