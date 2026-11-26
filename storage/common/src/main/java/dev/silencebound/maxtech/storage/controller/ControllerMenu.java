package dev.silencebound.maxtech.storage.controller;

import dev.silencebound.maxtech.storage.cluster.ClusterPreview;
import dev.silencebound.maxtech.storage.controller.recovery.RecoveryBin;
import dev.silencebound.maxtech.storage.controller.recovery.RecoveryTabPayload;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.alarm.Alarm;
import dev.silencebound.maxtech.storage.controller.alarm.AlarmCenter;
import dev.silencebound.maxtech.storage.controller.alarm.AlarmTabPayload;
import dev.silencebound.maxtech.storage.controller.card.CardContent;
import dev.silencebound.maxtech.storage.controller.card.CardKind;
import dev.silencebound.maxtech.storage.controller.card.ControllerCards;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import java.util.List;

import dev.silencebound.maxtech.lib.highlight.Highlights;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.analyzer.NetworkOverview;
import dev.silencebound.maxtech.storage.crafting.job.CraftLimits;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModMenus;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import dev.silencebound.maxtech.storage.label.LabelMenu;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import org.jspecify.annotations.Nullable;

/**
 * The controller core's screen (PRD 7.2 Diagnostics). On the server it belongs to one viewer: while it is open it
 * builds the core network's {@link NetworkOverview} every {@link #REFRESH_TICKS} ticks and sends it
 * ({@link NetworkTabPayload}) only when it differs from the last one sent; nothing is built for anyone else, and
 * nothing after the screen closes. Button {@code i} (a warning's index) and {@link #HOTSPOT_BUTTON}{@code + i} (a
 * busiest segment's) light that position for the viewer, resolved against what the server last sent. Opening needs at
 * least view access to the core.
 *
 * <p>
 * The Crafting tab (M7L2 chunk 20) shows the network's limits for automated crafting jobs and its free CPUs through
 * data slots, and its buttons from {@link #LIMIT_BUTTON} step the limits; changing them needs configure access to the
 * core.
 *
 * <p>
 * The Cards tab (M7R item 2) holds the controller's upgrade card slots and the player's inventory; its slots work only
 * while the tab shows ({@link #showCards}). A card slot takes one card while it is usable (the controller's size and
 * the Upgrade Cards research), its kind is under its limit and the player's team has the card's research; changing
 * cards needs configure access.
 */
