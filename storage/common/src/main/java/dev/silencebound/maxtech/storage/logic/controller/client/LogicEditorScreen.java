package dev.silencebound.maxtech.storage.logic.controller.client;

import dev.silencebound.maxtech.lib.client.gui.TextScale;
import dev.silencebound.maxtech.storage.palette.StatusPalette;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.client.gui.PanView;
import dev.silencebound.maxtech.lib.client.gui.PixelScale;
import dev.silencebound.maxtech.lib.client.gui.RightAngleWire;
import dev.silencebound.maxtech.lib.client.gui.SidePanel;
import dev.silencebound.maxtech.lib.client.gui.UndoStack;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.text.TextFit;
import dev.silencebound.maxtech.storage.crafting.client.KeyDisplay;
import dev.silencebound.maxtech.storage.logic.GraphError;
import dev.silencebound.maxtech.storage.logic.LogicCompiler;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicNode;
import dev.silencebound.maxtech.storage.logic.NetworkRef;
import dev.silencebound.maxtech.storage.logic.NodeSettings;
import dev.silencebound.maxtech.storage.logic.NodeType;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Tooltip;
import dev.silencebound.maxtech.storage.logic.Port;
import dev.silencebound.maxtech.storage.logic.Sequences;
import dev.silencebound.maxtech.storage.logic.PortType;
import dev.silencebound.maxtech.storage.logic.SettingKind;
import dev.silencebound.maxtech.storage.logic.SettingSpec;
import dev.silencebound.maxtech.storage.logic.SettingValue;
import dev.silencebound.maxtech.storage.logic.Wire;
import dev.silencebound.maxtech.storage.logic.controller.ControllerTexts;
import dev.silencebound.maxtech.storage.logic.controller.EditorPayloads;
import dev.silencebound.maxtech.storage.logic.controller.EditorTexts;
import dev.silencebound.maxtech.storage.logic.controller.GraphSlot;
import dev.silencebound.maxtech.storage.logic.controller.HistoryEntry;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerMenu;
import dev.silencebound.maxtech.storage.logic.controller.LogicEditing;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.logic.controller.LogicWorker;
import dev.silencebound.maxtech.storage.logic.editor.EditorGraph;
import dev.silencebound.maxtech.storage.logic.editor.EditorNote;
import dev.silencebound.maxtech.storage.logic.editor.LiveLine;
import dev.silencebound.maxtech.storage.logic.editor.NodeLayout;
import dev.silencebound.maxtech.storage.logic.editor.Palette;
import dev.silencebound.maxtech.storage.logic.editor.SettingRules;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentButton;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * The node editor (M7L area F), opened from the Logic Controller screen per graph over the controller's open menu.
 *
 * <p>
 * It draws at its own scale ({@link PixelScale}): one unit is a whole number of window pixels, fitted to the window and
 * never smaller than two pixels, whatever the player's GUI scale, so the canvas uses the window and text stays crisp.
 * Left: the palette by category; centre: a pannable canvas ({@link PanView}) with compact nodes, typed ports and
 * right-angled wires ({@link RightAngleWire}); right: the selected node's settings ({@link SidePanel}); bottom: the
 * "why" line. Dragging from a port draws a wire that joins only a port of the same type; dropped on empty space, it
 * leaves the palette showing only the nodes that take it. Delete removes the selection, Ctrl+Z undoes
 * ({@link UndoStack} of graph snapshots), Ctrl+S saves. Errors and warnings show on their nodes while editing (the
 * compiler runs on the client too); live values on wires and the why line come from the server while the editor is
 * open.
 *
 * <p>
 * One player edits a graph at a time: others see it read-only with the editor's name. Save sends the whole graph; the
 * server checks and saves it, and a graph with errors is saved but paused. Client only.
 */
public final class LogicEditorScreen extends Screen {
    // Layout in units.
    /** The narrowest layout; a wide palette widens it, so text is never cut to fit a fixed size. */
    private static final int DESIGN_WIDTH = 400;
    private static final int DESIGN_HEIGHT = 240;
    private static final int MIN_PIXELS = 2;
    private static final int BAR = 16;
    private static final int GAP = 2;
    private static final int PALETTE_MIN = 90;
    private static final int PALETTE_MAX = 150;
    /** The palette row's text starts this far into the body (after the category mark). */
    private static final int PALETTE_TEXT = 7;
    private static final int MIN_CANVAS = 180;
    private static final int PANEL_WIDTH = 116;
    private static final int WHY_HEIGHT = 12;
    private static final int ROW = 10;
    private static final int GRID = 16;
    private static final int BUTTON_WIDTH = 30;
    private static final int BUTTON_HEIGHT = 12;
    private static final int NAME_MIN = 100;
    private static final int NAME_MAX = 170;
    private static final int STATUS_MIN = 90;
    private static final int UNDO_DEPTH = 100;
    private static final long FLASH_MILLIS = 3500;
    private static final int ITEM_RESULTS = 12;
    private static final int LABEL_CHIPS = 6;
    private static final int STUB = 6;
    private static final int ASK_AGAIN_TICKS = 20;
    /** How long Revert waits for the second click that confirms it. */
    private static final long REVERT_MILLIS = 4000;
    private static final int PIN_MARK = 0xFF40C8E0;
    /** Tooltip lines wrap at half the window's GUI width, but never narrower than this. */
    private static final int TOOLTIP_MIN_WRAP = 160;

    // Colours: Accent Frame for the frame, the concept page's port colours for wires and ports.
    private static final int CANVAS = 0xFF101316;
    private static final int GRID_COLOUR = 0xFF191D21;
    private static final int SELECTED = 0xFFFFFF55;
    private static final int WHY_MARK = 0xFFFF9F40;
    private static final int LIVE_BACK = 0xC0000000;
    private static final int DIM = 0xFF4A5257;
    private static final int HOVER = 0x30FFFFFF;
    private static final SidePanel.Style PANEL_STYLE = new SidePanel.Style(AccentFrame.GRAPHITE, AccentFrame.OUTLINE,
            AccentFrame.TEXT);

    private enum Drag {
        NONE, PAN, NODE, WIRE, PALETTE
    }

    private enum ControlKind {
        LINE, CHOICE, FLAG, LABEL, HELD, RESULT, ITEM, DELETE, PIN
    }

    /**
     * Something drawn in the settings panel, in content coordinates; clickable unless it is a line or the item. Made by
     * {@link #control}, which cuts {@code shown} to fit and makes the icon once.
     */
    private record Control(ControlKind kind, String setting, int x, int y, int width, int height, String value,
            String shown, String full, @Nullable LogicKey key, ItemStack icon, int colour, boolean on) {
    }

    /** A setting shown in the panel and its text box, if it has one. */
    private static final class Entry {
        final SettingSpec spec;
        final boolean wired;
        @Nullable
        EditBox box;
        @Nullable
        EditBox search;
        int boxY;
        int searchY;

        Entry(SettingSpec spec, boolean wired) {
            this.spec = spec;
            this.wired = wired;
        }
    }

    private final Screen parent;
    private final LogicControllerMenu menu;
    private final int index;
    /** The node to centre on and select when the graph first arrives; -1 for none. */
    private final int focus;
    private boolean asked;
    private int waited;
    private boolean closed;
    private PixelScale scale = PixelScale.of(DESIGN_WIDTH, DESIGN_HEIGHT, 1, DESIGN_WIDTH, DESIGN_HEIGHT, MIN_PIXELS);
    private final PanView view = new PanView();
    private final SidePanel palette = new SidePanel();
    private final SidePanel panel = new SidePanel();
    /** Whether the palette lists every node type or only the common ones; remembered for the player. */
    private boolean paletteAll = LogicEditorPrefs.paletteAll();
    private List<Palette.Row> rows = Palette.rows(paletteAll);
    private final boolean[] accepts = new boolean[Palette.rows(true).size()];
    private final List<Component> reasons = new ArrayList<>(accepts.length);
    private boolean centred;

    // The graph and what was received with it.
    private @Nullable EditorGraph<LogicKey> graph;
    private String name;
    private boolean readOnly;
    private String editor = "";
    /** The editor's history panel shows in place of the settings (M7L2 chunk 9). */
    private boolean showHistory;
    /** The history list the panel was laid out from, by identity; the menu holds a new list after each change. */
    private @Nullable List<HistoryEntry> shownHistory;
    private @Nullable AccentButton editNow;
    private List<EditorPayloads.LabelInfo> labels = List.of();
    private boolean networkKnown;
    private int maxNodes = 64;
    /** Nodes whose values are pinned to the controller screen; saved with the graph. */
    private final IntArrayList pins = new IntArrayList();
    private int maxPins;
    /** Revert was clicked once; a second click before this time confirms it. */
    private long revertUntil;
    private boolean dirty;
    private boolean leaveArmed;
    /** After the last save: whether it was saved paused; null when nothing was saved in this editor. */
    private @Nullable Boolean savedPaused;
    private final UndoStack<LogicGraph<LogicKey>> undo = new UndoStack<>(UNDO_DEPTH);
    private String lastTyped = "";

    // Caches rebuilt when the graph changes, so drawing a frame looks nothing up by name.
    private final Int2ObjectOpenHashMap<LogicNode<LogicKey>> byId = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<List<EditorNote>> notesByNode = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<String> summaries = new Int2ObjectOpenHashMap<>();
    private final Map<NodeType, String> titles = new HashMap<>();
    /** Full summaries, for the tooltip when the shown one is shortened. */
    private final Int2ObjectOpenHashMap<String> fullSummaries = new Int2ObjectOpenHashMap<>();
    /** Per node: its width ({@link NodeLayout#width}) and its title fitted to it. */
    private final Int2IntOpenHashMap widths = new Int2IntOpenHashMap();
    private final Int2ObjectOpenHashMap<String> nodeTitles = new Int2ObjectOpenHashMap<>();
    /** Per node with a live line: the line fitted to the node, its colour and its tooltip. */
    private final Int2ObjectOpenHashMap<String> liveLines = new Int2ObjectOpenHashMap<>();
    private final Int2IntOpenHashMap liveColours = new Int2IntOpenHashMap();
    private final Int2ObjectOpenHashMap<List<Component>> liveTips = new Int2ObjectOpenHashMap<>();
    private boolean liveCountdown;
    /** Palette titles fitted to the palette's width, made once per layout. */
    private final Map<NodeType, String> paletteTitles = new HashMap<>();
    private int paletteWidth = PALETTE_MIN;
    /** Where the top bar's parts are, from the last layout. */
    private EditorTopBar.@Nullable Layout bar;
    /** The More menu with the secondary top-bar buttons is open (only when they did not fit on the bar). */
    private boolean moreOpen;
    private final List<AccentButton> menuButtons = new ArrayList<>(3);
    private List<EditorNote> notes = List.of();
    private int errors;
    private Wire[] wires = new Wire[0];
    private int[] wireOut = new int[0];
    private int[] wireIn = new int[0];
    private PortType[] wireTypes = new PortType[0];
    private @Nullable String[] wireLabels = new String[0];
    private boolean[] wireLit = new boolean[0];

    // Live values from the server.
    private EditorPayloads.@Nullable Live live;
    private final Int2ObjectOpenHashMap<Object2LongOpenHashMap<String>> liveValues = new Int2ObjectOpenHashMap<>();
    private @Nullable Component whyLine;
    private List<Component> whyLines = List.of();

    // Selection and dragging.
    private int selected = -1;
    private @Nullable Wire selectedWire;
    private Drag drag = Drag.NONE;
    private int dragNode = -1;
    private double grabX;
    private double grabY;
    private @Nullable LogicGraph<LogicKey> dragBefore;
    private int wireNode = -1;
    private String wirePort = "";
    private PortType wireType = PortType.EVENT;
    private boolean wireFromOutput;
    private double wireX;
    private double wireY;
    private boolean pending;
    private @Nullable NodeType paletteType;
    private double pressX;
    private double pressY;

    // The settings panel.
    private final List<Entry> entries = new ArrayList<>();
    private final List<Control> controls = new ArrayList<>();
    private String search = "";
    private List<LogicKey> results = List.of();

    private @Nullable EditBox nameBox;
    private @Nullable Component flash;
    private int flashColour = StatusPalette.bad();
    private long flashUntil;
    private final int[] route = new int[RightAngleWire.MAX_POINTS * 2];
    private final int[] labelAt = new int[2];
    private @Nullable List<Component> tooltip;

    public LogicEditorScreen(Screen parent, LogicControllerMenu menu, int index) {
        this(parent, menu, index, -1);
    }

