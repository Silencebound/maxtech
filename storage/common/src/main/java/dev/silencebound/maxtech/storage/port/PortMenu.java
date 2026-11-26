package dev.silencebound.maxtech.storage.port;

import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.devicetab.DeviceTabLayout;
import dev.silencebound.maxtech.storage.devicetab.UpgradeSlot;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.lib.platform.Platform;
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
import net.minecraft.world.item.ItemStack;
import dev.silencebound.maxtech.storage.label.LabelMenu;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.priority.PriorityControl;
import dev.silencebound.maxtech.storage.priority.PriorityMenu;
import dev.silencebound.maxtech.storage.redstone.RedstoneSync;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The port's GUI: nine ghost filter slots, the speed card slots ({@code ports.speedCards.slots}, after the player's
 * inventory in slot order, shown in the screen's upgrades flyout) and the mode. Filter slots never hold real items:
 * clicking one with a stack puts a copy of one item there, a left click with an empty hand clears it, a right click
 * with an empty hand steps its match mode (B09 item 18), and shift-clicking an inventory stack names it in the first
 * free slot; nothing leaves the player's inventory. Each slot's match mode and tag and its keep amount (B09 item 22)
 * travel as data slots, as does the export order; the keep amount comes back in a {@link PortPayloads.Keep}. Card slots
 * are real slots that take speed cards and one crafting card; a shift-clicked card goes there instead of into the
 * filter. The mode, the storage access, the priority and the port's status (research wait, land claim, loop refused)
 * and its redstone mode and state travel as data slots; menu buttons cycle the mode, the access (storage mode) and the
 * redstone mode, and the shared priority payload sets the priority.
 */
public final class PortMenu extends AbstractContainerMenu implements LabelMenu, PriorityMenu {
    public static final int WIDTH = 176;
    public static final int HEIGHT = 166;
    public static final int FILTER_X = 62;
    public static final int FILTER_Y = 22;
    public static final int INVENTORY_X = 8;
    public static final int INVENTORY_Y = 84;
    /** The first card slot's index: after the nine filter slots and the 36 inventory slots. */
    public static final int FIRST_CARD_SLOT = PortFilter.SIZE + 36;
    /** The button id {@link #clickMenuButton} takes for the mode. */
    public static final int CYCLE_MODE = 0;
    /** The button id {@link #clickMenuButton} takes for the storage access. */
    public static final int CYCLE_ACCESS = 1;
    /** The button id {@link #clickMenuButton} takes for the next redstone mode. */
    public static final int CYCLE_REDSTONE = 2;
    /** The button id {@link #clickMenuButton} takes for the previous redstone mode. */
    public static final int REDSTONE_BACK = 3;
    /** The button id {@link #clickMenuButton} takes for the export order. */
    public static final int CYCLE_ORDER = 4;
    /** Button ids from this one plus a filter slot step that slot's match mode forward. */
    public static final int MATCH_NEXT = 10;
    /** Button ids from this one plus a filter slot step that slot's match mode back. */
    public static final int MATCH_BACK = 20;
    /** The status data slot's value for a port a land claim blocks; 1 is a research wait, 0 neither. */
    private static final int CLAIM_BLOCKED = 2;
    /** The status data slot's value for a storage bus that refuses its target because it would loop. */
    private static final int LOOP_REFUSED = 3;
    /** The status data slot's value for a storage bus that faces nothing it can mount. */
    private static final int NOTHING_MOUNTED = 4;

    private final @Nullable PortDevice port;
    private final Container filter;
    private final DataSlot mode;
    private final DataSlot research;
    private final DataSlot access;
    private final DataSlot redstone;
    private final DataSlot order;
    private final DataSlot[] matches = new DataSlot[PortFilter.SIZE];
    private final DataSlot[] keeps = new DataSlot[PortFilter.SIZE];
    private final PriorityControl.Sync priority;
    private final PortTier tier;
    private final int cardSlots;
    private final UpgradeSlot.Shown cardsShown = new UpgradeSlot.Shown();