public final class ControllerMenu extends AbstractContainerMenu implements LabelMenu {
    /** Ticks between two overview builds while open: once a second. */
    public static final int REFRESH_TICKS = 20;
    /** Buttons from here locate the busiest segments. */
    public static final int HOTSPOT_BUTTON = 64;
    /**
     * Buttons from here change the craft limits: per-job maximum down and up, CPUs kept free down and up, then back to
     * the config's defaults.
     */
    public static final int LIMIT_BUTTON = 1000;
    public static final int MAX_PER_JOB_DOWN = LIMIT_BUTTON;
    public static final int MAX_PER_JOB_UP = LIMIT_BUTTON + 1;
    public static final int KEPT_FREE_DOWN = LIMIT_BUTTON + 2;
    public static final int KEPT_FREE_UP = LIMIT_BUTTON + 3;
    public static final int LIMITS_DEFAULT = LIMIT_BUTTON + 4;
    /** Alarms tab (M7C item 16): button {@code ALARM_BUTTON + i} acknowledges the i-th alarm sent. */
    public static final int ALARM_BUTTON = 2000;
    /** Recovery tab (M7C item 20): button {@code RECOVER_BUTTON + id} recovers the row with that id. */
    public static final int RECOVER_BUTTON = 3000;
    /** Data slots: the limits in force, which follow the config, and the free CPUs (all and for automation). */
    public static final int DATA_MAX_PER_JOB = 0;
    public static final int DATA_KEPT_FREE = 1;
    public static final int DATA_FLAGS = 2;
    public static final int DATA_FREE_CPUS = 3;
    public static final int DATA_AUTO_CPUS = 4;
    /** The CPUs the network can use at all, busy or not. */
    public static final int DATA_CPUS = 5;
    /** The usable card slots, and how many cards of each kind count (M7R item 2). */
    public static final int DATA_CARD_SLOTS = 6;
    public static final int DATA_LIMIT_AMPLIFIERS = 7;
    public static final int DATA_LIMIT_EFFICIENCY = 8;
    public static final int DATA_LIMIT_SURGE = 9;
    /** What the cards do now: the batch size and FE cost in percent, the surge reserve in percent (0 without). */
    public static final int DATA_BATCH_PERCENT = 10;
    public static final int DATA_ENERGY_PERCENT = 11;
    public static final int DATA_SURGE_PERCENT = 12;
    private static final int DATA_COUNT = 13;
    /** {@link #DATA_FLAGS} bits. */
    public static final int FLAG_MAX_FROM_CONFIG = 1;
    public static final int FLAG_KEPT_FROM_CONFIG = 2;
    public static final int FLAG_MAY_CONFIGURE = 4;
    /** The owner's team lacks the Upgrade Cards research, so the controller has no card slots. */
    public static final int FLAG_NO_CARD_RESEARCH = 8;
    /** Where the card slots and the player's inventory sit on the Cards tab. */
    public static final int CARD_X = 21;
    public static final int CARD_Y = 54;
    public static final int INVENTORY_X = 48;
    public static final int INVENTORY_Y = 132;
    /** Ticks a located position stays lit. */
    public static final int LOCATE_TICKS = 200;
    public static final int WIDTH = 256;
    public static final int HEIGHT = 216;
    private static final double REACH = 8.0;

    private final @Nullable ControllerBlockEntity core;
    private final @Nullable ServerPlayer viewer;
    private final @Nullable Consumer<NetworkTabPayload> sender;
    private final @Nullable Consumer<AlarmTabPayload> alarmSender;
    private final @Nullable Consumer<RecoveryTabPayload> recoverySender;
    /** The Recovery tab as last sent (server) or received (client). */
    private RecoveryTabPayload recovery;
    private int recoveryVersion = Integer.MIN_VALUE;
    private long nextRecoverySend;
    /** The alarms as last sent (server) or received (client), in the screen's order. */
    private List<Alarm> alarms = List.of();
    private int alarmVersion = Integer.MIN_VALUE;
    private final ContainerData limits;
    private @Nullable NetworkOverview overview;
    private long nextBuild;
    /** The game time before which a rename is not taken (M7L2 row 13): one per second per open screen. */
    private long nextRename;
    private final Player player;
    private final Container cards;
    /** Whether the Cards tab shows, so its slots work; the client's screen sets it, the server's stays true. */
    private boolean cardsShown = true;

