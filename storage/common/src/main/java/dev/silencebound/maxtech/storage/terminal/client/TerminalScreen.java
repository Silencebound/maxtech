package dev.silencebound.maxtech.storage.terminal.client;

import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.lib.api.client.gui.AccentButton;
import dev.silencebound.maxtech.lib.api.client.gui.IconButton;
import dev.silencebound.maxtech.lib.api.client.gui.Accent;
import dev.silencebound.maxtech.lib.api.client.gui.AccentToast;
import dev.silencebound.maxtech.lib.client.gui.accent.SideTabs;
import dev.silencebound.maxtech.storage.crafting.client.JobToastPanel;
import dev.silencebound.maxtech.storage.crafting.cpu.client.JobToasts;
import dev.silencebound.maxtech.storage.label.client.LabelTab;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.client.gui.ScreenComponents;
import dev.silencebound.maxtech.lib.client.gui.VirtualGrid;
import dev.silencebound.maxtech.lib.resource.StackKeys;
import dev.silencebound.maxtech.lib.sync.ListSort;
import dev.silencebound.maxtech.lib.sync.SyncedListIndex;
import dev.silencebound.maxtech.lib.api.client.gui.TextFit;
import dev.silencebound.maxtech.storage.config.StorageClientConfig;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternPanel;
import dev.silencebound.maxtech.storage.crafting.terminal.client.PatternPanelView;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.terminal.AmountFormat;
import dev.silencebound.maxtech.storage.terminal.TerminalAction;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import dev.silencebound.maxtech.storage.terminal.TerminalLayout;
import dev.silencebound.maxtech.storage.terminal.crafting.CraftingPanel;
import dev.silencebound.maxtech.storage.terminal.crafting.CraftingTerminalContent;
import dev.silencebound.maxtech.storage.tool.tablet.CraftingTabletContent;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.storage.terminal.TerminalOrder;
import dev.silencebound.maxtech.storage.terminal.TerminalPrefs;
import dev.silencebound.maxtech.storage.terminal.TerminalStatus;
import dev.silencebound.maxtech.storage.terminal.TerminalView;
import dev.silencebound.maxtech.storage.terminal.TerminalKind;
import dev.silencebound.maxtech.storage.terminal.TerminalSize;
import dev.silencebound.maxtech.storage.cell.CellFilter;
import dev.silencebound.maxtech.storage.terminal.filter.ViewFilterContent;
import dev.silencebound.maxtech.storage.terminal.filter.ViewFilterItem;
import dev.silencebound.maxtech.storage.terminal.filter.ViewFilters;
import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.Slot;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import dev.silencebound.maxtech.storage.crafting.client.CraftRequestScreen;
import dev.silencebound.maxtech.storage.crafting.request.RequestContent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.RenderPipelines;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * The terminal GUI in the Accent Frame style: a search box, the view button (all, items or fluids, kept in
 * {@link TerminalPrefs}) and sort buttons, a grid of the synced contents (items and fluids inline, a fluid drawn as its
 * bucket), a status line and the player's inventory. Search, view and sort run on the client's own index; {@code $}
 * words search tooltips, which {@link TerminalTooltips} builds lazily and caches. Clicks on the grid: with an empty
 * cursor, left takes a stack, right half a stack, middle (or control) one (on a craftable entry they request crafting
 * instead), shift-left a stack into the inventory; with a stack on the cursor, left stores it, right stores one or
 * empties a fluid container, and a click on a fluid fills the carried container. Releasing a stack picked up from an
 * inventory slot over the grid stores it too. Shift-right-click pins an entry; pinned entries come first, then the ones
 * taken lately. Saved searches sit as tabs left of the panel, and the deposit and restock keys ({@link TerminalKeys})
 * act while the screen is open. Craftable entries (M7c) carry a small cyan "C" at the top right; middle-, control- or
 * alt-click one, or click a craftable entry the network does not hold (greyed, listed after the stored ones, or alone
 * with the craftable filter), to request crafting. The buttons right of the panel are the craftable filter and the
 * registered {@link TerminalTab}s. A crafting terminal's menu shows its crafting panel in place of the grid's last
 * three rows ({@link TerminalLayout}): the 3 x 3 grid with a clear button left of it, the result, and the request
 * button under the result. A pattern terminal's menu shows its {@link PatternPanelView} there instead; with a draft
 * slot selected, a left click on an entry sets that slot to it, and an entry dragged from the grid onto a draft slot
 * does the same (both only name the key; no item moves). The size button right of the sort buttons picks small, tall or
 * fill ({@link TerminalSize}, kept per {@link TerminalKind}); the screen is made again with the menu's slots moved
 * whenever the size or the window's height changes the layout. A terminal block or part has four view filter slots in a
 * strip left of the panel, under the saved search tabs ({@link ViewFilters}): the grid lists only what their filters
 * show. Client only.
 */
public final class TerminalScreen extends AbstractContainerScreen<TerminalMenu> implements JobToasts.Host {
    public static final int WIDTH = 186;
    /** The hotbar ends 8 above the bottom edge, like vanilla's, so the base layout fits a 240 high window. */
    public static final int HEIGHT = 240;
    private static final int GRID_X = 8;
    private static final int GRID_Y = 24;
    private static final int COLUMNS = 9;
    private static final int CELL = 18;
    private static final int SEARCH_WIDTH = 82;
    private static final int SMALL_BUTTON = 14;
    private static final int SORT_WIDTH = 36;
    private static final ListSort[] SORTS = ListSort.values();
    /**
     * Kept between openings while the game runs, like the creative search; the sort starts from the client config's
     * defaults on the first opening.
     */
    private static String lastSearch = "";
    private static @Nullable ListSort lastSort;
    private static boolean lastReversed;
    /** Crafting job notices inside the screen (B09 item 21d). */
    private final JobToastPanel jobToast = new JobToastPanel();
    /** Whether the grid shows only craftable entries; kept between openings like the search. */
    private static boolean craftFilter;
    private static Component lastTitle = Component.empty();
    /** Grid ids at or below this stand for craftable keys that are not stored: {@code CRAFT_ONLY - craftableId}. */
    private static final int CRAFT_ONLY = -2;
    private static final int SIDE_BUTTON = 18;
    private static final Identifier GRID_ICON = TerminalTab.sprite("tab_grid");
    private static final Identifier VIEW_ALL_ICON = TerminalTab.sprite("view_all");
    private static final Identifier VIEW_ITEMS_ICON = TerminalTab.sprite("view_items");
    private static final Identifier VIEW_FLUIDS_ICON = TerminalTab.sprite("view_fluids");
    /** The view button's icons are 10 x 10, centred in the 14 x 14 button. */
    private static final int VIEW_ICON = 10;

    /** The crafting panel's clear button: a cross. */
    private static final dev.silencebound.maxtech.lib.api.client.gui.Icon CLEAR_ICON = dev.silencebound.maxtech.lib.api.client.gui.Icon
            .glyph("#....#", ".#..#.", "..##..", "..##..", ".#..#.", "#....#");

