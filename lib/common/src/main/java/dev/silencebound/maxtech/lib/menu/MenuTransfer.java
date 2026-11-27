package dev.silencebound.maxtech.lib.menu;

import dev.silencebound.maxtech.lib.inventory.LibItemSlots;
import dev.silencebound.maxtech.lib.inventory.RoleView;
import java.util.List;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.phys.Vec3;
// Shift-clicks and bucket clicks are NeoForge transfers between the player and Lib's inventories (ADR-0002).
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.capabilities.Capabilities;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.CombinedResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.RangedResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.access.ItemAccess;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.fluid.FluidResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.fluid.FluidUtil;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.PlayerInventoryWrapper;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.resource.ResourceStack;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * The moves of a toolkit menu, each one root transaction against the real handlers (the player's inventory, Lib's slots
 * and tanks) that commits only when both sides moved the same amount, so a full or partly full side never dupes or
 * loses anything. Runs on the thread of the menu's side (the client predicts shift-clicks over its mirror), never
 * inside another transaction.
 */
final class MenuTransfer {
    private MenuTransfer() {
    }

    /**
     * Moves what is in the player's inventory slot {@code inventorySlot} into {@code groups}, filling each group's
     * matching stacks and then its empty slots before the next group; returns how many items moved.
     */
    static int toGroups(Player player, int inventorySlot, List<SlotGroup> groups) {
        ResourceHandler<ItemResource> source = PlayerInventoryWrapper.of(player).getSlot(inventorySlot);
        ItemResource resource = source.getResource(0);
        int amount = source.getAmountAsInt(0);
        if (resource.isEmpty() || amount == 0 || groups.isEmpty()) {
            return 0;
        }
        try (Transaction transaction = Transaction.openRoot()) {
            int moved = 0;
            for (int i = 0; i < groups.size() && moved < amount; i++) {
                moved += ResourceHandlerUtil.insertStacking(groups.get(i).playerView(), resource, amount - moved,
                        transaction);
            }
            if (moved == 0 || source.extract(0, resource, moved, transaction) != moved) {
                return 0;
            }
            transaction.commit();
            return moved;
        }
    }

    /**
     * A shift-click on the player's inventory slot {@code inventorySlot}: into {@code groups} ({@link #toGroups}), or,
     * when no slot of them takes the item at all (full ones aside), to the other section ({@link #betweenSections});
     * returns how many items moved.
     */
    static int fromPlayer(Player player, int inventorySlot, List<SlotGroup> groups) {
        int moved = toGroups(player, inventorySlot, groups);
        if (moved > 0) {
            return moved;
        }
        ItemResource resource = PlayerInventoryWrapper.of(player).getSlot(inventorySlot).getResource(0);
        if (resource.isEmpty() || anyAccepts(groups, resource)) {
            return 0;
        }
        return betweenSections(player, inventorySlot);
    }

