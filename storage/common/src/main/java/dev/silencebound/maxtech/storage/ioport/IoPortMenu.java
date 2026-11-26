package dev.silencebound.maxtech.storage.ioport;

import dev.silencebound.maxtech.storage.devicetab.DeviceTabLayout;
import dev.silencebound.maxtech.storage.devicetab.UpgradeSlot;
import dev.silencebound.maxtech.storage.redstone.RedstoneSync;
import java.util.List;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.cell.StorageCellItem;
import dev.silencebound.maxtech.storage.label.LabelMenu;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.port.PortContent;
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
import net.minecraft.world.item.ItemStack;
// PLATFORM-LEAK: the cell slots are NeoForge's slots over the port's item resource handler.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ResourceHandlerSlot;
import org.jspecify.annotations.Nullable;

/**
 * The IO port's screen (B09 item 11): six input and six output cell slots, three speed card slots, the mode and the
 * output setting, over the player's inventory. The cell slots are the port's own handler
 * ({@link IoPortBlockEntity#slots()}), so a cell going in is mounted and one coming out leaves with fresh stats; the
 * output slots take nothing from the player. Creative cloning out of a cell slot is refused (a copy would carry the
 * cell's id), and so is every click once the port is gone. Full access opens it and keeps it open; the settings change
 * as menu button clicks ({@link #CYCLE_MODE}, {@link #CYCLE_OUTPUT}, {@link #CYCLE_REDSTONE}, {@link #REDSTONE_BACK})
 * and travel in data slots. The speed card slots show in the screen's upgrades flyout (B09 item 21b).
 */
public final class IoPortMenu extends AbstractContainerMenu implements LabelMenu {
    public static final int WIDTH = 176;
    public static final int HEIGHT = 180;
    public static final int INPUT_X = 8;
    public static final int OUTPUT_X = 134;
    public static final int SLOTS_Y = 20;
    /** The settings buttons between the input and output columns. */
    public static final int BUTTON_X = 46;
    public static final int BUTTON_WIDTH = 68;
    public static final int MODE_Y = 19;
    public static final int INVENTORY_X = 8;
    public static final int INVENTORY_Y = 98;
    /** Menu button id that switches the mode. */
    public static final int CYCLE_MODE = 0;
    /** Menu button id that switches when cells move on. */
    public static final int CYCLE_OUTPUT = 1;
    /** Menu button id for the next redstone mode. */
    public static final int CYCLE_REDSTONE = 2;
    /** Menu button id for the previous redstone mode. */
    public static final int REDSTONE_BACK = 3;
    private static final int CELL_SLOTS = IoPortBlockEntity.SLOTS;
    private static final int OWN_SLOTS = CELL_SLOTS + IoPortBlockEntity.CARD_SLOTS;
    private static final double REACH = 8.0;

    private final @Nullable IoPortBlockEntity port;
    private final DataSlot mode;
    private final DataSlot output;
    private final DataSlot operations;
    private final DataSlot redstone;
    private final UpgradeSlot.Shown cardsShown = new UpgradeSlot.Shown();

    private IoPortMenu(int containerId, Inventory inventory, @Nullable IoPortBlockEntity port,
            ItemStacksResourceHandler cells, Container cards) {
        super(IoPortContent.MENU.get(), containerId);
        this.port = port;
        for (int i = 0; i < CELL_SLOTS; i++) {
            addSlot(new ResourceHandlerSlot(cells, cells::set, i, slotX(i), slotY(i)));
        }
        for (int i = 0; i < IoPortBlockEntity.CARD_SLOTS; i++) {
            // Shown in the screen's upgrades flyout (B09 item 21b).
            addSlot(new UpgradeSlot(cards, i, DeviceTabLayout.slotX(WIDTH, i), DeviceTabLayout.slotY(i), cardsShown));
        }
        addStandardInventorySlots(inventory, INVENTORY_X, INVENTORY_Y);
        mode = addDataSlot(port == null ? DataSlot.standalone() : new Live(port, Live.MODE));
        output = addDataSlot(port == null ? DataSlot.standalone() : new Live(port, Live.OUTPUT));
        operations = addDataSlot(port == null ? DataSlot.standalone() : new Live(port, Live.OPERATIONS));
        redstone = addDataSlot(RedstoneSync.slot(port));
    }

    /** The x of cell slot {@code i}: inputs (0 to 5) in two columns on the left, outputs on the right. */
    public static int slotX(int i) {
        int column = i % 2;
        return (i < IoPortBlockEntity.INPUTS ? INPUT_X : OUTPUT_X - 18) + column * 18;
    }

    /** The y of cell slot {@code i}. */
    public static int slotY(int i) {
        return SLOTS_Y + (i % IoPortBlockEntity.INPUTS) / 2 * 18;
    }

