package dev.silencebound.maxtech.storage.drive;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.cell.StorageCellItem;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.priority.PriorityControl;
import dev.silencebound.maxtech.storage.priority.PriorityMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
// PLATFORM-LEAK: the cell slots are NeoForge's slots over the drive's item resource handler.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ResourceHandlerSlot;
import dev.silencebound.maxtech.storage.label.LabelMenu;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import org.jspecify.annotations.Nullable;

/**
 * The drive's screen: its eight cell slots over the player's inventory, and the priority tab. The slots are the drive's
 * own item handler ({@link DriveBlockEntity#cells()}), the same path hoppers and ports use, so a cell going in is
 * mounted and one coming out leaves with its contents and fresh stats, one per slot. Creative cloning out of a cell
 * slot is refused, since a copy would carry the cell's id, and so is every click once the drive is broken. Full access
 * opens it and keeps it open; the priority travels as a data slot and changes as a menu button click
 * ({@link PriorityControl}). Each cell slot has two transfer buttons (M7C item 12, {@link CellTransfer}), menu button
 * ids 0 to 15 ({@link #transferButton}), which need the same full access; the modes, results and the mode each result
 * belongs to travel in three more data slots.
 */
public final class DriveMenu extends AbstractContainerMenu implements PriorityMenu, LabelMenu {
    public static final int WIDTH = 176;
    public static final int HEIGHT = 172;
    public static final int COLUMNS = 4;
    /** Left edge of the first cell slot; a column is a slot, its fill gauge and its two transfer buttons. */
    public static final int CELLS_X = 16;
    public static final int CELLS_Y = 20;
    public static final int COLUMN_WIDTH = 36;
    /** The transfer buttons' offset from their slot's left edge, and their size; "empty" on top, "fill" below. */
    public static final int TRANSFER_X = 25;
    public static final int TRANSFER_SIZE = 8;
    public static final int ROW_HEIGHT = 22;
    public static final int INVENTORY_X = 8;
    public static final int INVENTORY_Y = 90;
    private static final double REACH = 8.0;
    private static final String PREFIX = "gui.maxtech_storage.storage_drive.";
    /** The transfer buttons' names and tooltip lines (M7C item 12). */
    public static final String EMPTY_KEY = PREFIX + "empty";
    public static final String FILL_KEY = PREFIX + "fill";
    public static final String FILL_HINT_KEY = PREFIX + "fill_hint";
    public static final String RUNNING_KEY = PREFIX + "running";
    public static final String NO_CELL_KEY = PREFIX + "no_cell";
    public static final String EMPTY_DONE_KEY = PREFIX + "empty_done";
    public static final String EMPTY_BLOCKED_KEY = PREFIX + "empty_blocked";
    public static final String FILL_DONE_KEY = PREFIX + "fill_done";
    public static final String FILL_BLOCKED_KEY = PREFIX + "fill_blocked";
    /** The line under the cells on how to take one out by hand. */
    public static final String HELP_KEY = "gui.maxtech_storage.storage_drive.help";

    private final @Nullable DriveBlockEntity drive;
    private final PriorityControl.Sync priority;
    private final DataSlot transfers;
    private final DataSlot results;
    private final DataSlot ended;

    private DriveMenu(int containerId, Inventory inventory, @Nullable DriveBlockEntity drive,
            ItemStacksResourceHandler cells) {
        super(DriveContent.MENU.get(), containerId);
        this.drive = drive;
        for (int i = 0; i < DriveBlockEntity.SLOTS; i++) {
            addSlot(new ResourceHandlerSlot(cells, cells::set, i, slotX(i), slotY(i)));
        }
        addStandardInventorySlots(inventory, INVENTORY_X, INVENTORY_Y);
        priority = PriorityControl.sync(drive, this::addDataSlot);
        transfers = addDataSlot(drive == null ? DataSlot.standalone() : new Packed(drive, Packed.MODES));
        results = addDataSlot(drive == null ? DataSlot.standalone() : new Packed(drive, Packed.RESULTS));
        ended = addDataSlot(drive == null ? DataSlot.standalone() : new Packed(drive, Packed.ENDED));
    }

    /**
     * The menu button id of {@code slot}'s transfer button: {@link CellTransfer#EMPTY} or {@link CellTransfer#FILL}.
     * Ids 0 to 15.
     */
    public static int transferButton(int slot, int mode) {
        return slot * 2 + (mode == CellTransfer.FILL ? 1 : 0);
    }

    /** {@code slot}'s transfer mode as synced: {@link CellTransfer#NONE}, {@code EMPTY} or {@code FILL}. */
    public int transferMode(int slot) {
        return CellTransfer.unpack(transfers.get(), slot);
    }

    /**
     * How {@code slot}'s last transfer ended, as synced: {@link CellTransfer#NONE}, {@code FINISHED} or
     * {@code BLOCKED}.
     */
    public int transferResult(int slot) {
        return CellTransfer.unpack(results.get(), slot);
    }

