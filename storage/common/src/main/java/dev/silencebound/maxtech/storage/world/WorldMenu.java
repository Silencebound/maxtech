package dev.silencebound.maxtech.storage.world;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.registry.ModMenus;
import java.util.function.IntSupplier;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import dev.silencebound.maxtech.storage.label.LabelMenu;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import org.jspecify.annotations.Nullable;

/**
 * The settings GUI of every world device: nine ghost filter slots, the mode and (placer) action buttons and, for a
 * device that keeps one, the tool slot. Filter slots never hold real items: a click with a stack names one of it, an
 * empty-hand click clears the slot, a right-click with an empty hand moves a named slot through its item's tags and
 * back to the exact item, and a shift-clicked inventory stack is named in the first free slot. The tool slot is a real
 * slot for one tool or enchanted book. The mode, the action, the research state and each slot's tag travel as data
 * slots; the device's kind travels in the opening data.
 */
public final class WorldMenu extends AbstractContainerMenu implements LabelMenu {
    public static final int WIDTH = 176;
    public static final int HEIGHT = 166;
    public static final int FILTER_X = 62;
    public static final int FILTER_Y = 22;
    public static final int TOOL_X = 134;
    public static final int TOOL_Y = 40;
    public static final int INVENTORY_X = 8;
    public static final int INVENTORY_Y = 84;
    /** The button id {@link #clickMenuButton} takes for the mode. */
    public static final int CYCLE_MODE = 0;
    /** The button id {@link #clickMenuButton} takes for opening what the device holds. */
    public static final int OPEN_CONTENTS = 1;
    /** The button id {@link #clickMenuButton} takes for the action (B09 item 25). */
    public static final int CYCLE_ACTION = 2;

    private final @Nullable WorldDevice device;
    private final WorldKind kind;
    private final Container filter;
    private final @Nullable Container tool;
    private final DataSlot mode;
    private final DataSlot action;
    private final DataSlot researched;
    private final DataSlot[] tags = new DataSlot[WorldFilter.SIZE];
    private final int firstInventorySlot;