    /** The size button's icons, in {@link TerminalSize} order: a low box, a tall box, a box between two arrows. */
    private static final List<dev.silencebound.maxtech.lib.api.client.gui.Icon> SIZE_ICONS = List.of(
            dev.silencebound.maxtech.lib.api.client.gui.Icon.glyph("########", "#......#", "#......#", "#......#",
                    "########"),
            dev.silencebound.maxtech.lib.api.client.gui.Icon.glyph("########", "#......#", "#......#", "#......#",
                    "#......#", "#......#", "#......#", "#......#", "#......#", "########"),
            dev.silencebound.maxtech.lib.api.client.gui.Icon.glyph("...##...", "..####..", "........", "########",
                    "#......#", "#......#", "########", "........", "..####..", "...##..."));

    /** The sizes in the order of {@link #SIZE_ICONS}. */
    private static final List<TerminalSize> SIZES = List.of(TerminalSize.SMALL, TerminalSize.TALL, TerminalSize.FILL);

    private static final int TAB_WIDTH = 24;
    private static final int TAB_HEIGHT = 14;
    private static final int TAB_STEP = 16;

    private final SyncedListIndex<StackKey> index;
    private final SyncedListIndex<StackKey> craftIndex;
    private final Object2IntOpenHashMap<StackKey> storedIds = new Object2IntOpenHashMap<>();
    private int storedVersion = -1;
    private @Nullable IntList arrangedCraftFrom;
    private boolean arrangedFilter;
    private final TerminalPrefs<StackKey> prefs;
    private final IntArrayList arranged = new IntArrayList();
    private @Nullable IntList arrangedFrom;
    private int arrangedVersion = -1;
    private final Int2ObjectOpenHashMap<Icon> icons = new Int2ObjectOpenHashMap<>();
    private @Nullable EditBox search;
    private @Nullable VirtualGrid grid;
    private boolean dragFromSlot;
    /** The stored entry under the mouse at the last frame, -1 for none; the locate key acts on it. */
    private int hoveredEntry = -1;
    private final int rows;
    private final int statusY;
    private final Inventory inventory;
    private final TerminalKind kind;
    /** Set when the window's height asks for another layout; the screen is made again on the next tick. */
    private boolean relayoutWanted;
    /** What the screen this one replaced showed, applied on the first {@link #init}; null for a fresh screen. */
    private @Nullable Carried carried;
    /** A carried scroll row, applied once the grid has its entries (it clamps to them); -1 for none. */
    private int pendingScroll = -1;
    /** The view filters the grid's filter was built from, by slot; compared each tick, rebuilt only on a change. */
    private final @Nullable CellFilter[] shownFilters = new CellFilter[TerminalLayout.FILTER_SLOTS];
    private @Nullable TerminalView filterView;
    private @Nullable Predicate<StackKey> gridFilter;
    /** The pattern terminal's panel; null for other terminals. */
    private final @Nullable PatternPanelView patternView;
    /**
     * A pattern terminal's entry the mouse went down on with an empty hand and no slot selected, -1 for none: let go
     * over a draft slot it sets that slot, let go over the entry it takes a stack as on any terminal.
     */
    private int pressedEntry = -1;
    /** The {@link TerminalTooltips#generation} the indexes' tooltips were built in. */
    private int tooltipGeneration = TerminalTooltips.generation();

    /**
     * The client-only state a size change or window resize carries into the rebuilt screen: the grid's scroll row, the
     * pattern panel's selected slot and whether the search box had focus.
     */
    private record Carried(int scroll, int patternSelected, boolean searchFocused) {
    }

    /** A cached icon for one list entry; rebuilt when the id names another key. */
    private record Icon(StackKey key, ItemStack stack) {
    }

    public TerminalScreen(TerminalMenu menu, Inventory inventory, Component title) {
        this(menu, inventory, title, Minecraft.getInstance().getWindow().getGuiScaledHeight(), null);
    }

    /** The screen for a window {@code windowHeight} GUI pixels high: the menu's slots move to the layout first. */
    private TerminalScreen(TerminalMenu menu, Inventory inventory, Component title, int windowHeight,
            @Nullable Carried carried) {
        super(menu, inventory, title, WIDTH, prepare(menu, inventory, windowHeight).height());
        this.carried = carried;
        this.inventory = inventory;
        this.kind = kind(menu);
        this.rows = menu.layout().itemRows();
        this.statusY = menu.layout().statusY();
        PatternPanel pattern = menu.patternPanel();
        this.patternView = pattern == null ? null : new PatternPanelView(menu, pattern);
        this.index = new SyncedListIndex<>(menu.clientList(), TerminalTooltips.INSTANCE);
        this.craftIndex = new SyncedListIndex<>(menu.craftingData().craftables(), TerminalTooltips.INSTANCE);
        storedIds.defaultReturnValue(-1);
        this.prefs = TerminalPrefsFile.get(inventory.player.registryAccess());
        this.inventoryLabelX = TerminalMenu.INVENTORY_X;
        this.inventoryLabelY = menu.inventoryY() - 11;
        this.titleLabelY = -100;
    }

    private static TerminalKind kind(TerminalMenu menu) {
        return TerminalKind.of(menu.craftingPanel() != null, menu.patternPanel() != null);
    }

    /** The layout of the player's size for this kind in a window {@code windowHeight} high. */
    private static TerminalLayout layoutFor(TerminalMenu menu, TerminalPrefs<StackKey> prefs, int windowHeight) {
        return TerminalLayout.of(prefs.size(kind(menu)), windowHeight,
                menu.craftingPanel() != null || menu.patternPanel() != null);
    }

    /** Moves the menu's slots to the layout this screen will have, before the screen takes its height from it. */
    private static TerminalLayout prepare(TerminalMenu menu, Inventory inventory, int windowHeight) {
        TerminalLayout layout = layoutFor(menu, TerminalPrefsFile.get(inventory.player.registryAccess()), windowHeight);
        menu.relayout(layout);
        return layout;
    }

    /**
     * Makes the screen again for the current size and window when that changes the layout: the panel's height is fixed
     * when a screen is made, and the slots move with it. The menu, its list and the player's cursor stay.
     */
    private void relayoutIfNeeded() {
        if (minecraft != null && !layoutFor(menu, prefs, height).equals(menu.layout())) {
            VirtualGrid current = grid;
            EditBox box = search;
            PatternPanelView pattern = patternView;
            // The search text, sort, view and craftable filter are kept between screens already.
            Carried state = new Carried(current == null ? 0 : current.scroll(),
                    pattern == null ? -1 : pattern.selected(), box != null && box.isFocused());
            minecraft.setScreen(new TerminalScreen(menu, inventory, title, height, state));
        }
    }

