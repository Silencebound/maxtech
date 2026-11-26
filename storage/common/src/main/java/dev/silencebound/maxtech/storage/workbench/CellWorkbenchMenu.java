package dev.silencebound.maxtech.storage.workbench;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.cell.CellAddon;
import dev.silencebound.maxtech.storage.cell.CellAddonItem;
import dev.silencebound.maxtech.storage.cell.CellAssembly;
import dev.silencebound.maxtech.storage.cell.CellCasing;
import dev.silencebound.maxtech.storage.cell.CellCasingItem;
import dev.silencebound.maxtech.storage.cell.CellData;
import dev.silencebound.maxtech.storage.cell.CellFilter;
import dev.silencebound.maxtech.storage.cell.CellStorage;
import dev.silencebound.maxtech.storage.cell.ControllerChipItem;
import dev.silencebound.maxtech.storage.cell.MemoryModuleItem;
import dev.silencebound.maxtech.storage.cell.StorageCellItem;
import dev.silencebound.maxtech.storage.registry.ModMenus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

/**
 * The cell workbench's GUI (PRD 7.2, the cell builder): the casing slot on the left, the chip, memory and add-on slots
 * in the middle with the filter beside them, the cell's stats on the right. Only the casing slot holds a real item; the
 * part slots show the parts named in the cell's data, and clicking them runs a {@link CellAssembly} operation on the
 * server, which refuses anything the contents would not survive and never deletes a part: a removed part goes to the
 * cursor or, shift-clicked, into the inventory only when it has room. The filter slots are ghost slots. Nothing is
 * predicted on the client; the server's slot sync shows the result. Research gates parts through
 * {@link WorkbenchGates}; a refusal is shown as a notice (a data slot).
 */
public final class CellWorkbenchMenu extends AbstractContainerMenu {
    public static final int WIDTH = 256;
    public static final int HEIGHT = 196;
    public static final int CASING_X = 12;
    public static final int CASING_Y = 26;
    public static final int CHIP_Y = 56;
    public static final int MEMORY_X = 40;
    public static final int MEMORY_Y = 26;
    public static final int ADDON_Y = 74;
    public static final int FILTER_X = 124;
    public static final int FILTER_Y = 26;
    public static final int INVENTORY_X = 48;
    public static final int INVENTORY_Y = 114;

    public static final int CASING_SLOT = 0;
    public static final int CHIP_SLOT = 1;
    public static final int FIRST_MEMORY = 2;
    public static final int FIRST_ADDON = FIRST_MEMORY + CellCasing.MAX_MEMORY_SLOTS;
    public static final int FIRST_FILTER = FIRST_ADDON + CellCasing.MAX_ADDON_SLOTS;
    public static final int FIRST_INVENTORY = FIRST_FILTER + CellFilter.SIZE;

    /** Button ids for {@link #clickMenuButton}. */
    public static final int FUZZY = 0;
    /** Switches the filter between allow list and deny list. */
    public static final int DENY = 1;
    /**
     * Removes the filter's kept entries (keys of a removed mod, {@link CellFilter#kept()}); the cell's kept stored
     * contents are not touched.
     */
    public static final int CLEAR_UNKNOWN = 2;

    /** Notices: what the last refused change ran into. */
    public static final int NOTICE_NONE = 0;
    public static final int NOTICE_RESEARCH = 1;
    public static final int NOTICE_NO_FIT = 2;
    public static final int NOTICE_IN_USE = 3;
    public static final int NOTICE_NO_ROOM = 4;

    private static final double RANGE_BUFFER = 4.0;

    private final @Nullable CellWorkbenchBlockEntity workbench;
    private final Container casing;
    private final DataSlot notice;
    private final Player player;

