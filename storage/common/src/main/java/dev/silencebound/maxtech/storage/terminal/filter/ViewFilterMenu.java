package dev.silencebound.maxtech.storage.terminal.filter;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cell.CellFilter;
import dev.silencebound.maxtech.storage.workbench.CellWorkbenchMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The view filter's GUI: its nine entries as ghost slots over the player's inventory, with the exact/fuzzy and
 * show/hide buttons. The filter lives on the view filter in the main hand, so vanilla's slot sync shows every change on
 * the client; nothing is predicted there. Entry slots work like the cell workbench's filter: a carried item names
 * itself (a filled bucket its fluid), an empty left click or a shift-click clears the entry, an empty right click
 * cycles it through its tags. A recipe viewer's ingredient dropped on an entry arrives as a {@link Ghost}. The held
 * filter cannot be moved while the GUI is open; no item moves in this menu.
 */
public final class ViewFilterMenu extends AbstractContainerMenu {
    public static final int WIDTH = 176;
    public static final int HEIGHT = 172;
    public static final int ENTRY_X = 44;
    public static final int ENTRY_Y = 22;
    public static final int INVENTORY_X = 8;
    public static final int INVENTORY_Y = 90;
    /** The first inventory slot; the entries come first. */
    public static final int FIRST_INVENTORY = CellFilter.SIZE;

    /** Button ids for {@link #clickMenuButton}. */
    public static final int FUZZY = 0;
    /** Switches between showing and hiding what the list matches. */
    public static final int DENY = 1;
    /** Removes the entries of a removed mod ({@link CellFilter#kept()}). */
    public static final int CLEAR_UNKNOWN = 2;

    private final Inventory inventory;

    private ViewFilterMenu(int containerId, Inventory inventory) {
        super(ViewFilterContent.MENU.get(), containerId);
        this.inventory = inventory;
        for (int i = 0; i < CellFilter.SIZE; i++) {
            addSlot(new EntrySlot(i, ENTRY_X + (i % 3) * 18, ENTRY_Y + (i / 3) * 18));
        }
        addStandardInventorySlots(inventory, INVENTORY_X, INVENTORY_Y);
    }

