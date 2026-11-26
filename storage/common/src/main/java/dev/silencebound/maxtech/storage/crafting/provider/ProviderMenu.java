package dev.silencebound.maxtech.storage.crafting.provider;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
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
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import dev.silencebound.maxtech.storage.label.LabelMenu;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * The provider's GUI: up to four rows of pattern slots (rows beyond the working slots are hidden), the blocking toggle
 * (menu button {@link #TOGGLE_BLOCKING}), the lock mode (buttons {@link #LOCK_NEXT} and {@link #LOCK_PREVIOUS}, B09
 * item 26) and the crafts in flight. Real slots: patterns go in and out the vanilla way, shift-click included. Every
 * change checks access.
 */
public final class ProviderMenu extends AbstractContainerMenu implements LabelMenu {
    public static final int WIDTH = 176;
    public static final int HEIGHT = 206;
    public static final int SLOTS_X = 8;
    public static final int SLOTS_Y = 22;
    public static final int BUTTON_Y = 96;
    public static final int INVENTORY_X = 8;
    public static final int INVENTORY_Y = 124;
    public static final int TOGGLE_BLOCKING = 0;
    /** Steps the lock mode forward (B09 item 26). */
    public static final int LOCK_NEXT = 1;
    /** Steps the lock mode back. */
    public static final int LOCK_PREVIOUS = 2;
    private static final int DATA_USABLE = 0;
    private static final int DATA_BLOCKING = 1;
    private static final int DATA_IN_FLIGHT = 2;
    private static final int DATA_CLAIM = 3;
    private static final int DATA_CRAFTER = 4;
    /** The lock mode's id in the low four bits, {@link #LOCK_HOLDS} while it holds batches. */
    private static final int DATA_LOCK = 5;
    private static final int DATA_COUNT = 6;
    private static final int LOCK_ID_MASK = 0xF;
    private static final int LOCK_HOLDS = 1 << 4;

    private final @Nullable ProviderDevice device;
    private final ContainerData data;
    /** Replaces the device's reach check for a menu opened from the pattern overview (M7C item 1). */
    private final @Nullable Predicate<Player> remoteValid;

    private ProviderMenu(int containerId, Inventory inventory, @Nullable ProviderDevice device, Container slots,
            ContainerData data, @Nullable Predicate<Player> remoteValid) {
        super(ModMenus.PATTERN_PROVIDER.get(), containerId);
        this.device = device;
        this.data = data;
        this.remoteValid = remoteValid;
        for (int i = 0; i < ProviderLogic.MAX_SLOTS; i++) {
            addSlot(new PatternSlot(slots, i, SLOTS_X + (i % 9) * 18, SLOTS_Y + (i / 9) * 18));
        }
        addStandardInventorySlots(inventory, INVENTORY_X, INVENTORY_Y);
        addDataSlots(data);
    }

    /** The server's menu over {@code device}'s own slots. */
    public static ProviderMenu forDevice(int containerId, Inventory inventory, ProviderDevice device) {
        return forDevice(containerId, inventory, device, null);
    }

    private static ProviderMenu forDevice(int containerId, Inventory inventory, ProviderDevice device,
            @Nullable Predicate<Player> remoteValid) {
        ProviderLogic logic = device.logic();
        logic.refresh();
        ContainerData data = new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case DATA_USABLE -> logic.usableSlots();
                    case DATA_BLOCKING -> logic.blocking() ? 1 : 0;
                    case DATA_CLAIM -> logic.claimBlocked() ? 1 : 0;
                    case DATA_CRAFTER -> logic.crafter() ? 1 : 0;
                    case DATA_LOCK -> logic.lockMode().id() | (logic.locked() != null ? LOCK_HOLDS : 0);
                    default -> Math.min(Short.MAX_VALUE, logic.inFlight());
                };
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return DATA_COUNT;
            }
        };
        return new ProviderMenu(containerId, inventory, device, logic.slots(), data, remoteValid);
    }

    /** The client's menu; slot sync fills the slots, data slots the rest. */
    public static ProviderMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        buffer.readBlockPos();
        SimpleContainerData data = new SimpleContainerData(DATA_COUNT);
        data.set(DATA_USABLE, ProviderLogic.BASE_SLOTS);
        data.set(DATA_BLOCKING, 1);
        return new ProviderMenu(containerId, inventory, null, new SimpleContainer(ProviderLogic.MAX_SLOTS), data, null);
    }

    /** Opens {@code device}'s GUI for {@code player}. */
    public static void open(ServerPlayer player, ProviderDevice device, BlockPos pos, Component title) {
        Platform.get().menus().open(player,
                new SimpleMenuProvider((id, inventory, opener) -> forDevice(id, inventory, device), title),
                buffer -> buffer.writeBlockPos(pos));
    }

    /**
     * Opens {@code device}'s GUI for {@code player} from afar, from the pattern overview (M7C item 1): the menu stays
     * open while {@code valid} holds instead of while the player is within reach.
     */
    public static void openRemote(ServerPlayer player, ProviderDevice device, BlockPos pos, Component title,
            Predicate<Player> valid) {
        Platform.get().menus().open(player,
                new SimpleMenuProvider((id, inventory, opener) -> forDevice(id, inventory, device, valid), title),
                buffer -> buffer.writeBlockPos(pos));
    }

    /** Slots that work, as synced. */
    public int usableSlots() {
        return Math.max(0, Math.min(ProviderLogic.MAX_SLOTS, data.get(DATA_USABLE)));
    }

    public boolean blocking() {
        return data.get(DATA_BLOCKING) != 0;
    }

    public int inFlight() {
        return data.get(DATA_IN_FLIGHT);
    }

    /**
     * Whether the menu is a crafter's (B09 item 29): crafting patterns only, no blocking mode, no research slots; as
     * synced.
     */
    public boolean crafter() {
        return data.get(DATA_CRAFTER) != 0;
    }

    /** The lock mode, as synced. */
    public LockMode lockMode() {
        return LockMode.byId(data.get(DATA_LOCK) & LOCK_ID_MASK);
    }

    /** Whether the lock holds batches now, as synced. */
    public boolean lockHolds() {
        return (data.get(DATA_LOCK) & LOCK_HOLDS) != 0;
    }

    /** Whether a land claim keeps the provider from the machine it faces, as synced. */
    public boolean claimBlocked() {
        return data.get(DATA_CLAIM) != 0;
    }

    private boolean canChange(Player player) {
        ProviderDevice current = device;
        return current == null || NetworkAccess.canConfigure(player, current);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        ProviderDevice current = device;
        if (current == null || !canChange(player)) {
            return false;
        }
        ProviderLogic logic = current.logic();
        switch (id) {
            case TOGGLE_BLOCKING -> {
                if (logic.crafter()) {
                    return false;
                }
                logic.setBlocking(!logic.blocking());
            }
            case LOCK_NEXT -> logic.setLockMode(logic.lockMode().next());
            case LOCK_PREVIOUS -> logic.setLockMode(logic.lockMode().previous());
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        ItemStack stack = slot.getItem();
        if (stack.isEmpty() || !canChange(player)) {
            return ItemStack.EMPTY;
        }
        ItemStack copy = stack.copy();
        int inventoryStart = ProviderLogic.MAX_SLOTS;
        if (index < inventoryStart) {
            if (!moveItemStackTo(stack, inventoryStart, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!ProviderLogic.accepts(stack, crafter()) || !moveItemStackTo(stack, 0, usableSlots(), false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        ProviderDevice current = device;
        Predicate<Player> remote = remoteValid;
        if (current != null && remote != null) {
            return !current.isRemoved() && remote.test(player);
        }
        return current == null || current.stillValid(player);
    }

    /** A pattern slot: encoded patterns only, one each, and only while the slot works. */
    private final class PatternSlot extends Slot {
        PatternSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean isActive() {
            return getContainerSlot() < usableSlots() || hasItem();
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return getContainerSlot() < usableSlots() && ProviderLogic.accepts(stack, crafter());
        }

        @Override
        public boolean mayPickup(Player player) {
            return canChange(player);
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