    /**
     * Opens graph {@code index}; once the graph arrives the view centres on node {@code focus} and selects it (the
     * controller's NODES diagram passes the node that was clicked), or on the whole graph when {@code focus} is -1 or
     * not in the graph.
     */
    public LogicEditorScreen(Screen parent, LogicControllerMenu menu, int index, int focus) {
        super(Component.translatable(EditorTexts.TITLE));
        this.parent = parent;
        this.menu = menu;
        this.index = index;
        this.name = menu.name(index);
        this.focus = focus;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- layout

    @Override
    protected void init() {
        Window window = minecraft.getWindow();
        // Widths from the measured text first, then the largest scale at which that layout fits the window.
        // Sized for every node type, so switching between Common and All keeps the layout.
        List<String> paletteTexts = new ArrayList<>();
        paletteTexts.add(I18n.get(EditorTexts.PALETTE_COMMON));
        paletteTexts.add(I18n.get(EditorTexts.PALETTE_ALL));
        for (Palette.Row row : Palette.rows(true)) {
            paletteTexts.add(row.type().map(type -> I18n.get(type.nameKey()))
                    .orElseGet(() -> I18n.get(row.category().nameKey())));
        }
        paletteWidth = TextFit.grow(
                TextFit.widest(paletteTexts, font::width) + PALETTE_TEXT + 6 + SidePanel.SCROLLBAR_WIDTH, PALETTE_MIN,
                PALETTE_MAX);
        int designWidth = Math.max(DESIGN_WIDTH, paletteWidth + PANEL_WIDTH + MIN_CANVAS + GAP * 4);
        scale = PixelScale.of(window.getWidth(), window.getHeight(), window.getGuiScale(), designWidth, DESIGN_HEIGHT,
                MIN_PIXELS);
        int w = scale.width();
        int h = scale.height();
        // Each button is as wide as its own longest label; the secondary ones go into the More menu when the bar is
        // too narrow for all of them.
        int[] primary = {buttonWidth(EditorTexts.UNDO), buttonWidth(EditorTexts.REDO), buttonWidth(EditorTexts.SAVE),
                buttonWidth(EditorTexts.BACK)};
        int[] secondary = {buttonWidth(EditorTexts.HISTORY, EditorTexts.HISTORY_CLOSE),
                buttonWidth(ControllerTexts.SIMULATE, ControllerTexts.SIMULATE_STOP),
                buttonWidth(ControllerTexts.REVERT, ControllerTexts.REVERT_CONFIRM)};
        int editNowWidth = buttonWidth(EditorTexts.EDIT_NOW);
        EditorTopBar.Layout layout = EditorTopBar.layout(w, GAP, NAME_MIN, NAME_MAX, STATUS_MIN, primary, secondary,
                buttonWidth(EditorTexts.MORE), editNowWidth);
        bar = layout;
        if (!layout.menu()) {
            moreOpen = false;
        }
        palette.setBounds(GAP, BAR, paletteWidth, h - BAR - GAP);
        palette.setContentHeight(rows.size() * ROW);
        panel.setBounds(w - PANEL_WIDTH - GAP, BAR, PANEL_WIDTH, h - BAR - GAP);
        int canvasLeft = GAP * 2 + paletteWidth;
        int canvasWidth = w - canvasLeft - PANEL_WIDTH - GAP * 2;
        view.setViewport(canvasLeft + 1, BAR + 1, canvasWidth - 2, h - BAR - GAP - WHY_HEIGHT - 3);

        EditBox box = new EditBox(font, layout.nameX(), 2, layout.nameWidth(), BUTTON_HEIGHT,
                Component.translatable(EditorTexts.NAME_HINT)) {
            @Override
            public void setFocused(boolean focused) {
                super.setFocused(focused);
                // The name shows from its start; it scrolls only while it is being edited.
                if (!focused) {
                    moveCursorToStart(false);
                }
            }
        };
        box.setMaxLength(GraphSlot.MAX_NAME);
        box.setValue(name);
        box.moveCursorToStart(false);
        box.setHint(Component.translatable(EditorTexts.NAME_HINT));
        box.setTextColor(AccentFrame.TEXT);
        box.setEditable(!readOnly);
        box.setResponder(this::renamed);
        nameBox = addRenderableWidget(box);
        EditorTopBar.Positions at = layout.primaryX();
        addRenderableWidget(new AccentButton(at.get(0), 2, primary[0], BUTTON_HEIGHT,
                Component.translatable(EditorTexts.UNDO), () -> !readOnly && undo.canUndo(), this::undo));
        addRenderableWidget(new AccentButton(at.get(1), 2, primary[1], BUTTON_HEIGHT,
                Component.translatable(EditorTexts.REDO), () -> !readOnly && undo.canRedo(), this::redo));
        addRenderableWidget(new AccentButton(at.get(2), 2, primary[2], BUTTON_HEIGHT,
                Component.translatable(EditorTexts.SAVE), () -> graph != null && !readOnly && dirty, this::save));
        addRenderableWidget(new AccentButton(at.get(3), 2, primary[3], BUTTON_HEIGHT,
                Component.translatable(EditorTexts.BACK), () -> true, this::onClose));
        if (layout.menu()) {
            AccentButton more = addRenderableWidget(new AccentButton(layout.moreX(), 2, buttonWidth(EditorTexts.MORE),
                    BUTTON_HEIGHT, Component.translatable(EditorTexts.MORE), () -> true, () -> moreOpen = !moreOpen));
            more.setTooltip(Tooltip.create(Component.translatable(EditorTexts.MORE_TIP)));
        }
        // The secondary buttons: on the bar, or a column under More that shows while the menu is open.
        menuButtons.clear();
        AccentButton history = secondary(layout, secondary, 0,
                () -> Component.translatable(showHistory ? EditorTexts.HISTORY_CLOSE : EditorTexts.HISTORY),
                () -> graph != null && index < menu.count(), () -> {
                    toggleHistory();
                    moreOpen = false;
                });
        history.setTooltip(Tooltip.create(Component.translatable(EditorTexts.HISTORY_TIP)));
        AccentButton simulate = secondary(layout, secondary, 1,
                () -> Component.translatable(simulating() ? ControllerTexts.SIMULATE_STOP : ControllerTexts.SIMULATE),
                () -> !readOnly && live != null && index < menu.count(), () -> {
                    simulate();
                    moreOpen = false;
                });
        simulate.setTooltip(Tooltip.create(Component.translatable(ControllerTexts.SIMULATE_TIP)));
        AccentButton revert = secondary(layout, secondary, 2,
                () -> Component.translatable(
                        Util.getMillis() < revertUntil ? ControllerTexts.REVERT_CONFIRM : ControllerTexts.REVERT),
                () -> !readOnly && hasPrevious(), () -> {
                    revert();
                    // The first click only arms Revert; the menu stays open for the second.
                    moreOpen = Util.getMillis() < revertUntil;
                });
        revert.setTooltip(Tooltip.create(Component.translatable(ControllerTexts.REVERT_TIP)));
        // Offered to a read-only viewer once the editor left; placed at the status line's end.
        AccentButton now = addRenderableWidget(new AccentButton(layout.editNowX(), 2, editNowWidth, BUTTON_HEIGHT,
                Component.translatable(EditorTexts.EDIT_NOW), this::lockFree, this::editNow));
        now.setTooltip(Tooltip.create(Component.translatable(EditorTexts.EDIT_NOW_TIP)));
        now.visible = lockFree();
        editNow = now;
        entries.clear();
        rebuildPanel();
        if (!asked) {
            asked = true;
            Platform.get().network().sendToServer(new EditorPayloads.Open(menu.containerId, index));
        }
        for (NodeType type : NodeTypes.all()) {
            String title = I18n.get(type.nameKey());
            titles.put(type, title);
            paletteTitles.put(type, TextFit.fit(title, palette.bodyWidth() - PALETTE_TEXT - 1, font::width));
        }
        refresh();
    }

    /** A top-bar button's width: its longest label (a button can switch between labels) plus padding. */
    private int buttonWidth(String... keys) {
        int widest = 0;
        for (String key : keys) {
            widest = Math.max(widest, font.width(I18n.get(key)));
        }
        return Math.max(BUTTON_WIDTH, widest + 8);
    }

    /**
     * Adds secondary button {@code i}: on the bar where the layout put it, or as row {@code i} of the More menu, shown
     * only while the menu is open.
     */
    private AccentButton secondary(EditorTopBar.Layout layout, int[] widths, int i, Supplier<Component> label,
            BooleanSupplier enabled, Runnable action) {
        AccentButton button;
        if (layout.menu()) {
            button = AccentButton.dynamic(layout.menuX(), menuRowY(i), layout.menuWidth(), BUTTON_HEIGHT, label,
                    enabled, input -> action.run());
            button.visible = moreOpen;
            menuButtons.add(button);
        } else {
            button = AccentButton.dynamic(layout.secondaryX().get(i), 2, widths[i], BUTTON_HEIGHT, label, enabled,
                    input -> action.run());
        }
        return addRenderableWidget(button);
    }

    /** The y of row {@code i} of the More menu, which opens below the bar. */
    private static int menuRowY(int i) {
        return 2 + BUTTON_HEIGHT + 1 + i * (BUTTON_HEIGHT + 1);
    }

    /** Whether ({@code x}, {@code y}) is on the open More menu. */
    private boolean overMenu(double x, double y) {
        EditorTopBar.Layout layout = bar;
        return moreOpen && layout != null && !menuButtons.isEmpty() && x >= layout.menuX()
                && x < layout.menuX() + layout.menuWidth() && y >= menuRowY(0) && y < menuRowY(menuButtons.size());
    }

    /** Whether ({@code x}, {@code y}) is on the palette's heading, which switches Common and All. */
    private boolean paletteHeading(double x, double y) {
        return palette.contains(x, y) && y < palette.bodyTop();
    }

    private String title(NodeType type) {
        String title = titles.get(type);
        return title == null ? type.id() : title;
    }

    private boolean editable() {
        return graph != null && !readOnly;
    }

    /** Whether this view is read-only and the server said nobody edits the graph any more. */
    private boolean lockFree() {
        EditorPayloads.Live current = live;
        return readOnly && current != null && (current.flags() & EditorPayloads.LIVE_LOCK_FREE) != 0;
    }

    /** Asks to open the graph again, now as its editor; the server answers with the graph (read-only if taken). */
    private void editNow() {
        if (lockFree()) {
            live = null;
            Platform.get().network().sendToServer(new EditorPayloads.Open(menu.containerId, index));
        }
    }

    /** Shows the change history in the side panel, or the settings again. */
    private void toggleHistory() {
        showHistory = !showHistory;
        rebuildPanel();
    }

    // ---- packets

    void receive(EditorPayloads.Graph payload) {
        if (payload.containerId() != menu.containerId || payload.index() != index || minecraft.level == null) {
            return;
        }
        LogicGraph<LogicKey> loaded = LogicEditing.decode(minecraft.level.registryAccess(), payload.graph())
                .orElse(LogicGraph.empty());
        EditorGraph<LogicKey> current = graph;
        if (current == null) {
            graph = new EditorGraph<>(loaded);
        } else {
            current.load(loaded);
        }
        undo.clear();
        name = payload.name();
        readOnly = payload.readOnly();
        editor = payload.editor();
        labels = payload.labels();
        networkKnown = payload.networkKnown();
        maxNodes = payload.maxNodes();
        maxPins = payload.maxPins();
        pins.clear();
        for (int id : payload.pins()) {
            pins.add(id);
        }
        revertUntil = 0;
        dirty = false;
        EditBox box = nameBox;
        if (box != null) {
            box.setValue(name);
            box.moveCursorToStart(false);
            box.setEditable(!readOnly);
        }
        dirty = false;
        refresh();
        // The setting boxes follow read-only (a viewer who took over with Edit now can type in them).
        rebuildPanel();
        if (!centred) {
            centred = true;
            LogicNode<LogicKey> focused = focus >= 0 ? byId.get(focus) : null;
            if (focused == null) {
                centreView();
            } else {
                view.centreOn(focused.x() + width(focused) / 2, focused.y() + height(focused) / 2);
                select(focus);
            }
        }
    }

    void receive(EditorPayloads.Saved payload) {
        if (payload.containerId() != menu.containerId || payload.index() != index) {
            return;
        }
        if (!payload.saved()) {
            flash(Component.translatable(payload.refusal()), StatusPalette.bad());
            return;
        }
        boolean paused = false;
        for (EditorNote note : payload.notes()) {
            paused |= note.error();
        }
        dirty = false;
        savedPaused = paused;
        menu.setName(index, DeviceLabel.clean(name));
        flash(Component.translatable(paused ? EditorTexts.SAVED_PAUSED : EditorTexts.SAVED),
                paused ? StatusPalette.warn() : StatusPalette.good());
    }

    void receive(EditorPayloads.Live payload) {
        if (payload.containerId() != menu.containerId) {
            return;
        }
        live = payload;
        liveValues.clear();
        for (EditorPayloads.LiveValue value : payload.values()) {
            Object2LongOpenHashMap<String> outputs = liveValues.get(value.node());
            if (outputs == null) {
                outputs = new Object2LongOpenHashMap<>(4);
                liveValues.put(value.node(), outputs);
            }
            outputs.put(value.output(), value.value());
        }
        refreshLiveLines();
        List<Component> lines = new ArrayList<>();
        for (EditorPayloads.Why why : payload.why()) {
            lines.add(whyText(why));
        }
        whyLines = lines;
        if (lines.isEmpty()) {
            whyLine = payload.runs() == 0
                    ? Component.translatable(EditorTexts.WHY_NONE)
                    : Component.translatable(EditorTexts.WHY_OK,
                            ControllerTexts.costParts(payload.lastCost(), payload.lastPoints()));
        } else {
            MutableComponent line = Component.translatable(EditorTexts.WHY_LINE, lines.get(0));
            if (lines.size() > 1) {
                line.append(" ").append(Component.translatable(EditorTexts.WHY_MORE, lines.size() - 1));
            }
            whyLine = line;
        }
        refreshWireLabels();
    }

    private Component whyText(EditorPayloads.Why why) {
        LogicNode<LogicKey> node = byId.get(why.node());
        Component reason = Component.translatable(why.key(),
                why.text().isEmpty() ? InfoFormat.number(why.first()) : why.text(), InfoFormat.number(why.second()));
        return node == null ? reason : Component.literal(title(node.type()) + ": ").append(reason);
    }

    private void save() {
        EditorGraph<LogicKey> current = graph;
        if (current == null || readOnly || minecraft.level == null) {
            return;
        }
        // Pins of nodes deleted since are dropped; the server checks again.
        List<Integer> kept = new ArrayList<>(pins.size());
        for (int i = 0; i < pins.size(); i++) {
            if (byId.containsKey(pins.getInt(i))) {
                kept.add(pins.getInt(i));
            }
        }
        Platform.get().network().sendToServer(new EditorPayloads.Save(menu.containerId, index, DeviceLabel.clean(name),
                LogicEditing.encode(minecraft.level.registryAccess(), current.snapshot()), kept));
    }

    /** Whether the graph only simulates its actions, as the server last said. */
    private boolean simulating() {
        EditorPayloads.Live current = live;
        return current != null && (current.flags() & EditorPayloads.LIVE_SIMULATE) != 0;
    }

    /** Whether the graph has a last working version to revert to, as the server last said. */
    private boolean hasPrevious() {
        EditorPayloads.Live current = live;
        return current != null && (current.flags() & EditorPayloads.LIVE_PREVIOUS) != 0;
    }

    /** Switches simulate mode through the controller's menu, like its screen's button. */
    private void simulate() {
        if (minecraft.gameMode != null && index < menu.count()) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId,
                    LogicControllerMenu.BUTTON_SIMULATE + index);
        }
    }

    /**
     * Revert asks first: the first click arms it and says what it does, the second puts back the last working version
     * (the server sends the graph again, replacing what is open here).
     */
    private void revert() {
        if (minecraft.gameMode == null || readOnly || !hasPrevious()) {
            return;
        }
        if (Util.getMillis() < revertUntil) {
            revertUntil = 0;
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, LogicControllerMenu.BUTTON_REVERT + index);
        } else {
            revertUntil = Util.getMillis() + REVERT_MILLIS;
            flash(Component.translatable(EditorTexts.REVERT_ASK), StatusPalette.warn());
        }
    }

    /** Pins or unpins the selected node's value; saved with the graph. */
    private void togglePin(int id) {
        int at = pins.indexOf(id);
        if (at >= 0) {
            pins.removeInt(at);
        } else if (pins.size() >= maxPins) {
            flash(Component.translatable(EditorTexts.PINS_FULL, maxPins), StatusPalette.warn());
            return;
        } else {
            pins.add(id);
            flash(Component.translatable(EditorTexts.PIN_TIP), StatusPalette.good());
        }
        dirty = true;
        leaveArmed = false;
        layoutPanel();
    }

    private void renamed(String value) {
        if (!value.equals(name) && !readOnly) {
            name = value;
            dirty = true;
            leaveArmed = false;
        }
    }

    private static String noteMark(EditorNote note) {
        return StatusPalette.mark(note.error() ? StatusPalette.Tone.BAD : StatusPalette.Tone.WARN);
    }

    private void flash(Component message, int colour) {
        String mark = colour == StatusPalette.bad()
                ? StatusPalette.mark(StatusPalette.Tone.BAD)
                : colour == StatusPalette.warn()
                        ? StatusPalette.mark(StatusPalette.Tone.WARN)
                        : colour == StatusPalette.good() ? StatusPalette.mark(StatusPalette.Tone.GOOD) : "";
        flash = mark.isEmpty() ? message : Component.literal(mark).append(message);
        flashColour = colour;
        flashUntil = Util.getMillis() + FLASH_MILLIS;
    }

    // ---- changes

    /** Runs {@code action} on the graph as one undoable step; false when it changed nothing or editing is off. */
    private boolean change(Predicate<EditorGraph<LogicKey>> action) {
        EditorGraph<LogicKey> current = graph;
        if (current == null || readOnly) {
            return false;
        }
        LogicGraph<LogicKey> before = current.snapshot();
        if (!action.test(current)) {
            return false;
        }
        undo.record(before);
        lastTyped = "";
        edited();
        return true;
    }

    private void edited() {
        dirty = true;
        leaveArmed = false;
        savedPaused = null;
        refresh();
    }

    private void undo() {
        EditorGraph<LogicKey> current = graph;
        if (current != null && !readOnly) {
            undo.undo(current.snapshot()).ifPresent(previous -> {
                current.load(previous);
                lastTyped = "";
                edited();
                rebuildPanel();
            });
        }
    }

    private void redo() {
        EditorGraph<LogicKey> current = graph;
        if (current != null && !readOnly) {
            undo.redo(current.snapshot()).ifPresent(next -> {
                current.load(next);
                lastTyped = "";
                edited();
                rebuildPanel();
            });
        }
    }

    private void deleteSelection() {
        Wire wire = selectedWire;
        if (wire != null) {
            change(g -> g.remove(wire));
            selectedWire = null;
        } else if (selected >= 0) {
            int id = selected;
            if (change(g -> g.remove(id))) {
                selected = -1;
                rebuildPanel();
            }
        }
    }

    /** Rebuilds every cache from the graph: nodes by id, notes, summaries, wires and the canvas bounds. */
    private void refresh() {
        EditorGraph<LogicKey> current = graph;
        byId.clear();
        notesByNode.clear();
        summaries.clear();
        fullSummaries.clear();
        widths.clear();
        nodeTitles.clear();
        if (current == null) {
            return;
        }
        for (LogicNode<LogicKey> node : current.nodes()) {
            byId.put(node.id(), node);
            String summary = NodeArt.summary(node);
            String title = title(node.type());
            int width = NodeLayout.width(font.width(title),
                    Math.max(font.width(summary), NodeArt.liveSampleWidth(font, node)));
            widths.put(node.id(), width);
            nodeTitles.put(node.id(), NodeLayout.fitTitle(title, width, font::width));
            summaries.put(node.id(), NodeLayout.fitLine(summary, width, font::width));
            fullSummaries.put(node.id(), summary);
        }
        if (selected >= 0 && !byId.containsKey(selected)) {
            selected = -1;
        }
        LogicGraph<LogicKey> snapshot = current.snapshot();
        notes = EditorNote.of(LogicCompiler.compile(snapshot, maxNodes), clientLabels());
        errors = 0;
        for (EditorNote note : notes) {
            errors += note.error() ? 1 : 0;
            List<EditorNote> list = notesByNode.get(note.node());
            if (list == null) {
                list = new ArrayList<>(2);
                notesByNode.put(note.node(), list);
            }
            list.add(note);
        }
        List<Wire> list = current.wires();
        int n = list.size();
        wires = list.toArray(new Wire[0]);
        wireOut = new int[n];
        wireIn = new int[n];
        wireTypes = new PortType[n];
        wireLabels = new String[n];
        wireLit = new boolean[n];
        for (int i = 0; i < n; i++) {
            Wire wire = wires[i];
            LogicNode<LogicKey> from = byId.get(wire.from());
            LogicNode<LogicKey> to = byId.get(wire.to());
            wireOut[i] = from == null ? -1 : portIndex(from.type().outputs(from.settings()), wire.output());
            wireIn[i] = to == null ? -1 : portIndex(to.type().inputs(to.settings()), wire.input());
            wireTypes[i] = from == null || wireOut[i] < 0
                    ? PortType.EVENT
                    : from.type().outputs(from.settings()).get(wireOut[i]).type();
        }
        Wire chosen = selectedWire;
        if (chosen != null && !list.contains(chosen)) {
            selectedWire = null;
        }
        updateBounds();
        refreshWireLabels();
        refreshLiveLines();
        if (panelMatches()) {
            // Same boxes; the notes and buttons above and between them may have changed.
            layoutPanel();
        } else {
            rebuildPanel();
        }
    }

    private static int portIndex(List<Port> ports, String name) {
        for (int i = 0; i < ports.size(); i++) {
            if (ports.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private EditorNote.Labels clientLabels() {
        if (!networkKnown || labels.size() >= EditorPayloads.MAX_LABELS) {
            return EditorNote.Labels.UNKNOWN;
        }
        return new EditorNote.Labels() {
            @Override
            public boolean has(String label) {
                return labelInfo(label) != null;
            }

            @Override
            public boolean switchable(String label) {
                EditorPayloads.LabelInfo info = labelInfo(label);
                return info == null || info.switchable();
            }
        };
    }

    private EditorPayloads.@Nullable LabelInfo labelInfo(String label) {
        String key = DeviceLabel.key(label);
        for (EditorPayloads.LabelInfo info : labels) {
            if (info.label().key().equals(key)) {
                return info;
            }
        }
        return null;
    }

    private void updateBounds() {
        EditorGraph<LogicKey> current = graph;
        int minX = 0;
        int minY = 0;
        int maxX = view.viewWidth();
        int maxY = view.viewHeight();
        if (current != null && current.size() > 0) {
            minX = Integer.MAX_VALUE;
            minY = Integer.MAX_VALUE;
            maxX = Integer.MIN_VALUE;
            maxY = Integer.MIN_VALUE;
            for (LogicNode<LogicKey> node : current.nodes()) {
                minX = Math.min(minX, node.x());
                minY = Math.min(minY, node.y());
                maxX = Math.max(maxX, node.x() + width(node));
                maxY = Math.max(maxY, node.y() + height(node));
            }
        }
        int padX = view.viewWidth() / 2;
        int padY = view.viewHeight() / 2;
        view.setContent(minX - padX, minY - padY, maxX + padX, maxY + padY, 24);
    }

    private void centreView() {
        EditorGraph<LogicKey> current = graph;
        if (current == null || current.size() == 0) {
            view.centreOn(view.viewWidth() / 2, view.viewHeight() / 2);
            return;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (LogicNode<LogicKey> node : current.nodes()) {
            minX = Math.min(minX, node.x());
            minY = Math.min(minY, node.y());
            maxX = Math.max(maxX, node.x() + width(node));
            maxY = Math.max(maxY, node.y() + height(node));
        }
        view.centreOn((minX + maxX) / 2, (minY + maxY) / 2);
    }

    private void refreshWireLabels() {
        EditorPayloads.Live current = live;
        for (int i = 0; i < wires.length; i++) {
            wireLabels[i] = null;
            wireLit[i] = false;
            Object2LongOpenHashMap<String> outputs = liveValues.get(wires[i].from());
            if (current == null || outputs == null || !outputs.containsKey(wires[i].output())) {
                continue;
            }
            long value = outputs.getLong(wires[i].output());
            switch (wireTypes[i]) {
                case NUMBER -> wireLabels[i] = InfoFormat.number(value);
                case YES_NO -> wireLabels[i] = I18n.get(value != 0 ? EditorTexts.VALUE_YES : EditorTexts.VALUE_NO);
                case EVENT -> wireLit[i] = current.runs() > 0 && value == current.runs();
                default -> {
                }
            }
        }
    }

    static String keyName(LogicKey key) {
        return switch (key) {
            case LogicKey.Stack stack -> KeyDisplay.name(stack.key()).getString();
            case LogicKey.Tag tag -> "#" + tag.tag().location();
            case LogicKey.FluidTag tag -> "#" + tag.tag().location();
        };
    }

    static ItemStack icon(LogicKey key) {
        return switch (key) {
            case LogicKey.Stack stack -> KeyDisplay.icon(stack.key());
            case LogicKey.Tag tag -> BuiltInRegistries.ITEM.get(tag.tag()).filter(set -> set.size() > 0)
                    .map(set -> new ItemStack(set.get(0).value())).orElse(new ItemStack(Items.NAME_TAG));
            case LogicKey.FluidTag tag -> BuiltInRegistries.FLUID.get(tag.tag()).filter(set -> set.size() > 0)
                    .map(set -> KeyDisplay.icon(FluidKey.of(set.get(0).value()))).orElse(new ItemStack(Items.BUCKET));
        };
    }

    private int height(LogicNode<?> node) {
        return NodeArt.height(node);
    }

    private int width(LogicNode<?> node) {
        return widths.getOrDefault(node.id(), NodeLayout.WIDTH);
    }

    /** A new node's width before it has settings: its title's. */
    private int width(NodeType type) {
        return NodeLayout.width(font.width(title(type)), 0);
    }

    /**
     * Rebuilds the live lines inside nodes from the last live values: the watched count or value, a state, the time a
     * delay has left. Called when values arrive, when the graph changes and every tick while a delay counts down.
     */
    private void refreshLiveLines() {
        liveLines.clear();
        liveColours.clear();
        liveTips.clear();
        liveCountdown = false;
        EditorGraph<LogicKey> current = graph;
        if (current == null || live == null || minecraft == null || minecraft.level == null) {
            return;
        }
        long now = minecraft.level.getGameTime();
        for (LogicNode<LogicKey> node : current.nodes()) {
            Optional<LiveLine.Spec> found = LiveLine.of(node.type());
            Object2LongOpenHashMap<String> values = liveValues.get(node.id());
            if (found.isEmpty() || values == null || !values.containsKey(found.get().entry())) {
                continue;
            }
            LiveLine.Spec spec = found.get();
            NodeArt.Live line = NodeArt.live(node, spec, values.getLong(spec.entry()), now);
            liveCountdown |= line.countdown();
            String text = line.text();
            int colour = line.colour();
            List<Component> tips = new ArrayList<>(line.tips());
            int room = NodeLayout.lineRoom(width(node));
            if (TextFit.cut(text, room, font::width) && spec.format() != LiveLine.Format.CROSSING) {
                tips.add(Component.literal(text));
            }
            liveLines.put(node.id(), TextFit.fit(text, room, font::width));
            liveColours.put(node.id(), colour);
            if (!tips.isEmpty()) {
                liveTips.put(node.id(), tips);
            }
        }
    }

    // ---- settings panel

    /** Whether the panel shows the selected node's current settings (same node, same visible and wired settings). */
    private boolean panelMatches() {
        LogicNode<LogicKey> node = selected >= 0 ? byId.get(selected) : null;
        EditorGraph<LogicKey> current = graph;
        if (node == null || current == null) {
            return panelNode == -1;
        }
        LogicGraph<LogicKey> snapshot = current.snapshot();
        int i = 0;
        for (SettingSpec spec : node.type().settings()) {
            if (!SettingRules.shows(node.type(), node.settings(), spec)) {
                continue;
            }
            if (i >= entries.size() || !entries.get(i).spec.equals(spec)
                    || entries.get(i).wired != SettingRules.wired(snapshot, node, spec)) {
                return false;
            }
            i++;
        }
        return i == entries.size() && panelNode == node.id();
    }

    private int panelNode = -1;

    /** Makes the panel's text boxes for the selected node anew and lays the panel out. */
    private void rebuildPanel() {
        for (Entry entry : entries) {
            if (entry.box != null) {
                removeWidget(entry.box);
            }
            if (entry.search != null) {
                removeWidget(entry.search);
            }
        }
        entries.clear();
        LogicNode<LogicKey> node = selected >= 0 && !showHistory ? byId.get(selected) : null;
        EditorGraph<LogicKey> current = graph;
        panelNode = node == null ? -1 : node.id();
        if (node != null && current != null) {
            LogicGraph<LogicKey> snapshot = current.snapshot();
            for (SettingSpec spec : node.type().settings()) {
                if (!SettingRules.shows(node.type(), node.settings(), spec)) {
                    continue;
                }
                Entry entry = new Entry(spec, SettingRules.wired(snapshot, node, spec));
                entries.add(entry);
                if (entry.wired) {
                    continue;
                }
                switch (spec.kind()) {
                    case NUMBER -> entry.box = numberBox(node, spec);
                    case TEXT, LABEL -> entry.box = textBox(node, spec);
                    case ITEM, FLUID, TAG -> entry.search = searchBox(spec.kind());
                    default -> {
                    }
                }
            }
        }
        layoutPanel();
    }

    private EditBox box(String value, int maxLength) {
        EditBox box = new EditBox(font, 0, 0, panel.bodyWidth(), BUTTON_HEIGHT, Component.empty());
        box.setMaxLength(maxLength);
        box.setValue(value);
        box.setTextColor(AccentFrame.TEXT);
        box.setEditable(!readOnly);
        return addRenderableWidget(box);
    }

    private EditBox numberBox(LogicNode<LogicKey> node, SettingSpec spec) {
        long value = node.settings().number(spec.name()).orElse(spec.defaultValue());
        EditBox box = box(Long.toString(value), 20);
        box.setFilter(text -> text.matches("-?[0-9]*"));
        int id = node.id();
        box.setResponder(text -> {
            try {
                setSetting(id, spec.name(), new SettingValue.Num<>(spec.clamp(Long.parseLong(text))), true);
            } catch (NumberFormatException e) {
                // Empty or a lone minus while typing: keep the last value.
            }
        });
        return box;
    }

    private EditBox textBox(LogicNode<LogicKey> node, SettingSpec spec) {
        int max = spec.kind() == SettingKind.LABEL ? NetworkRef.MAX_REF : SettingSpec.MAX_TEXT;
        EditBox box = box(node.settings().text(spec.name()).orElse(""), max);
        if (spec.kind() == SettingKind.LABEL) {
            // An empty label that is not required means the whole network.
            box.setHint(Component.translatable(spec.required() ? EditorTexts.LABEL_HINT : EditorTexts.WHOLE_NETWORK));
        }
        if (spec.kind() == SettingKind.TEXT && NodeTypes.NOTIFY.equals(node.type())) {
            box.setHint(Component.translatable(EditorTexts.MARKUP_HINT));
            box.setTooltip(Tooltip.create(Component.translatable(EditorTexts.MARKUP_TIP)));
        } else if (spec.kind() == SettingKind.TEXT && NodeTypes.WRITE_DISPLAY.equals(node.type())) {
            box.setHint(Component.translatable(EditorTexts.DISPLAY_TEXT_HINT));
            box.setTooltip(Tooltip.create(Component.translatable(EditorTexts.DISPLAY_TEXT_TIP)));
        }
        int id = node.id();
        box.setResponder(text -> {
            setSetting(id, spec.name(), new SettingValue.Text<>(text), true);
            if (spec.kind() == SettingKind.LABEL) {
                layoutPanel();
            }
        });
        return box;
    }

    private EditBox searchBox(SettingKind kind) {
        EditBox box = box(search, 64);
        box.setHint(Component.translatable(switch (kind) {
            case FLUID -> EditorTexts.SEARCH_FLUID_HINT;
            case TAG -> EditorTexts.SEARCH_TAG_HINT;
            default -> EditorTexts.SEARCH_HINT;
        }));
        box.setResponder(text -> {
            search = text;
            results = switch (kind) {
                case FLUID -> searchFluids(text);
                case TAG -> searchTags(text);
                default -> search(text);
            };
            layoutPanel();
        });
        return box;
    }

    /** Changes one setting of node {@code id}; typing into one box is one undo step. */
    private void setSetting(int id, String setting, SettingValue<LogicKey> value, boolean typed) {
        EditorGraph<LogicKey> current = graph;
        LogicNode<LogicKey> node = current == null ? null : current.node(id);
        if (current == null || node == null || readOnly) {
            return;
        }
        NodeSettings<LogicKey> next = node.settings().with(setting, value);
        String key = id + "/" + setting;
        if (typed && key.equals(lastTyped)) {
            if (current.setSettings(id, next)) {
                edited();
            }
            return;
        }
        if (change(g -> g.setSettings(id, next)) && typed) {
            lastTyped = key;
        }
    }

    /** Places the panel's controls and boxes in content coordinates. */
    private void layoutPanel() {
        controls.clear();
        panelLines.clear();
        LogicNode<LogicKey> node = selected >= 0 ? byId.get(selected) : null;
        int width = panel.bodyWidth();
        int y = 2;
        if (showHistory) {
            List<HistoryEntry> history = menu.history(index);
            shownHistory = history;
            if (history.isEmpty()) {
                for (FormattedCharSequence line : font.split(Component.translatable(EditorTexts.HISTORY_NONE), width)) {
                    y = line(y, line, AccentFrame.LABEL);
                }
            }
            // Newest first.
            for (int i = history.size() - 1; i >= 0; i--) {
                HistoryEntry entry = history.get(i);
                for (FormattedCharSequence line : font.split(HistoryText.heading(entry), width)) {
                    y = line(y, line, AccentFrame.ACCENT);
                }
                for (FormattedCharSequence line : font.split(HistoryText.summary(entry), width)) {
                    y = line(y, line, AccentFrame.TEXT);
                }
                y += 3;
            }
            panel.setContentHeight(y);
            return;
        }
        if (node == null) {
            for (FormattedCharSequence line : font.split(Component.translatable(EditorTexts.NO_SELECTION), width)) {
                y = line(y, line, AccentFrame.LABEL);
            }
            y = line(y, FormattedCharSequence.EMPTY, AccentFrame.LABEL);
            for (FormattedCharSequence line : font.split(Component.translatable(EditorTexts.HELP), width)) {
                y = line(y, line, AccentFrame.LABEL);
            }
            panel.setContentHeight(y);
            return;
        }
        List<EditorNote> nodeNotes = notesByNode.get(node.id());
        if (nodeNotes != null) {
            for (EditorNote note : nodeNotes) {
                for (FormattedCharSequence line : font.split(Component.literal(noteMark(note)).append(noteText(note)),
                        width)) {
                    y = line(y, line, note.error() ? StatusPalette.bad() : StatusPalette.warn());
                }
            }
            y += 2;
        }
        if (entries.isEmpty()) {
            y = line(y, Component.translatable(EditorTexts.NO_SETTINGS).getVisualOrderText(), AccentFrame.LABEL);
        }
        for (Entry entry : entries) {
            SettingSpec spec = entry.spec;
            int titleY = y;
            y = line(y, Component.translatable(spec.nameKey()).getVisualOrderText(), AccentFrame.LABEL);
            if (entry.wired) {
                y = line(y, Component.translatable(EditorTexts.FROM_WIRE).getVisualOrderText(), AccentFrame.ACCENT);
                y += 3;
                continue;
            }
            switch (spec.kind()) {
                case NUMBER, TEXT -> {
                    entry.boxY = y;
                    y += BUTTON_HEIGHT + 2;
                }
                case LABEL -> {
                    entry.boxY = y;
                    y += BUTTON_HEIGHT + 2;
                    y = labelChips(entry, y, width);
                }
                case FLAG -> {
                    boolean on = node.settings().flag(spec.name()).orElse(spec.defaultValue() != 0);
                    controls.add(control(ControlKind.FLAG, spec.name(), width - 26, titleY - 1, 26, 10, "",
                            Component.translatable(on ? EditorTexts.VALUE_YES : EditorTexts.VALUE_NO), null, 0, on));
                }
                case CHOICE -> {
                    String current = SettingRules.choice(node.type(), node.settings(), spec.name());
                    int chip = (width - 2) / 2;
                    for (int i = 0; i < spec.choices().size(); i++) {
                        String choice = spec.choices().get(i);
                        controls.add(control(ControlKind.CHOICE, spec.name(), (i % 2) * (chip + 2), y + (i / 2) * 12,
                                chip, 11, choice, Component.translatable(SettingSpec.choiceKey(choice)), null, 0,
                                choice.equals(current)));
                    }
                    y += (spec.choices().size() + 1) / 2 * 12;
                }
                case ITEM, FLUID, TAG -> y = itemControls(entry, node, y, width);
            }
            y += 3;
        }
        if (!readOnly && maxPins > 0 && LiveLine.pin(node.type(), node.settings()).isPresent()) {
            boolean pinned = pins.contains(node.id());
            controls.add(control(ControlKind.PIN, "", 0, y, width, 11, "",
                    Component.translatable(pinned ? EditorTexts.UNPIN : EditorTexts.PIN), null, 0, pinned));
            y += 13;
        }
        if (!readOnly) {
            controls.add(control(ControlKind.DELETE, "", 0, y, width, 11, "",
                    Component.translatable(EditorTexts.DELETE), null, 0, false));
            y += 13;
        }
        panel.setContentHeight(y);
    }

    private int line(int y, FormattedCharSequence text, int colour) {
        controls.add(control(ControlKind.LINE, "", 0, y, panel.bodyWidth(), 9, "", null, null, colour, false));
        panelLines.add(text);
        return y + 9;
    }

    /** Lines in the panel, in the order of their LINE controls. */
    private final List<FormattedCharSequence> panelLines = new ArrayList<>();

    private int labelChips(Entry entry, int y, int width) {
        EditBox box = entry.box;
        String typed = box == null ? "" : DeviceLabel.key(box.getValue());
        int chip = (width - 2) / 2;
        int shown = 0;
        for (EditorPayloads.LabelInfo info : labels) {
            if (shown == LABEL_CHIPS) {
                break;
            }
            if (!typed.isEmpty() && !info.label().key().contains(typed)) {
                continue;
            }
            DyeColor dye = info.label().colour();
            controls.add(control(ControlKind.LABEL, entry.spec.name(), (shown % 2) * (chip + 2), y + (shown / 2) * 11,
                    chip, 10, info.label().text(), null, null,
                    dye == null ? AccentFrame.ACCENT : dye.getTextureDiffuseColor(), false));
            shown++;
        }
        return y + (shown + 1) / 2 * 11;
    }

    private int itemControls(Entry entry, LogicNode<LogicKey> node, int y, int width) {
        LogicKey key = node.settings().item(entry.spec.name()).orElse(null);
        String none = switch (entry.spec.kind()) {
            case FLUID -> EditorTexts.NO_FLUID;
            case TAG -> EditorTexts.NO_TAG;
            default -> EditorTexts.NO_ITEM;
        };
        controls.add(control(ControlKind.ITEM, entry.spec.name(), 0, y, width, 18, "",
                key == null ? Component.translatable(none) : Component.literal(keyName(key)), key, 0, false));
        y += 20;
        if (entry.spec.kind() == SettingKind.ITEM) {
            controls.add(control(ControlKind.HELD, entry.spec.name(), 0, y, width, 11, "",
                    Component.translatable(EditorTexts.HELD_ITEM), null, 0, false));
            y += 13;
        }
        entry.searchY = y;
        y += BUTTON_HEIGHT + 2;
        int perRow = Math.max(1, width / 18);
        for (int i = 0; i < results.size(); i++) {
            controls.add(control(ControlKind.RESULT, entry.spec.name(), (i % perRow) * 18, y + (i / perRow) * 18, 18,
                    18, "", Component.literal(keyName(results.get(i))), results.get(i), 0, false));
        }
        return y + (results.size() + perRow - 1) / perRow * 18;
    }

    /** Items whose id or name contains {@code text}, or the tag {@code #text}; at most {@link #ITEM_RESULTS}. */
    static List<LogicKey> search(String text) {
        String query = text.strip().toLowerCase(Locale.ROOT);
        List<LogicKey> found = new ArrayList<>();
        if (query.isEmpty()) {
            return found;
        }
        if (query.startsWith("#")) {
            Identifier id = Identifier.tryParse(query.substring(1));
            if (id != null) {
                TagKey<Item> tag = TagKey.create(Registries.ITEM, id);
                if (BuiltInRegistries.ITEM.get(tag).isPresent()) {
                    found.add(LogicKey.tag(tag));
                }
            }
            return found;
        }
        for (Item item : BuiltInRegistries.ITEM) {
            if (Items.AIR.equals(item)) {
                continue;
            }
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if ((id != null && id.toString().contains(query))
                    || I18n.get(item.getDescriptionId()).toLowerCase(Locale.ROOT).contains(query)) {
                found.add(LogicKey.of(ItemKey.of(item)));
                if (found.size() == ITEM_RESULTS) {
                    break;
                }
            }
        }
        return found;
    }

    /**
     * Fluids (source fluids only) whose id or name contains {@code text}, or the fluid tag {@code #text}; at most
     * {@link #ITEM_RESULTS}.
     */
    static List<LogicKey> searchFluids(String text) {
        String query = text.strip().toLowerCase(Locale.ROOT);
        List<LogicKey> found = new ArrayList<>();
        if (query.isEmpty()) {
            return found;
        }
        if (query.startsWith("#")) {
            Identifier id = Identifier.tryParse(query.substring(1));
            if (id != null) {
                TagKey<Fluid> tag = TagKey.create(Registries.FLUID, id);
                if (BuiltInRegistries.FLUID.get(tag).isPresent()) {
                    found.add(LogicKey.fluidTag(tag));
                }
            }
            return found;
        }
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            // The empty fluid and flowing variants are no amounts anyone stores.
            if (!fluid.defaultFluidState().isSource()) {
                continue;
            }
            FluidKey key = FluidKey.of(fluid);
            Identifier id = BuiltInRegistries.FLUID.getKey(fluid);
            if ((id != null && id.toString().contains(query))
                    || KeyDisplay.name(key).getString().toLowerCase(Locale.ROOT).contains(query)) {
                found.add(LogicKey.of(key));
                if (found.size() == ITEM_RESULTS) {
                    break;
                }
            }
        }
        return found;
    }

    /** Item tags whose id contains {@code text} (a leading {@code #} is ignored); at most {@link #ITEM_RESULTS}. */
    static List<LogicKey> searchTags(String text) {
        String query = text.strip().toLowerCase(Locale.ROOT);
        String plain = query.startsWith("#") ? query.substring(1) : query;
        List<LogicKey> found = new ArrayList<>();
        if (plain.isEmpty()) {
            return found;
        }
        // Allocates a stream per keystroke in the editor only; never on the server.
        BuiltInRegistries.ITEM.getTags().map(set -> set.key()).filter(tag -> tag.location().toString().contains(plain))
                .sorted((a, b) -> {
                    // An exact match first, then by id.
                    boolean exactA = a.location().toString().equals(plain);
                    boolean exactB = b.location().toString().equals(plain);
                    return exactA == exactB ? a.location().compareTo(b.location()) : exactA ? -1 : 1;
                }).limit(ITEM_RESULTS).forEach(tag -> found.add(LogicKey.tag(tag)));
        return found;
    }

    private Component noteText(EditorNote note) {
        return note.arg().isEmpty()
                ? Component.translatable(note.key())
                : Component.translatable(note.key(), note.arg());
    }

    /** Puts the panel's text boxes where the scroll has them; hides those not wholly in the body. */
    private void placeBoxes() {
        for (Entry entry : entries) {
            place(entry.box, entry.boxY);
            place(entry.search, entry.searchY);
        }
    }

    private void place(@Nullable EditBox box, int contentY) {
        if (box == null) {
            return;
        }
        int y = panel.screenY(contentY);
        box.setX(panel.bodyLeft());
        box.setY(y);
        box.visible = graph != null && y >= panel.bodyTop() && y + BUTTON_HEIGHT <= panel.bodyTop() + panel.bodyHeight()
                && !underMenu(box);
    }

    /** Whether {@code box} is under the open More menu, which is drawn first and would be covered by it. */
    private boolean underMenu(EditBox box) {
        EditorTopBar.Layout layout = bar;
        return moreOpen && layout != null && !menuButtons.isEmpty() && box.getX() < layout.menuX() + layout.menuWidth()
                && box.getX() + box.getWidth() > layout.menuX() && box.getY() < menuRowY(menuButtons.size());
    }

    private void clickControl(Control control) {
        if (readOnly) {
            return;
        }
        int id = selected;
        switch (control.kind()) {
            case CHOICE -> {
                setSetting(id, control.setting(), new SettingValue.Text<>(control.value()), false);
                rebuildPanel();
            }
            case FLAG -> {
                setSetting(id, control.setting(), new SettingValue.Flag<>(!control.on()), false);
                layoutPanel();
            }
            case LABEL -> {
                for (Entry entry : entries) {
                    EditBox box = entry.box;
                    if (entry.spec.name().equals(control.setting()) && box != null) {
                        lastTyped = "";
                        box.setValue(control.value());
                    }
                }
            }
            case HELD -> {
                ItemStack held = minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getMainHandItem();
                if (!held.isEmpty()) {
                    setSetting(id, control.setting(), new SettingValue.Item<>(LogicKey.of(ItemKey.of(held.getItem()))),
                            false);
                    layoutPanel();
                }
            }
            case RESULT -> {
                LogicKey key = control.key();
                if (key != null) {
                    setSetting(id, control.setting(), new SettingValue.Item<>(key), false);
                    layoutPanel();
                }
            }
            case DELETE -> deleteSelection();
            case PIN -> togglePin(id);
            default -> {
            }
        }
    }

    // ---- drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        tooltip = null;
        double ux = scale.toUnits(mouseX);
        double uy = scale.toUnits(mouseY);
        int mx = (int) Math.floor(ux);
        int my = (int) Math.floor(uy);
        scale.push(graphics);
        int w = scale.width();
        int h = scale.height();
        graphics.fill(0, 0, w, h, AccentFrame.OUTLINE);
        graphics.fill(1, 1, w - 1, h - 1, AccentFrame.GRAPHITE);
        EditorGraph<LogicKey> current = graph;
        if (current == null) {
            TextScale.text(graphics, font, Component.translatable(EditorTexts.LOADING), view.viewX() + 4,
                    view.viewY() + 4, AccentFrame.LABEL, false);
        } else {
            drawStatus(graphics, mx, my);
            drawPalette(graphics, mx, my);
            drawCanvas(graphics, current, ux, uy);
            drawPanel(graphics, mx, my);
            drawWhy(graphics, mx, my);
        }
        for (AccentButton button : menuButtons) {
            button.visible = moreOpen;
        }
        EditorTopBar.Layout layout = bar;
        if (moreOpen && layout != null && !menuButtons.isEmpty()) {
            // A frame behind the open menu; the buttons are drawn over it with the other widgets.
            graphics.fill(layout.menuX() - 1, menuRowY(0) - 1, layout.menuX() + layout.menuWidth() + 1,
                    menuRowY(menuButtons.size()), AccentFrame.OUTLINE);
            if (overMenu(mx, my)) {
                tooltip = null;
            }
        }
        placeBoxes();
        super.extractRenderState(graphics, mx, my, a);
        scale.pop(graphics);
        List<Component> lines = tooltip;
        if (lines != null) {
            // Long lines (a why reason, a whole status) wrap instead of running off the window.
            List<FormattedCharSequence> wrapped = new ArrayList<>(lines.size());
            int wrap = Math.max(TOOLTIP_MIN_WRAP, width / 2);
            for (Component line : lines) {
                wrapped.addAll(font.split(line, wrap));
            }
            graphics.setTooltipForNextFrame(font, wrapped, mouseX, mouseY);
        }
    }

    private void drawStatus(GuiGraphicsExtractor graphics, int mx, int my) {
        Component status;
        int colour = AccentFrame.LABEL;
        if (readOnly && lockFree()) {
            status = editor.isEmpty()
                    ? Component.translatable(EditorTexts.EDITOR_LEFT_PLAIN)
                    : Component.translatable(EditorTexts.EDITOR_LEFT, editor);
            colour = StatusPalette.good();
        } else if (readOnly) {
            status = editor.isEmpty()
                    ? Component.translatable(EditorTexts.READ_ONLY_PLAIN)
                    : Component.translatable(EditorTexts.READ_ONLY, editor);
            colour = StatusPalette.warn();
        } else if (errors > 0) {
            // A problem with the whole graph (too many nodes) has no node to sit on: it goes here.
            EditorNote wide = null;
            for (EditorNote note : notes) {
                if (note.node() == GraphError.GRAPH) {
                    wide = note;
                }
            }
            status = wide == null ? Component.translatable(EditorTexts.PROBLEMS, errors) : noteText(wide);
            colour = StatusPalette.bad();
        } else if (dirty) {
            status = Component.translatable(EditorTexts.UNSAVED);
            colour = StatusPalette.warn();
        } else if (Boolean.TRUE.equals(savedPaused)) {
            status = Component.translatable(EditorTexts.SAVED_PAUSED);
            colour = StatusPalette.warn();
        } else if (simulating()) {
            status = Component.translatable(EditorTexts.SIMULATING);
            colour = AccentFrame.ACCENT;
        } else if (live != null) {
            status = Component.translatable(ControllerTexts.statusKey(LogicWorker.Status.byId(live.status())));
        } else {
            status = Component.empty();
        }
        EditorTopBar.Layout layout = bar;
        if (layout == null) {
            return;
        }
        int x = layout.statusX();
        AccentButton now = editNow;
        int room = now != null && now.visible ? layout.statusRoomBesideEditNow(GAP) : layout.statusWidth();
        String full = status.getString();
        TextScale.text(graphics, font, TextFit.fit(full, room, font::width), x, 4, colour, false);
        if (my >= 2 && my < 2 + BUTTON_HEIGHT) {
            if (mx >= x && mx < x + room && TextFit.cut(full, room, font::width)) {
                tooltip = List.of(status);
            }
            EditBox box = nameBox;
            // The name box shows the start of a long name; its whole text is the tooltip while it is not edited.
            if (box != null && !box.isFocused() && mx >= layout.nameX() && mx < layout.nameX() + layout.nameWidth()
                    && TextFit.cut(box.getValue(), box.getInnerWidth(), font::width)) {
                tooltip = List.of(Component.literal(box.getValue()));
            }
        }
    }

    private void drawPalette(GuiGraphicsExtractor graphics, int mx, int my) {
        boolean filtering = drag == Drag.WIRE || pending;
        String heading = filtering
                ? EditorTexts.PALETTE_PICK
                : paletteAll ? EditorTexts.PALETTE_ALL : EditorTexts.PALETTE_COMMON;
        palette.begin(graphics, font, Component.translatable(heading), PANEL_STYLE);
        if (paletteHeading(mx, my)) {
            tooltip = List.of(Component.translatable(paletteAll ? EditorTexts.SHOW_COMMON : EditorTexts.SHOW_ALL));
        }
        int hovered = palette.bodyContains(mx, my) ? (int) Math.floor(palette.contentY(my) / ROW) : -1;
        for (int i = 0; i < rows.size(); i++) {
            if (!palette.shows(i * ROW, ROW)) {
                continue;
            }
            Palette.Row row = rows.get(i);
            int y = palette.screenY(i * ROW);
            int x = palette.bodyLeft();
            if (row.type().isEmpty()) {
                TextScale.text(graphics, font, I18n.get(row.category().nameKey()), x, y + 1,
                        NodeArt.categoryColour(row.category()), false);
                continue;
            }
            NodeType type = row.type().get();
            boolean usable = editable() && (!filtering || accepts[i]);
            if (i == hovered && usable) {
                graphics.fill(x - 1, y, x + palette.bodyWidth(), y + ROW, HOVER);
            }
            graphics.fill(x + 1, y + 3, x + 4, y + 6, usable ? NodeArt.categoryColour(type.category()) : DIM);
            TextScale.text(graphics, font, paletteTitles.getOrDefault(type, ""), x + 7, y + 1,
                    usable ? AccentFrame.TEXT : DIM, false);
            if (i == hovered && filtering && !accepts[i] && i < reasons.size()) {
                tooltip = List.of(Component.literal(title(type)), reasons.get(i));
            } else if (i == hovered) {
                // The whole name and what the node does.
                tooltip = List.of(Component.literal(title(type)),
                        Component.translatable(type.descriptionKey()).withStyle(ChatFormatting.GRAY));
            }
        }
        palette.end(graphics);
    }

    /** Works out which palette rows take the wire being dragged (or waiting), with the reason for the rest. */
    private void filterPalette() {
        reasons.clear();
        Component typeName = Component.translatable(wireType.nameKey());
        for (int i = 0; i < rows.size(); i++) {
            Optional<NodeType> type = rows.get(i).type();
            accepts[i] = type.isPresent() && Palette.joinPort(type.get(), wireType, wireFromOutput).isPresent();
            reasons.add(Component.translatable(wireFromOutput ? EditorTexts.TAKES_NO : EditorTexts.GIVES_NO, typeName));
        }
    }

    private void drawCanvas(GuiGraphicsExtractor graphics, EditorGraph<LogicKey> current, double ux, double uy) {
        int left = view.viewX();
        int top = view.viewY();
        int right = left + view.viewWidth();
        int bottom = top + view.viewHeight();
        graphics.fill(left - 1, top - 1, right + 1, bottom + 1, AccentFrame.OUTLINE);
        graphics.enableScissor(left, top, right, bottom);
        graphics.fill(left, top, right, bottom, CANVAS);
        int firstX = view.scrollX() - Math.floorMod(view.scrollX(), GRID);
        for (int x = view.screenX(firstX); x < right; x += GRID) {
            graphics.fill(x, top, x + 1, bottom, GRID_COLOUR);
        }
        int firstY = view.scrollY() - Math.floorMod(view.scrollY(), GRID);
        for (int y = view.screenY(firstY); y < bottom; y += GRID) {
            graphics.fill(left, y, right, y + 1, GRID_COLOUR);
        }
        double cx = view.canvasX(ux);
        double cy = view.canvasY(uy);
        boolean overCanvas = view.contains(ux, uy) && drag != Drag.PALETTE;
        for (int i = 0; i < wires.length; i++) {
            drawWire(graphics, i);
        }
        int hoverNode = overCanvas && drag == Drag.NONE ? nodeAt(cx, cy) : -1;
        for (LogicNode<LogicKey> node : current.nodes()) {
            drawNode(graphics, node, node.id() == hoverNode, cx, cy, overCanvas);
        }
        CanvasFade.draw(graphics, left, top, right, bottom, CANVAS);
        if (drag == Drag.WIRE || pending) {
            LogicNode<LogicKey> from = byId.get(wireNode);
            if (from != null) {
                int port = wireFromOutput
                        ? portIndex(from.type().outputs(from.settings()), wirePort)
                        : portIndex(from.type().inputs(from.settings()), wirePort);
                int px = view.screenX(from.x()) + NodeLayout.portX(wireFromOutput, width(from));
                int py = view.screenY(from.y()) + NodeLayout.portY(Math.max(0, port));
                int ex = view.screenX((int) Math.round(wireX));
                int ey = view.screenY((int) Math.round(wireY));
                int count = wireFromOutput
                        ? RightAngleWire.route(px, py, ex, ey, STUB, route)
                        : RightAngleWire.route(ex, ey, px, py, STUB, route);
                RightAngleWire.draw(graphics, route, count, NodeArt.thickness(wireType), NodeArt.portColour(wireType));
            }
        }
        NodeType dragged = paletteType;
        if (drag == Drag.PALETTE && dragged != null && view.contains(ux, uy)) {
            int x = (int) Math.floor(ux) - width(dragged) / 2;
            int y = (int) Math.floor(uy) - NodeLayout.HEADER / 2;
            graphics.outline(x, y, width(dragged),
                    NodeLayout.height(dragged.inputs(NodeSettings.empty()).size(),
                            dragged.outputs(NodeSettings.empty()).size(), LiveLine.has(dragged)),
                    NodeArt.categoryColour(dragged.category()));
        }
        graphics.disableScissor();
    }

    private void drawWire(GuiGraphicsExtractor graphics, int i) {
        Wire wire = wires[i];
        LogicNode<LogicKey> from = byId.get(wire.from());
        LogicNode<LogicKey> to = byId.get(wire.to());
        if (from == null || to == null || wireOut[i] < 0 || wireIn[i] < 0) {
            return;
        }
        int count = route(from, wireOut[i], to, wireIn[i]);
        PortType type = wireTypes[i];
        int colour = wireLit[i] ? 0xFFFFC070 : NodeArt.portColour(type);
        if (wire.equals(selectedWire)) {
            RightAngleWire.draw(graphics, route, count, NodeArt.thickness(type) + 2, SELECTED);
        }
        RightAngleWire.draw(graphics, route, count, NodeArt.thickness(type), colour);
        String label = wireLabels[i];
        if (label != null) {
            RightAngleWire.labelPoint(route, count, labelAt);
            int width = font.width(label);
            int x = labelAt[0] - width / 2;
            int y = labelAt[1] - 4;
            graphics.fill(x - 1, y - 1, x + width + 1, y + 8, LIVE_BACK);
            TextScale.text(graphics, font, label, x, y, 0xFFFFFFFF, false);
        }
    }

    /** Routes the wire from {@code from}'s output {@code out} to {@code to}'s input {@code in} into {@link #route}. */
    private int route(LogicNode<LogicKey> from, int out, LogicNode<LogicKey> to, int in) {
        return RightAngleWire.route(view.screenX(from.x()) + width(from) + 3,
                view.screenY(from.y()) + NodeLayout.portY(out), view.screenX(to.x()) - 3,
                view.screenY(to.y()) + NodeLayout.portY(in), STUB, route);
    }

    private void drawNode(GuiGraphicsExtractor graphics, LogicNode<LogicKey> node, boolean hovered, double cx,
            double cy, boolean overCanvas) {
        int x = view.screenX(node.x());
        int y = view.screenY(node.y());
        int height = height(node);
        int width = width(node);
        if (x > view.viewX() + view.viewWidth() + 8 || y > view.viewY() + view.viewHeight() + 8
                || x + width < view.viewX() - 8 || y + height < view.viewY() - 8) {
            return;
        }
        List<EditorNote> nodeNotes = notesByNode.get(node.id());
        boolean error = false;
        boolean warning = false;
        if (nodeNotes != null) {
            for (EditorNote note : nodeNotes) {
                error |= note.error();
                warning |= !note.error();
            }
        }
        boolean why = inWhy(node.id());
        int edge = node.id() == selected
                ? SELECTED
                : error ? StatusPalette.bad() : why ? WHY_MARK : warning ? StatusPalette.warn() : 0;
        if (edge != 0) {
            graphics.fill(x - 2, y - 2, x + width + 2, y + height + 2, edge);
        }
        String shownTitle = nodeTitles.getOrDefault(node.id(), "");
        String summary = summaries.get(node.id());
        NodeArt.body(graphics, font, x, y, width, height, node.type().category(), shownTitle, summary,
                liveLines.get(node.id()), liveColours.get(node.id()));
        Object2LongOpenHashMap<String> nodeValues = live == null ? null : liveValues.get(node.id());
        NodeArt.stepRows(graphics, font, x, y, width, node,
                nodeValues == null ? 0 : nodeValues.getOrDefault(Sequences.STEP, 0L));
        double ry = cy - node.y();
        boolean overBody = hovered && cx - node.x() >= 3 && cx - node.x() < width - 3;
        String fullSummary = fullSummaries.get(node.id());
        if (overBody && ry >= NodeLayout.SUMMARY_TOP - 1 && ry < NodeLayout.SUMMARY_TOP + 9 && fullSummary != null
                && !fullSummary.equals(summary)) {
            tooltip = List.of(Component.literal(fullSummary));
        }
        List<Component> liveTip = liveTips.get(node.id());
        if (overBody && liveTip != null && ry >= NodeLayout.LIVE_TOP - 1 && ry < NodeLayout.LIVE_TOP + 9) {
            tooltip = liveTip;
        }
        String fullTitle = title(node.type());
        if (hovered && !fullTitle.equals(shownTitle) && NodeLayout.onHeader(width, cx - node.x(), ry)) {
            tooltip = List.of(Component.literal(fullTitle));
        }
        if (pins.contains(node.id())) {
            // Pinned to the controller screen: a small mark at the title's right end, left of a note badge.
            int px = x + width - (error || warning ? 15 : 8);
            graphics.fill(px + 1, y + 3, px + 5, y + 7, PIN_MARK);
            graphics.fill(px + 2, y + 7, px + 4, y + NodeLayout.HEADER - 1, PIN_MARK);
            if (hovered && cx - node.x() >= px - x && cx - node.x() < px - x + 6 && cy - node.y() < NodeLayout.HEADER) {
                tooltip = List.of(Component.translatable(EditorTexts.PINNED_MARK));
            }
        }
        if (error || warning) {
            int bx = x + width - 8;
            graphics.fill(bx, y + 2, bx + 6, y + NodeLayout.HEADER - 1,
                    error ? StatusPalette.bad() : StatusPalette.warn());
            if (error) {
                // A cross for an error, an exclamation mark for a warning, so the badge reads without colour.
                graphics.fill(bx + 1, y + 3, bx + 2, y + 4, 0xFF000000);
                graphics.fill(bx + 4, y + 3, bx + 5, y + 4, 0xFF000000);
                graphics.fill(bx + 2, y + 4, bx + 4, y + 6, 0xFF000000);
                graphics.fill(bx + 1, y + 6, bx + 2, y + 7, 0xFF000000);
                graphics.fill(bx + 4, y + 6, bx + 5, y + 7, 0xFF000000);
            } else {
                graphics.fill(bx + 2, y + 3, bx + 4, y + 6, 0xFF000000);
                graphics.fill(bx + 2, y + 7, bx + 4, y + 8, 0xFF000000);
            }
        }
        if (hovered && nodeNotes != null && NodeLayout.onHeader(width, cx - node.x(), cy - node.y())) {
            List<Component> lines = new ArrayList<>();
            if (!fullTitle.equals(shownTitle)) {
                lines.add(Component.literal(fullTitle));
            }
            for (EditorNote note : nodeNotes) {
                lines.add(Component.literal(noteMark(note)).append(noteText(note))
                        .withColor(note.error() ? StatusPalette.bad() : StatusPalette.warn()));
            }
            tooltip = lines;
        }
        List<Port> inputs = node.type().inputs(node.settings());
        List<Port> outputs = node.type().outputs(node.settings());
        int hit = overCanvas
                ? NodeLayout.portAt(inputs.size(), outputs.size(), width, cx - node.x(), cy - node.y())
                : NodeLayout.MISS;
        for (int i = 0; i < inputs.size(); i++) {
            Port port = inputs.get(i);
            boolean bad = portError(nodeNotes, port.name());
            NodeArt.port(graphics, port.type(), x, y + NodeLayout.portY(i), bad);
            if (hit != NodeLayout.MISS && !NodeLayout.hitOutput(hit) && NodeLayout.hitIndex(hit) == i) {
                tooltip = portTooltip(node, port, false);
            }
        }
        for (int i = 0; i < outputs.size(); i++) {
            Port port = outputs.get(i);
            NodeArt.port(graphics, port.type(), x + width, y + NodeLayout.portY(i), false);
            if (hit != NodeLayout.MISS && NodeLayout.hitOutput(hit) && NodeLayout.hitIndex(hit) == i) {
                tooltip = portTooltip(node, port, true);
            }
        }
    }

    private static boolean portError(@Nullable List<EditorNote> notes, String port) {
        if (notes == null) {
            return false;
        }
        for (EditorNote note : notes) {
            if (note.error() && note.port().equals(port)) {
                return true;
            }
        }
        return false;
    }

    private boolean inWhy(int id) {
        EditorPayloads.Live current = live;
        if (current == null) {
            return false;
        }
        for (EditorPayloads.Why why : current.why()) {
            if (why.node() == id) {
                return true;
            }
        }
        return false;
    }

    private List<Component> portTooltip(LogicNode<LogicKey> node, Port port, boolean output) {
        List<Component> lines = new ArrayList<>(2);
        lines.add(
                Component
                        .translatable(EditorTexts.PORT, Component.translatable(port.nameKey()),
                                Component.translatable(port.type().nameKey()))
                        .withColor(NodeArt.portColour(port.type())));
        Object2LongOpenHashMap<String> values = liveValues.get(node.id());
        EditorPayloads.Live current = live;
        if (output && values != null && current != null && values.containsKey(port.name())) {
            long value = values.getLong(port.name());
            lines.add(switch (port.type()) {
                case NUMBER -> Component.literal(InfoFormat.number(value));
                case YES_NO -> Component.translatable(value != 0 ? EditorTexts.VALUE_YES : EditorTexts.VALUE_NO);
                case EVENT -> Component.translatable(
                        current.runs() > 0 && value == current.runs() ? EditorTexts.FIRED : EditorTexts.NOT_FIRED);
                default -> Component.empty();
            });
        }
        return lines;
    }

    private void drawPanel(GuiGraphicsExtractor graphics, int mx, int my) {
        LogicNode<LogicKey> node = selected >= 0 ? byId.get(selected) : null;
        Component heading = showHistory
                ? Component.translatable(EditorTexts.HISTORY)
                : node == null ? Component.translatable(EditorTexts.SETTINGS) : Component.literal(title(node.type()));
        panel.begin(graphics, font, heading, PANEL_STYLE);
        int lineIndex = 0;
        int left = panel.bodyLeft();
        for (Control control : controls) {
            if (control.kind() == ControlKind.LINE) {
                if (panel.shows(control.y(), control.height()) && lineIndex < panelLines.size()) {
                    TextScale.text(graphics, font, panelLines.get(lineIndex), left, panel.screenY(control.y()),
                            control.colour(), false);
                }
                lineIndex++;
                continue;
            }
            if (!panel.shows(control.y(), control.height())) {
                continue;
            }
            int x = left + control.x();
            int y = panel.screenY(control.y());
            boolean hovered = !readOnly && mx >= x && my >= y && mx < x + control.width() && my < y + control.height()
                    && panel.bodyContains(mx, my);
            drawControl(graphics, control, x, y, hovered);
            if (control.kind() != ControlKind.RESULT && !control.shown().equals(control.full()) && mx >= x && my >= y
                    && mx < x + control.width() && my < y + control.height() && panel.bodyContains(mx, my)) {
                tooltip = List.of(Component.literal(control.full()));
            }
        }
        panel.end(graphics);
    }

    private void drawControl(GuiGraphicsExtractor graphics, Control control, int x, int y, boolean hovered) {
        int w = control.width();
        int h = control.height();
        String shown = control.shown();
        switch (control.kind()) {
            case CHOICE, FLAG, HELD, DELETE, PIN -> {
                boolean on = control.on();
                graphics.fill(x, y, x + w, y + h, hovered ? AccentFrame.TEXT : AccentFrame.SLOT_EDGE);
                graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1,
                        on ? AccentFrame.ACCENT : control.kind() == ControlKind.DELETE ? 0xFF5A2020 : AccentFrame.SLOT);
                TextScale.text(graphics, font, shown, x + (w - font.width(shown)) / 2, y + (h - 8) / 2 + 1,
                        on ? 0xFFFFFFFF : AccentFrame.TEXT, false);
            }
            case LABEL -> {
                graphics.fill(x, y, x + w, y + h, hovered ? AccentFrame.TEXT : AccentFrame.SLOT_EDGE);
                graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, AccentFrame.SLOT);
                graphics.fill(x + 2, y + 3, x + 6, y + 7, control.colour());
                TextScale.text(graphics, font, shown, x + 8, y + 1, AccentFrame.TEXT, false);
            }
            case ITEM -> {
                AccentFrame.slot(graphics, x + 1, y + 1);
                if (!control.icon().isEmpty()) {
                    graphics.item(control.icon(), x + 1, y + 1);
                }
                TextScale.text(graphics, font, shown, x + 21, y + 5,
                        control.key() == null ? AccentFrame.LABEL : AccentFrame.TEXT, false);
            }
            case RESULT -> {
                if (hovered) {
                    graphics.fill(x, y, x + w, y + h, HOVER);
                    tooltip = List.of(Component.literal(shown));
                }
                graphics.item(control.icon(), x + 1, y + 1);
            }
            case LINE -> {
            }
        }
    }

    /**
     * A panel control with its text cut to fit and its item icon made once, so drawing a frame makes neither. A label
     * chip shows {@code value}; the rest show {@code text}.
     */
    private Control control(ControlKind kind, String setting, int x, int y, int width, int height, String value,
            @Nullable Component text, @Nullable LogicKey key, int colour, boolean on) {
        String full = kind == ControlKind.LABEL ? value : text == null ? "" : text.getString();
        int room = switch (kind) {
            case LABEL -> width - 10;
            case ITEM -> width - 22;
            case RESULT -> Integer.MAX_VALUE;
            default -> width - 4;
        };
        String shown = room == Integer.MAX_VALUE ? full : TextFit.fit(full, Math.max(0, room), font::width);
        return new Control(kind, setting, x, y, width, height, value, shown, full, key,
                key == null ? ItemStack.EMPTY : icon(key), colour, on);
    }

    private void drawWhy(GuiGraphicsExtractor graphics, int mx, int my) {
        int left = view.viewX() - 1;
        int top = view.viewY() + view.viewHeight() + 2;
        int width = view.viewWidth() + 2;
        graphics.fill(left, top, left + width, top + WHY_HEIGHT, AccentFrame.OUTLINE);
        graphics.fill(left + 1, top + 1, left + width - 1, top + WHY_HEIGHT - 1, AccentFrame.SLOT);
        Component text;
        int colour;
        boolean reasons = false;
        Component shown = flash;
        if (shown != null && Util.getMillis() < flashUntil) {
            text = shown;
            colour = flashColour;
        } else if (whyLine != null) {
            text = whyLine;
            colour = whyLines.isEmpty() ? StatusPalette.good() : WHY_MARK;
            reasons = !whyLines.isEmpty();
        } else {
            text = Component.translatable(EditorTexts.HELP);
            colour = AccentFrame.LABEL;
        }
        String full = text.getString();
        TextScale.text(graphics, font, TextFit.fit(full, width - 6, font::width), left + 3, top + 2, colour, false);
        if (mx >= left && my >= top && mx < left + width && my < top + WHY_HEIGHT) {
            // The why line's tooltip lists every reason in full; any other line shortened here shows whole.
            if (reasons) {
                tooltip = whyLines;
            } else if (TextFit.cut(full, width - 6, font::width)) {
                tooltip = List.of(text);
            }
        }
    }

    // ---- hit tests

    /** The topmost node at canvas point ({@code cx}, {@code cy}), or -1. */
    private int nodeAt(double cx, double cy) {
        EditorGraph<LogicKey> current = graph;
        if (current == null) {
            return -1;
        }
        List<LogicNode<LogicKey>> nodes = current.nodes();
        for (int i = nodes.size() - 1; i >= 0; i--) {
            LogicNode<LogicKey> node = nodes.get(i);
            List<Port> outputs = node.type().outputs(node.settings());
            double x = cx - node.x();
            double y = cy - node.y();
            int width = width(node);
            if (NodeLayout.portAt(node.type().inputs(node.settings()).size(), outputs.size(), width, x,
                    y) != NodeLayout.MISS
                    || NodeLayout.inside(node.type().inputs(node.settings()).size(), outputs.size(),
                            LiveLine.has(node.type()), width, x, y)) {
                return node.id();
            }
        }
        return -1;
    }

    /** The wire near screen point ({@code ux}, {@code uy}), or null. */
    private @Nullable Wire wireAt(double ux, double uy) {
        for (int i = wires.length - 1; i >= 0; i--) {
            LogicNode<LogicKey> from = byId.get(wires[i].from());
            LogicNode<LogicKey> to = byId.get(wires[i].to());
            if (from == null || to == null || wireOut[i] < 0 || wireIn[i] < 0) {
                continue;
            }
            int count = route(from, wireOut[i], to, wireIn[i]);
            if (RightAngleWire.near(route, count, ux, uy, 2)) {
                return wires[i];
            }
        }
        return null;
    }

    // ---- input

    private MouseButtonEvent units(MouseButtonEvent event) {
        return new MouseButtonEvent(scale.toUnits(event.x()), scale.toUnits(event.y()), event.buttonInfo());
    }

    @Override
    public void mouseMoved(double x, double y) {
        super.mouseMoved(scale.toUnits(x), scale.toUnits(y));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent raw, boolean doubleClick) {
        MouseButtonEvent event = units(raw);
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (moreOpen) {
            // A click on a greyed-out menu row does nothing; a click anywhere else only closes the menu.
            if (!overMenu(event.x(), event.y())) {
                moreOpen = false;
            }
            return true;
        }
        setFocused(null);
        EditorGraph<LogicKey> current = graph;
        if (current == null) {
            return false;
        }
        double ux = event.x();
        double uy = event.y();
        pressX = ux;
        pressY = uy;
        if (panel.bodyContains(ux, uy)) {
            if (event.button() == 0) {
                for (Control control : controls) {
                    int x = panel.bodyLeft() + control.x();
                    int y = panel.screenY(control.y());
                    if (control.kind() != ControlKind.LINE && control.kind() != ControlKind.ITEM && ux >= x && uy >= y
                            && ux < x + control.width() && uy < y + control.height()) {
                        clickControl(control);
                        return true;
                    }
                }
            }
            return true;
        }
        if (event.button() == 0 && paletteHeading(ux, uy)) {
            // The heading switches between the common node types and all of them.
            paletteAll = !paletteAll;
            LogicEditorPrefs.setPaletteAll(paletteAll);
            rows = Palette.rows(paletteAll);
            palette.setContentHeight(rows.size() * ROW);
            if (drag == Drag.WIRE || pending) {
                filterPalette();
            }
            return true;
        }
        if (palette.bodyContains(ux, uy)) {
            int row = (int) Math.floor(palette.contentY(uy) / ROW);
            if (event.button() == 0 && editable() && row >= 0 && row < rows.size()) {
                Optional<NodeType> type = rows.get(row).type();
                boolean filtering = pending;
                if (type.isPresent() && (!filtering || accepts[row])) {
                    paletteType = type.get();
                    drag = Drag.PALETTE;
                }
            }
            return true;
        }
        if (!view.contains(ux, uy)) {
            return false;
        }
        double cx = view.canvasX(ux);
        double cy = view.canvasY(uy);
        if (event.button() != 0) {
            drag = Drag.PAN;
            return true;
        }
        pending = false;
        List<LogicNode<LogicKey>> nodes = current.nodes();
        for (int i = nodes.size() - 1; i >= 0; i--) {
            LogicNode<LogicKey> node = nodes.get(i);
            List<Port> inputs = node.type().inputs(node.settings());
            List<Port> outputs = node.type().outputs(node.settings());
            int hit = NodeLayout.portAt(inputs.size(), outputs.size(), width(node), cx - node.x(), cy - node.y());
            if (hit != NodeLayout.MISS) {
                if (editable()) {
                    startWire(current, node, hit, inputs, outputs, cx, cy);
                }
                return true;
            }
        }
        int id = nodeAt(cx, cy);
        if (id >= 0) {
            LogicNode<LogicKey> node = byId.get(id);
            select(id);
            current.raise(id);
            if (node != null && editable()) {
                drag = Drag.NODE;
                dragNode = id;
                grabX = cx - node.x();
                grabY = cy - node.y();
                dragBefore = current.snapshot();
            }
            return true;
        }
        Wire wire = wireAt(ux, uy);
        if (wire != null) {
            select(-1);
            selectedWire = wire;
            return true;
        }
        select(-1);
        selectedWire = null;
        drag = Drag.PAN;
        return true;
    }

    private void startWire(EditorGraph<LogicKey> current, LogicNode<LogicKey> node, int hit, List<Port> inputs,
            List<Port> outputs, double cx, double cy) {
        boolean output = NodeLayout.hitOutput(hit);
        Port port = output ? outputs.get(NodeLayout.hitIndex(hit)) : inputs.get(NodeLayout.hitIndex(hit));
        Wire existing = output ? null : current.wireInto(node.id(), port.name());
        if (existing != null) {
            // Picking up a wire by its end: it comes off the input and follows the mouse from its source.
            change(g -> g.remove(existing));
            wireNode = existing.from();
            wirePort = existing.output();
            wireFromOutput = true;
        } else {
            wireNode = node.id();
            wirePort = port.name();
            wireFromOutput = output;
        }
        wireType = port.type();
        wireX = cx;
        wireY = cy;
        drag = Drag.WIRE;
        filterPalette();
    }

    private void select(int id) {
        if (selected != id) {
            selected = id;
            selectedWire = null;
            search = "";
            results = List.of();
            rebuildPanel();
        }
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent raw, double dx, double dy) {
        MouseButtonEvent event = units(raw);
        double ux = event.x();
        double uy = event.y();
        if (drag == Drag.NONE) {
            return super.mouseDragged(event, scale.toUnits(dx), scale.toUnits(dy));
        }
        switch (drag) {
            case PAN -> view.drag(scale.toUnits(dx), scale.toUnits(dy));
            case NODE -> moveDragged(ux, uy);
            case WIRE -> {
                wireX = view.canvasX(ux);
                wireY = view.canvasY(uy);
            }
            default -> {
            }
        }
        return true;
    }

    /** Moves the dragged node under the mouse, snapped; the first move of a drag is one undo step. */
    private void moveDragged(double ux, double uy) {
        EditorGraph<LogicKey> current = graph;
        if (current == null) {
            return;
        }
        int x = NodeLayout.snap((int) Math.round(view.canvasX(ux) - grabX));
        int y = NodeLayout.snap((int) Math.round(view.canvasY(uy) - grabY));
        LogicGraph<LogicKey> before = dragBefore;
        if (!current.move(dragNode, x, y)) {
            return;
        }
        if (before != null) {
            undo.record(before);
            dragBefore = null;
            dirty = true;
            leaveArmed = false;
            savedPaused = null;
        }
        LogicNode<LogicKey> moved = current.node(dragNode);
        if (moved != null) {
            byId.put(dragNode, moved);
        }
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent raw) {
        MouseButtonEvent event = units(raw);
        Drag ended = drag;
        drag = Drag.NONE;
        double ux = event.x();
        double uy = event.y();
        switch (ended) {
            case NODE -> {
                dragBefore = null;
                updateBounds();
            }
            case WIRE -> dropWire(ux, uy);
            case PALETTE -> dropPalette(ux, uy);
            default -> {
            }
        }
        return super.mouseReleased(event) || ended != Drag.NONE;
    }

    private void dropWire(double ux, double uy) {
        EditorGraph<LogicKey> current = graph;
        if (current == null) {
            return;
        }
        if (!view.contains(ux, uy)) {
            if (palette.contains(ux, uy)) {
                pending = true;
            }
            return;
        }
        double cx = view.canvasX(ux);
        double cy = view.canvasY(uy);
        List<LogicNode<LogicKey>> nodes = current.nodes();
        for (int i = nodes.size() - 1; i >= 0; i--) {
            LogicNode<LogicKey> node = nodes.get(i);
            List<Port> inputs = node.type().inputs(node.settings());
            List<Port> outputs = node.type().outputs(node.settings());
            int hit = NodeLayout.portAt(inputs.size(), outputs.size(), width(node), cx - node.x(), cy - node.y());
            if (hit == NodeLayout.MISS) {
                continue;
            }
            boolean output = NodeLayout.hitOutput(hit);
            Port port = output ? outputs.get(NodeLayout.hitIndex(hit)) : inputs.get(NodeLayout.hitIndex(hit));
            if (output != wireFromOutput) {
                connect(current, node.id(), port);
            }
            // An output onto an output (or a click on a port without a drag) makes nothing.
            return;
        }
        int target = nodeAt(cx, cy);
        LogicNode<LogicKey> onto = target < 0 ? null : byId.get(target);
        if (onto == null) {
            // Dropped on empty space: the palette offers the nodes that take the wire, to place here.
            pending = true;
            wireX = cx;
            wireY = cy;
            return;
        }
        // Dropped on a node's body: join its first port that takes the wire.
        List<Port> ports = wireFromOutput ? onto.type().inputs(onto.settings()) : onto.type().outputs(onto.settings());
        for (Port port : ports) {
            if (port.type() == wireType) {
                connect(current, onto.id(), port);
                return;
            }
        }
        flash(Component.literal(title(onto.type()) + " ")
                .append(Component.translatable(wireFromOutput ? EditorTexts.TAKES_NO : EditorTexts.GIVES_NO,
                        Component.translatable(wireType.nameKey()))),
                StatusPalette.bad());
    }

    private void connect(EditorGraph<LogicKey> current, int node, Port port) {
        int from = wireFromOutput ? wireNode : node;
        String output = wireFromOutput ? wirePort : port.name();
        int to = wireFromOutput ? node : wireNode;
        String input = wireFromOutput ? port.name() : wirePort;
        EditorGraph.Connect[] result = {EditorGraph.Connect.MISSING};
        change(g -> {
            result[0] = g.connect(from, output, to, input);
            return result[0].made();
        });
        if (result[0] == EditorGraph.Connect.WRONG_TYPE) {
            LogicNode<LogicKey> target = current.node(to);
            PortType type = target == null ? port.type() : EditorGraph.input(target, input).orElse(port).type();
            flash(Component.translatable(type.wrongTypeKey()), StatusPalette.bad());
        } else if (result[0] == EditorGraph.Connect.SAME_NODE) {
            flash(Component.translatable(EditorTexts.SAME_NODE), StatusPalette.bad());
        }
    }

    private void dropPalette(double ux, double uy) {
        NodeType type = paletteType;
        paletteType = null;
        EditorGraph<LogicKey> current = graph;
        if (type == null || current == null) {
            return;
        }
        boolean click = Math.abs(ux - pressX) < 3 && Math.abs(uy - pressY) < 3;
        int x;
        int y;
        if (view.contains(ux, uy) && !click) {
            x = (int) Math.round(view.canvasX(ux)) - width(type) / 2;
            y = (int) Math.round(view.canvasY(uy)) - NodeLayout.HEADER / 2;
        } else if (click && pending) {
            x = (int) Math.round(wireX) - (wireFromOutput ? 0 : width(type));
            y = (int) Math.round(wireY) - NodeLayout.PORT_TOP;
        } else if (click) {
            x = view.scrollX() + view.viewWidth() / 2 - width(type) / 2;
            y = view.scrollY() + view.viewHeight() / 3;
        } else {
            return;
        }
        addNode(current, type, NodeLayout.snap(x), NodeLayout.snap(y));
    }

    private void addNode(EditorGraph<LogicKey> current, NodeType type, int x, int y) {
        if (current.size() >= maxNodes) {
            flash(Component.translatable(EditorTexts.FULL, maxNodes), StatusPalette.bad());
            return;
        }
        boolean joining = pending;
        pending = false;
        Optional<String> port = joining ? Palette.joinPort(type, wireType, wireFromOutput) : Optional.empty();
        NodeSettings<LogicKey> settings = port.isPresent() && !wireFromOutput
                ? Palette.settingsFor(type, wireType, NodeSettings.empty())
                : NodeSettings.empty();
        int[] added = {-1};
        change(g -> {
            added[0] = g.add(type, x, y, settings);
            if (port.isPresent()) {
                if (wireFromOutput) {
                    g.connect(wireNode, wirePort, added[0], port.get());
                } else {
                    g.connect(added[0], port.get(), wireNode, wirePort);
                }
            }
            return true;
        });
        if (added[0] >= 0) {
            select(added[0]);
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        double ux = scale.toUnits(x);
        double uy = scale.toUnits(y);
        if (palette.mouseScrolled(ux, uy, scrollY, ROW * 2) || panel.mouseScrolled(ux, uy, scrollY, ROW * 2)) {
            return true;
        }
        if (view.contains(ux, uy)) {
            view.drag(scrollX * GRID, scrollY * GRID);
            return true;
        }
        return super.mouseScrolled(ux, uy, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            if (moreOpen) {
                moreOpen = false;
                return true;
            }
            if (pending) {
                pending = false;
                return true;
            }
            onClose();
            return true;
        }
        if (getFocused() instanceof EditBox box && box.isFocused()) {
            return super.keyPressed(event);
        }
        int key = event.key();
        if (event.hasControlDown()) {
            if (key == InputConstants.KEY_Z) {
                if (event.hasShiftDown()) {
                    redo();
                } else {
                    undo();
                }
                return true;
            }
            if (key == InputConstants.KEY_Y) {
                redo();
                return true;
            }
            if (key == InputConstants.KEY_S) {
                if (dirty) {
                    save();
                }
                return true;
            }
        }
        if (key == InputConstants.KEY_DELETE || key == InputConstants.KEY_BACKSPACE) {
            deleteSelection();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    @SuppressWarnings("ReferenceEquality") // the menu holds a new history list after each change
    public void tick() {
        super.tick();
        if (minecraft.player == null || minecraft.player.containerMenu.containerId != menu.containerId) {
            minecraft.setScreen(null);
            return;
        }
        if (liveCountdown) {
            refreshLiveLines();
        }
        AccentButton now = editNow;
        if (now != null) {
            now.visible = lockFree();
        }
        if (showHistory && menu.history(index) != shownHistory) {
            layoutPanel();
        }
        // The server leaves an open asked too soon after another unanswered (its rate limit): ask again.
        if (graph == null && ++waited % ASK_AGAIN_TICKS == 0) {
            Platform.get().network().sendToServer(new EditorPayloads.Open(menu.containerId, index));
        }
    }

    /** Escape and Back go back to the controller screen; unsaved changes need a second press. */
    @Override
    public void onClose() {
        if (dirty && !readOnly && !leaveArmed) {
            leaveArmed = true;
            flash(Component.translatable(EditorTexts.LEAVE_UNSAVED), StatusPalette.warn());
            return;
        }
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        super.removed();
        if (!closed) {
            closed = true;
            Platform.get().network().sendToServer(new EditorPayloads.Close(menu.containerId));
        }
    }
}