    /** Whether any slot of {@code groups} would take {@code resource} from a player, room aside. */
    static boolean anyAccepts(List<SlotGroup> groups, ItemResource resource) {
        for (int i = 0; i < groups.size(); i++) {
            ResourceHandler<ItemResource> view = groups.get(i).playerView();
            for (int index = 0; index < view.size(); index++) {
                if (view.isValid(index, resource)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Moves what is in the player's inventory slot {@code inventorySlot} to the other section, the hotbar for a main
     * inventory slot and the main inventory for a hotbar slot, matching stacks first, like vanilla machines do with an
     * item they do not take; returns how many items moved.
     */
    static int betweenSections(Player player, int inventorySlot) {
        PlayerInventoryWrapper inventory = PlayerInventoryWrapper.of(player);
        ResourceHandler<ItemResource> source = inventory.getSlot(inventorySlot);
        ItemResource resource = source.getResource(0);
        int amount = source.getAmountAsInt(0);
        if (resource.isEmpty() || amount == 0) {
            return 0;
        }
        int hotbar = Inventory.getSelectionSize();
        ResourceHandler<ItemResource> target = inventorySlot < hotbar
                ? RangedResourceHandler.of(inventory, hotbar, Inventory.INVENTORY_SIZE)
                : RangedResourceHandler.of(inventory, 0, hotbar);
        try (Transaction transaction = Transaction.openRoot()) {
            int moved = ResourceHandlerUtil.insertStacking(target, resource, amount, transaction);
            if (moved == 0 || source.extract(0, resource, moved, transaction) != moved) {
                return 0;
            }
            transaction.commit();
            return moved;
        }
    }

    /**
     * Moves what slot {@code index} of {@code slots} holds into the player's main inventory and then the hotbar,
     * matching stacks first, like vanilla; returns how many items moved. Display slots give nothing.
     */
    static int toPlayer(Player player, LibItemSlots slots, int index) {
        RoleView view = slots.player();
        ItemResource resource = view.getResource(index);
        int amount = view.getAmountAsInt(index);
        if (!view.canExtract(index) || resource.isEmpty() || amount == 0) {
            return 0;
        }
        PlayerInventoryWrapper inventory = PlayerInventoryWrapper.of(player);
        int hotbar = Inventory.getSelectionSize();
        ResourceHandler<ItemResource> target = new CombinedResourceHandler<>(
                RangedResourceHandler.of(inventory, hotbar, Inventory.INVENTORY_SIZE),
                RangedResourceHandler.of(inventory, 0, hotbar));
        try (Transaction transaction = Transaction.openRoot()) {
            int moved = ResourceHandlerUtil.insertStacking(target, resource, amount, transaction);
            if (moved == 0 || view.extract(index, resource, moved, transaction) != moved) {
                return 0;
            }
            transaction.commit();
            return moved;
        }
    }

    /**
     * A click with the carried item on a tank gauge: a carried fluid container (one of a stack) that already holds the
     * tank's fluid empties into the tank, or else fills from it; any other container fills from the tank, or else
     * empties into it. Each try moves as much as both sides accept, in one transaction; the changed container goes back
     * on the cursor or, for a stack, into the inventory. Returns whether anything moved; plays the fluid's sound on the
     * server.
     */
    static boolean tankClick(Player player, AbstractContainerMenu menu, ResourceHandler<FluidResource> tank) {
        ResourceHandler<FluidResource> container = ItemAccess.forPlayerCursor(player, menu).oneByOne()
                .getCapability(Capabilities.Fluid.ITEM);
        if (container == null) {
            return false;
        }
        ResourceStack<FluidResource> moved;
        boolean pickup;
        boolean emptyFirst = holds(container, tank.getResource(0));
        try (Transaction transaction = Transaction.openRoot()) {
            moved = emptyFirst ? null : fill(tank, container, transaction);
            pickup = moved != null;
            if (moved == null) {
                moved = ResourceHandlerUtil.moveFirst(container, tank, resource -> true, Integer.MAX_VALUE,
                        transaction);
            }
            if (moved == null && emptyFirst) {
                moved = fill(tank, container, transaction);
                pickup = moved != null;
            }
            if (moved == null) {
                return false;
            }
            transaction.commit();
        }
        if (!player.level().isClientSide()) {
            FluidUtil.triggerSoundAndGameEvent(moved.resource(), player.level(),
                    new Vec3(player.getX(), player.getY() + 0.5, player.getZ()), player, pickup);
        }
        return true;
    }

    private static @Nullable ResourceStack<FluidResource> fill(ResourceHandler<FluidResource> tank,
            ResourceHandler<FluidResource> container, Transaction transaction) {
        return ResourceHandlerUtil.moveFirst(tank, container, resource -> true, Integer.MAX_VALUE, transaction);
    }

    /** Whether {@code container} holds some of {@code fluid}; false for an empty fluid. */
    private static boolean holds(ResourceHandler<FluidResource> container, FluidResource fluid) {
        if (fluid.isEmpty()) {
            return false;
        }
        for (int index = 0; index < container.size(); index++) {
            if (container.getAmountAsLong(index) > 0 && fluid.equals(container.getResource(index))) {
                return true;
            }
        }
        return false;
    }
}
