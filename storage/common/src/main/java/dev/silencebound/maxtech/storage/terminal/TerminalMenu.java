package dev.silencebound.maxtech.storage.terminal;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.resource.StackKeys;
import dev.silencebound.maxtech.lib.sync.ClientSyncedList;
import dev.silencebound.maxtech.lib.sync.ServerSyncedList;
import dev.silencebound.maxtech.lib.sync.SyncedListHolder;
import dev.silencebound.maxtech.lib.sync.SyncedListType;
import dev.silencebound.maxtech.lib.gui.transfer.CraftingGridMenu;
import dev.silencebound.maxtech.lib.gui.transfer.IngredientSource;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternPanel;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternTerminal;
import dev.silencebound.maxtech.storage.terminal.crafting.CraftingPanel;
import dev.silencebound.maxtech.storage.terminal.crafting.CraftingTerminal;
import dev.silencebound.maxtech.storage.terminal.crafting.CraftingTerminalContent;
import dev.silencebound.maxtech.storage.terminal.crafting.TerminalGrid;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import dev.silencebound.maxtech.storage.crafting.request.CraftingClientData;
import dev.silencebound.maxtech.storage.crafting.request.CraftingSession;
import dev.silencebound.maxtech.storage.crafting.request.RequestContent;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import dev.silencebound.maxtech.storage.network.NetworkActor;
import dev.silencebound.maxtech.storage.network.ContentsWatcher;
import dev.silencebound.maxtech.storage.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import dev.silencebound.maxtech.storage.terminal.filter.ViewFilterItem;
import dev.silencebound.maxtech.storage.terminal.filter.ViewFilterSlots;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
// PLATFORM-LEAK: fluid containers are reached through NeoForge's item fluid capability (ADR-0002 allows it).
import net.neoforged.neoforge.capabilities.Capabilities;
// PLATFORM-LEAK: see above; the cursor access swaps an empty bucket for a filled one.
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.access.ItemAccess;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.fluid.FluidResource;
// PLATFORM-LEAK: player transfers run in NeoForge transactions, like every Storage transfer (PRD 7.2).
import net.neoforged.neoforge.transfer.transaction.Transaction;
import dev.silencebound.maxtech.storage.label.LabelMenu;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkState;
import org.jspecify.annotations.Nullable;

/**
 * The GUI of the item terminal and the storage chest: the player's inventory below a grid of everything the
 * {@link TerminalSource} holds. The grid is Lib's synced list: the screen asks for a full dump when it opens
 * ({@link TerminalAction.Kind#REFRESH}), then the source's changes arrive as deltas, merged per key and sent at most
 * {@code sync.listUpdatesPerSecond} times a second, to this player only and only while the menu is open. Search and
 * sort run on the client. Clicks come back as {@link TerminalAction}s through vanilla's button packet and run as one
 * transaction each, after an access check; shift-clicking an inventory slot stores the stack. Status values travel as
 * data slots. A network terminal's menu also runs a {@link CraftingSession} for autocrafting requests (M7c). A crafting
 * terminal's menu ({@link CraftingTerminal}, its own menu type) adds the {@link CraftingPanel}: the grid's nine slots
 * and the result after the player's inventory, filled by a recipe viewer's "+" instead of the inventory. A pattern
 * terminal's menu ({@link PatternTerminal}, its own menu type) adds the {@link PatternPanel} instead: a draft without
 * slots, edited through {@link TerminalAction.Kind#PATTERN} buttons and ghost payloads, never through items. A source
 * with view filter slots ({@link TerminalSource#viewFilters}) adds them last; their filters reach the client through
 * the slot sync and only the client filters with them (B09 item 24). The client's slots follow the screen's size
 * ({@link #relayout}).
 */