    /** The mode {@code slot}'s {@link #transferResult} belongs to, as synced. */
    public int lastTransfer(int slot) {
        return CellTransfer.unpack(ended.get(), slot);
    }

    /** The x of cell slot {@code i} in the menu. */
    public static int slotX(int i) {
        return CELLS_X + (i % COLUMNS) * COLUMN_WIDTH;
    }

    /** The y of cell slot {@code i} in the menu. */
    public static int slotY(int i) {
        return CELLS_Y + (i / COLUMNS) * ROW_HEIGHT;
    }

    /** The client's menu; slot sync fills the cells and the data slot the priority. */
    public static DriveMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        buffer.readBlockPos();
        return new DriveMenu(containerId, inventory, null, new ClientCells());
    }

    /** The server's menu over {@code drive}'s slots. */
    public static DriveMenu forDrive(int containerId, Inventory inventory, DriveBlockEntity drive) {
        return new DriveMenu(containerId, inventory, drive, drive.cells());
    }

    /** Opens {@code drive}'s screen for {@code player}. */
    public static void open(ServerPlayer player, DriveBlockEntity drive, Component title) {
        Platform.get().menus().open(player,
                new SimpleMenuProvider((id, inventory, opener) -> forDrive(id, inventory, drive), title),
                buffer -> buffer.writeBlockPos(drive.getBlockPos()));
    }

    @Override
    public int priority() {
        return PriorityControl.read(priority);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        DriveBlockEntity current = drive;
        if (current == null || current.isRemoved() || !NetworkAccess.canConfigure(player, current)) {
            return false;
        }
        if (id >= 0 && id < DriveBlockEntity.SLOTS * 2) {
            return current.toggleTransfer(id / 2, id % 2 == 0 ? CellTransfer.EMPTY : CellTransfer.FILL);
        }
        return false;
    }

    @Override
    public boolean applyPriority(Player player, int priority) {
        DriveBlockEntity current = drive;
        if (current == null || current.isRemoved() || !NetworkAccess.canConfigure(player, current)) {
            return false;
        }
        current.setPriority(priority);
        return true;
    }

    /** A creative clone of a cell would copy its id; cell slots refuse it. */
    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput containerInput, Player player) {
        if (driveGone()
                || (containerInput == ContainerInput.CLONE && slotIndex >= 0 && slotIndex < DriveBlockEntity.SLOTS)) {
            return;
        }
        super.clicked(slotIndex, buttonNum, containerInput, player);
    }

    /**
     * Whether the server's drive was broken or unloaded while the screen is still open (a click can arrive before the
     * menu closes). Its cells already dropped with the drive item, so its slots must not give or take anything.
     */
    private boolean driveGone() {
        DriveBlockEntity current = drive;
        return current != null && current.isRemoved();
    }

    /** A cell slot empties into the inventory; a cell from the inventory goes into the first free cell slot. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (driveGone() || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < DriveBlockEntity.SLOTS) {
            if (!moveItemStackTo(stack, DriveBlockEntity.SLOTS, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!(stack.getItem() instanceof StorageCellItem)
                || !moveItemStackTo(stack, 0, DriveBlockEntity.SLOTS, false)) {
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
        DriveBlockEntity current = drive;
        return current == null
                || (!current.isRemoved() && player.position().closerThan(current.getBlockPos().getCenter(), REACH)
                        && NetworkAccess.canConfigure(player, current));
    }

    /** The drive's packed transfer modes, results or ended modes, read live on the server (16 bits, two per slot). */
    private static final class Packed extends DataSlot {
        static final int MODES = 0;
        static final int RESULTS = 1;
        static final int ENDED = 2;
        private final DriveBlockEntity drive;
        private final int which;

        Packed(DriveBlockEntity drive, int which) {
            this.drive = drive;
            this.which = which;
        }

        @Override
        public int get() {
            CellTransfer transfer = drive.transfer();
            return which == MODES
                    ? transfer.packedModes()
                    : which == RESULTS ? transfer.packedResults() : transfer.packedEnded();
        }

        @Override
        public void set(int value) {
            // Server side the drive is the truth; buttons change it.
        }
    }

    /** The client's copy of the cell slots, with the drive's rules so the client predicts clicks the same way. */
    private static final class ClientCells extends ItemStacksResourceHandler {
        ClientCells() {
            super(DriveBlockEntity.SLOTS);
        }

        @Override
        public boolean isValid(int index, ItemResource resource) {
            return resource.getItem() instanceof StorageCellItem;
        }

        @Override
        protected int getCapacity(int index, ItemResource resource) {
            return 1;
        }
    }

    @Override
    public @Nullable NetworkDevice labelDevice() {
        return drive;
    }
}
