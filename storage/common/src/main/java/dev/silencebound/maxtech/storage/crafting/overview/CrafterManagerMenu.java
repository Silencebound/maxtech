package dev.silencebound.maxtech.storage.crafting.overview;

import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderLogic;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModMenus;
import dev.silencebound.maxtech.storage.terminal.TerminalSource;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The crafter manager's menu (B09 item 23): the network's provider list (sent as {@link CrafterManager.Providers}) and
 * the pattern slots of the one provider picked in it, plus the player's inventory. The pattern slots are real slots
 * over the picked provider's own container, so patterns move between it, the inventory and the cursor through vanilla's
 * click paths only, and a pattern carried on the cursor while another provider is picked lands there: the menu stays
 * the same, so the cursor keeps it. Only the picked provider's slots are synced, by vanilla's slot sync; the list is
 * sent again when the network's pattern registry or labels or the viewer's access level change, at most every
 * {@link #LIST_INTERVAL} ticks. The menu stays open only while the terminal that opened it is valid for the player (in
 * reach, in wireless range, still held), as the terminal's own menu would. Picking needs full access to the provider; a
 * picked provider that leaves the network (broken, unloaded) or that the player loses access to is dropped, and its
 * slots show empty and refuse every click from then on. Server side state is null on the client.
 */
public final class CrafterManagerMenu extends AbstractContainerMenu {
    public static final int WIDTH = 300;
    public static final int HEIGHT = 222;
    public static final int MARGIN = 8;
    public static final int LIST_WIDTH = 112;
    public static final int SLOTS_X = 130;
    public static final int SLOTS_Y = 36;
    public static final int INVENTORY_X = 130;
    public static final int INVENTORY_Y = 140;
    /** Fewest ticks between two list sends while the registry keeps changing. */
    public static final int LIST_INTERVAL = 5;
    private static final int DATA_USABLE = 0;
    private static final int DATA_COUNT = 1;

    private final @Nullable NetworkState network;
    /**
     * What opened the manager (a terminal block or part, a wireless terminal); the menu lives only while it is valid.
     */
    private final @Nullable TerminalSource source;
    private final @Nullable Player player;
    private final @Nullable Consumer<CustomPacketPayload> sender;
    private final ContainerData data;
    private @Nullable ProviderLogic selected;
    private boolean listDirty = true;
    private long sentGeneration = Long.MIN_VALUE;
    private long sentLabels = Long.MIN_VALUE;
    /** The viewer's access level on the network when the list was sent; a change re-sends the editable flags. */
    private @Nullable AccessLevel sentAccess;
    private long lastSent = Long.MIN_VALUE / 2;

    private CrafterManagerMenu(int containerId, Inventory inventory, @Nullable NetworkState network,
            @Nullable TerminalSource source, @Nullable Consumer<CustomPacketPayload> sender, Container window,
            ContainerData data) {
        super(ModMenus.CRAFTER_MANAGER.get(), containerId);
        this.network = network;
        this.source = source;
        this.player = network == null ? null : inventory.player;
        this.sender = sender;
        this.data = data;
        for (int i = 0; i < ProviderLogic.MAX_SLOTS; i++) {
            addSlot(new PatternSlot(window, i, SLOTS_X + (i % 9) * 18, SLOTS_Y + (i / 9) * 18));
        }
        addStandardInventorySlots(inventory, INVENTORY_X, INVENTORY_Y);
        addDataSlots(data);
    }

    /**
     * The server's menu over {@code network}, opened through {@code source} (the terminal whose pattern overview opened
     * it), sending its list through {@code sender}: the player's connection normally, a recorder in tests. The menu
     * stays open only while {@code source} is valid for the player (in reach, in wireless range, still held).
     */
    public static CrafterManagerMenu server(int containerId, Inventory inventory, NetworkState network,
            TerminalSource source, Consumer<CustomPacketPayload> sender) {
        Holder holder = new Holder();
        ContainerData data = new ContainerData() {
            @Override
            public int get(int index) {
                CrafterManagerMenu menu = holder.menu;
                ProviderLogic current = menu == null ? null : menu.live();
                return current == null ? 0 : current.usableSlots();
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return DATA_COUNT;
            }
        };
        Window window = new Window(holder, inventory.player);
        CrafterManagerMenu menu = new CrafterManagerMenu(containerId, inventory, network, source, sender, window, data);
        holder.menu = menu;
        return menu;
    }

