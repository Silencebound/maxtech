package dev.silencebound.maxtech.storage.world;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.research.ResearchNode;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
// PLATFORM-LEAK: player clicks are network operations in NeoForge transactions, as in the terminal (PRD 7.2).
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * A player's view of a network chest: vanilla's chest GUI (the client builds a plain {@link ChestMenu} of the same
 * type, so no screen of our own), with the chest's slots handled here on the server. A click on a chest slot takes from
 * the network (left a stack, right half of it, shift into the inventory), a click with a carried stack stores it (right
 * one item), a shift-clicked inventory stack is stored; each is one player operation (energy, like a terminal). Nothing
 * else touches the chest's slots: drags, double-click collection, number keys and throws skip them. The client predicts
 * nothing that matters, since vanilla's slot and cursor sync bring the server's result back.
 */
public final class NetworkChestMenu extends ChestMenu {
    public static final String NEEDS_RESEARCH_KEY = "message." + MaxtechStorage.MOD_ID + ".world.needs_research";
    public static final String NO_ACCESS_KEY = "message." + MaxtechStorage.MOD_ID + ".world.no_access";

    private final NetworkChestView view;
    private final int chestSlots;

    private NetworkChestMenu(MenuType<?> type, int containerId, Inventory inventory, NetworkChestView view, int rows) {
        super(type, containerId, inventory, new ViewContainer(view, rows * 9), rows);
        this.view = view;
        this.chestSlots = rows * 9;
    }

    /**
     * Opens {@code view} for {@code player}: refused with a message when the device lacks research or the network's
     * owner or the chest's does not let the player in, or when the chest is in no network.
     */
    public static void open(ServerPlayer player, NetworkChestView view) {
        if (!view.researched()) {
            message(player, Component.translatable(NEEDS_RESEARCH_KEY,
                    Component.translatable(ResearchNode.unlockKey(view.gate().unlock()))));
            return;
        }
        if (usable(view, player) == null) {
            message(player, Component.translatable(NO_ACCESS_KEY));
            return;
        }
        player.openMenu(new SimpleMenuProvider((id, inventory, opener) -> forView(id, inventory, view),
                WorldContent.NETWORK_CHEST.get().getName()));
    }

    /** The server's menu over {@code view}, with as many rows as the chest's mode shows. */
    public static NetworkChestMenu forView(int containerId, Inventory inventory, NetworkChestView view) {
        int rows = view.size() / 9;
        MenuType<?> type = rows == 3 ? MenuType.GENERIC_9x3 : MenuType.GENERIC_9x6;
        return new NetworkChestMenu(type, containerId, inventory, view, rows);
    }

    private static void message(ServerPlayer player, Component message) {
        // A GameTest player has no connection to send the message to.
        if (player.connection != null) {
            player.sendOverlayMessage(message);
        }
    }