    private ControllerMenu(int containerId, Inventory inventory, @Nullable ControllerBlockEntity core,
            @Nullable ServerPlayer viewer, @Nullable Consumer<NetworkTabPayload> sender,
            @Nullable Consumer<AlarmTabPayload> alarmSender, @Nullable Consumer<RecoveryTabPayload> recoverySender) {
        super(ModMenus.CONTROLLER.get(), containerId);
        this.core = core;
        this.viewer = viewer;
        this.sender = sender;
        this.alarmSender = alarmSender;
        this.recoverySender = recoverySender;
        this.player = inventory.player;
        this.recovery = new RecoveryTabPayload(containerId, List.of(), 0, false);
        this.limits = core == null ? new SimpleContainerData(DATA_COUNT) : new LimitData(core, viewer);
        this.cards = core == null ? new SimpleContainer(ClusterStats.Cards.MOST) : core.cards();
        for (int i = 0; i < cards.getContainerSize(); i++) {
            addSlot(new CardSlot(cards, i, CARD_X + i * 18, CARD_Y));
        }
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new InventorySlot(inventory, column + row * 9 + 9, INVENTORY_X + column * 18,
                        INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new InventorySlot(inventory, column, INVENTORY_X + column * 18, INVENTORY_Y + 58));
        }
        addDataSlots(limits);
    }

    /** Shows or hides the Cards tab's slots. Client side, from the screen. */
    public void showCards(boolean shown) {
        cardsShown = shown;
    }

    /** The usable card slots, as synced. */
    public int cardSlots() {
        return Math.max(0, Math.min(ClusterStats.Cards.MOST, limits.get(DATA_CARD_SLOTS)));
    }

    /** How many cards of {@code kind} count, as synced. */
    public int cardLimit(CardKind kind) {
        return Math.max(0, limits.get(switch (kind) {
            case BATCH_AMPLIFIER -> DATA_LIMIT_AMPLIFIERS;
            case EFFICIENCY_CORE -> DATA_LIMIT_EFFICIENCY;
            case SURGE_REGULATOR -> DATA_LIMIT_SURGE;
        }));
    }

    /** The cards installed in usable slots now, by kind, from the slots (the client's synced copy on the client). */
    public int installed(CardKind kind) {
        int count = 0;
        for (int i = 0; i < cardSlots(); i++) {
            if (CardContent.kind(cards.getItem(i)) == kind) {
                count++;
            }
        }
        return count;
    }

    /** Whether {@link #player} may change the cards: configure access to the core; on the client, as synced. */
    private boolean mayChangeCards(Player who) {
        ControllerBlockEntity at = core;
        if (at == null) {
            return (limits.get(DATA_FLAGS) & FLAG_MAY_CONFIGURE) != 0;
        }
        return NetworkAccess.canConfigure(who, at);
    }

    /** Data slot {@code index} as the client last got it, or the server's value now. */
    public int limitData(int index) {
        return limits.get(index);
    }

    /** The client's menu; the overview arrives as payloads. */
    public static ControllerMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        buffer.readBlockPos();
        return new ControllerMenu(containerId, inventory, null, null, null, null, null);
    }

    /** The server's menu for {@code viewer} at {@code core}, sending overviews to {@code sender}. */
    public static ControllerMenu server(int containerId, ControllerBlockEntity core, ServerPlayer viewer,
            Consumer<NetworkTabPayload> sender) {
        return new ControllerMenu(containerId, viewer.getInventory(), core, viewer, sender, null, null);
    }

    /** The server's menu for {@code viewer} at {@code core}, sending overviews and alarms to their senders. */
    public static ControllerMenu server(int containerId, ControllerBlockEntity core, ServerPlayer viewer,
            Consumer<NetworkTabPayload> sender, Consumer<AlarmTabPayload> alarmSender) {
        return new ControllerMenu(containerId, viewer.getInventory(), core, viewer, sender, alarmSender, null);
    }

    /** The server's menu for {@code viewer} at {@code core}, sending overviews, alarms and the recovery bin. */
    public static ControllerMenu server(int containerId, ControllerBlockEntity core, ServerPlayer viewer,
            Consumer<NetworkTabPayload> sender, Consumer<AlarmTabPayload> alarmSender,
            Consumer<RecoveryTabPayload> recoverySender) {
        return new ControllerMenu(containerId, viewer.getInventory(), core, viewer, sender, alarmSender,
                recoverySender);
    }

    /** Whether {@code player} may open the screen: any access to the core. */
    public static boolean mayOpen(Player player, ControllerBlockEntity core) {
        return NetworkAccess.level(player, core) != AccessLevel.NONE;
    }

    /** Opens the core's screen for {@code player}. */
    public static void open(ServerPlayer player, ControllerBlockEntity core, Component title) {
        BlockPos pos = core.getBlockPos();
        Platform.get().menus().open(player,
                new SimpleMenuProvider((id, inventory, opener) -> server(id, core, player,
                        payload -> Platform.get().network().sendToPlayer(player, payload),
                        payload -> Platform.get().network().sendToPlayer(player, payload),
                        payload -> Platform.get().network().sendToPlayer(player, payload)), title),
                buffer -> buffer.writeBlockPos(pos));
    }

    /** The core this server menu shows; null on the client. */
    public @Nullable ControllerBlockEntity core() {
        return core;
    }

    /** Whether a rename may go through now, and if so, holds the next one back for a second. Server side. */
    boolean renameAllowed(long gameTime) {
        if (gameTime < nextRename) {
            return false;
        }
        nextRename = gameTime + REFRESH_TICKS;
        return true;
    }

    /** The last overview: the one sent (server) or received (client); null before the first. */
    public @Nullable NetworkOverview overview() {
        return overview;
    }

    /** The alarms as last sent (server) or received (client), fresh ones first. */
    public List<Alarm> alarms() {
        return alarms;
    }

    /** Takes the alarms the server sent for this menu. Client side. */
    public void acceptAlarms(List<Alarm> received) {
        alarms = received;
    }

    /** The Recovery tab as last sent (server) or received (client). */
    public RecoveryTabPayload recovery() {
        return recovery;
    }

    /** Takes the Recovery tab the server sent for this menu. Client side. */
    public void acceptRecovery(RecoveryTabPayload received) {
        recovery = received;
    }

    /** Takes an overview the server sent for this menu. Client side. */
    public void accept(NetworkOverview received) {
        overview = received;
    }

    /** The overview of the core's network now, or null while the core is in none. Server side. */
    public @Nullable NetworkOverview build() {
        ControllerBlockEntity at = core;
        NetworkState network = at == null ? null : at.network();
        if (at == null || network == null) {
            return null;
        }
        return NetworkOverview.of(network, new NetworkOverview.Cluster(at.blockCount(), at.cap(), at.overCapacity(),
                ClusterPreview.networkNeeds(at, network)));
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        ServerPlayer to = viewer;
        Consumer<NetworkTabPayload> out = sender;
        if (to == null || out == null) {
            return;
        }
        sendAlarms();
        sendRecovery(to);
        long now = to.level().getGameTime();
        if (now < nextBuild) {
            return;
        }
        nextBuild = now + REFRESH_TICKS;
        NetworkOverview built = build();
        if (built != null && !built.equals(overview)) {
            overview = built;
            out.accept(new NetworkTabPayload(containerId, built));
        }
    }

    /** Sends the alarms when they changed since the last send; an int compare otherwise. */
    private void sendAlarms() {
        ControllerBlockEntity at = core;
        if (at == null) {
            return;
        }
        AlarmCenter center = at.alarms();
        if (center.version() == alarmVersion) {
            return;
        }
        alarmVersion = center.version();
        alarms = center.shown();
        Consumer<AlarmTabPayload> out = alarmSender;
        if (out != null) {
            out.accept(new AlarmTabPayload(containerId, alarms));
        }
    }

    /**
     * Sends the recovery bin when it changed since the last send, at most once per {@link #REFRESH_TICKS} (a farm may
     * void every tick), or at once when the viewer's right to recover changed; expired rows are dropped first. A
     * version compare and a permission check otherwise.
     */
    private void sendRecovery(ServerPlayer to) {
        ControllerBlockEntity at = core;
        Consumer<RecoveryTabPayload> out = recoverySender;
        if (at == null || out == null) {
            return;
        }
        RecoveryBin bin = at.recovery();
        long now = to.level().getGameTime();
        bin.prune(now);
        boolean may = RecoveryBin.mayUse(to, at.ownerRecord(), at.network());
        if (may == recovery.mayRecover() && (bin.version() == recoveryVersion || now < nextRecoverySend)) {
            return;
        }
        nextRecoverySend = now + REFRESH_TICKS;
        recoveryVersion = bin.version();
        recovery = new RecoveryTabPayload(containerId, bin.rows(now),
                StorageServerConfig.RECOVERY_KEEP_TICKS.getAsInt(), may);
        out.accept(recovery);
    }

    /**
     * Puts the bin row {@code rowId} back into the core's network for {@code player}, who must be the network's owner
     * or an admin (checked here, on the server); a row gone meanwhile or an unknown id is left alone.
     */
    private boolean recover(Player player, int rowId) {
        ControllerBlockEntity at = core;
        NetworkState network = at == null ? null : at.network();
        if (at == null || network == null || rowId < 0 || !RecoveryBin.mayUse(player, at.ownerRecord(), network)) {
            return false;
        }
        return at.recover(rowId, network, player) > 0;
    }

    /**
     * Acknowledges the {@code index}-th alarm sent to this menu for {@code player}, who needs deposit access to the
     * network; an alarm that cleared or was acknowledged meanwhile is left as it is.
     */
    private boolean acknowledge(Player player, int index) {
        ControllerBlockEntity at = core;
        if (at == null || index < 0 || index >= alarms.size()
                || !NetworkAccess.level(player, at).atLeast(AccessLevel.DEPOSIT)) {
            return false;
        }
        return at.alarms().acknowledge(alarms.get(index).key(), player.getGameProfile().name(),
                player.level().getGameTime());
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id >= RECOVER_BUTTON) {
            return recover(player, id - RECOVER_BUTTON);
        }
        if (id >= ALARM_BUTTON) {
            return acknowledge(player, id - ALARM_BUTTON);
        }
        if (id >= LIMIT_BUTTON) {
            return changeLimits(player, id);
        }
        NetworkOverview sent = overview;
        if (!(player instanceof ServerPlayer serverPlayer) || sent == null || id < 0) {
            return false;
        }
        long pos = Long.MIN_VALUE;
        if (id >= HOTSPOT_BUTTON) {
            int index = id - HOTSPOT_BUTTON;
            if (index < sent.hotspots().size()) {
                pos = sent.hotspots().get(index).pos();
            }
        } else {
            NetworkOverview.Warning warning = sent.warning(id);
            if (warning != null) {
                pos = warning.pos();
            }
        }
        if (pos == Long.MIN_VALUE) {
            return false;
        }
        Highlights.locate(serverPlayer, LOCATE_TICKS, BlockPos.of(pos));
        return true;
    }

    private boolean changeLimits(Player player, int id) {
        ControllerBlockEntity at = core;
        if (at == null || !NetworkAccess.canConfigure(player, at)) {
            return false;
        }
        CraftLimits now = at.craftLimits();
        CraftLimits next = switch (id) {
            case MAX_PER_JOB_DOWN -> now.stepMaxPerJob(false);
            case MAX_PER_JOB_UP -> now.stepMaxPerJob(true);
            case KEPT_FREE_DOWN -> now.stepKeptFree(false);
            case KEPT_FREE_UP -> now.stepKeptFree(true);
            case LIMITS_DEFAULT -> CraftLimits.DEFAULT;
            default -> null;
        };
        if (next == null) {
            return false;
        }
        at.setCraftLimits(next);
        return true;
    }

    /** The server's view of the core's craft limits and its network's free CPUs; read when changes are broadcast. */
    private static final class LimitData implements ContainerData {
        private final ControllerBlockEntity core;
        private final @Nullable ServerPlayer viewer;

        LimitData(ControllerBlockEntity core, @Nullable ServerPlayer viewer) {
            this.core = core;
            this.viewer = viewer;
        }

        @Override
        public int get(int index) {
            CraftLimits limits = core.craftLimits();
            NetworkState network = core.network();
            ServerPlayer player = viewer;
            return switch (index) {
                case DATA_MAX_PER_JOB -> (int) limits.effectiveMaxPerJob();
                case DATA_KEPT_FREE -> limits.effectiveCpusKeptFree();
                case DATA_FLAGS -> (limits.maxPerJob() == CraftLimits.FOLLOW_CONFIG ? FLAG_MAX_FROM_CONFIG : 0)
                        | (limits.cpusKeptFree() == CraftLimits.FOLLOW_CONFIG ? FLAG_KEPT_FROM_CONFIG : 0)
                        | (player != null && NetworkAccess.canConfigure(player, core) ? FLAG_MAY_CONFIGURE : 0)
                        | (core.getLevel() instanceof ServerLevel server && !ResearchUnlocks.isUnlocked(server,
                                core.ownerRecord(), ResearchGates.CONTROLLER_CARDS) ? FLAG_NO_CARD_RESEARCH : 0);
                case DATA_FREE_CPUS -> network == null ? 0 : JobQueue.freeCpus(network);
                case DATA_AUTO_CPUS -> network == null ? 0 : JobQueue.freeForAutomation(network);
                case DATA_CPUS -> network == null ? 0 : JobQueue.cpus(network);
                case DATA_CARD_SLOTS -> core.cards().usable();
                case DATA_LIMIT_AMPLIFIERS -> StorageServerConfig.CARD_LIMIT_AMPLIFIERS.getAsInt();
                case DATA_LIMIT_EFFICIENCY -> StorageServerConfig.CARD_LIMIT_EFFICIENCY.getAsInt();
                case DATA_LIMIT_SURGE -> StorageServerConfig.CARD_LIMIT_SURGE.getAsInt();
                case DATA_BATCH_PERCENT -> Math.min(Short.MAX_VALUE, core.stats().batchPercent());
                case DATA_ENERGY_PERCENT -> core.stats().energyPercent();
                case DATA_SURGE_PERCENT -> core.stats().surgeReservePercent();
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
        }

        @Override
        public int getCount() {
            return DATA_COUNT;
        }
    }

    /**
     * Shift-click: a card from a card slot to the inventory, or a card from the inventory into the first usable card
     * slot that takes it; one card per call (vanilla repeats it while cards are left). Needs configure access.
     */
    @Override
    public ItemStack quickMoveStack(Player who, int index) {
        if (index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }
        Slot slot = slots.get(index);
        ItemStack stack = slot.getItem();
        int inventoryStart = cards.getContainerSize();
        if (stack.isEmpty() || !mayChangeCards(who)) {
            return ItemStack.EMPTY;
        }
        ItemStack copy = stack.copy();
        if (index < inventoryStart) {
            if (!moveItemStackTo(stack, inventoryStart, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (CardContent.kind(stack) == null || !moveItemStackTo(stack, 0, cardSlots(), false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    /**
     * A card slot: one card, only while the slot is usable, its kind under its limit, the player may configure the
     * network and the player's team has the card's research (checked on the server; the client's prediction allows it).
     */
    private final class CardSlot extends Slot {
        CardSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean isActive() {
            return cardsShown && (getContainerSlot() < cardSlots() || hasItem());
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            CardKind kind = CardContent.kind(stack);
            if (kind == null || !mayChangeCards(player) || !ControllerCards.allows(cards, cardSlots(),
                    getContainerSlot(), stack, ControllerMenu.this::cardLimit)) {
                return false;
            }
            Identifier unlock = kind.unlock();
            return unlock == null || ResearchUnlocks.isUnlocked(player, unlock);
        }

        @Override
        public boolean mayPickup(Player who) {
            return mayChangeCards(who);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }

    /** A slot of the player's inventory, shown with the Cards tab only. */
    private final class InventorySlot extends Slot {
        InventorySlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean isActive() {
            return cardsShown;
        }
    }

    @Override
    public boolean stillValid(Player player) {
        ControllerBlockEntity at = core;
        return at == null || (!at.isRemoved() && player.position().closerThan(at.getBlockPos().getCenter(), REACH)
                && mayOpen(player, at));
    }

    @Override
    public @Nullable NetworkDevice labelDevice() {
        return core;
    }
}