public final class TerminalMenu extends AbstractContainerMenu
        implements
            SyncedListHolder,
            ContentsWatcher,
            CraftingGridMenu,
            LabelMenu {
    public static final int STATUS = 0;
    public static final int ENERGY = 1;
    public static final int CAPACITY = 2;
    public static final int CHANNELS = 3;
    public static final int LOAD = 4;
    public static final int KINDS = 5;
    public static final int USED_SLOTS = 6;
    public static final int SLOTS = 7;
    private static final int DATA = 8;
    /**
     * Fewest ticks between two full dumps a client may ask for. A performance bound against a client that keeps asking,
     * not a balance value; the first dump is never delayed.
     */
    static final int REFRESH_TICKS = 10;
    public static final int INVENTORY_X = TerminalLayout.INVENTORY_X;
    public static final int INVENTORY_Y = TerminalLayout.BASE_INVENTORY_Y;
    /** The first grid slot of a crafting terminal's menu, after the player's 36. */
    public static final int GRID_SLOT = 36;
    /** The result slot of a crafting terminal's menu. */
    public static final int RESULT_SLOT = GRID_SLOT + TerminalGrid.SIZE;

    private final @Nullable TerminalSource source;
    private final @Nullable CraftingSession crafting;
    private final CraftingClientData craftingData = new CraftingClientData();
    private final @Nullable Player player;
    private final @Nullable ServerSyncedList<StackKey> list;
    private final ClientSyncedList<StackKey> clientList = new ClientSyncedList<>();
    private final ContainerData data;
    private boolean opened;
    /** The client's layout follows the screen's size ({@link #relayout}); the server's stays the base one. */
    private TerminalLayout layout;
    private final @Nullable CraftingPanel panel;
    /** The first view filter slot, -1 for a source without them; the four come after every other slot. */
    private final int firstFilter;
    private final @Nullable PatternPanel patternPanel;
    /**
     * Whether the source tells this menu about changes. Set by the client's first refresh, not in the constructor: a
     * menu that never reaches the player never gets {@link #removed}, and would stay in the source's list.
     */
    private boolean watching;
    private boolean dumpWanted;
    private long lastDump = Long.MIN_VALUE / 2;
    private long lastLocate = Long.MIN_VALUE / 2;

    private TerminalMenu(MenuType<?> type, int containerId, Inventory inventory, @Nullable TerminalSource source,
            ContainerData data, ServerSyncedList.@Nullable Sink<StackKey> sink,
            @Nullable Consumer<CustomPacketPayload> payloads, @Nullable TerminalGrid grid,
            @Nullable PatternPanel pattern, @Nullable Container filters) {
        super(type, containerId);
        this.source = source;
        this.data = data;
        this.patternPanel = pattern;
        // Slot positions matter only on the client, which moves them to its screen's size; the server keeps the base.
        this.layout = new TerminalLayout(0, grid != null || pattern != null);
        addStandardInventorySlots(inventory, INVENTORY_X, inventoryY());
        if (grid != null) {
            CraftingPanel crafting = new CraftingPanel(grid, source, inventory.player, layout);
            for (Slot slot : crafting.gridSlots()) {
                addSlot(slot);
            }
            addSlot(crafting.resultSlot());
            this.panel = crafting;
        } else {
            this.panel = null;
        }
        if (filters != null) {
            this.firstFilter = slots.size();
            for (int i = 0; i < TerminalLayout.FILTER_SLOTS; i++) {
                addSlot(new ViewFilterSlots.FilterSlot(filters, i, layout.filterX(i), layout.filterY(i)));
            }
        } else {
            this.firstFilter = -1;
        }
        addDataSlots(data);
        if (source != null && sink != null) {
            this.player = inventory.player;
            this.list = new ServerSyncedList<>(TerminalContent.LIST, containerId, source::key, sink,
                    TerminalContent.LIST.sizer(inventory.player.registryAccess()));
            this.crafting = payloads == null
                    ? null
                    : new CraftingSession(containerId, inventory.player, source::network, payloads);
        } else {
            this.player = null;
            this.list = null;
            this.crafting = null;
        }
    }

    /**
     * The server's menu over {@code source}, sending its list through {@code sink}: the player's connection normally, a
     * recorder in tests.
     */
    public static TerminalMenu server(int containerId, Inventory inventory, TerminalSource source,
            ServerSyncedList.Sink<StackKey> sink) {
        return server(containerId, inventory, source, sink, payload -> {
        });
    }

    /**
     * The server's menu over {@code source}, sending its list through {@code sink} and its crafting payloads
     * ({@link CraftingSession}) through {@code payloads}.
     */
    public static TerminalMenu server(int containerId, Inventory inventory, TerminalSource source,
            ServerSyncedList.Sink<StackKey> sink, Consumer<CustomPacketPayload> payloads) {
        ContainerData data = new ContainerData() {
            @Override
            public int get(int index) {
                return source.status(index);
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return DATA;
            }
        };
        if (source instanceof CraftingTerminal terminal) {
            return new TerminalMenu(CraftingTerminalContent.MENU.get(), containerId, inventory, source, data, sink,
                    payloads, terminal.grid(), null, source.viewFilters());
        }
        if (source instanceof PatternTerminal terminal) {
            return new TerminalMenu(ModMenus.PATTERN_TERMINAL.get(), containerId, inventory, source, data, sink,
                    payloads, null, new PatternPanel(terminal), source.viewFilters());
        }
        return new TerminalMenu(ModMenus.TERMINAL.get(), containerId, inventory, source, data, sink, payloads, null,
                null, source.viewFilters());
    }

    /**
     * Moves the client's slots to {@code next}, the layout of the screen's size (B09 item 21c): the inventory follows
     * the item grid's rows, the crafting grid the panel, the view filters the panel's bottom. {@code Slot} positions
     * are final, so each slot is replaced by a copy at its new place, with the same index; the server never sees a
     * difference. Client only, before a screen is made for this menu.
     */
    public void relayout(TerminalLayout next) {
        if (next.equals(layout)) {
            return;
        }
        int dy = next.inventoryY() - layout.inventoryY();
        layout = next;
        for (int i = 0; i < GRID_SLOT; i++) {
            Slot old = slots.get(i);
            replace(i, new Slot(old.container, old.getContainerSlot(), old.x, old.y + dy));
        }
        CraftingPanel crafting = panel;
        if (crafting != null) {
            crafting.relayout(next);
            for (int cell = 0; cell < TerminalGrid.SIZE; cell++) {
                replace(GRID_SLOT + cell, crafting.gridSlots().get(cell));
            }
            replace(RESULT_SLOT, crafting.resultSlot());
        }
        if (firstFilter >= 0) {
            for (int i = 0; i < TerminalLayout.FILTER_SLOTS; i++) {
                Slot old = slots.get(firstFilter + i);
                replace(firstFilter + i,
                        new ViewFilterSlots.FilterSlot(old.container, i, next.filterX(i), next.filterY(i)));
            }
        }
    }

    private void replace(int index, Slot slot) {
        slot.index = index;
        slots.set(index, slot);
    }

    /** Whether the menu has view filter slots (every source but the tablet's jobs view). */
    public boolean hasViewFilters() {
        return firstFilter >= 0;
    }

    /** Whether menu slot {@code index} is a view filter slot. */
    public boolean isViewFilterSlot(int index) {
        return firstFilter >= 0 && index >= firstFilter && index < firstFilter + TerminalLayout.FILTER_SLOTS;
    }

    /** View filter slot {@code i} (0 to 3); only when {@link #hasViewFilters}. */
    public Slot viewFilterSlot(int i) {
        return slots.get(firstFilter + i);
    }

    /** The y of the player's inventory in the GUI. */
    public int inventoryY() {
        return layout.inventoryY();
    }

    /** Where the GUI puts things: the client's layout, the base one on the server. */
    public TerminalLayout layout() {
        return layout;
    }

    /** The crafting terminal's grid and result; null for every other terminal. */
    public @Nullable CraftingPanel craftingPanel() {
        return panel;
    }

    /** The pattern terminal's draft; null for every other terminal. */
    public @Nullable PatternPanel patternPanel() {
        return patternPanel;
    }

    /** The client's menu; its list and status are filled from the server. */
    public static TerminalMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        buffer.readBlockPos();
        return new TerminalMenu(ModMenus.TERMINAL.get(), containerId, inventory, null, new SimpleContainerData(DATA),
                null, null, null, null, clientFilters(buffer));
    }

    /** The client's stand-in for the view filter slots, when {@link #open} said the source has them. */
    private static @Nullable Container clientFilters(RegistryFriendlyByteBuf buffer) {
        return buffer.readBoolean() ? new SimpleContainer(TerminalLayout.FILTER_SLOTS) : null;
    }

    /** The client's crafting terminal menu: a terminal menu with a grid that mirrors the server's. */
    public static TerminalMenu fromNetworkCrafting(int containerId, Inventory inventory,
            RegistryFriendlyByteBuf buffer) {
        buffer.readBlockPos();
        return new TerminalMenu(CraftingTerminalContent.MENU.get(), containerId, inventory, null,
                new SimpleContainerData(DATA), null, null, TerminalGrid.detached(), null, clientFilters(buffer));
    }

    /** The client's pattern terminal menu: a terminal menu with a pattern panel the server's drafts fill. */
    public static TerminalMenu fromNetworkPattern(int containerId, Inventory inventory,
            RegistryFriendlyByteBuf buffer) {
        buffer.readBlockPos();
        return new TerminalMenu(ModMenus.PATTERN_TERMINAL.get(), containerId, inventory, null,
                new SimpleContainerData(DATA), null, null, null, new PatternPanel(null), clientFilters(buffer));
    }

    /** Opens {@code source}'s GUI at {@code pos} for {@code player}, titled {@code title}. */
    public static void open(ServerPlayer player, TerminalSource source, BlockPos pos, Component title) {
        Platform.get().menus().open(player,
                new SimpleMenuProvider((id, inventory, opener) -> server(id, inventory, source,
                        update -> Platform.get().network().sendToPlayer(player, update),
                        payload -> Platform.get().network().sendToPlayer(player, payload)), title),
                buffer -> {
                    buffer.writeBlockPos(pos);
                    buffer.writeBoolean(source.viewFilters() != null);
                });
        opened(player, source);
    }

    /**
     * Tells {@code source}'s network that {@code player} opened it, when the player's open menu is a terminal's (logic
     * graphs may wait for a player at a terminal, M7L2); one check when nothing listens. {@link #open} calls it; public
     * for GameTests, whose players cannot receive the open-screen packet.
     */
    public static void opened(Player player, TerminalSource source) {
        NetworkState network = source.network();
        if (network != null && player.containerMenu instanceof TerminalMenu) {
            network.terminalOpened(source instanceof NetworkDevice device ? device : null);
        }
    }

    @Override
    @SuppressWarnings("unchecked") // the menu holds exactly one list, of this type
    public <E> @Nullable ClientSyncedList<E> syncedList(SyncedListType<E> type) {
        if (type.payloadType().equals(TerminalContent.LIST.payloadType())) {
            return (ClientSyncedList<E>) clientList;
        }
        if (type.payloadType().equals(RequestContent.CRAFTABLES.payloadType())) {
            return (ClientSyncedList<E>) craftingData.craftables();
        }
        if (type.payloadType().equals(RequestContent.PROGRESS.payloadType())) {
            return (ClientSyncedList<E>) craftingData.progress();
        }
        return null;
    }

    /** The server's crafting session; null on the client and for menus built without a payload sink. */
    public @Nullable CraftingSession crafting() {
        return crafting;
    }

    /** What the server's menu shows; null on the client. */
    public @Nullable TerminalSource source() {
        return source;
    }

    /** The client's copy of the crafting state; empty on the server. */
    public CraftingClientData craftingData() {
        return craftingData;
    }

    /** Whether this menu's source can be a network (and so offer crafting); the client reads it from the status. */
    public boolean networked() {
        TerminalStatus status = status();
        return status != TerminalStatus.LOCAL && status != TerminalStatus.NO_NETWORK;
    }

    /** The client's copy of the contents. */
    public ClientSyncedList<StackKey> clientList() {
        return clientList;
    }

    public int data(int field) {
        return data.get(field);
    }

    public TerminalStatus status() {
        return TerminalStatus.byIndex(data.get(STATUS));
    }

    /** Whether the client asked for the contents and got its dump; tests use it. */
    public boolean isOpened() {
        return opened;
    }

    @Override
    public void changed(int id, long total) {
        ServerSyncedList<StackKey> current = list;
        if (opened && current != null) {
            current.changed(id, total);
        }
    }

    @Override
    public void released(int id) {
        ServerSyncedList<StackKey> current = list;
        Player viewer = player;
        if (opened && current != null && viewer != null) {
            // The id may name another key soon; whatever the client still has to learn about it goes now.
            current.tick(viewer.level().getGameTime(), 0);
        }
    }

    /** The source's contents were replaced (another network): the client gets a new dump. */
    public void reset() {
        if (opened) {
            dumpWanted = true;
        }
        if (crafting != null) {
            crafting.reset();
        }
    }

    @Override
    public void broadcastChanges() {
        CraftingPanel gridPanel = panel;
        Player viewer = player;
        if (gridPanel != null && viewer != null) {
            // Before the slots go out, so a changed grid sends its new result with it.
            gridPanel.refresh(viewer);
        }
        super.broadcastChanges();
        PatternPanel pattern = patternPanel;
        if (pattern != null && viewer instanceof ServerPlayer serverPlayer) {
            pattern.broadcast(serverPlayer, containerId);
        }
        ServerSyncedList<StackKey> current = list;
        TerminalSource from = source;
        if (current == null || from == null || viewer == null) {
            return;
        }
        long now = viewer.level().getGameTime();
        if (dumpWanted) {
            dumpWanted = false;
            opened = true;
            lastDump = now;
            current.open(from.contents(), now);
        } else if (opened) {
            current.tick(now);
        }
        if (opened && crafting != null) {
            crafting.tick(now);
        }
    }

    /** Every action runs for {@code clicker}, so a ledger module records who took or stored what. */
    @Override
    public boolean clickMenuButton(Player clicker, int buttonId) {
        UUID previous = NetworkActor.begin(clicker);
        try {
            return click(clicker, buttonId);
        } finally {
            NetworkActor.end(previous);
        }
    }

    private boolean click(Player clicker, int buttonId) {
        TerminalSource from = source;
        TerminalAction action = TerminalAction.decode(buttonId);
        if (from == null || action == null) {
            return false;
        }
        if (action.kind() == TerminalAction.Kind.REFRESH) {
            if (!watching && list != null) {
                watching = true;
                from.watch(this);
            }
            long now = clicker.level().getGameTime();
            if (!opened || now - lastDump >= REFRESH_TICKS) {
                dumpWanted = true;
                return true;
            }
            return false;
        }
        if (!from.canAccess(clicker)) {
            return false;
        }
        // A security module may let a player see and deposit without taking.
        return switch (action.kind()) {
            case TAKE_STACK, TAKE_HALF, TAKE_ONE, TAKE_TO_INVENTORY ->
                from.canTake(clicker) && take(from, clicker, action);
            case DEPOSIT_ALL -> from.canDeposit(clicker) && deposit(from, getCarried().getCount());
            case DEPOSIT_ONE -> from.canDeposit(clicker) && (empty(from, clicker) || deposit(from, 1));
            case FILL_CONTAINER -> from.canTake(clicker) && fill(from, clicker, action.keyId());
            case DEPOSIT_STORED -> from.canDeposit(clicker) && TerminalComfort.depositStored(from, clicker) > 0;
            case RESTOCK_HOTBAR -> from.canTake(clicker) && TerminalComfort.restockHotbar(from, clicker) > 0;
            case LOCATE -> locate(from, clicker, action.keyId());
            case GRID_CLEAR ->
                panel != null && clicker instanceof ServerPlayer serverPlayer && panel.clear(serverPlayer);
            case GRID_REQUEST ->
                panel != null && clicker instanceof ServerPlayer serverPlayer && panel.requestMissing(serverPlayer);
            case PATTERN -> patternPanel != null && clicker instanceof ServerPlayer serverPlayer
                    && patternPanel.click(serverPlayer, action.keyId(), getCarried());
            case REFRESH -> false;
        };
    }

    /**
     * Tells the player which blocks hold the entry {@code keyId} and outlines them ({@link ItemLocator}); only for a
     * source with a network, at most once every {@link #REFRESH_TICKS}.
     */
    private boolean locate(TerminalSource from, Player clicker, int keyId) {
        NetworkState network = from.network();
        StackKey key = from.key(keyId);
        long now = clicker.level().getGameTime();
        if (network == null || key == null || !(clicker instanceof ServerPlayer serverPlayer)
                || now - lastLocate < REFRESH_TICKS) {
            return false;
        }
        // A wireless terminal pays one operation for it, as for a transfer (B09 item 23).
        if (!RemoteCost.pay(from)) {
            return false;
        }
        lastLocate = now;
        ItemLocator.locate(serverPlayer, network, key, name(key));
        return true;
    }

    /** The name of {@code key} as a player sees it. */
    private static Component name(StackKey key) {
        return switch (key) {
            case ItemKey item -> item.toStack(1).getHoverName();
            case FluidKey fluid -> StackKeys.toResource(fluid).getHoverName();
        };
    }

    private boolean take(TerminalSource from, Player clicker, TerminalAction action) {
        boolean toInventory = action.kind() == TerminalAction.Kind.TAKE_TO_INVENTORY;
        if (!(from.key(action.keyId()) instanceof ItemKey item) || (!toInventory && !getCarried().isEmpty())) {
            return false;
        }
        long stored = from.amount(action.keyId());
        ItemStack sample = item.toStack(1);
        int max = sample.getMaxStackSize();
        long wanted = switch (action.kind()) {
            case TAKE_HALF -> (Math.min(stored, max) + 1) / 2;
            case TAKE_ONE -> 1;
            default -> max;
        };
        if (toInventory) {
            wanted = Math.min(wanted, TerminalComfort.room(clicker, sample));
        }
        if (wanted <= 0) {
            return false;
        }
        long taken;
        try (Transaction transaction = Transaction.openRoot()) {
            taken = from.extract(item, wanted, transaction);
            transaction.commit();
        }
        if (taken <= 0) {
            return false;
        }
        ItemStack stack = item.toStack((int) taken);
        if (toInventory) {
            // Room was counted first, so everything fits; a stack the inventory still refuses is dropped, never lost.
            if (!clicker.getInventory().add(stack) && !stack.isEmpty()) {
                clicker.drop(stack, false);
            }
        } else {
            setCarried(stack);
        }
        return true;
    }

    private boolean deposit(TerminalSource from, int count) {
        ItemStack carried = getCarried();
        if (carried.isEmpty() || count <= 0) {
            return false;
        }
        long stored = store(from, carried, Math.min(count, carried.getCount()));
        if (stored <= 0) {
            return false;
        }
        carried.shrink((int) stored);
        setCarried(carried);
        return true;
    }

    private static long store(TerminalSource from, ItemStack stack, int count) {
        try (Transaction transaction = Transaction.openRoot()) {
            long stored = from.insert(ItemKey.of(stack), count, transaction);
            transaction.commit();
            return stored;
        }
    }

    /** Fills the carried container (one at a time from a stack) from the fluid entry {@code keyId}. */
    private boolean fill(TerminalSource from, Player clicker, int keyId) {
        if (!(from.key(keyId) instanceof FluidKey fluid) || getCarried().isEmpty()) {
            return false;
        }
        ItemAccess access = ItemAccess.forPlayerCursor(clicker, this).oneByOne();
        ResourceHandler<FluidResource> container = access.getCapability(Capabilities.Fluid.ITEM);
        if (container == null) {
            return false;
        }
        FluidResource resource = StackKeys.toResource(fluid);
        int wanted = (int) Math.min(Integer.MAX_VALUE, from.amount(keyId));
        try (Transaction transaction = Transaction.openRoot()) {
            int room;
            try (Transaction probe = Transaction.open(transaction)) {
                room = container.insert(resource, wanted, probe);
            }
            if (room <= 0) {
                return false;
            }
            long taken = from.extract(fluid, room, transaction);
            // A container that takes only whole buckets refuses a partial amount: then nothing moves.
            if (taken <= 0 || container.insert(resource, (int) taken, transaction) != taken) {
                return false;
            }
            transaction.commit();
            return true;
        }
    }

    /** Empties the carried container (one at a time from a stack) into the source. */
    private boolean empty(TerminalSource from, Player clicker) {
        if (getCarried().isEmpty()) {
            return false;
        }
        ItemAccess access = ItemAccess.forPlayerCursor(clicker, this).oneByOne();
        ResourceHandler<FluidResource> container = access.getCapability(Capabilities.Fluid.ITEM);
        if (container == null) {
            return false;
        }
        for (int index = 0; index < container.size(); index++) {
            FluidResource resource = container.getResource(index);
            if (resource.isEmpty()) {
                continue;
            }
            try (Transaction transaction = Transaction.openRoot()) {
                int available;
                try (Transaction probe = Transaction.open(transaction)) {
                    available = container.extract(resource, Integer.MAX_VALUE, probe);
                }
                if (available <= 0) {
                    continue;
                }
                long stored = from.insert(StackKeys.of(resource), available, transaction);
                if (stored <= 0 || container.extract(resource, (int) stored, transaction) != stored) {
                    continue;
                }
                transaction.commit();
                return true;
            }
        }
        return false;
    }

    /** The crafting terminal's grid slots; a plain terminal has no grid and is never filled through them. */
    @Override
    public int gridSlot(int cell) {
        if (patternPanel != null) {
            // No real slots: receiveRecipe hands the recipe to the pattern panel, so Lib's fill never asks.
            return cell;
        }
        if (panel == null) {
            throw new UnsupportedOperationException("A terminal has no crafting grid");
        }
        return GRID_SLOT + cell;
    }

    @Override
    public IngredientSource ingredientSource(ServerPlayer player) {
        CraftingPanel gridPanel = panel;
        return gridPanel == null ? IngredientSource.NONE : gridPanel.ingredients(player);
    }

    /**
     * A recipe viewer's "+" on a crafting recipe: a crafting terminal fills its grid ({@link CraftingPanel}); any other
     * terminal pulls the ingredients from the source into the player's inventory (one set, or as many as fit a stack
     * with {@code maxTransfer}).
     */
    @Override
    public void receiveRecipe(ServerPlayer player, List<List<ItemStack>> cells, boolean maxTransfer) {
        TerminalSource from = source;
        CraftingPanel gridPanel = panel;
        PatternPanel pattern = patternPanel;
        if (pattern != null) {
            // The recipe becomes a crafting draft; no items move.
            pattern.receiveCrafting(player, cells);
            return;
        }
        if (gridPanel != null) {
            if (from != null && from.canAccess(player)) {
                gridPanel.receiveRecipe(this, player, cells, maxTransfer);
            }
            return;
        }
        if (from != null && from.canAccess(player) && from.canTake(player)) {
            UUID previous = NetworkActor.begin(player);
            try {
                TerminalComfort.pullRecipe(from, player, cells, maxTransfer);
            } finally {
                NetworkActor.end(previous);
            }
        }
    }

    /**
     * Shift-clicking an inventory or grid slot stores its stack (as far as it fits); on the result it crafts as many as
     * it can ({@link CraftingPanel#craftMany}). A view filter goes into a free filter slot first, and one shift-clicked
     * out of a filter slot goes back to the inventory. Nothing happens on the client.
     */
    @Override
    public ItemStack quickMoveStack(Player clicker, int index) {
        TerminalSource from = source;
        CraftingPanel gridPanel = panel;
        if (gridPanel != null && index == RESULT_SLOT) {
            if (from != null && clicker instanceof ServerPlayer serverPlayer) {
                gridPanel.craftMany(serverPlayer);
            }
            // Empty, so vanilla does not call this again.
            return ItemStack.EMPTY;
        }
        if (from == null || index < 0 || index >= slots.size() || !from.canDeposit(clicker)) {
            return ItemStack.EMPTY;
        }
        Slot slot = slots.get(index);
        ItemStack stack = slot.getItem();
        if (stack.isEmpty() || !slot.mayPickup(clicker)) {
            return ItemStack.EMPTY;
        }
        if (isViewFilterSlot(index)) {
            if (moveItemStackTo(stack, 0, GRID_SLOT, true)) {
                slot.setChanged();
            }
            return ItemStack.EMPTY;
        }
        if (firstFilter >= 0 && index < GRID_SLOT && ViewFilterItem.is(stack)
                && moveItemStackTo(stack, firstFilter, firstFilter + TerminalLayout.FILTER_SLOTS, false)) {
            slot.setChanged();
            return ItemStack.EMPTY;
        }
        long stored;
        UUID previous = NetworkActor.begin(clicker);
        try {
            stored = store(from, stack, stack.getCount());
        } finally {
            NetworkActor.end(previous);
        }
        if (stored > 0) {
            slot.remove((int) stored);
            slot.setChanged();
        }
        // Empty, so vanilla does not call this again for the rest of the stack.
        return ItemStack.EMPTY;
    }

    /** A double click never collects from the result slot. */
    @Override
    public boolean canTakeItemForPickAll(ItemStack carried, Slot target) {
        CraftingPanel gridPanel = panel;
        return (gridPanel == null || !gridPanel.isResults(target.container))
                && super.canTakeItemForPickAll(carried, target);
    }

    @Override
    public boolean stillValid(Player viewer) {
        TerminalSource from = source;
        PatternPanel pattern = patternPanel;
        return from == null
                || (from.stillValid(viewer) && from.canAccess(viewer) && (pattern == null || pattern.usable(viewer)));
    }

    @Override
    public void removed(Player viewer) {
        super.removed(viewer);
        TerminalSource from = source;
        if (from != null && watching) {
            watching = false;
            from.unwatch(this);
        }
        if (from != null) {
            from.closed(this);
        }
        if (crafting != null) {
            crafting.close();
        }
    }

    @Override
    public @Nullable NetworkDevice labelDevice() {
        return LabelMenu.device(source);
    }
}