    /**
     * The chest's network while {@code player} may use it, else null: both the chest ({@link NetworkAccess}: the
     * network's security rules, else the chest's owner) and the network must give the player at least
     * {@link AccessLevel#VIEW}. Taking needs full access and storing deposit, as at a terminal.
     */
    private static @Nullable NetworkState usable(NetworkChestView view, Player player) {
        NetworkState network = view.network();
        return network != null && NetworkAccess.level(player, view.holder()).atLeast(AccessLevel.VIEW)
                && network.canAccess(player) ? network : null;
    }

    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput containerInput, Player player) {
        if (slotIndex < 0 || slotIndex >= chestSlots) {
            super.clicked(slotIndex, buttonNum, containerInput, player);
            return;
        }
        NetworkState network = usable(view, player);
        if (network == null) {
            return;
        }
        // A security module may let a player see and deposit without taking, as at a terminal.
        AccessLevel level = network.accessLevel(player);
        if (containerInput == ContainerInput.PICKUP) {
            if (getCarried().isEmpty()) {
                if (level == AccessLevel.FULL) {
                    take(network, player, slotIndex, buttonNum == 1, false);
                }
            } else if (level.atLeast(AccessLevel.DEPOSIT)) {
                store(network, player, getCarried(), buttonNum == 1 ? 1 : getCarried().getCount());
            }
        } else if (containerInput == ContainerInput.QUICK_MOVE && level == AccessLevel.FULL) {
            take(network, player, slotIndex, false, true);
        }
    }

    /** Takes what slot {@code slot} shows (half of it for {@code half}) onto the cursor or into the inventory. */
    private void take(NetworkState network, Player player, int slot, boolean half, boolean toInventory) {
        ItemKey key = view.key(slot);
        if (key == null) {
            return;
        }
        ItemStack shown = view.stack(slot);
        long wanted = half ? (shown.getCount() + 1) / 2 : shown.getCount();
        if (toInventory) {
            wanted = Math.min(wanted, room(player, shown));
        }
        if (wanted <= 0) {
            return;
        }
        long taken;
        try (Transaction transaction = Transaction.openRoot()) {
            taken = network.extract(key, wanted, transaction, player);
            transaction.commit();
        }
        if (taken <= 0) {
            return;
        }
        ItemStack stack = key.toStack((int) taken);
        if (!toInventory) {
            setCarried(stack);
        } else if (!player.getInventory().add(stack) && !stack.isEmpty()) {
            // Room was counted first, so everything fits; a stack the inventory still refuses is dropped, never lost.
            player.drop(stack, false);
        }
    }

    /** Stores {@code count} of {@code stack} if the chest takes it; the stack shrinks by what went in. */
    private void store(NetworkState network, Player player, ItemStack stack, int count) {
        ItemKey key = ItemKey.of(stack);
        if (!view.accepts(key) || count <= 0) {
            return;
        }
        long stored;
        try (Transaction transaction = Transaction.openRoot()) {
            stored = network.insert(key, count, transaction, player);
            transaction.commit();
        }
        stack.shrink((int) stored);
    }

    /** How many of {@code sample} fit in the main inventory and hotbar. */
    private static long room(Player player, ItemStack sample) {
        Inventory inventory = player.getInventory();
        long room = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack held = inventory.getItem(i);
            if (held.isEmpty()) {
                room += sample.getMaxStackSize();
            } else if (ItemStack.isSameItemSameComponents(held, sample)) {
                room += Math.max(0, held.getMaxStackSize() - held.getCount());
            }
        }
        return room;
    }

    /** A shift-clicked inventory stack is stored as far as the chest takes it. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        NetworkState network = usable(view, player);
        if (index >= chestSlots && network != null && network.accessLevel(player).atLeast(AccessLevel.DEPOSIT)) {
            Slot slot = slots.get(index);
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
                store(network, player, stack, stack.getCount());
                slot.setChanged();
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return slot.index >= chestSlots && super.canDragTo(slot);
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack carried, Slot slot) {
        return slot.index >= chestSlots && super.canTakeItemForPickAll(carried, slot);
    }

    @Override
    public boolean stillValid(Player player) {
        return view.stillValid(player) && usable(view, player) != null;
    }

    /**
     * The chest's slots as a container: what the view shows, read-only. Clicks never reach it, since the menu handles
     * every click on these slots itself.
     */
    private record ViewContainer(NetworkChestView view, int size) implements Container {
        @Override
        public int getContainerSize() {
            return size;
        }

        @Override
        public boolean isEmpty() {
            return view.key(0) == null;
        }

        @Override
        public ItemStack getItem(int slot) {
            return view.stack(slot);
        }

        @Override
        public ItemStack removeItem(int slot, int count) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
        }

        @Override
        public void setChanged() {
        }

        @Override
        public boolean stillValid(Player player) {
            return view.stillValid(player);
        }

        @Override
        public void clearContent() {
        }
    }
}