    /** The client's menu; slot sync fills the slots, the list comes as a payload. */
    public static CrafterManagerMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        return new CrafterManagerMenu(containerId, inventory, null, null, null,
                new SimpleContainer(ProviderLogic.MAX_SLOTS), new SimpleContainerData(DATA_COUNT));
    }

    /** Working slots of the picked provider, as synced; 0 with none picked. */
    public int usableSlots() {
        return Math.max(0, Math.min(ProviderLogic.MAX_SLOTS, data.get(DATA_USABLE)));
    }

    /** The provider whose slots the menu shows; null with none picked. Server side. */
    public @Nullable ProviderLogic selected() {
        return selected;
    }

    /**
     * Shows the slots of the provider at {@code pos} facing {@code facing}. False, keeping the current pick, for a
     * provider not on this menu's network or one {@code viewer} lacks full access to. Server thread.
     */
    public boolean select(Player viewer, BlockPos pos, Direction facing) {
        NetworkState current = network;
        if (current == null || current.isClosed()) {
            return false;
        }
        ProviderLogic found = CrafterManager.find(current, pos, facing);
        if (found == null || !NetworkAccess.canConfigure(viewer, found.device())) {
            return false;
        }
        if (found != selected) {
            selected = found;
            sendListNow();
        }
        return true;
    }

    /** The picked provider while it is still on this menu's network; null otherwise. */
    @SuppressWarnings("ReferenceEquality") // a network is one object
    private @Nullable ProviderLogic live() {
        ProviderLogic current = selected;
        NetworkState net = network;
        if (current == null || net == null || current.device().isRemoved()
                || current.device().deviceLink().network() != net) {
            return null;
        }
        return current;
    }

    /** Whether the server's {@code viewer} may change the picked provider now; the client's menu always may. */
    private boolean mayChange(Player viewer) {
        if (network == null) {
            return true;
        }
        ProviderLogic current = live();
        return current != null && stillValid(viewer) && NetworkAccess.canConfigure(viewer, current.device());
    }

    @Override
    public void broadcastChanges() {
        NetworkState net = network;
        Player viewer = player;
        if (net != null && viewer != null) {
            if (selected != null && !mayChange(viewer)) {
                selected = null;
                listDirty = true;
            }
            long generation = net.patterns().generation();
            long labels = net.labels().revision();
            if (generation != sentGeneration || labels != sentLabels || net.accessLevel(viewer) != sentAccess) {
                listDirty = true;
            }
            long now = net.level().getGameTime();
            if (listDirty && now - lastSent >= LIST_INTERVAL) {
                sendList(net, viewer, generation, labels, now);
            }
        }
        super.broadcastChanges();
    }

    /** Sends the list and remembers what it was built from. */
    private void sendList(NetworkState net, Player viewer, long generation, long labels, long now) {
        Consumer<CustomPacketPayload> out = sender;
        if (out == null) {
            return;
        }
        listDirty = false;
        sentGeneration = generation;
        sentLabels = labels;
        sentAccess = net.accessLevel(viewer);
        lastSent = now;
        out.accept(CrafterManager.providers(containerId, net, viewer, selected, CrafterManager.MAX_BYTES));
    }

    /** Sends the list at once, whatever the interval; for the first send, a pick and tests. */
    public void sendListNow() {
        NetworkState net = network;
        Player viewer = player;
        if (net != null && viewer != null) {
            sendList(net, viewer, net.patterns().generation(), net.labels().revision(), net.level().getGameTime());
        }
    }

    @Override
    public ItemStack quickMoveStack(Player clicker, int index) {
        Slot slot = slots.get(index);
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int inventoryStart = ProviderLogic.MAX_SLOTS;
        if (index < inventoryStart) {
            if (!slot.mayPickup(clicker)) {
                return ItemStack.EMPTY;
            }
        } else if (PatternItem.pattern(stack) == null || !mayChange(clicker)) {
            return ItemStack.EMPTY;
        }
        ItemStack copy = stack.copy();
        if (index < inventoryStart) {
            if (!moveItemStackTo(stack, inventoryStart, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, 0, usableSlots(), false)) {
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
    public boolean stillValid(Player viewer) {
        NetworkState net = network;
        TerminalSource from = source;
        return net == null || from == null
                || (from.stillValid(viewer) && from.canAccess(viewer) && CrafterManager.mayOpen(viewer, net));
    }

    /** Lets the window container reach the menu built after it. */
    private static final class Holder {
        @Nullable
        CrafterManagerMenu menu;
    }

    /**
     * The server's view of the picked provider's pattern slots: reads and writes go to that provider's own container
     * while it is on the network; without one it is empty, and a stack set into it (which the slots' checks never
     * allow) goes back to the player instead of being lost.
     */
    private static final class Window implements Container {
        private final Holder holder;
        private final Player owner;

        Window(Holder holder, Player owner) {
            this.holder = holder;
            this.owner = owner;
        }

        private @Nullable Container target() {
            CrafterManagerMenu menu = holder.menu;
            ProviderLogic current = menu == null ? null : menu.live();
            return current == null ? null : current.slots();
        }

        @Override
        public int getContainerSize() {
            return ProviderLogic.MAX_SLOTS;
        }

        @Override
        public boolean isEmpty() {
            Container target = target();
            return target == null || target.isEmpty();
        }

        @Override
        public ItemStack getItem(int slot) {
            Container target = target();
            return target == null ? ItemStack.EMPTY : target.getItem(slot);
        }

        @Override
        public ItemStack removeItem(int slot, int count) {
            Container target = target();
            return target == null ? ItemStack.EMPTY : target.removeItem(slot, count);
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            Container target = target();
            return target == null ? ItemStack.EMPTY : target.removeItemNoUpdate(slot);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            Container target = target();
            if (target != null) {
                target.setItem(slot, stack);
            } else if (!stack.isEmpty()) {
                owner.getInventory().placeItemBackInInventory(stack);
            }
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            Container target = target();
            return target != null && target.canPlaceItem(slot, stack);
        }

        @Override
        public void setChanged() {
            Container target = target();
            if (target != null) {
                target.setChanged();
            }
        }

        @Override
        public boolean stillValid(Player viewer) {
            return true;
        }

        /** Never empties the provider: the menu closing must not touch its patterns. */
        @Override
        public void clearContent() {
        }
    }

    /** A pattern slot of the picked provider: encoded patterns only, one each, only while the slot works. */
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
            if (getContainerSlot() >= usableSlots() || PatternItem.pattern(stack) == null) {
                return false;
            }
            Player viewer = player;
            return viewer == null ? network == null : mayChange(viewer);
        }

        @Override
        public boolean mayPickup(Player viewer) {
            return mayChange(viewer);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }
}