    private PortMenu(int containerId, Inventory inventory, @Nullable PortDevice port, Container filter, DataSlot mode,
            Container cards, int cardSlots, DataSlot research, DataSlot access, PortTier tier) {
        super(ModMenus.PORT.get(), containerId);
        this.port = port;
        this.filter = filter;
        this.mode = mode;
        this.research = research;
        this.access = access;
        this.tier = tier;
        this.cardSlots = cardSlots;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                addSlot(new FilterSlot(filter, row * 3 + column, FILTER_X + column * 18, FILTER_Y + row * 18));
            }
        }
        addStandardInventorySlots(inventory, INVENTORY_X, INVENTORY_Y);
        // The card slots show in the screen's upgrades flyout (B09 item 21b).
        for (int i = 0; i < cardSlots; i++) {
            addSlot(new UpgradeSlot(cards, i, DeviceTabLayout.slotX(WIDTH, i), DeviceTabLayout.slotY(i), cardsShown));
        }
        addDataSlot(mode);
        addDataSlot(research);
        addDataSlot(access);
        redstone = addDataSlot(RedstoneSync.slot(port));
        order = addDataSlot(port == null ? DataSlot.standalone() : new Live(port, Live.ORDER, 0));
        for (int i = 0; i < PortFilter.SIZE; i++) {
            matches[i] = addDataSlot(port == null ? DataSlot.standalone() : new Live(port, Live.MATCH, i));
            keeps[i] = addDataSlot(port == null ? DataSlot.standalone() : new Live(port, Live.KEEP, i));
        }
        priority = PriorityControl.sync(port, this::addDataSlot);
    }

    /** A data slot that reads the port's export order or one filter slot's settings; server side. */
    private static final class Live extends DataSlot {
        static final int ORDER = 0;
        static final int MATCH = 1;
        static final int KEEP = 2;

        private final PortDevice port;
        private final int what;
        private final int slot;

        Live(PortDevice port, int what, int slot) {
            this.port = port;
            this.what = what;
            this.slot = slot;
        }

        @Override
        public int get() {
            return switch (what) {
                case ORDER -> port.worker().order().id();
                case MATCH -> port.filter().matchData(slot);
                default -> port.filter().keep(slot);
            };
        }

        @Override
        public void set(int value) {
            // Server side the port is the truth; buttons and payloads change it.
        }
    }

    /** The server's menu over {@code port}'s own filter. */
    public static PortMenu forPort(int containerId, Inventory inventory, PortDevice port) {
        DataSlot mode = new DataSlot() {
            @Override
            public int get() {
                return port.mode().id();
            }

            @Override
            public void set(int value) {
            }
        };
        DataSlot research = new DataSlot() {
            @Override
            public int get() {
                PortWorker worker = port.worker();
                if (port.mode() == PortMode.STORAGE) {
                    return switch (worker.busStatus()) {
                        case BLOCKED -> CLAIM_BLOCKED;
                        case LOOP -> LOOP_REFUSED;
                        case NOTHING -> NOTHING_MOUNTED;
                        case MOUNTED -> 0;
                    };
                }
                return worker.claimBlocked() ? CLAIM_BLOCKED : worker.waitsForResearch() ? 1 : 0;
            }

            @Override
            public void set(int value) {
            }
        };
        DataSlot access = new DataSlot() {
            @Override
            public int get() {
                return port.worker().busAccess().id();
            }

            @Override
            public void set(int value) {
            }
        };
        return new PortMenu(containerId, inventory, port, port.filter(), mode, port.worker().cards(), PortCards.slots(),
                research, access, port.worker().tier());
    }

    /**
     * The client's menu; slot sync fills the filter and cards, the data slots the mode and research wait. The card slot
     * count and the tier come with the opening, clamped so a bad packet cannot ask for more slots than a port has.
     */
    public static PortMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        buffer.readBlockPos();
        int cardSlots = Math.clamp(buffer.readVarInt(), 0, PortCards.MAX_SLOTS);
        PortTier tier = PortTier.of(buffer.readVarInt());
        return new PortMenu(containerId, inventory, null, new SimpleContainer(PortFilter.SIZE), DataSlot.standalone(),
                new SimpleContainer(PortCards.MAX_SLOTS), cardSlots, DataSlot.standalone(), DataSlot.standalone(),
                tier);
    }

    /** Opens {@code port}'s GUI for {@code player}. */
    public static void open(ServerPlayer player, PortDevice port, BlockPos pos, Component title) {
        int cardSlots = PortCards.slots();
        int tier = port.worker().tier().number();
        Platform.get().menus().open(player,
                new SimpleMenuProvider((id, inventory, opener) -> forPort(id, inventory, port), title), buffer -> {
                    buffer.writeBlockPos(pos);
                    buffer.writeVarInt(cardSlots);
                    buffer.writeVarInt(tier);
                });
    }

    /** How many card slots the GUI shows. */
    public int cardSlots() {
        return cardSlots;
    }

    /** Whether the screen shows the card slots now (its upgrades flyout is open); client side. */
    public UpgradeSlot.Shown cardsShown() {
        return cardsShown;
    }

    /** The port's tier. */
    public PortTier tier() {
        return tier;
    }

    /** Whether the port waits for its tier's research, as synced. */
    public boolean waitsForResearch() {
        return research.get() == 1;
    }

    /** Whether a land claim keeps the port from the inventory it faces, as synced. */
    public boolean claimBlocked() {
        return research.get() == CLAIM_BLOCKED;
    }

    /** Whether a storage bus faces nothing it can mount, as synced. */
    public boolean nothingMounted() {
        return research.get() == NOTHING_MOUNTED;
    }

    /** Whether a storage bus refuses its target because mounting it would loop, as synced. */
    public boolean loopRefused() {
        return research.get() == LOOP_REFUSED;
    }

    /** The mode the port is in, as synced. */
    public PortMode mode() {
        return PortMode.byId(mode.get());
    }

    /** The storage access, as synced. */
    public BusAccess access() {
        return BusAccess.byId(access.get());
    }

    /** The export order, as synced. */
    public PortOrder order() {
        return PortOrder.byId(order.get());
    }

    /** How filter slot {@code slot} matches, as synced. */
    public MatchMode match(int slot) {
        return PortFilter.dataMode(matches[slot].get());
    }

    /** The tag filter slot {@code slot} matches by in tag mode, as synced. */
    public Optional<Identifier> matchTag(int slot) {
        return PortFilter.dataTag(matches[slot].get(), slots.get(slot).getItem());
    }

    /** Filter slot {@code slot}'s keep amount, as synced; 0 for none. */
    public int keep(int slot) {
        return keeps[slot].get();
    }

    /**
     * Sets filter slot {@code slot}'s keep amount for {@code player}; false when the slot is out of range or the player
     * may not configure the port. Server side, from a {@link PortPayloads.Keep}.
     */
    boolean applyKeep(Player player, int slot, int keep) {
        PortDevice current = port;
        if (current == null || slot < 0 || slot >= PortFilter.SIZE || !current.stillValid(player)
                || !NetworkAccess.canConfigure(player, current)) {
            return false;
        }
        current.filter().setKeep(slot, keep);
        return true;
    }

    /**
     * Names {@code stack} (one of it) in filter slot {@code slot} for {@code player}, as a recipe viewer's ghost drag
     * does; false when refused, as for {@link #applyKeep}. Server side, from a {@link PortPayloads.Ghost}.
     */
    boolean applyGhost(Player player, int slot, ItemStack stack) {
        PortDevice current = port;
        if (current == null || slot < 0 || slot >= PortFilter.SIZE || !current.stillValid(player)
                || !NetworkAccess.canConfigure(player, current)) {
            return false;
        }
        current.filter().setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
        return true;
    }

    /** The redstone mode and state, as synced. */
    public RedstoneSync.State redstone() {
        return RedstoneSync.read(redstone);
    }

    @Override
    public int priority() {
        return PriorityControl.read(priority);
    }

    @Override
    public boolean applyPriority(Player player, int priority) {
        PortDevice current = port;
        if (current == null || !current.stillValid(player) || !NetworkAccess.canConfigure(player, current)) {
            return false;
        }
        current.setPriority(priority);
        return true;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        PortDevice current = port;
        if (current == null || !NetworkAccess.canConfigure(player, current)) {
            return false;
        }
        if (id == CYCLE_MODE) {
            current.setMode(current.mode().next());
            return true;
        }
        if (id == CYCLE_ACCESS) {
            current.worker().setBusAccess(current.worker().busAccess().next());
            return true;
        }
        if (id == CYCLE_REDSTONE || id == REDSTONE_BACK) {
            RedstoneSync.step(current.redstone(), id == CYCLE_REDSTONE);
            return true;
        }
        if (id == CYCLE_ORDER) {
            current.worker().setOrder(current.worker().order().next());
            return true;
        }
        if (id >= MATCH_NEXT && id < MATCH_NEXT + PortFilter.SIZE) {
            current.filter().cycleMatch(id - MATCH_NEXT, true);
            return true;
        }
        if (id >= MATCH_BACK && id < MATCH_BACK + PortFilter.SIZE) {
            current.filter().cycleMatch(id - MATCH_BACK, false);
            return true;
        }
        return false;
    }

    /**
     * Filter slots take a copy of the carried item, or clear with an empty hand (left click) or a shift-click; a right
     * click with an empty hand on a named slot steps its match mode. Nothing real moves.
     */
    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput containerInput, Player player) {
        if (slotIndex >= 0 && slotIndex < PortFilter.SIZE) {
            if (containerInput == ContainerInput.PICKUP) {
                ItemStack carried = getCarried();
                if (carried.isEmpty() && buttonNum == 1 && !filter.getItem(slotIndex).isEmpty()) {
                    PortDevice current = port;
                    if (current != null && NetworkAccess.canConfigure(player, current)) {
                        current.filter().cycleMatch(slotIndex, true);
                    }
                    return;
                }
                filter.setItem(slotIndex, carried.isEmpty() ? ItemStack.EMPTY : carried.copyWithCount(1));
            } else if (containerInput == ContainerInput.QUICK_MOVE) {
                filter.setItem(slotIndex, ItemStack.EMPTY);
            }
            return;
        }
        super.clicked(slotIndex, buttonNum, containerInput, player);
    }

    /**
     * A shift-clicked card moves between the card slots and the inventory; any other shift-clicked inventory stack is
     * named in the first free filter slot unless a slot names it already.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < PortFilter.SIZE) {
            return ItemStack.EMPTY;
        }
        Slot slot = slots.get(index);
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (index >= FIRST_CARD_SLOT) {
            ItemStack copy = stack.copy();
            if (!moveItemStackTo(stack, PortFilter.SIZE, FIRST_CARD_SLOT, true)) {
                return ItemStack.EMPTY;
            }
            slot.setChanged();
            return copy;
        }
        if ((stack.is(PortContent.SPEED_CARD.get()) || stack.is(PortContent.CRAFTING_CARD.get())) && cardSlots > 0) {
            ItemStack copy = stack.copy();
            if (moveItemStackTo(stack, FIRST_CARD_SLOT, FIRST_CARD_SLOT + cardSlots, false)) {
                slot.setChanged();
                return copy;
            }
            return ItemStack.EMPTY;
        }
        int free = -1;
        for (int i = 0; i < PortFilter.SIZE; i++) {
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
        PortDevice current = port;
        return current == null || current.stillValid(player);
    }

    /** A ghost slot: it shows its item but cannot be taken from or placed into the vanilla way. */
    private static final class FilterSlot extends Slot {
        FilterSlot(Container container, int index, int x, int y) {
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

    @Override
    public @Nullable NetworkDevice labelDevice() {
        return LabelMenu.device(port);
    }
}