    private CellWorkbenchMenu(int containerId, Inventory inventory, @Nullable CellWorkbenchBlockEntity workbench,
            Container casing, DataSlot notice) {
        super(ModMenus.WORKBENCH.get(), containerId);
        this.workbench = workbench;
        this.casing = casing;
        this.notice = notice;
        this.player = inventory.player;
        addSlot(new CasingSlot(casing, CASING_X, CASING_Y));
        addSlot(new PartSlot(CHIP_SLOT, CASING_X, CHIP_Y));
        for (int i = 0; i < CellCasing.MAX_MEMORY_SLOTS; i++) {
            addSlot(new PartSlot(FIRST_MEMORY + i, MEMORY_X + (i % 4) * 18, MEMORY_Y + (i / 4) * 18));
        }
        for (int i = 0; i < CellCasing.MAX_ADDON_SLOTS; i++) {
            addSlot(new PartSlot(FIRST_ADDON + i, MEMORY_X + i * 18, ADDON_Y));
        }
        for (int i = 0; i < CellFilter.SIZE; i++) {
            addSlot(new PartSlot(FIRST_FILTER + i, FILTER_X + (i % 3) * 18, FILTER_Y + (i / 3) * 18));
        }
        addStandardInventorySlots(inventory, INVENTORY_X, INVENTORY_Y);
        addDataSlot(notice);
    }

    /** The server's menu over {@code workbench}. */
    public static CellWorkbenchMenu server(int containerId, Inventory inventory, CellWorkbenchBlockEntity workbench) {
        return new CellWorkbenchMenu(containerId, inventory, workbench, workbench.slot(), DataSlot.standalone());
    }

