package dev.silencebound.maxtech.storage.iface;

import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.devicetab.DeviceTabLayout;
import dev.silencebound.maxtech.storage.devicetab.UpgradeSlot;
import dev.silencebound.maxtech.storage.redstone.RedstoneSync;
import java.util.function.BooleanSupplier;
import dev.silencebound.maxtech.storage.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import dev.silencebound.maxtech.storage.label.LabelMenu;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import org.jspecify.annotations.Nullable;

/**
 * The interface's GUI: nine ghost item stock slots and three ghost fluid slots, a read-only row showing the items held,
 * and the fluid held per tank as data slots. Ghost slots never hold real items: a click with a stack sets the slot to
 * that item and count (a filled bucket in a fluid slot: one bucket), a right-click with the same item adds one, an
 * empty-handed click clears and an empty-handed right-click takes one away; a shift-click from the inventory sets the
 * first free slot. The card slot (M7 C21) is a real slot for one item in {@link InterfaceCrafting#CARDS}. Every change
 * checks access.
 */
public final class InterfaceMenu extends AbstractContainerMenu implements LabelMenu {
    public static final int WIDTH = 176;
    public static final int HEIGHT = 192;
    public static final int STOCK_X = 8;
    public static final int STOCK_Y = 22;
    public static final int HELD_Y = 44;
    public static final int FLUID_X = 8;
    public static final int FLUID_Y = 72;
    public static final int FLUID_SPACING = 54;
    public static final int INVENTORY_X = 8;
    public static final int INVENTORY_Y = 110;
    /** Menu slot index of the first held (read-only) slot. */
    public static final int HELD_START = InterfaceWorker.CONFIG_SIZE;
    /** Menu slot index of the card slot. */
    public static final int CARD_SLOT = HELD_START + InterfaceWorker.ITEM_SLOTS;
    private static final int INVENTORY_START = CARD_SLOT + 1;
    /** The button id {@link #clickMenuButton} takes for the next redstone mode. */
    public static final int CYCLE_REDSTONE = 0;
    /** The button id {@link #clickMenuButton} takes for the previous redstone mode. */
    public static final int REDSTONE_BACK = 1;

    private final @Nullable InterfaceDevice device;
    private final Container config;
    private final DataSlot[] fluidAmounts = new DataSlot[InterfaceWorker.FLUID_SLOTS];
    private final DataSlot redstone;
    private final UpgradeSlot.Shown cardShown = new UpgradeSlot.Shown();

    private InterfaceMenu(int containerId, Inventory inventory, @Nullable InterfaceDevice device, Container config,
            Container held, Container card, DataSlot[] amounts) {
        super(ModMenus.INTERFACE.get(), containerId);
        this.device = device;
        this.config = config;
        for (int i = 0; i < InterfaceWorker.ITEM_SLOTS; i++) {
            addSlot(new GhostSlot(config, i, STOCK_X + i * 18, STOCK_Y));
        }
        for (int i = 0; i < InterfaceWorker.FLUID_SLOTS; i++) {
            addSlot(new GhostSlot(config, InterfaceWorker.ITEM_SLOTS + i, FLUID_X + i * FLUID_SPACING, FLUID_Y));
        }
        for (int i = 0; i < InterfaceWorker.ITEM_SLOTS; i++) {
            addSlot(new GhostSlot(held, i, STOCK_X + i * 18, HELD_Y));
        }
        // Shown in the screen's upgrades flyout (B09 item 21b).
        addSlot(new CardSlot(card, DeviceTabLayout.slotX(WIDTH, 0), DeviceTabLayout.slotY(0), cardShown));
        addStandardInventorySlots(inventory, INVENTORY_X, INVENTORY_Y);
        for (int i = 0; i < amounts.length; i++) {
            fluidAmounts[i] = amounts[i];
            addDataSlot(amounts[i]);
        }
        redstone = addDataSlot(RedstoneSync.slot(device));
    }

    /** The server's menu over {@code device}'s own ghost slots and stock. */
    public static InterfaceMenu forDevice(int containerId, Inventory inventory, InterfaceDevice device) {
        InterfaceWorker worker = device.worker();
        DataSlot[] amounts = new DataSlot[InterfaceWorker.FLUID_SLOTS];
        for (int i = 0; i < amounts.length; i++) {
            int tank = i;
            amounts[i] = new DataSlot() {
                @Override
                public int get() {
                    return worker.fluidStock().getAmountAsInt(tank);
                }

                @Override
                public void set(int value) {
                }
            };
        }
        return new InterfaceMenu(containerId, inventory, device, worker.config(), new HeldView(worker), worker.card(),
                amounts);
    }

