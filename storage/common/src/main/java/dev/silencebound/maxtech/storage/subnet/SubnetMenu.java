package dev.silencebound.maxtech.storage.subnet;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.priority.PriorityControl;
import dev.silencebound.maxtech.storage.priority.PriorityMenu;
import dev.silencebound.maxtech.storage.registry.ModMenus;
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
import dev.silencebound.maxtech.storage.label.LabelMenu;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import org.jspecify.annotations.Nullable;

/**
 * The subnet controller's screen: a mode button, nine filter ghost slots over the player's inventory, and the priority
 * tab. Buttons (the priority page's too, {@link PriorityControl}) are vanilla menu button clicks and the filter slots
 * take copies of the carried item, both checked against full access; mode and priority travel back as data slots and
 * the filter through vanilla slot sync. No payload of its own.
 */
public final class SubnetMenu extends AbstractContainerMenu implements PriorityMenu, LabelMenu {
    public static final int WIDTH = 176;
    public static final int HEIGHT = 172;
    public static final int FILTER_X = 8;
    public static final int FILTER_Y = 64;
    public static final int INVENTORY_X = 8;
    public static final int INVENTORY_Y = 90;
    public static final int CYCLE_MODE = 0;
    private static final double REACH = 8.0;

    private final @Nullable SubnetBlockEntity subnet;
    private final DataSlot mode;
    private final PriorityControl.Sync priority;

    private SubnetMenu(int containerId, Inventory inventory, @Nullable SubnetBlockEntity subnet, Container filter) {
        super(ModMenus.SUBNET.get(), containerId);
        this.subnet = subnet;
        for (int i = 0; i < SubnetFilter.SIZE; i++) {
            addSlot(new GhostSlot(filter, i, FILTER_X + i * 18, FILTER_Y));
        }
        addStandardInventorySlots(inventory, INVENTORY_X, INVENTORY_Y);
        mode = addDataSlot(subnet == null ? DataSlot.standalone() : new Mode(subnet));
        priority = PriorityControl.sync(subnet, this::addDataSlot);
    }

    /** The client's menu; data slots and slot sync fill it. */
    public static SubnetMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        buffer.readBlockPos();
        return new SubnetMenu(containerId, inventory, null, new SimpleContainer(SubnetFilter.SIZE));
    }

    /** The server's menu over {@code subnet}'s live settings. */
    public static SubnetMenu forSubnet(int containerId, Inventory inventory, SubnetBlockEntity subnet) {
        return new SubnetMenu(containerId, inventory, subnet, subnet.filter());
    }

    /** Opens {@code subnet}'s screen for {@code player}. */
    public static void open(ServerPlayer player, SubnetBlockEntity subnet, Component title) {
        Platform.get().menus().open(player,
                new SimpleMenuProvider((id, inventory, opener) -> forSubnet(id, inventory, subnet), title),
                buffer -> buffer.writeBlockPos(subnet.getBlockPos()));
    }

    public SubnetMode mode() {
        return SubnetMode.byId(mode.get());
    }

    @Override
    public int priority() {
        return PriorityControl.read(priority);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        SubnetBlockEntity current = subnet;
        if (current == null || !NetworkAccess.canConfigure(player, current)) {
            return false;
        }
        if (id == CYCLE_MODE) {
            current.setMode(current.mode().next());
            return true;
        }
        return false;
    }

    @Override
    public boolean applyPriority(Player player, int priority) {
        SubnetBlockEntity current = subnet;
        if (current == null || !NetworkAccess.canConfigure(player, current)) {
            return false;
        }
        current.setPriority(priority);
        return true;
    }

    /** A filter slot takes a copy of the carried item, or clears with an empty hand; nothing real moves. */
    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput containerInput, Player player) {
        if (slotIndex >= 0 && slotIndex < SubnetFilter.SIZE) {
            SubnetBlockEntity current = subnet;
            if (current != null && NetworkAccess.canConfigure(player, current)
                    && containerInput == ContainerInput.PICKUP) {
                ItemStack carried = getCarried();
                current.filter().setItem(slotIndex, carried.isEmpty() ? ItemStack.EMPTY : carried.copyWithCount(1));
            }
            return;
        }
        super.clicked(slotIndex, buttonNum, containerInput, player);
    }

    /** A shift-clicked inventory stack goes into the first empty filter slot, as a copy. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        SubnetBlockEntity current = subnet;
        if (index >= SubnetFilter.SIZE && current != null && NetworkAccess.canConfigure(player, current)) {
            ItemStack stack = slots.get(index).getItem();
            SubnetFilter filter = current.filter();
            for (int i = 0; i < SubnetFilter.SIZE && !stack.isEmpty(); i++) {
                if (filter.getItem(i).isEmpty()) {
                    filter.setItem(i, stack.copyWithCount(1));
                    break;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        SubnetBlockEntity current = subnet;
        return current == null
                || (!current.isRemoved() && player.position().closerThan(current.getBlockPos().getCenter(), REACH)
                        && NetworkAccess.canConfigure(player, current));
    }

    /** The mode read live from the controller; server side. */
    private static final class Mode extends DataSlot {
        private final SubnetBlockEntity subnet;

        Mode(SubnetBlockEntity subnet) {
            this.subnet = subnet;
        }

        @Override
        public int get() {
            return subnet.mode().id();
        }

        @Override
        public void set(int value) {
            // Server side the controller is the truth; clicks change it.
        }
    }

    /** A filter slot: shows its item, and nothing real goes in or out. */
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

    @Override
    public @Nullable NetworkDevice labelDevice() {
        return subnet;
    }
}
