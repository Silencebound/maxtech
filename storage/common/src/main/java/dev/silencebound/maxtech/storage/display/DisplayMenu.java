package dev.silencebound.maxtech.storage.display;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.condition.ConditionMenu;
import dev.silencebound.maxtech.storage.display.task.TaskBoardSync;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.registry.ModMenus;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import dev.silencebound.maxtech.storage.label.LabelMenu;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import org.jspecify.annotations.Nullable;

/**
 * A screen's settings GUI in the Accent Frame style: one ghost slot for the counted item (whose tags the tag count
 * steps through, as the condition switch does) and buttons for the rest, sent as vanilla menu button clicks, so it
 * needs no payload of its own. Settings travel back as data slots. The line and bar modes need the {@code holo_graphs}
 * research of the player's team; without it the mode button skips them. A panel's GUI sets its whole wall.
 */
public final class DisplayMenu extends AbstractContainerMenu implements LabelMenu {
    public static final int WIDTH = 176;
    public static final int HEIGHT = 194;
    public static final int SLOT_X = 8;
    public static final int SLOT_Y = 56;
    public static final int INVENTORY_X = 8;
    public static final int INVENTORY_Y = 112;

    public static final int CYCLE_MODE = 0;
    public static final int CYCLE_SOURCE = 1;
    public static final int NEXT_TAG = 2;
    public static final int CYCLE_WINDOW = 3;
    public static final int CYCLE_ACCENT = 4;
    /** A history chart's sample interval ({@link ChartSettings#INTERVALS}). */
    public static final int CYCLE_INTERVAL = 5;

    private static final int MODE = 0;
    private static final int SOURCE = 1;
    private static final int WINDOW = 2;
    private static final int ACCENT = 3;
    private static final int TAG = 4;
    private static final int LOCKED = 5;
    private static final int INTERVAL = 6;
    private static final int CHART_HOURS = 7;
    private static final int SLOTS = 8;

    private final @Nullable DisplayDevice device;
    private final Container ghost;
    /** Where the screen is (its panel, or its cable), and the part's face or -1; for the client's text editor. */
    private final BlockPos pos;
    private final int side;
    private final DataSlot[] data = new DataSlot[SLOTS];
    /** The task editor's board as the server last sent it; client side. */
    private @Nullable TaskBoardSync tasks;
    private int tasksVersion;

    private DisplayMenu(int containerId, Inventory inventory, @Nullable DisplayDevice device, Container ghost,
            BlockPos pos, int side) {
        super(ModMenus.DISPLAY.get(), containerId);
        this.device = device;
        this.ghost = ghost;
        this.pos = pos;
        this.side = side;
        addSlot(new GhostSlot(ghost, SLOT_X, SLOT_Y, this));
        addStandardInventorySlots(inventory, INVENTORY_X, INVENTORY_Y);
        for (int i = 0; i < SLOTS; i++) {
            data[i] = device == null ? DataSlot.standalone() : new Live(device, inventory.player, i);
            addDataSlot(data[i]);
        }
    }

