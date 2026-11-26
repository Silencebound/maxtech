package dev.silencebound.maxtech.storage.tool.belt;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The restock belt's GUI: the player's own inventory, where clicks mark slots instead of moving items. Left-click marks
 * a slot with what it holds now, at its current count; right-click takes the mark off; the screen's scroll wheel over a
 * marked slot sends menu button clicks that change its count. Marks live on the belt in the main hand, so vanilla's
 * slot sync shows every change on the client without a payload of our own. Nothing moves in this menu.
 */
public final class BeltMenu extends AbstractContainerMenu {
    public static final int WIDTH = 176;
    public static final int HEIGHT = 120;
    public static final int INVENTORY_X = 8;
    public static final int INVENTORY_Y = 38;

    /** Button {@code slot * OPS + op} changes {@code slot}'s count by {@link #STEPS}{@code [op]}. */
    public static final int OPS = 4;
    private static final int[] STEPS = {1, -1, 16, -16};

    private final Inventory inventory;

    private BeltMenu(int containerId, Inventory inventory) {
        super(BeltContent.MENU.get(), containerId);
        this.inventory = inventory;
        addStandardInventorySlots(inventory, INVENTORY_X, INVENTORY_Y);
    }

    /** The client's menu. */
    public static BeltMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        return new BeltMenu(containerId, inventory);
    }

    /** The server's menu; the belt is in {@code inventory}'s player's main hand. */
    public static BeltMenu server(int containerId, Inventory inventory) {
        return new BeltMenu(containerId, inventory);
    }

    /** Opens the GUI of the belt in {@code player}'s main hand. */
    public static void open(ServerPlayer player, Component title) {
        Platform.get().menus().open(player,
                new SimpleMenuProvider((id, inventory, opener) -> server(id, inventory), title), buffer -> {
                });
    }

    /** The belt being set up; empty once it left the main hand. */
    public ItemStack belt() {
        ItemStack stack = inventory.player.getItemInHand(InteractionHand.MAIN_HAND);
        return stack.getItem() instanceof RestockBeltItem ? stack : ItemStack.EMPTY;
    }

    /** The belt's marks, as the slot sync last brought them on the client. */
    public BeltMarks marks() {
        return RestockBeltItem.marks(belt());
    }

    /** The menu button id that moves {@code slot}'s count by {@code delta} (one of 1, -1, 16, -16). */
    public static int button(int slot, int delta) {
        for (int op = 0; op < OPS; op++) {
            if (STEPS[op] == delta) {
                return slot * OPS + op;
            }
        }
        throw new IllegalArgumentException("no step " + delta);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        int slot = id / OPS;
        ItemStack belt = belt();
        if (id < 0 || slot >= BeltMarks.SLOTS || belt.isEmpty()) {
            return false;
        }
        BeltMarks marks = RestockBeltItem.marks(belt);
        BeltMarks.Mark mark = marks.at(slot);
        if (mark == null) {
            return false;
        }
        BeltMarks changed = marks.adjusted(slot, STEPS[id % OPS], mark.key().toStack(1).getMaxStackSize());
        belt.set(BeltContent.MARKS.get(), changed);
        return true;
    }

    /**
     * Marks or unmarks the clicked inventory slot on the server; every click is refused the vanilla way, so nothing
     * moves and the client's prediction is fixed by the slot sync.
     */
    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput containerInput, Player player) {
        if (player.level().isClientSide() || slotIndex < 0 || slotIndex >= slots.size()
                || containerInput != ContainerInput.PICKUP) {
            return;
        }
        ItemStack belt = belt();
        if (belt.isEmpty()) {
            return;
        }
        Slot slot = slots.get(slotIndex);
        int inventorySlot = slot.getContainerSlot();
        BeltMarks marks = RestockBeltItem.marks(belt);
        BeltMarks changed;
        if (buttonNum == 1) {
            changed = marks.without(inventorySlot);
        } else {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || stack.getItem() instanceof RestockBeltItem) {
                return;
            }
            changed = marks.with(inventorySlot, ItemKey.of(stack), stack.getCount(), RestockBeltItem.maxMarks());
            if (changed.equals(marks) && marks.at(inventorySlot) == null) {
                RestockBeltItem.tell(player, Component.translatable(BeltContent.FULL_KEY, RestockBeltItem.maxMarks()));
            }
        }
        belt.set(BeltContent.MARKS.get(), changed);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return !belt().isEmpty();
    }
}
