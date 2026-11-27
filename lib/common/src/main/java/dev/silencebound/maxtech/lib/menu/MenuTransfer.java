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
     * A click with the carried item on a tank gauge: fills the carried fluid container (one of a stack) from
     * {@code tank}, or else empties it into the tank, as much as both accept, in one transaction; the changed container
     * goes back on the cursor or, for a stack, into the inventory. Returns whether anything moved; plays the fluid's
     * sound on the server.
     */
    static boolean tankClick(Player player, AbstractContainerMenu menu, ResourceHandler<FluidResource> tank) {
        ResourceHandler<FluidResource> container = ItemAccess.forPlayerCursor(player, menu).oneByOne()
                .getCapability(Capabilities.Fluid.ITEM);
        if (container == null) {
            return false;
        }
        ResourceStack<FluidResource> moved;
        boolean pickup;
        try (Transaction transaction = Transaction.openRoot()) {
            moved = ResourceHandlerUtil.moveFirst(tank, container, resource -> true, Integer.MAX_VALUE, transaction);
            pickup = moved != null;
            if (moved == null) {
                moved = ResourceHandlerUtil.moveFirst(container, tank, resource -> true, Integer.MAX_VALUE,
                        transaction);
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
}