    /** The client's menu. */
    public static ViewFilterMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        return new ViewFilterMenu(containerId, inventory);
    }

    /** The server's menu; the view filter is in {@code inventory}'s player's main hand. */
    public static ViewFilterMenu server(int containerId, Inventory inventory) {
        return new ViewFilterMenu(containerId, inventory);
    }

    /** Opens the GUI of the view filter in {@code player}'s main hand. */
    public static void open(ServerPlayer player, Component title) {
        Platform.get().menus().open(player,
                new SimpleMenuProvider((id, inventory, opener) -> server(id, inventory), title), buffer -> {
                });
    }

    /** The view filter being set up; empty once it left the main hand. */
    public ItemStack held() {
        ItemStack stack = inventory.player.getItemInHand(InteractionHand.MAIN_HAND);
        return ViewFilterItem.is(stack) ? stack : ItemStack.EMPTY;
    }

    /** The held filter's list, as the slot sync last brought it on the client. */
    public CellFilter filter() {
        return ViewFilterItem.filter(held());
    }

    @Override
    public boolean clickMenuButton(Player clicker, int id) {
        CellFilter filter = filter();
        CellFilter changed = switch (id) {
            case FUZZY -> filter.withFuzzy(!filter.fuzzy());
            case DENY -> filter.withDeny(!filter.deny());
            case CLEAR_UNKNOWN -> filter.withoutKept();
            default -> null;
        };
        return changed != null && set(changed);
    }

    /** Sets entry {@code index} to {@code key}, as a recipe viewer's drop does; false when there is no such entry. */
    public boolean setEntry(int index, StackKey key) {
        if (index < 0 || index >= CellFilter.SIZE) {
            return false;
        }
        return set(filter().with(index, CellFilter.Entry.of(key)));
    }

    private boolean set(CellFilter changed) {
        ItemStack stack = held();
        if (stack.isEmpty()) {
            return false;
        }
        if (changed.isBlank()) {
            stack.remove(ViewFilterContent.FILTER.get());
        } else {
            stack.set(ViewFilterContent.FILTER.get(), changed);
        }
        return true;
    }

    /**
     * Entry slots change the filter on the server only; the held filter's own slot refuses every click (and the number
     * keys that would swap it away), so it stays in the hand while the GUI is open.
     */
    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player clicker) {
        int selected = inventory.getSelectedSlot();
        if (input == ContainerInput.SWAP && buttonNum == selected) {
            return;
        }
        if (slotIndex >= FIRST_INVENTORY && slotIndex < slots.size()
                && slots.get(slotIndex).getContainerSlot() == selected) {
            return;
        }
        if (slotIndex < 0 || slotIndex >= FIRST_INVENTORY) {
            super.clicked(slotIndex, buttonNum, input, clicker);
            return;
        }
        if (clicker.level().isClientSide() || (input != ContainerInput.PICKUP && input != ContainerInput.QUICK_MOVE)) {
            return;
        }
        CellFilter filter = filter();
        ItemStack carried = getCarried();
        if (input == ContainerInput.QUICK_MOVE || (carried.isEmpty() && buttonNum == 0)) {
            set(filter.with(slotIndex, null));
        } else if (carried.isEmpty()) {
            if (slotIndex < filter.entries().size()) {
                set(filter.with(slotIndex, CellWorkbenchMenu.nextTag(filter.entries().get(slotIndex))));
            }
        } else {
            set(filter.with(slotIndex, CellFilter.Entry.of(CellWorkbenchMenu.keyOf(carried))));
        }
    }

    /** What entry slot {@code index} shows: the entry's item, or a fluid's bucket; empty for none. */
    ItemStack entry(int index) {
        CellFilter filter = filter();
        if (index >= filter.entries().size()) {
            return ItemStack.EMPTY;
        }
        return switch (filter.entries().get(index).key()) {
            case ItemKey item -> item.toStack(1);
            case FluidKey fluid -> {
                Item bucket = fluid.fluid().getBucket();
                yield new ItemStack(new ItemStack(bucket).isEmpty() ? Items.BUCKET : bucket);
            }
        };
    }

    /** Shift-clicks move nothing: the entries are ghosts and the inventory has nowhere else to go. */
    @Override
    public ItemStack quickMoveStack(Player clicker, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player viewer) {
        return !held().isEmpty();
    }

    /**
     * Client to server: set entry {@code index} of the open view filter GUI {@code containerId} to {@code key} (a
     * recipe viewer's ingredient). Only the filter changes; no item moves.
     */
    public record Ghost(int containerId, int index, StackKey key) implements CustomPacketPayload {
        public static final Type<Ghost> TYPE = new Type<>(MaxtechStorage.id("view_filter_ghost"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Ghost> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Ghost::containerId, ByteBufCodecs.VAR_INT, Ghost::index, StackKey.STREAM_CODEC,
                Ghost::key, Ghost::new);

        @Override
        public Type<Ghost> type() {
            return TYPE;
        }
    }

    /** Registers the GUI's payload; called once during mod construction. */
    static void registerPayloads() {
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Ghost.TYPE, Ghost.CODEC, (payload, player) -> {
            if (player.containerMenu instanceof ViewFilterMenu menu && menu.containerId == payload.containerId()
                    && menu.stillValid(player)) {
                menu.setEntry(payload.index(), payload.key());
            }
        });
    }

    /**
     * A slot that shows one entry of the held filter. Vanilla never moves anything through it: {@link #clicked} handles
     * clicks, and {@link #set} ignores the slot sync, since the entry comes from the held filter.
     */
    private final class EntrySlot extends Slot {
        private final int entry;

        EntrySlot(int entry, int x, int y) {
            super(new SimpleContainer(1), 0, x, y);
            this.entry = entry;
        }

        @Override
        public ItemStack getItem() {
            return entry(entry);
        }

        @Override
        public boolean hasItem() {
            return !getItem().isEmpty();
        }

        @Override
        public void set(ItemStack stack) {
        }

        @Override
        public void setByPlayer(ItemStack stack, ItemStack previous) {
        }

        @Override
        public ItemStack remove(int amount) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player clicker) {
            return false;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }
}