    /** The client's menu; data slots and slot sync fill it. */
    public static DisplayMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        int side = buffer.readVarInt();
        return new DisplayMenu(containerId, inventory, null, new SimpleContainer(1), pos, side);
    }

    /** The server's menu over {@code device}'s live settings; GameTests build it directly. */
    public static DisplayMenu forDevice(int containerId, Inventory inventory, DisplayDevice device) {
        return new DisplayMenu(containerId, inventory, device, new SimpleContainer(1), BlockPos.ZERO, -1);
    }

    /** The screen the menu configures; null on the client. */
    public @Nullable DisplayDevice device() {
        return device;
    }

    /**
     * The client's copy of the screen's settings (with its text), from the panel or part the opening packet named; the
     * defaults if the client does not have it. Client side.
     */
    public DisplaySettings clientSettings(Level level) {
        DisplaySettings settings = DisplayTargets.settings(level, pos, side);
        return settings == null ? DisplaySettings.DEFAULT : settings;
    }

    /** Takes the board the server sent for the task editor; client side. */
    public void acceptTasks(TaskBoardSync sync) {
        tasks = sync;
        tasksVersion++;
    }

    /** The task editor's board as the server last sent it, or null before the first; client side. */
    public @Nullable TaskBoardSync tasks() {
        return tasks;
    }

    /** Counts the boards received; the editor rebuilds its list when it changed. */
    public int tasksVersion() {
        return tasksVersion;
    }

    /** Whether this GUI configures a part rather than a panel wall. */
    public boolean isPart() {
        return side >= 0;
    }

    /** The screen's size in blocks as the client knows it: its wall's panels, or a part's 12 px. Client side. */
    public Vec2 clientSize(Level level) {
        if (side >= 0) {
            return new Vec2(0.75F, 0.75F);
        }
        if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof DisplayPanelBlockEntity panel
                && level.getBlockEntity(panel.anchor()) instanceof DisplayPanelBlockEntity anchor) {
            return new Vec2(anchor.wallWidth(), anchor.wallHeight());
        }
        return new Vec2(1, 1);
    }

    /** The client's view of the screen (its live values), or null if the client does not have it. Client side. */
    public @Nullable DisplayView clientView(Level level) {
        if (side < 0 && level.isLoaded(pos) && level.getBlockEntity(pos) instanceof DisplayPanelBlockEntity panel) {
            // A wall's values live in its anchor's view.
            return DisplayTargets.view(level, panel.anchor(), -1);
        }
        return DisplayTargets.view(level, pos, side);
    }

    /** Opens the GUI of the screen at {@code pos} ({@code side} -1 for a panel, else the part's face). */
    public static void open(ServerPlayer player, DisplayDevice device, BlockPos pos, int side, Component title) {
        Platform.get().menus().open(player,
                new SimpleMenuProvider((id, inventory, opener) -> forDevice(id, inventory, device), title), buffer -> {
                    buffer.writeBlockPos(pos);
                    buffer.writeVarInt(side);
                });
    }

    public DisplayMode mode() {
        return DisplayMode.byId(data[MODE].get());
    }

    public DisplaySourceKind source() {
        return DisplaySourceKind.byId(data[SOURCE].get());
    }

    public int windowSeconds() {
        return data[WINDOW].get();
    }

    public int accent() {
        return data[ACCENT].get();
    }

    /** The synced tag index in the counted item's tags ({@link ConditionMenu#tags}), -1 for none. */
    public int tagIndex() {
        return (short) data[TAG].get();
    }

    /** A history chart's chosen sample interval in ticks. */
    public int chartInterval() {
        return data[INTERVAL].get();
    }

    /** A history chart's window in hours. */
    public int chartHours() {
        return data[CHART_HOURS].get();
    }

    /** Whether the graph modes wait for research. */
    public boolean graphsLocked() {
        return data[LOCKED].get() != 0;
    }

    /** The ghost slot's item: the counted item, or the tag's source item. */
    public ItemStack countedItem() {
        return slots.get(0).getItem();
    }

    @Override
    public void broadcastChanges() {
        DisplayDevice current = device;
        if (current != null) {
            ItemStack shown = current.settings().item()
                    .map(key -> key instanceof ItemKey item ? item.toStack(1) : ItemStack.EMPTY)
                    .orElse(ItemStack.EMPTY);
            if (!ItemStack.isSameItemSameComponents(shown, ghost.getItem(0))) {
                ghost.setItem(0, shown);
            }
        }
        super.broadcastChanges();
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        DisplayDevice current = device;
        if (current == null || !NetworkAccess.canConfigure(player, current)) {
            return false;
        }
        DisplaySettings settings = current.settings();
        DisplaySettings changed = switch (id) {
            case CYCLE_MODE -> settings.withMode(nextMode(settings.mode(), player));
            case CYCLE_SOURCE -> settings.withSource(nextSource(settings.source(), player));
            case NEXT_TAG -> settings.withTag(nextTag(settings).orElse(null));
            case CYCLE_WINDOW -> settings.source() == DisplaySourceKind.HISTORY
                    ? settings.withChart(settings.chart().nextWindow())
                    : settings.withWindow(nextWindow(settings.windowSeconds()));
            case CYCLE_INTERVAL -> settings.withChart(settings.chart().nextInterval());
            case CYCLE_ACCENT -> settings.withAccent((settings.accent() + 1) % DisplaySettings.ACCENTS.size());
            default -> null;
        };
        if (changed == null) {
            return false;
        }
        current.applySettings(changed);
        return true;
    }

    /** The mode after {@code mode}, skipping the graphs while the player's team lacks their research. */
    static DisplayMode nextMode(DisplayMode mode, Player player) {
        DisplayMode next = mode.next();
        return next.isGraph() && !ResearchUnlocks.isUnlocked(player, DisplayContent.HOLO_GRAPHS)
                ? DisplayMode.VALUE
                : next;
    }

    /** The source after {@code source}, skipping the history chart while the player's team lacks graph research. */
    static DisplaySourceKind nextSource(DisplaySourceKind source, Player player) {
        DisplaySourceKind next = source.next();
        return next == DisplaySourceKind.HISTORY && !ResearchUnlocks.isUnlocked(player, DisplayContent.HOLO_GRAPHS)
                ? next.next()
                : next;
    }

    static int nextWindow(int seconds) {
        int index = DisplaySettings.WINDOWS.indexOf(seconds);
        return DisplaySettings.WINDOWS.getInt((index + 1) % DisplaySettings.WINDOWS.size());
    }

    private static Optional<TagKey<Item>> nextTag(DisplaySettings settings) {
        if (settings.item().isEmpty() || !(settings.item().get() instanceof ItemKey key)) {
            return Optional.empty();
        }
        List<TagKey<Item>> tags = ConditionMenu.tags(key.item());
        if (tags.isEmpty()) {
            return Optional.empty();
        }
        int index = settings.tag().isPresent() ? tags.indexOf(settings.tag().get()) : -1;
        return Optional.of(tags.get((index + 1) % tags.size()));
    }

    /**
     * The ghost slot takes the carried item as the counted key, or clears with an empty hand; nothing real moves. A new
     * item also picks its first tag, so a tag count works at once.
     */
    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput containerInput, Player player) {
        if (slotIndex == 0) {
            DisplayDevice current = device;
            if (current != null && NetworkAccess.canConfigure(player, current)
                    && (containerInput == ContainerInput.PICKUP || containerInput == ContainerInput.QUICK_MOVE)) {
                setItem(current, containerInput == ContainerInput.PICKUP ? getCarried() : ItemStack.EMPTY);
            }
            return;
        }
        super.clicked(slotIndex, buttonNum, containerInput, player);
    }

    /** A shift-clicked inventory stack becomes the counted item. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        DisplayDevice current = device;
        if (index > 0 && current != null && NetworkAccess.canConfigure(player, current)) {
            ItemStack stack = slots.get(index).getItem();
            if (!stack.isEmpty()) {
                setItem(current, stack);
            }
        }
        return ItemStack.EMPTY;
    }

    private static void setItem(DisplayDevice device, ItemStack stack) {
        DisplaySettings settings = device.settings();
        if (stack.isEmpty()) {
            device.applySettings(settings.withItem(null).withTag(null));
            return;
        }
        List<TagKey<Item>> tags = ConditionMenu.tags(stack.getItem());
        device.applySettings(settings.withItem(ItemKey.of(stack)).withTag(tags.isEmpty() ? null : tags.get(0)));
    }

    @Override
    public boolean stillValid(Player player) {
        DisplayDevice current = device;
        return current == null || (current.stillValid(player) && NetworkAccess.canConfigure(player, current));
    }

    /** A data slot read from the live settings on the server. */
    private static final class Live extends DataSlot {
        private final DisplayDevice device;
        private final Player player;
        private final int index;
        /** The settings the tag index was worked out for; it needs the item's tag list, so not every tick. */
        private @Nullable DisplaySettings cachedFor;
        private int cachedTag;

        Live(DisplayDevice device, Player player, int index) {
            this.device = device;
            this.player = player;
            this.index = index;
        }

        // Settings are replaced, never changed, so identity says whether they are the ones the cache was made for.
        @SuppressWarnings("ReferenceEquality")
        @Override
        public int get() {
            DisplaySettings settings = device.settings();
            return switch (index) {
                case MODE -> settings.mode().id();
                case SOURCE -> settings.source().id();
                case WINDOW -> settings.windowSeconds();
                case ACCENT -> settings.accent();
                case TAG -> {
                    if (settings != cachedFor) {
                        cachedFor = settings;
                        cachedTag = tagIndex(settings);
                    }
                    yield cachedTag;
                }
                case INTERVAL -> settings.chart().intervalTicks();
                case CHART_HOURS -> settings.chart().windowHours();
                default -> ResearchUnlocks.isUnlocked(player, DisplayContent.HOLO_GRAPHS) ? 0 : 1;
            };
        }

        private static int tagIndex(DisplaySettings settings) {
            return settings.item().isPresent() && settings.item().get() instanceof ItemKey key
                    && settings.tag().isPresent() ? ConditionMenu.tags(key.item()).indexOf(settings.tag().get()) : -1;
        }

        @Override
        public void set(int value) {
        }
    }

    /** A ghost slot: it shows the counted item and cannot be taken from or placed into the vanilla way. */
    private static final class GhostSlot extends Slot {
        private final DisplayMenu menu;

        GhostSlot(Container container, int x, int y, DisplayMenu menu) {
            super(container, 0, x, y);
            this.menu = menu;
        }

        /** Hidden for a history chart, whose value is typed where the slot sits. */
        @Override
        public boolean isActive() {
            return menu.source() != DisplaySourceKind.HISTORY;
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
        return LabelMenu.device(device);
    }
}