    private WorldMenu(int containerId, Inventory inventory, @Nullable WorldDevice device, WorldKind kind,
            Container filter, @Nullable Container tool, DataSlot mode, DataSlot action, DataSlot researched,
            DataSlot[] tags) {
        super(ModMenus.WORLD.get(), containerId);
        this.device = device;
        this.kind = kind;
        this.filter = filter;
        this.tool = tool;
        this.mode = mode;
        this.action = action;
        this.researched = researched;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                addSlot(new GhostSlot(filter, row * 3 + column, FILTER_X + column * 18, FILTER_Y + row * 18));
            }
        }
        if (tool != null) {
            addSlot(new ToolSlot(tool, TOOL_X, TOOL_Y));
        }
        firstInventorySlot = slots.size();
        addStandardInventorySlots(inventory, INVENTORY_X, INVENTORY_Y);
        addDataSlot(mode);
        addDataSlot(action);
        addDataSlot(researched);
        for (int i = 0; i < tags.length; i++) {
            this.tags[i] = tags[i];
            addDataSlot(tags[i]);
        }
    }

    /** The server's menu over {@code device}'s own filter and tool. */
    public static WorldMenu forDevice(int containerId, Inventory inventory, WorldDevice device) {
        DataSlot[] tags = new DataSlot[WorldFilter.SIZE];
        for (int i = 0; i < tags.length; i++) {
            int slot = i;
            tags[i] = read(() -> device.filter().tagIndex(slot));
        }
        return new WorldMenu(containerId, inventory, device, device.kind(), device.filter(), device.tool(),
                read(device::mode), read(device::action), read(() -> device.researched() ? 1 : 0), tags);
    }

    private static DataSlot read(IntSupplier value) {
        return new DataSlot() {
            @Override
            public int get() {
                return value.getAsInt();
            }

            @Override
            public void set(int ignored) {
            }
        };
    }

    /** The client's menu; slot sync fills the filter and tool, data slots the rest. */
    public static WorldMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        buffer.readBlockPos();
        WorldKind kind = WorldKind.byId(buffer.readVarInt());
        DataSlot[] tags = new DataSlot[WorldFilter.SIZE];
        for (int i = 0; i < tags.length; i++) {
            tags[i] = DataSlot.standalone();
        }
        return new WorldMenu(containerId, inventory, null, kind, new SimpleContainer(WorldFilter.SIZE),
                kind.hasTool() ? new SimpleContainer(1) : null, DataSlot.standalone(), DataSlot.standalone(),
                DataSlot.standalone(), tags);
    }

    /** Opens {@code device}'s settings for {@code player} if they may configure it ({@link NetworkAccess}). */
    public static void open(ServerPlayer player, WorldDevice device, BlockPos pos, Component title) {
        if (!NetworkAccess.canConfigure(player, device.holder())) {
            return;
        }
        Platform.get().menus().open(player,
                new SimpleMenuProvider((id, inventory, opener) -> forDevice(id, inventory, device), title), buffer -> {
                    buffer.writeBlockPos(pos);
                    buffer.writeVarInt(device.kind().id());
                });
    }

    public WorldKind kind() {
        return kind;
    }

    /** The mode as synced. */
    public int mode() {
        return mode.get();
    }

    /** The action as synced (B09 item 25). */
    public int action() {
        return action.get();
    }

    /** Whether the owner's team has the device's research, as synced. */
    public boolean researched() {
        return researched.get() != 0;
    }

    /**
     * The tag slot {@code slot} matches by, as an index into {@link WorldFilter#tagsOf}, or {@link WorldFilter#EXACT}.
     */
    public int tagIndex(int slot) {
        // Data slots travel as 16 bits; -1 must come back as -1.
        return (short) tags[slot].get();
    }

    /** Whether the device keeps a tool, i.e. the menu has a tool slot. */
    public boolean hasTool() {
        return tool != null;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        WorldDevice current = device;
        if (id == CYCLE_MODE && current != null && NetworkAccess.canConfigure(player, current.holder())) {
            current.setMode(current.mode() + 1);
            return true;
        }
        if (id == CYCLE_ACTION && current != null && current.kind().actionCount() > 1
                && NetworkAccess.canConfigure(player, current.holder())) {
            current.setAction(current.action() + 1);
            return true;
        }
        if (id == OPEN_CONTENTS && current != null && player instanceof ServerPlayer serverPlayer
                && NetworkAccess.canConfigure(player, current.holder())) {
            return current.openContents(serverPlayer);
        }
        return false;
    }

    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput containerInput, Player player) {
        if (slotIndex >= 0 && slotIndex < WorldFilter.SIZE) {
            WorldDevice current = device;
            if (current == null || !NetworkAccess.canConfigure(player, current.holder())) {
                return;
            }
            ItemStack carried = getCarried();
            if (containerInput == ContainerInput.PICKUP && buttonNum == 1 && carried.isEmpty()) {
                current.filter().cycleTag(slotIndex);
            } else if (containerInput == ContainerInput.PICKUP) {
                filter.setItem(slotIndex, carried.isEmpty() ? ItemStack.EMPTY : carried.copyWithCount(1));
            } else if (containerInput == ContainerInput.QUICK_MOVE) {
                filter.setItem(slotIndex, ItemStack.EMPTY);
            }
            return;
        }
        if (tool != null && slotIndex == WorldFilter.SIZE
                && (device == null || !NetworkAccess.canConfigure(player, device.holder()))) {
            return;
        }
        super.clicked(slotIndex, buttonNum, containerInput, player);
    }

    /**
     * An inventory stack goes into an empty tool slot when it fits there, else it is named in the first free filter
     * slot; the tool slot's stack goes back to the inventory.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < WorldFilter.SIZE) {
            return ItemStack.EMPTY;
        }
        Slot slot = slots.get(index);
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (index < firstInventorySlot) {
            moveItemStackTo(stack, firstInventorySlot, slots.size(), true);
            slot.setChanged();
            return ItemStack.EMPTY;
        }
        Container toolSlot = tool;
        if (toolSlot != null && toolSlot.isEmpty() && fitsTool(stack)) {
            moveItemStackTo(stack, WorldFilter.SIZE, WorldFilter.SIZE + 1, false);
            slot.setChanged();
            return ItemStack.EMPTY;
        }
        int free = -1;
        for (int i = 0; i < WorldFilter.SIZE; i++) {
            ItemStack named = filter.getItem(i);
            if (ItemStack.isSameItemSameComponents(named, stack)) {
                return ItemStack.EMPTY;
            }
            if (named.isEmpty() && free < 0) {
                free = i;
            }
        }
        if (free >= 0) {
            filter.setItem(free, stack.copyWithCount(1));
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        WorldDevice current = device;
        return current == null || (current.stillValid(player) && NetworkAccess.canConfigure(player, current.holder()));
    }

    /** What a tool slot takes: a damageable tool or an enchanted book. */
    public static boolean fitsTool(ItemStack stack) {
        return stack.isDamageableItem() || stack.is(Items.ENCHANTED_BOOK);
    }

    /** A ghost slot: it shows its item but cannot be taken from or placed into the vanilla way. */
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

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }

    /** The device's tool: one tool or enchanted book. */
    private static final class ToolSlot extends Slot {
        ToolSlot(Container container, int x, int y) {
            super(container, 0, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return fitsTool(stack);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }

    @Override
    public @Nullable NetworkDevice labelDevice() {
        return LabelMenu.device(device);
    }
}