    /** The client's menu; slot sync fills the ghost and held slots, data slots the fluid amounts. */
    public static InterfaceMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        buffer.readBlockPos();
        DataSlot[] amounts = new DataSlot[InterfaceWorker.FLUID_SLOTS];
        for (int i = 0; i < amounts.length; i++) {
            amounts[i] = DataSlot.standalone();
        }
        return new InterfaceMenu(containerId, inventory, null, new GhostContainer(InterfaceWorker.CONFIG_SIZE),
                new SimpleContainer(InterfaceWorker.ITEM_SLOTS), new SimpleContainer(1), amounts);
    }

    /** Opens {@code device}'s GUI for {@code player}. */
    public static void open(ServerPlayer player, InterfaceDevice device, BlockPos pos, Component title) {
        Platform.get().menus().open(player,
                new SimpleMenuProvider((id, inventory, opener) -> forDevice(id, inventory, device), title),
                buffer -> buffer.writeBlockPos(pos));
    }

    /** Whether the screen shows the card slot now (its upgrades flyout is open); client side. */
    public UpgradeSlot.Shown cardShown() {
        return cardShown;
    }

    /** The redstone mode and state, as synced. */
    public RedstoneSync.State redstone() {
        return RedstoneSync.read(redstone);
    }

    /** The redstone button: steps the mode forward ({@link #CYCLE_REDSTONE}) or back ({@link #REDSTONE_BACK}). */
    @Override
    public boolean clickMenuButton(Player player, int id) {
        InterfaceDevice current = device;
        if (current == null || !canChange(player) || (id != CYCLE_REDSTONE && id != REDSTONE_BACK)) {
            return false;
        }
        RedstoneSync.step(current.redstone(), id == CYCLE_REDSTONE);
        return true;
    }

    /** mB the fluid tank {@code tank} holds, as synced. */
    public int fluidAmount(int tank) {
        return fluidAmounts[tank].get();
    }

    /** The ghost slots take what the class comment says; nothing real moves. */
    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput containerInput, Player player) {
        if (slotIndex == CARD_SLOT && !canChange(player)) {
            return;
        }
        if (slotIndex >= 0 && slotIndex < CARD_SLOT) {
            if (slotIndex < HELD_START && canChange(player)) {
                if (containerInput == ContainerInput.PICKUP) {
                    config.setItem(slotIndex,
                            ghostClick(slotIndex, config.getItem(slotIndex), getCarried(), buttonNum == 1));
                } else if (containerInput == ContainerInput.QUICK_MOVE) {
                    config.setItem(slotIndex, ItemStack.EMPTY);
                }
            }
            return;
        }
        super.clicked(slotIndex, buttonNum, containerInput, player);
    }

    /** What a ghost slot holds after a click; see the class comment. */
    static ItemStack ghostClick(int slot, ItemStack current, ItemStack carried, boolean right) {
        boolean fluid = slot >= InterfaceWorker.ITEM_SLOTS;
        int max = fluid ? maxBuckets() : InterfaceWorker.MAX_ITEM_TARGET;
        if (carried.isEmpty()) {
            if (!right || current.getCount() <= 1) {
                return ItemStack.EMPTY;
            }
            return current.copyWithCount(current.getCount() - 1);
        }
        if (fluid && !isFilledBucket(carried)) {
            return current;
        }
        if (right && ItemStack.isSameItemSameComponents(current, carried)) {
            return current.copyWithCount(Math.min(max, current.getCount() + 1));
        }
        return carried.copyWithCount(fluid || right ? 1 : Math.min(max, carried.getCount()));
    }

    private static int maxBuckets() {
        return (int) Math.max(1, InterfaceWorker.tankMbOrDefault() / InterfaceWorker.BUCKET_MB);
    }

    private static boolean isFilledBucket(ItemStack stack) {
        return stack.getItem() instanceof BucketItem bucket && !bucket.content.isSame(Fluids.EMPTY);
    }

    private boolean canChange(Player player) {
        InterfaceDevice current = device;
        return current == null || NetworkAccess.canConfigure(player, current);
    }

    /** A shift-clicked inventory stack sets the first free ghost slot of its kind with its count. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < CARD_SLOT || !canChange(player)) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slots.get(index).getItem();
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (index == CARD_SLOT || stack.is(InterfaceCrafting.CARDS)) {
            return moveCard(index, stack);
        }
        boolean fluid = isFilledBucket(stack);
        int from = fluid ? InterfaceWorker.ITEM_SLOTS : 0;
        int to = fluid ? InterfaceWorker.CONFIG_SIZE : InterfaceWorker.ITEM_SLOTS;
        for (int i = from; i < to; i++) {
            if (config.getItem(i).isEmpty()) {
                config.setItem(i, ghostClick(i, ItemStack.EMPTY, stack, false));
                break;
            }
        }
        return ItemStack.EMPTY;
    }

    /** A card goes between the card slot and the inventory, the vanilla way. */
    private ItemStack moveCard(int index, ItemStack stack) {
        ItemStack copy = stack.copy();
        boolean moved = index == CARD_SLOT
                ? moveItemStackTo(stack, INVENTORY_START, slots.size(), true)
                : moveItemStackTo(stack, CARD_SLOT, CARD_SLOT + 1, false);
        if (!moved) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slots.get(index).setByPlayer(ItemStack.EMPTY);
        } else {
            slots.get(index).setChanged();
        }
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        InterfaceDevice current = device;
        return current == null || current.stillValid(player);
    }

    /** A slot that shows its item but cannot be taken from or placed into the vanilla way. */
    private static final class GhostSlot extends Slot {
        GhostSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }
    }

    /** The card slot: one item in {@link InterfaceCrafting#CARDS}. */
    private static final class CardSlot extends UpgradeSlot {
        CardSlot(Container container, int x, int y, BooleanSupplier shown) {
            super(container, 0, x, y, shown);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.is(InterfaceCrafting.CARDS);
        }
    }

    /** The item stock as a read-only container for the held row; server side. */
    private static final class HeldView extends SimpleContainer {
        private final InterfaceWorker worker;

        HeldView(InterfaceWorker worker) {
            super(InterfaceWorker.ITEM_SLOTS);
            this.worker = worker;
        }

        @Override
        public ItemStack getItem(int slot) {
            int amount = worker.itemStock().getAmountAsInt(slot);
            return amount > 0 ? worker.itemStock().getResource(slot).toStack(amount) : ItemStack.EMPTY;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
        }
    }

    @Override
    public @Nullable NetworkDevice labelDevice() {
        return LabelMenu.device(device);
    }
}