    @Override
    protected void init() {
        super.init();
        // A resize, or a return from a sub-screen after one, may ask for another layout.
        relayoutWanted = !layoutFor(menu, prefs, height).equals(menu.layout());
        addRenderableWidget(LabelTab.of(this, menu, leftPos, topPos, imageWidth, imageHeight));
        lastTitle = title;
        startSort();
        EditBox box = new EditBox(font, leftPos + GRID_X + 4, topPos + 10, SEARCH_WIDTH - 8, 10,
                Component.translatable(TerminalContent.SEARCH_KEY));
        box.setBordered(false);
        box.setMaxLength(100);
        box.setTextColor(AccentFrame.TEXT);
        box.setHint(Component.translatable(TerminalContent.SEARCH_KEY));
        box.setTooltip(Tooltip.create(Component.translatable(TerminalContent.SEARCH_HELP_KEY)));
        if (StorageClientConfig.jeiSearchSync()) {
            String shared = TerminalSearchSync.get();
            if (shared != null) {
                lastSearch = shared;
            }
        }
        box.setValue(lastSearch);
        box.setResponder(text -> {
            lastSearch = text;
            if (StorageClientConfig.jeiSearchSync()) {
                TerminalSearchSync.set(text);
            }
        });
        search = addRenderableWidget(box);
        int buttonX = leftPos + GRID_X + SEARCH_WIDTH + 3;
        addRenderableWidget(new ViewButton(buttonX, topPos + 7));
        addRenderableWidget(new SortButton(buttonX + SMALL_BUTTON + 2, topPos + 7, SORT_WIDTH));
        addRenderableWidget(new DirectionButton(buttonX + SMALL_BUTTON + SORT_WIDTH + 4, topPos + 7));
        addRenderableWidget(IconButton.cycle(buttonX + 2 * SMALL_BUTTON + SORT_WIDTH + 6, topPos + 7, SIZE_ICONS,
                () -> SIZES.indexOf(prefs.size(kind)), state -> Component.translatable(TerminalContent.SIZE_KEY,
                        Component.translatable(sizeKey(SIZES.get(state)))),
                forward -> {
                    prefs.setSize(kind, prefs.size(kind).step(forward));
                    relayoutIfNeeded();
                }).size(SMALL_BUTTON));
        grid = addRenderableWidget(new VirtualGrid(leftPos + GRID_X, topPos + GRID_Y, COLUMNS, rows, CELL,
                Component.translatable(TerminalContent.SEARCH_KEY), this::renderCell, this::cellClicked));
        addRenderableWidget(new TabStrip(leftPos - TAB_WIDTH - 2, topPos + 8));
        addRenderableWidget(new SideStrip(leftPos + imageWidth + 2, topPos + 8));
        if (menu.craftingPanel() != null) {
            TerminalLayout layout = menu.layout();
            // The grid goes back to the network.
            addRenderableWidget(IconButton.of(leftPos + layout.clearX(), topPos + layout.clearY(), CLEAR_ICON,
                    Component.translatable(CraftingTerminalContent.CLEAR_KEY),
                    () -> send(TerminalAction.Kind.GRID_CLEAR, -1)));
            // As the crafting tablet's: orders jobs for what the grid lacks of the recipe a viewer sent last.
            Component request = Component.translatable(CraftingTabletContent.REQUEST_KEY);
            addRenderableWidget(new AccentButton(leftPos + layout.requestX(), topPos + layout.requestY(),
                    Math.min(layout.requestWidth(), AccentButton.width(font, request)), AccentButton.HEIGHT, request,
                    menu::networked, () -> send(TerminalAction.Kind.GRID_REQUEST, -1))
                    .tooltip(Component.translatable(CraftingTabletContent.REQUEST_TOOLTIP_KEY)));
        }
        PatternPanelView pattern = patternView;
        if (pattern != null) {
            pattern.init(leftPos, topPos, this::addRenderableWidget);
        }
        Carried state = carried;
        if (state != null) {
            carried = null;
            pendingScroll = state.scroll();
            if (pattern != null) {
                pattern.select(state.patternSelected());
            }
            if (state.searchFocused()) {
                setFocused(search);
            }
        }
        // The server sends nothing until asked, so the dump cannot arrive before this menu exists on the client.
        send(TerminalAction.Kind.REFRESH, -1);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (tooltipGeneration != TerminalTooltips.generation()) {
            // Resources or the language were reloaded while the screen was open.
            tooltipGeneration = TerminalTooltips.generation();
            index.invalidateTooltips();
            craftIndex.invalidateTooltips();
        }
        if (relayoutWanted) {
            relayoutWanted = false;
            relayoutIfNeeded();
        }
        if (patternView != null) {
            patternView.tick();
        }
        TerminalView view = prefs.view();
        Predicate<StackKey> filter = gridFilter(view);
        index.setFilter(filter);
        index.setSearch(lastSearch);
        index.setSort(sort(), lastReversed);
        craftIndex.setFilter(filter);
        craftIndex.setSearch(lastSearch);
        craftIndex.setSort(sort() == ListSort.COUNT ? ListSort.NAME : sort(), lastReversed);
    }