    /** The client's menu; slot sync fills the cells and cards, the data slots the settings. */
    public static IoPortMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        buffer.readBlockPos();
        return new IoPortMenu(containerId, inventory, null, new ClientCells(),
                new SimpleContainer(IoPortBlockEntity.CARD_SLOTS) {
                    @Override
                    public int getMaxStackSize() {
                        return 1;
                    }

                    @Override
                    public boolean canPlaceItem(int slot, ItemStack stack) {
                        return stack.is(PortContent.SPEED_CARD.get());
                    }
                });
    }

    /** The server's menu over {@code port}'s slots. */
    public static IoPortMenu forPort(int containerId, Inventory inventory, IoPortBlockEntity port) {
        return new IoPortMenu(containerId, inventory, port, port.slots(), port.cards());
    }

    /** Opens {@code port}'s screen for {@code player}. */
    public static void open(ServerPlayer player, IoPortBlockEntity port, Component title) {
        Platform.get().menus().open(player,
                new SimpleMenuProvider((id, inventory, opener) -> forPort(id, inventory, port), title),
                buffer -> buffer.writeBlockPos(port.getBlockPos()));
    }

    /** The mode as synced. */
    public IoPortMode mode() {
        return IoPortMode.byId(mode.get());
    }

    /** When cells move on, as synced. */
    public IoPortOutput output() {
        return IoPortOutput.byId(output.get());
    }

    /** Whether the screen shows the card slots now (its upgrades flyout is open); client side. */
    public UpgradeSlot.Shown cardsShown() {
        return cardsShown;
    }

    /** The card slots, in the menu's slot list. */
    public List<Slot> cardSlots() {
        return slots.subList(CELL_SLOTS, OWN_SLOTS);
    }

    /** The redstone mode and state, as synced. */
    public RedstoneSync.State redstone() {
        return RedstoneSync.read(redstone);
    }

    /** Operations per tick, as synced. */
    public int operations() {
        return operations.get();
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        IoPortBlockEntity current = port;
        if (current == null || current.isRemoved() || !NetworkAccess.canConfigure(player, current)) {
            return false;
        }
        if (id == CYCLE_MODE) {
            current.setMode(current.mode().next());
            return true;
        }
        if (id == CYCLE_OUTPUT) {
            current.setOutput(current.output().next());
            return true;
        }
        if (id == CYCLE_REDSTONE || id == REDSTONE_BACK) {
            RedstoneSync.step(current.redstone(), id == CYCLE_REDSTONE);
            return true;
        }
        return false;
    }

    /** A creative clone of a cell would copy its id; cell slots refuse it. */
    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput containerInput, Player player) {
        if (portGone() || (containerInput == ContainerInput.CLONE && slotIndex >= 0 && slotIndex < CELL_SLOTS)) {
            return;
        }
        super.clicked(slotIndex, buttonNum, containerInput, player);
    }

    /** Whether the server's port was broken or unloaded while the screen is still open. */
    private boolean portGone() {
        IoPortBlockEntity current = port;
        return current != null && current.isRemoved();
    }

    /**
     * A cell or card slot empties into the inventory; a cell from the inventory goes into the first free input slot, a
     * speed card into the first free card slot.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (portGone() || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < OWN_SLOTS) {
            if (!moveItemStackTo(stack, OWN_SLOTS, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (stack.getItem() instanceof StorageCellItem) {
            if (!moveItemStackTo(stack, 0, IoPortBlockEntity.INPUTS, false)) {
                return ItemStack.EMPTY;
            }
        } else if (!stack.is(PortContent.SPEED_CARD.get()) || !moveItemStackTo(stack, CELL_SLOTS, OWN_SLOTS, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        IoPortBlockEntity current = port;
        return current == null
                || (!current.isRemoved() && player.position().closerThan(current.getBlockPos().getCenter(), REACH)
                        && NetworkAccess.canConfigure(player, current));
    }

    @Override
    public @Nullable NetworkDevice labelDevice() {
        return port;
    }

    /** One of the port's settings, read live on the server. */
    private static final class Live extends DataSlot {
        static final int MODE = 0;
        static final int OUTPUT = 1;
        static final int OPERATIONS = 2;
        private final IoPortBlockEntity port;
        private final int which;

        Live(IoPortBlockEntity port, int which) {
            this.port = port;
            this.which = which;
        }

        @Override
        public int get() {
            return switch (which) {
                case MODE -> port.mode().id();
                case OUTPUT -> port.output().id();
                default -> port.operations();
            };
        }

        @Override
        public void set(int value) {
            // Server side the port is the truth; buttons change it.
        }
    }

    /** The client's copy of the cell slots, with the port's rules so the client predicts clicks the same way. */
    private static final class ClientCells extends ItemStacksResourceHandler {
        ClientCells() {
            super(CELL_SLOTS);
        }

        @Override
        public boolean isValid(int index, ItemResource resource) {
            return index < IoPortBlockEntity.INPUTS && resource.getItem() instanceof StorageCellItem;
        }

        @Override
        protected int getCapacity(int index, ItemResource resource) {
            return 1;
        }
    }
}