    /** The client's menu; slot sync fills the casing slot and the notice. */
    public static CellWorkbenchMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        buffer.readBlockPos();
        return new CellWorkbenchMenu(containerId, inventory, null, new SimpleContainer(1), DataSlot.standalone());
    }

    static void open(ServerPlayer player, CellWorkbenchBlockEntity workbench) {
        BlockPos pos = workbench.getBlockPos();
        Platform.get().menus().open(player,
                new SimpleMenuProvider((id, inventory, opener) -> server(id, inventory, workbench),
                        workbench.getBlockState().getBlock().getName()),
                buffer -> buffer.writeBlockPos(pos));
    }

    /** The casing or cell in the workbench, as synced; empty for none. */
    public ItemStack cell() {
        return casing.getItem(0);
    }

    /** The cell's data; {@code null} for an empty casing or none. */
    public @Nullable CellData data() {
        return StorageCellItem.data(cell());
    }

    /** What the last refused change ran into ({@link #NOTICE_NONE} ...). */
    public int notice() {
        return notice.get();
    }

    /** Kept entries in the cell's filter (keys of a removed mod); 0 without a cell. */
    public int unknownFilterEntries() {
        CellData data = data();
        return data == null ? 0 : data.filter().kept().size();
    }

    /** Whether the filter slots are in use: the cell has a partition or void overflow add-on. */
    public boolean filterShown() {
        CellData data = data();
        return data != null && (data.has(CellAddon.PARTITION) || data.has(CellAddon.VOID_OVERFLOW));
    }

    @Override
    public boolean clickMenuButton(Player clicker, int id) {
        CellWorkbenchBlockEntity current = workbench;
        CellData data = data();
        CellStorage storage = storage(clicker);
        if (current == null || data == null || storage == null) {
            return false;
        }
        CellFilter filter = data.filter();
        ItemStack result = null;
        if (id == FUZZY && filterShown()) {
            result = CellAssembly.setFilter(cell(), filter.withFuzzy(!filter.fuzzy()), storage);
        } else if (id == DENY && filterShown()) {
            result = CellAssembly.setFilter(cell(), filter.withDeny(!filter.deny()), storage);
        } else if (id == CLEAR_UNKNOWN && filterShown() && !filter.kept().isEmpty()) {
            result = CellAssembly.setFilter(cell(), filter.withoutKept(), storage);
        }
        return apply(current, result, NOTICE_IN_USE);
    }

    /** The server's cell storage; {@code null} on the client, which changes nothing itself. */
    private static @Nullable CellStorage storage(Player clicker) {
        MinecraftServer server = clicker.level().getServer();
        return server == null ? null : CellStorage.get(server);
    }

    /**
     * Part and filter slots are handled here, on the server only: the client changes nothing and waits for the slot
     * sync, so a refused change never shows.
     */
    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player clicker) {
        if (slotIndex == CASING_SLOT && workbench != null && !getCarried().isEmpty()
                && CellAssembly.casing(getCarried()) != null && !WorkbenchGates.allowed(clicker, getCarried())) {
            // Checked here as well as in mayPlace, which vanilla also calls for slot highlighting and quiet probes.
            notice.set(NOTICE_RESEARCH);
            return;
        }
        if (slotIndex < CHIP_SLOT || slotIndex >= FIRST_INVENTORY) {
            super.clicked(slotIndex, buttonNum, input, clicker);
            return;
        }
        CellWorkbenchBlockEntity current = workbench;
        CellStorage storage = storage(clicker);
        if (current == null || storage == null
                || (input != ContainerInput.PICKUP && input != ContainerInput.QUICK_MOVE)) {
            return;
        }
        if (slotIndex >= FIRST_FILTER) {
            clickFilter(current, slotIndex - FIRST_FILTER, buttonNum, input, storage);
        } else if (input == ContainerInput.QUICK_MOVE) {
            takeToInventory(current, slotIndex, clicker, storage);
        } else if (getCarried().isEmpty()) {
            take(current, slotIndex, storage);
        } else {
            put(current, slotIndex, clicker, storage);
        }
    }

    /** Takes the part in {@code slotIndex} onto the cursor. */
    private void take(CellWorkbenchBlockEntity current, int slotIndex, CellStorage storage) {
        ItemStack part = part(slotIndex);
        if (part.isEmpty()) {
            return;
        }
        if (apply(current, without(slotIndex, storage), refusal(storage))) {
            setCarried(part);
        }
    }

    /** Shift-click: the part goes into the inventory, if it has room; else nothing changes. */
    private void takeToInventory(CellWorkbenchBlockEntity current, int slotIndex, Player clicker, CellStorage storage) {
        ItemStack part = part(slotIndex);
        if (part.isEmpty()) {
            return;
        }
        Inventory inventory = clicker.getInventory();
        if (inventory.getFreeSlot() < 0 && inventory.getSlotWithRemainingSpace(part) < 0) {
            notice.set(NOTICE_NO_ROOM);
            return;
        }
        if (apply(current, without(slotIndex, storage), refusal(storage)) && !inventory.add(part) && !part.isEmpty()) {
            // The room check above makes this rare (a slot filled meanwhile); the part is dropped, never lost.
            clicker.drop(part, false);
        }
    }

    /** Puts one carried part into {@code slotIndex}; a part there swaps onto the cursor (only from a single item). */
    private void put(CellWorkbenchBlockEntity current, int slotIndex, Player clicker, CellStorage storage) {
        ItemStack carried = getCarried();
        if (!WorkbenchGates.allowed(clicker, carried)) {
            notice.set(NOTICE_RESEARCH);
            return;
        }
        ItemStack old = part(slotIndex);
        if (!old.isEmpty() && carried.getCount() > 1) {
            return;
        }
        if (apply(current, with(slotIndex, carried.getItem(), storage), refusal(storage))) {
            carried.shrink(1);
            if (!old.isEmpty()) {
                setCarried(old);
            }
        }
    }

    /** The cell with the part in {@code slotIndex} taken out; {@code null} when refused. */
    private @Nullable ItemStack without(int slotIndex, CellStorage storage) {
        ItemStack cell = cell();
        if (slotIndex == CHIP_SLOT) {
            return CellAssembly.removeChip(cell, storage);
        }
        if (slotIndex < FIRST_ADDON) {
            return CellAssembly.takeMemory(cell, slotIndex - FIRST_MEMORY, storage);
        }
        return CellAssembly.takeAddon(cell, slotIndex - FIRST_ADDON, storage);
    }

    /** The cell with {@code part} in {@code slotIndex}; {@code null} when refused or the wrong kind of part. */
    private @Nullable ItemStack with(int slotIndex, Item part, CellStorage storage) {
        ItemStack cell = cell();
        if (slotIndex == CHIP_SLOT) {
            return part instanceof ControllerChipItem chip ? CellAssembly.setChip(cell, chip, storage) : null;
        }
        if (slotIndex < FIRST_ADDON) {
            return part instanceof MemoryModuleItem module
                    ? CellAssembly.putMemory(cell, slotIndex - FIRST_MEMORY, module, storage)
                    : null;
        }
        return part instanceof CellAddonItem addon
                ? CellAssembly.putAddon(cell, slotIndex - FIRST_ADDON, addon, storage)
                : null;
    }

    /** Why a change to the cell now would be refused, for the notice. */
    private int refusal(CellStorage storage) {
        return CellAssembly.editable(cell(), storage) ? NOTICE_NO_FIT : NOTICE_IN_USE;
    }

    /** Stores {@code result} in the workbench, or shows {@code refused} when there is none. */
    private boolean apply(CellWorkbenchBlockEntity current, @Nullable ItemStack result, int refused) {
        if (result == null) {
            notice.set(refused);
            return false;
        }
        current.setCell(result);
        notice.set(NOTICE_NONE);
        return true;
    }

    /**
     * Filter slots: a carried item names itself there (a filled bucket its fluid), an empty left click or a shift-click
     * clears the entry, an empty right click cycles the entry through its item's or fluid's tags and back.
     */
    private void clickFilter(CellWorkbenchBlockEntity current, int index, int button, ContainerInput input,
            CellStorage storage) {
        CellData data = data();
        if (data == null || !filterShown()) {
            return;
        }
        CellFilter filter = data.filter();
        ItemStack carried = getCarried();
        CellFilter changed;
        if (input == ContainerInput.QUICK_MOVE || (carried.isEmpty() && button == 0)) {
            changed = filter.with(index, null);
        } else if (carried.isEmpty()) {
            if (index >= filter.entries().size()) {
                return;
            }
            changed = filter.with(index, nextTag(filter.entries().get(index)));
        } else {
            changed = filter.with(index, CellFilter.Entry.of(keyOf(carried)));
        }
        apply(current, CellAssembly.setFilter(cell(), changed, storage), NOTICE_IN_USE);
    }

    /** What a carried stack names in a filter: a filled bucket its fluid, anything else itself. */
    public static StackKey keyOf(ItemStack stack) {
        return stack.getItem() instanceof BucketItem bucket && !bucket.content.isSame(Fluids.EMPTY)
                ? FluidKey.of(bucket.content)
                : ItemKey.of(stack.copyWithCount(1));
    }

    /** {@code entry} with the next tag of its item or fluid in id order, or without a tag after the last. */
    public static CellFilter.Entry nextTag(CellFilter.Entry entry) {
        List<Identifier> tags = new ArrayList<>();
        switch (entry.key()) {
            case ItemKey item -> item.item().builtInRegistryHolder().tags().map(TagKey::location).forEach(tags::add);
            case FluidKey fluid ->
                fluid.fluid().builtInRegistryHolder().tags().map(TagKey::location).forEach(tags::add);
        }
        tags.sort(Comparator.comparing(Identifier::toString));
        int at = entry.tag().map(tags::indexOf).orElse(-1);
        return new CellFilter.Entry(entry.key(),
                at + 1 < tags.size() ? Optional.of(tags.get(at + 1)) : Optional.empty());
    }

    /** What part slot {@code slotIndex} shows, from the cell's data; empty for none. */
    ItemStack part(int slotIndex) {
        ItemStack cell = cell();
        CellData data = StorageCellItem.data(cell);
        if (data == null || cell.getItem() instanceof CellCasingItem) {
            return ItemStack.EMPTY;
        }
        if (slotIndex == CHIP_SLOT) {
            return CellAssembly.chip(data.type());
        }
        if (slotIndex < FIRST_ADDON) {
            int i = slotIndex - FIRST_MEMORY;
            return i < data.memory().size() ? new ItemStack(data.memory().get(i)) : ItemStack.EMPTY;
        }
        if (slotIndex < FIRST_FILTER) {
            int i = slotIndex - FIRST_ADDON;
            return i < data.addons().size() ? new ItemStack(data.addons().get(i)) : ItemStack.EMPTY;
        }
        int i = slotIndex - FIRST_FILTER;
        if (i >= data.filter().entries().size()) {
            return ItemStack.EMPTY;
        }
        return switch (data.filter().entries().get(i).key()) {
            case ItemKey item -> item.toStack(1);
            case FluidKey fluid -> {
                Item bucket = fluid.fluid().getBucket();
                yield new ItemStack(new ItemStack(bucket).isEmpty() ? Items.BUCKET : bucket);
            }
        };
    }

    /** Whether part slot {@code slotIndex} is shown: the casing's slots, and any further part the cell still has. */
    boolean partActive(int slotIndex) {
        CellCasing casingKind = CellAssembly.casing(cell());
        if (casingKind == null) {
            return false;
        }
        if (slotIndex == CHIP_SLOT) {
            return true;
        }
        CellData data = data();
        if (data == null || cell().getItem() instanceof CellCasingItem) {
            return false;
        }
        if (slotIndex < FIRST_ADDON) {
            int i = slotIndex - FIRST_MEMORY;
            return i < casingKind.memorySlots() || i < data.memory().size();
        }
        if (slotIndex < FIRST_FILTER) {
            int i = slotIndex - FIRST_ADDON;
            return i < casingKind.addonSlots() || i < data.addons().size();
        }
        return filterShown();
    }

    /**
     * Shift-clicking the casing slot moves the cell to the inventory; shift-clicking an inventory stack puts a casing
     * into the empty casing slot or one part into the first free slot of its kind. Part slots are handled in
     * {@link #clicked}.
     */
    @Override
    public ItemStack quickMoveStack(Player clicker, int index) {
        if (index == CASING_SLOT) {
            Slot slot = slots.get(index);
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && moveItemStackTo(stack, FIRST_INVENTORY, slots.size(), true)) {
                slot.setChanged();
            }
            return ItemStack.EMPTY;
        }
        if (index < FIRST_INVENTORY || index >= slots.size()) {
            return ItemStack.EMPTY;
        }
        Slot slot = slots.get(index);
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (CellAssembly.casing(stack) != null) {
            if (cell().isEmpty() && moveItemStackTo(stack, CASING_SLOT, CASING_SLOT + 1, false)) {
                slot.setChanged();
            }
            return ItemStack.EMPTY;
        }
        CellWorkbenchBlockEntity current = workbench;
        CellStorage storage = storage(clicker);
        if (current == null || storage == null) {
            return ItemStack.EMPTY;
        }
        if (!WorkbenchGates.allowed(clicker, stack)) {
            notice.set(NOTICE_RESEARCH);
            return ItemStack.EMPTY;
        }
        int target = freeSlot(stack.getItem());
        if (target >= 0 && apply(current, with(target, stack.getItem(), storage), refusal(storage))) {
            slot.remove(1);
            slot.setChanged();
        }
        return ItemStack.EMPTY;
    }

    /** The slot a shift-clicked part goes to: the chip slot while empty, else the first free slot of its kind. */
    private int freeSlot(Item part) {
        CellData data = data();
        if (part instanceof ControllerChipItem) {
            return part(CHIP_SLOT).isEmpty() ? CHIP_SLOT : -1;
        }
        if (data == null) {
            return -1;
        }
        if (part instanceof MemoryModuleItem) {
            return FIRST_MEMORY + data.memory().size();
        }
        return part instanceof CellAddonItem ? FIRST_ADDON + data.addons().size() : -1;
    }

    @Override
    public boolean stillValid(Player viewer) {
        CellWorkbenchBlockEntity current = workbench;
        return current == null
                || (!current.isRemoved() && viewer.isWithinBlockInteractionRange(current.getBlockPos(), RANGE_BUFFER));
    }

    /** The casing slot: casings and cells, one at a time, research allowing. */
    private final class CasingSlot extends Slot {
        CasingSlot(Container container, int x, int y) {
            super(container, 0, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            if (CellAssembly.casing(stack) == null) {
                return false;
            }
            if (!WorkbenchGates.allowed(player, stack)) {
                notice.set(NOTICE_RESEARCH);
                return false;
            }
            return true;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public void setChanged() {
            super.setChanged();
            notice.set(NOTICE_NONE);
        }
    }

    /**
     * A slot that shows a part or filter entry of the cell. Vanilla never moves anything through it: {@link #clicked}
     * handles clicks, and {@link #set} ignores the slot sync, since the shown part comes from the casing slot's cell.
     */
    private final class PartSlot extends Slot {
        private final int menuIndex;

        PartSlot(int menuIndex, int x, int y) {
            super(new SimpleContainer(1), 0, x, y);
            this.menuIndex = menuIndex;
        }

        @Override
        public ItemStack getItem() {
            return part(menuIndex);
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

        @Override
        public boolean isActive() {
            return partActive(menuIndex);
        }
    }
}