    /**
     * The grid's filter: the view's kinds and the view filters in the filter slots. The filters' lists are compared
     * with the ones the last filter was built from, so a new filter object (and an index rebuild) comes only when a
     * filter was put in, taken out or changed.
     */
    private @Nullable Predicate<StackKey> gridFilter(TerminalView view) {
        if (!menu.hasViewFilters()) {
            return view.filter();
        }
        boolean changed = view != filterView;
        for (int i = 0; i < TerminalLayout.FILTER_SLOTS; i++) {
            ItemStack stack = menu.viewFilterSlot(i).getItem();
            CellFilter filter = ViewFilterItem.is(stack) ? ViewFilterItem.filter(stack) : null;
            if (!Objects.equals(filter, shownFilters[i])) {
                shownFilters[i] = filter;
                changed = true;
            }
        }
        if (changed) {
            filterView = view;
            List<CellFilter> filters = new ArrayList<>(TerminalLayout.FILTER_SLOTS);
            for (CellFilter filter : shownFilters) {
                if (filter != null) {
                    filters.add(filter);
                }
            }
            gridFilter = ViewFilters.of(view, filters);
        }
        return gridFilter;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        AccentFrame.panel(graphics, font, leftPos, topPos, imageWidth, imageHeight, title, mouseX, mouseY);
        if (menu.hasViewFilters()) {
            AccentFrame.box(graphics, leftPos + TerminalLayout.FILTER_STRIP_X, topPos + menu.layout().filterStripY(),
                    TerminalLayout.FILTER_STRIP_WIDTH, TerminalLayout.FILTER_STRIP_HEIGHT);
        }
        graphics.fill(leftPos + GRID_X, topPos + 7, leftPos + GRID_X + SEARCH_WIDTH, topPos + 21,
                AccentFrame.SLOT_EDGE);
        graphics.fill(leftPos + GRID_X + 1, topPos + 8, leftPos + GRID_X + SEARCH_WIDTH - 1, topPos + 20,
                AccentFrame.SLOT);
        for (int i = 0; i < menu.slots.size(); i++) {
            AccentFrame.slot(graphics, leftPos + menu.slots.get(i).x, topPos + menu.slots.get(i).y);
        }
        // Every cell gets its slot here; the grid only calls renderCell for cells that hold an entry.
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                AccentFrame.slot(graphics, leftPos + GRID_X + column * CELL + 1, topPos + GRID_Y + row * CELL + 1);
            }
        }
        CraftingPanel panel = menu.craftingPanel();
        if (panel != null) {
            TerminalLayout layout = menu.layout();
            AccentFrame.horizontalBar(graphics, leftPos + layout.arrowX(), topPos + layout.arrowY(),
                    TerminalLayout.ARROW_WIDTH, TerminalLayout.ARROW_HEIGHT, panel.result().isEmpty() ? 0.0F : 1.0F);
        }
        if (patternView != null) {
            patternView.background(graphics);
        }
        VirtualGrid current = grid;
        if (current != null) {
            current.setEntries(arranged());
            if (pendingScroll >= 0) {
                current.setScroll(pendingScroll);
                pendingScroll = -1;
            }
        }
        hoveredEntry = current != null && overCells(mouseX, mouseY) ? current.entryAt(mouseX, mouseY) : -1;
    }

    /** The search's matches with pinned and recent entries first; rearranged only when something changed. */
    @SuppressWarnings("ReferenceEquality") // the indexes hand out a new list object whenever their view changed
    private IntList arranged() {
        IntList view = index.view();
        IntList craftView = craftIndex.view();
        if (view != arrangedFrom || prefs.version() != arrangedVersion || craftView != arrangedCraftFrom
                || arrangedFilter != craftFilter || (craftFilter && storedVersion != menu.clientList().version())) {
            arrangedFrom = view;
            arrangedVersion = prefs.version();
            arrangedCraftFrom = craftView;
            arrangedFilter = craftFilter;
            if (craftFilter) {
                arranged.clear();
                for (int i = 0; i < craftView.size(); i++) {
                    int craftable = craftView.getInt(i);
                    StackKey key = menu.craftingData().craftables().entry(craftable);
                    int stored = key == null ? -1 : storedId(key);
                    arranged.add(stored >= 0 ? stored : CRAFT_ONLY - craftable);
                }
            } else {
                TerminalOrder.arrange(view, this::pinned, this::recentRank, TerminalPrefs.RECENT, arranged);
                // Craftable keys the network does not hold come last, so they can be requested from the grid.
                for (int i = 0; i < craftView.size(); i++) {
                    int craftable = craftView.getInt(i);
                    StackKey key = menu.craftingData().craftables().entry(craftable);
                    if (key != null && storedId(key) < 0) {
                        arranged.add(CRAFT_ONLY - craftable);
                    }
                }
            }
        }
        return arranged;
    }

    /**
     * The keys the grid lists now, in its order: the current search and craftable filter, pinned and recent entries
     * first, then craftable ones the network does not hold (M7C item 10's "only what is shown" export). Allocates.
     */
    public List<StackKey> shownKeys() {
        IntList ids = arranged();
        List<StackKey> keys = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            StackKey key = keyOf(ids.getInt(i));
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    /** The contents id of {@code key}, or -1 when it is not stored; the lookup is rebuilt when the contents change. */
    private int storedId(StackKey key) {
        if (storedVersion != menu.clientList().version()) {
            storedVersion = menu.clientList().version();
            storedIds.clear();
            for (IntIterator it = menu.clientList().ids().iterator(); it.hasNext();) {
                int id = it.nextInt();
                StackKey stored = menu.clientList().entry(id);
                if (stored != null) {
                    storedIds.put(stored, id);
                }
            }
        }
        return storedIds.getInt(key);
    }

    /** The key a grid id shows: a stored entry, or a craftable one that is not stored. */
    private @Nullable StackKey keyOf(int id) {
        return id <= CRAFT_ONLY ? menu.craftingData().craftables().entry(CRAFT_ONLY - id) : menu.clientList().entry(id);
    }

    private boolean craftable(@Nullable StackKey key) {
        return key != null && menu.craftingData().isCraftable(key);
    }

    /** Opens the planner for {@code key}, one craft's worth by default. */
    private void request(StackKey key) {
        if (minecraft != null) {
            minecraft.setScreen(new CraftRequestScreen(menu, this, title, key, 1, false));
        }
    }

    /** The title of the terminal screen opened last; a crafting screen opened from a recipe viewer goes back to it. */
    public static Component lastTitle() {
        return lastTitle;
    }

    /**
     * Where draft slot {@code slot} of a pattern terminal is on the screen, for a recipe viewer's drag; null on other
     * terminals and for slots the draft's mode does not use.
     */
    public @Nullable Rect2i patternSlotArea(int slot) {
        PatternPanelView pattern = patternView;
        return pattern == null ? null : pattern.slotArea(slot);
    }

    /** A recipe viewer's ingredient dropped on draft slot {@code slot}: the slot is set to it, no item moves. */
    public void dropOnPattern(int slot, StackKey key) {
        PatternPanelView pattern = patternView;
        if (pattern != null) {
            pattern.ghost(slot, key);
        }
    }

    /** The areas the terminal draws outside its panel (the tab strips), for recipe viewers to keep clear. */
    public List<Rect2i> extraAreas() {
        List<Rect2i> areas = new ArrayList<>(3);
        areas.add(new Rect2i(leftPos - TAB_WIDTH - 2, topPos + 8, TAB_WIDTH, TAB_STEP * (TerminalPrefs.MAX_TABS + 1)));
        areas.add(new Rect2i(leftPos + imageWidth + 2, topPos + 8, SIDE_BUTTON, SIDE_BUTTON * (1 + tabs().size())));
        if (menu.hasViewFilters()) {
            areas.add(filterStrip());
        }
        return areas;
    }

    /** The view filter strip left of the panel, in screen coordinates. */
    private Rect2i filterStrip() {
        return new Rect2i(leftPos + TerminalLayout.FILTER_STRIP_X, topPos + menu.layout().filterStripY(),
                TerminalLayout.FILTER_STRIP_WIDTH, TerminalLayout.FILTER_STRIP_HEIGHT);
    }

    /** A click on the filter strip's frame is no click outside the screen, so it never throws the carried stack. */
    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top) {
        if (!super.hasClickedOutside(mouseX, mouseY, left, top)) {
            return false;
        }
        if (!menu.hasViewFilters()) {
            return true;
        }
        Rect2i strip = filterStrip();
        return !ScreenComponents.isHovering(strip.getX(), strip.getY(), strip.getWidth(), strip.getHeight(),
                (int) mouseX, (int) mouseY);
    }

    private List<TerminalTab> tabs() {
        List<TerminalTab> visible = new ArrayList<>();
        for (TerminalTab tab : TerminalTabs.all()) {
            if (tab.visible().test(menu)) {
                visible.add(tab);
            }
        }
        return visible;
    }

    private boolean pinned(int id) {
        StackKey key = menu.clientList().entry(id);
        return key != null && prefs.isPinned(key);
    }

    private int recentRank(int id) {
        StackKey key = menu.clientList().entry(id);
        return key == null ? -1 : prefs.recentRank(key);
    }

    private void renderCell(GuiGraphicsExtractor graphics, int id, int x, int y, boolean hovered) {
        Icon icon = icon(id);
        if (icon == null) {
            return;
        }
        graphics.item(icon.stack(), x + 1, y + 1);
        if (id <= CRAFT_ONLY) {
            // Not stored, only craftable: a greyed icon with the craftable mark and "craftable" under it.
            graphics.fill(x + 1, y + 1, x + 17, y + 17, 0x9015181B);
            craftableMark(graphics, x, y);
            String label = Component.translatable(RequestContent.CRAFTABLE_KEY).getString();
            graphics.pose().pushMatrix();
            graphics.pose().translate(x + 17 - font.width(label) / 2.0F, y + 13);
            graphics.pose().scale(0.5F, 0.5F);
            TextScale.text(graphics, font, label, 0, 0, AccentFrame.BAR_TOP, true);
            graphics.pose().popMatrix();
            return;
        }
        if (craftable(icon.key())) {
            craftableMark(graphics, x, y);
        }
        if (prefs.isPinned(icon.key())) {
            // A cyan corner marks a pin.
            graphics.fill(x + 1, y + 1, x + 4, y + 2, AccentFrame.BAR_TOP);
            graphics.fill(x + 1, y + 2, x + 2, y + 4, AccentFrame.BAR_TOP);
        }
        long amount = menu.clientList().count(id);
        boolean fluid = icon.key() instanceof FluidKey;
        graphics.itemDecorations(font, icon.stack(), x + 1, y + 1, "");
        boolean full = StorageClientConfig.amountStyle() == StorageClientConfig.AmountStyle.FULL;
        String label = fluid
                ? AmountFormat.fluid(amount)
                : full ? AmountFormat.full(amount) : AmountFormat.compact(amount);
        if (!fluid && label.length() <= 3) {
            if (amount > 1) {
                TextScale.text(graphics, font, label, x + 18 - font.width(label), y + 10, 0xFFFFFFFF, true);
            }
            return;
        }
        // Long labels in half-size text, so they stay inside the cell.
        graphics.pose().pushMatrix();
        graphics.pose().translate(x + 17 - font.width(label) / 2.0F, y + 13);
        graphics.pose().scale(0.5F, 0.5F);
        TextScale.text(graphics, font, label, 0, 0, fluid ? AccentFrame.BAR_TOP : 0xFFFFFFFF, true);
        graphics.pose().popMatrix();
    }

    /** A small cyan "C" on a dark backing at the cell's top right: the entry can be crafted. */
    private static void craftableMark(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.fill(x + 12, y + 1, x + 17, y + 7, 0xC00C0E10);
        graphics.fill(x + 13, y + 2, x + 16, y + 3, AccentFrame.BAR_TOP);
        graphics.fill(x + 13, y + 3, x + 14, y + 5, AccentFrame.BAR_TOP);
        graphics.fill(x + 13, y + 5, x + 16, y + 6, AccentFrame.BAR_TOP);
    }

    private @Nullable Icon icon(int id) {
        StackKey key = keyOf(id);
        if (key == null) {
            return null;
        }
        Icon icon = icons.get(id);
        if (icon == null || !icon.key().equals(key)) {
            ItemStack stack = switch (key) {
                case ItemKey item -> item.toStack(1);
                case FluidKey fluid -> {
                    ItemStack bucket = new ItemStack(fluid.fluid().getBucket());
                    yield bucket.isEmpty() ? new ItemStack(Items.BUCKET) : bucket;
                }
            };
            icon = new Icon(key, stack);
            icons.put(id, icon);
        }
        return icon;
    }

    private void cellClicked(int id, MouseButtonEvent event) {
        StackKey clicked = keyOf(id);
        PatternPanelView pattern = patternView;
        if (pattern != null && clicked != null && pattern.selected() >= 0 && plainLeft(event)) {
            // A draft slot is selected: the entry only names what goes there.
            pattern.ghostSelected(clicked);
            return;
        }
        if (clicked != null && menu.getCarried().isEmpty() && requests(id, clicked, event)) {
            request(clicked);
            return;
        }
        if (id <= CRAFT_ONLY) {
            return;
        }
        if (!menu.getCarried().isEmpty()) {
            depositOrFill(id, event);
            return;
        }
        int button = event.button();
        if (button == 1 && event.hasShiftDown()) {
            StackKey key = menu.clientList().entry(id);
            if (key != null) {
                prefs.togglePin(key);
            }
            return;
        }
        if (!(menu.clientList().entry(id) instanceof ItemKey)) {
            return;
        }
        TerminalAction.Kind kind;
        if (button == 2 || (button == 0 && event.hasControlDown())) {
            kind = TerminalAction.Kind.TAKE_ONE;
        } else if (button == 1) {
            kind = TerminalAction.Kind.TAKE_HALF;
        } else if (event.hasShiftDown()) {
            kind = TerminalAction.Kind.TAKE_TO_INVENTORY;
        } else {
            kind = TerminalAction.Kind.TAKE_STACK;
        }
        StackKey key = menu.clientList().entry(id);
        if (key != null) {
            prefs.used(key);
        }
        send(kind, id);
    }

    /**
     * Whether a click with an empty cursor on entry {@code id} requests crafting: any left or middle click on a
     * craftable entry the network does not hold; on a stored craftable one a middle, control or alt click (AE2's
     * bindings; alt is the older one), which on other entries take one item.
     */
    private boolean requests(int id, StackKey clicked, MouseButtonEvent event) {
        int button = event.button();
        if (id <= CRAFT_ONLY) {
            return button == 0 || button == 2;
        }
        return craftable(clicked) && (button == 2 || (button == 0 && (event.hasControlDown() || event.hasAltDown())));
    }

    /** A click with a stack on the cursor: on a fluid it fills the container, elsewhere it stores. */
    private void depositOrFill(int id, MouseButtonEvent event) {
        if (id >= 0 && menu.clientList().entry(id) instanceof FluidKey) {
            send(TerminalAction.Kind.FILL_CONTAINER, id);
        } else if (event.button() == 1) {
            // The server empties a fluid container, or stores one item of anything else.
            send(TerminalAction.Kind.DEPOSIT_ONE, -1);
        } else {
            send(TerminalAction.Kind.DEPOSIT_ALL, -1);
        }
    }

    private void send(TerminalAction.Kind kind, int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, new TerminalAction(kind, id).buttonId());
        }
    }

    private boolean overCells(double mouseX, double mouseY) {
        return ScreenComponents.isHovering(leftPos + GRID_X, topPos + GRID_Y, COLUMNS * CELL, rows * CELL, (int) mouseX,
                (int) mouseY);
    }

    /** A left click with an empty hand and no modifier keys. */
    private boolean plainLeft(MouseButtonEvent event) {
        return event.button() == 0 && menu.getCarried().isEmpty() && !event.hasShiftDown() && !event.hasControlDown()
                && !event.hasAltDown();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        dragFromSlot = false;
        pressedEntry = -1;
        if (event.button() == 0
                && jobToast.clicked(this, menu, font, leftPos, topPos, imageWidth, imageHeight, event.x(), event.y())) {
            return true;
        }
        PatternPanelView pattern = patternView;
        if (pattern != null && pattern.mouseClicked(event)) {
            return true;
        }
        if (pattern != null && grid != null && plainLeft(event) && overCells(event.x(), event.y())) {
            int entry = grid.entryAt(event.x(), event.y());
            StackKey key = entry == -1 ? null : keyOf(entry);
            if (key != null && pattern.selected() >= 0) {
                pattern.ghostSelected(key);
                return true;
            }
            if (key != null && entry >= 0) {
                // Taken on release, unless it is let go over a draft slot.
                pressedEntry = entry;
                return true;
            }
        }
        if (!menu.getCarried().isEmpty() && overCells(event.x(), event.y())) {
            // Empty cells too: the grid only reports clicks on entries.
            VirtualGrid current = grid;
            depositOrFill(current == null ? -1 : current.entryAt(event.x(), event.y()), event);
            return true;
        }
        if (menu.getCarried().isEmpty() && (event.button() == 0 || event.button() == 2)
                && overCells(event.x(), event.y()) && grid != null
                && grid.entryAt(event.x(), event.y()) <= CRAFT_ONLY) {
            // Lib's grid reports clicks on ids from 0 up only, so craftable-only entries are handled here.
            cellClicked(grid.entryAt(event.x(), event.y()), event);
            return true;
        }
        if (menu.getCarried().isEmpty() && hoveredSlot != null && hoveredSlot.hasItem()) {
            dragFromSlot = true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        int pressed = pressedEntry;
        PatternPanelView pattern = patternView;
        if (pressed >= 0 && pattern != null) {
            pressedEntry = -1;
            StackKey key = keyOf(pressed);
            int slot = pattern.slotAt(event.x(), event.y());
            if (key != null && slot >= 0) {
                pattern.ghost(slot, key);
            } else if (grid != null && overCells(event.x(), event.y())
                    && grid.entryAt(event.x(), event.y()) == pressed) {
                cellClicked(pressed, event);
            }
            return true;
        }
        boolean handled = super.mouseReleased(event);
        if (dragFromSlot && !menu.getCarried().isEmpty() && overCells(event.x(), event.y())) {
            send(TerminalAction.Kind.DEPOSIT_ALL, -1);
            handled = true;
        }
        dragFromSlot = false;
        return handled;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        EditBox box = search;
        if (box != null && box.isFocused() && !event.isEscape()) {
            // Printable keys arrive through charTyped; this keeps "e" and the hotbar keys from acting on the screen.
            box.keyPressed(event);
            return true;
        }
        if (TerminalKeys.DEPOSIT.matches(event)) {
            send(TerminalAction.Kind.DEPOSIT_STORED, -1);
            return true;
        }
        if (TerminalKeys.RESTOCK.matches(event)) {
            send(TerminalAction.Kind.RESTOCK_HOTBAR, -1);
            return true;
        }
        if (TerminalKeys.LOCATE.matches(event) && hoveredEntry >= 0 && menu.networked()) {
            send(TerminalAction.Kind.LOCATE, hoveredEntry);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void removed() {
        super.removed();
        if (minecraft != null && minecraft.player != null) {
            TerminalPrefsFile.save(minecraft.player.registryAccess());
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        TextScale.text(graphics, font, playerInventoryTitle, inventoryLabelX, inventoryLabelY + 1, AccentFrame.LABEL,
                false);
        TerminalStatus status = menu.status();
        Component state = Component.translatable(status.translationKey());
        TextScale.text(graphics, font, state, GRID_X, statusY,
                status == TerminalStatus.ONLINE || status == TerminalStatus.LOCAL ? AccentFrame.TEXT : 0xFFD9A05B,
                false);
        Component summary = patternView != null && patternView.result() != null
                ? patternView.result()
                : summary(status);
        if (summary != null) {
            // Right-aligned in the room the status leaves; the tooltip over the line has the details.
            int left = GRID_X + font.width(state) + 6;
            int width = Math.min(font.width(summary), imageWidth - 8 - left);
            int colour = patternView != null && patternView.result() != null
                    ? patternView.encoded() ? AccentFrame.TEXT : AccentFrame.BAR_TOP
                    : AccentFrame.LABEL;
            AccentFrame.text(graphics, font, summary, imageWidth - 8 - width, statusY, width, colour, xm, ym);
        }
        if (patternView != null) {
            patternView.labels(graphics);
        }
    }

    private @Nullable Component summary(TerminalStatus status) {
        if (status == TerminalStatus.LOCAL) {
            return Component.translatable(TerminalContent.SLOTS_KEY, menu.data(TerminalMenu.USED_SLOTS),
                    menu.data(TerminalMenu.SLOTS));
        }
        if (status == TerminalStatus.NO_NETWORK) {
            return null;
        }
        return Component.translatable(TerminalContent.SUMMARY_KEY, menu.data(TerminalMenu.CHANNELS),
                AmountFormat.compact(menu.data(TerminalMenu.ENERGY)));
    }

    @Override
    public void jobToast(Component title, Component detail, AccentToast.Kind kind, @Nullable UUID job) {
        jobToast.show(title, detail, kind, job);
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        jobToast.draw(graphics, font, leftPos, topPos, imageWidth, imageHeight);
        super.extractTooltip(graphics, mouseX, mouseY);
        PatternPanelView pattern = patternView;
        if (pressedEntry >= 0) {
            // The entry being dragged towards a draft slot follows the mouse.
            Icon dragged = icon(pressedEntry);
            if (dragged != null) {
                graphics.item(dragged.stack(), mouseX - 8, mouseY - 8);
            }
            return;
        }
        List<Component> patternLines = pattern == null ? null : pattern.tooltip(mouseX, mouseY);
        if (patternLines != null) {
            ScreenComponents.tooltip(graphics, font, patternLines, mouseX, mouseY);
            return;
        }
        Slot slot = hoveredSlot;
        if (slot != null && !slot.hasItem() && menu.getCarried().isEmpty() && menu.hasViewFilters()
                && menu.isViewFilterSlot(slot.index)) {
            ScreenComponents.tooltip(graphics, font, List.of(Component.translatable(ViewFilterContent.SLOTS_KEY),
                    Component.translatable(ViewFilterContent.SLOTS_HINT_KEY)), mouseX, mouseY);
            return;
        }
        if (slot != null || !menu.getCarried().isEmpty()) {
            return;
        }
        VirtualGrid current = grid;
        int id = current == null || !overCells(mouseX, mouseY) ? -1 : current.entryAt(mouseX, mouseY);
        Icon icon = id == -1 ? null : icon(id);
        if (icon != null && minecraft != null && id <= CRAFT_ONLY) {
            List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(minecraft, icon.stack()));
            lines.add(Component.translatable(RequestContent.CRAFTABLE_KEY).withColor(AccentFrame.BAR_TOP));
            lines.add(Component.translatable(RequestContent.CRAFT_HINT_KEY));
            ScreenComponents.tooltip(graphics, font, lines, mouseX, mouseY);
        } else if (icon != null && minecraft != null) {
            long amount = menu.clientList().count(id);
            List<Component> lines = new ArrayList<>();
            if (icon.key() instanceof FluidKey fluid) {
                lines.add(StackKeys.toResource(fluid).getHoverName());
                lines.add(Component.translatable(TerminalContent.FLUID_STORED_KEY, String.format("%,d", amount)));
                lines.add(Component.translatable(TerminalContent.FLUID_HINT_KEY));
            } else {
                lines.addAll(Screen.getTooltipFromItem(minecraft, icon.stack()));
                lines.add(Component.translatable(TerminalContent.STORED_KEY, String.format("%,d", amount)));
            }
            if (prefs.isPinned(icon.key())) {
                lines.add(Component.translatable(TerminalContent.PINNED_KEY));
            }
            if (craftable(icon.key())) {
                lines.add(Component.translatable(RequestContent.CRAFTABLE_KEY).withColor(AccentFrame.BAR_TOP));
                lines.add(Component.translatable(RequestContent.CRAFT_HINT_KEY));
            }
            lines.add(Component.translatable(TerminalContent.PIN_HINT_KEY));
            if (menu.networked()) {
                lines.add(Component.translatable(TerminalContent.LOCATE_HINT_KEY,
                        TerminalKeys.LOCATE.getTranslatedKeyMessage()));
            }
            ScreenComponents.tooltip(graphics, font, lines, mouseX, mouseY);
        } else if (menu.craftingPanel() != null
                && ScreenComponents.isHovering(leftPos + menu.layout().arrowX(), topPos + menu.layout().arrowY() - 4,
                        TerminalLayout.ARROW_WIDTH, TerminalLayout.ARROW_HEIGHT + 8, mouseX, mouseY)) {
            ScreenComponents.tooltip(graphics, font, List.of(
                    Component.translatable(CraftingTerminalContent.RESULT_HINT_KEY, CraftingPanel.MAX_SHIFT_CRAFTS)),
                    mouseX, mouseY);
        } else if (ScreenComponents.isHovering(leftPos + GRID_X, topPos + statusY - 1, COLUMNS * CELL, 10, mouseX,
                mouseY) && menu.status() != TerminalStatus.NO_NETWORK && menu.status() != TerminalStatus.LOCAL) {
            ScreenComponents.tooltip(graphics, font,
                    List.of(Component.translatable(TerminalContent.ENERGY_KEY, menu.data(TerminalMenu.ENERGY),
                            menu.data(TerminalMenu.CAPACITY)),
                            Component.translatable(TerminalContent.LINK_KEY, menu.data(TerminalMenu.CHANNELS),
                                    menu.data(TerminalMenu.LOAD)),
                            Component.translatable(TerminalContent.KINDS_KEY, menu.data(TerminalMenu.KINDS))),
                    mouseX, mouseY);
        }
    }

    /** The client config's sort, on the first opening in a session; later openings keep the player's choice. */
    private static void startSort() {
        if (lastSort == null) {
            lastSort = StorageClientConfig.sort();
            lastReversed = StorageClientConfig.ascending();
        }
    }

    private static ListSort sort() {
        ListSort sort = lastSort;
        return sort == null ? ListSort.COUNT : sort;
    }

    private static Component sortName(ListSort sort) {
        return Component.translatable(switch (sort) {
            case COUNT -> TerminalContent.SORT_COUNT_KEY;
            case NAME -> TerminalContent.SORT_NAME_KEY;
            case MOD -> TerminalContent.SORT_MOD_KEY;
        });
    }

    /** Cycles the sort key: count, name, mod. */
    private final class SortButton extends AbstractButton {
        SortButton(int x, int y, int width) {
            super(x, y, width, 14, Component.empty());
        }

        @Override
        public void onPress(InputWithModifiers input) {
            for (int i = 0; i < SORTS.length; i++) {
                if (SORTS[i] == sort()) {
                    lastSort = SORTS[(i + 1) % SORTS.length];
                    break;
                }
            }
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            setMessage(Component.translatable(TerminalContent.SORT_KEY, sortName(sort())));
            AccentFrame.button(graphics, font, getX(), getY(), width, height, sortName(sort()), isHovered(), mouseX,
                    mouseY);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private static String sizeKey(TerminalSize size) {
        return switch (size) {
            case SMALL -> TerminalContent.SIZE_SMALL_KEY;
            case TALL -> TerminalContent.SIZE_TALL_KEY;
            case FILL -> TerminalContent.SIZE_FILL_KEY;
        };
    }

    private static String viewKey(TerminalView view) {
        return switch (view) {
            case ALL -> TerminalContent.VIEW_ALL_KEY;
            case ITEMS -> TerminalContent.VIEW_ITEMS_KEY;
            case FLUIDS -> TerminalContent.VIEW_FLUIDS_KEY;
        };
    }

    private static Identifier viewIcon(TerminalView view) {
        return switch (view) {
            case ALL -> VIEW_ALL_ICON;
            case ITEMS -> VIEW_ITEMS_ICON;
            case FLUIDS -> VIEW_FLUIDS_ICON;
        };
    }

    /** Cycles which kinds the grid lists: all, items, fluids; saved in the terminal prefs. */
    private final class ViewButton extends AbstractButton {
        private @Nullable TerminalView shown;

        ViewButton(int x, int y) {
            super(x, y, SMALL_BUTTON, SMALL_BUTTON, Component.empty());
        }

        @Override
        public void onPress(InputWithModifiers input) {
            prefs.setView(prefs.view().next());
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            TerminalView view = prefs.view();
            if (view != shown) {
                shown = view;
                Component name = Component.translatable(TerminalContent.VIEW_KEY,
                        Component.translatable(viewKey(view)));
                setMessage(name);
                setTooltip(Tooltip.create(Component.translatable(TerminalContent.VIEW_HINT_KEY, name)));
            }
            AccentFrame.button(graphics, font, getX(), getY(), width, height, Component.empty(), isHovered(), mouseX,
                    mouseY);
            int inset = (SMALL_BUTTON - VIEW_ICON) / 2;
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, viewIcon(view), getX() + inset, getY() + inset, VIEW_ICON,
                    VIEW_ICON);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** Flips the sort direction; draws its arrow as pixels, so it needs no glyph or texture. */
    private final class DirectionButton extends AbstractButton {
        DirectionButton(int x, int y) {
            super(x, y, SMALL_BUTTON, SMALL_BUTTON, Component.empty());
        }

        @Override
        public void onPress(InputWithModifiers input) {
            lastReversed = !lastReversed;
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            setMessage(Component
                    .translatable(lastReversed ? TerminalContent.ASCENDING_KEY : TerminalContent.DESCENDING_KEY));
            AccentFrame.button(graphics, font, getX(), getY(), width, height, Component.empty(), isHovered(), mouseX,
                    mouseY);
            int centreX = getX() + width / 2;
            for (int row = 0; row < 4; row++) {
                // Down-pointing by default (largest first); up when reversed.
                int half = lastReversed ? row : 3 - row;
                int y = getY() + 5 + row;
                graphics.fill(centreX - half - 1, y, centreX + half, y + 1, AccentFrame.TEXT);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /**
     * The saved searches as tabs down the left of the panel, with a "+" under them that saves the current search. A
     * click on a tab searches for it, a right-click removes it; the tab whose search is showing is lit.
     */
    private final class TabStrip extends AbstractWidget {
        TabStrip(int x, int y) {
            super(x, y, TAB_WIDTH, TAB_STEP * (TerminalPrefs.MAX_TABS + 1),
                    Component.translatable(TerminalContent.TAB_ADD_KEY));
        }

        /** Only the drawn tabs take clicks; the rest of the strip lets them through (to drop a carried stack). */
        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            return super.isMouseOver(mouseX, mouseY) && slotAt(mouseY) >= 0;
        }

        /** The tab under {@code mouseY}: its index, {@code tabs.size()} for the "+", or -1. */
        private int slotAt(double mouseY) {
            int slot = (int) Math.floor((mouseY - getY()) / TAB_STEP);
            boolean onButton = mouseY - getY() - slot * TAB_STEP < TAB_HEIGHT;
            int tabs = prefs.tabs().size();
            int last = tabs < TerminalPrefs.MAX_TABS ? tabs : tabs - 1;
            return onButton && slot >= 0 && slot <= last ? slot : -1;
        }

        @Override
        protected boolean isValidClickButton(MouseButtonInfo buttonInfo) {
            return buttonInfo.button() == 0 || buttonInfo.button() == 1;
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            int slot = slotAt(event.y());
            List<String> tabs = prefs.tabs();
            if (slot < 0) {
                return;
            }
            if (slot == tabs.size()) {
                if (event.button() == 0) {
                    prefs.addTab(lastSearch);
                }
            } else if (event.button() == 1) {
                prefs.removeTab(slot);
            } else if (search != null) {
                search.setValue(tabs.get(slot));
            }
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            List<String> tabs = prefs.tabs();
            int hovered = isHovered() ? slotAt(mouseY) : -1;
            for (int i = 0; i < tabs.size(); i++) {
                String tab = tabs.get(i);
                String label = TextFit.fit(tab, AccentFrame.labelRoom(TAB_WIDTH), font::width);
                AccentFrame.button(graphics, font, getX(), getY() + i * TAB_STEP, TAB_WIDTH, TAB_HEIGHT,
                        Component.literal(label), i == hovered || tab.equals(lastSearch.strip()));
            }
            if (tabs.size() < TerminalPrefs.MAX_TABS) {
                AccentFrame.button(graphics, font, getX(), getY() + tabs.size() * TAB_STEP, TAB_WIDTH, TAB_HEIGHT,
                        Component.literal("+"), hovered == tabs.size());
            }
            if (hovered >= 0) {
                Component tip = hovered < tabs.size()
                        ? Component.translatable(TerminalContent.TAB_KEY, tabs.get(hovered))
                        : Component.translatable(TerminalContent.TAB_ADD_KEY);
                ScreenComponents.tooltip(graphics, font, List.of(tip), mouseX, mouseY);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, getMessage());
        }
    }

    /**
     * The buttons down the right of the panel: the craftable filter, then the registered {@link TerminalTab}s that show
     * for this menu. Only network terminals offer them.
     */
    private final class SideStrip extends AbstractWidget {
        SideStrip(int x, int y) {
            super(x, y, SIDE_BUTTON, SIDE_BUTTON * 8, Component.translatable(RequestContent.FILTER_CRAFTABLE_KEY));
        }

        /** Only the drawn buttons take clicks; the rest of the strip lets them through (to drop a carried stack). */
        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            return super.isMouseOver(mouseX, mouseY) && slotAt(mouseY) >= 0;
        }

        private int slotAt(double mouseY) {
            int slot = (int) Math.floor((mouseY - getY()) / SIDE_BUTTON);

            return menu.networked() && slot >= 0 && slot <= tabs().size() ? slot : -1;
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            int slot = slotAt(event.y());
            if (slot == 0) {
                craftFilter = !craftFilter;
            } else if (slot > 0 && minecraft != null) {
                minecraft.setScreen(tabs().get(slot - 1).opener().open(TerminalScreen.this, menu));
            }
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            if (!menu.networked()) {
                return;
            }
            List<TerminalTab> tabs = tabs();
            int hovered = isHovered() ? slotAt(mouseY) : -1;
            for (int slot = 0; slot <= tabs.size(); slot++) {
                int y = getY() + slot * SIDE_BUTTON;
                boolean lit = slot == 0 && craftFilter;
                AccentFrame.button(graphics, font, getX(), y, SIDE_BUTTON, SIDE_BUTTON, Component.empty(),
                        lit || slot == hovered);
                Identifier icon = slot == 0 ? GRID_ICON : tabs.get(slot - 1).icon();
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, icon, getX() + 1, y + 1, 16, 16);
                SideTabs.Badge badge = slot == 0 ? null : tabs.get(slot - 1).badge().get();
                if (badge != null) {
                    // B09 item 21d: red or amber, as on the device side tabs.
                    Accent.dot(graphics, getX() + SIDE_BUTTON - 1 - SideTabs.BADGE, y + 1, SideTabs.BADGE,
                            badge.level().colour());
                }
            }
            if (hovered >= 0) {
                Component tip = hovered == 0
                        ? Component.translatable(
                                craftFilter ? RequestContent.FILTER_CRAFTABLE_KEY : RequestContent.FILTER_ALL_KEY)
                        : tabs.get(hovered - 1).title();
                SideTabs.Badge badge = hovered == 0 ? null : tabs.get(hovered - 1).badge().get();
                ScreenComponents.tooltip(graphics, font,
                        badge == null
                                ? List.of(tip)
                                : List.of(tip, badge.reason().copy().withColor(badge.level().colour())),
                        mouseX, mouseY);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, getMessage());
        }
    }
}
