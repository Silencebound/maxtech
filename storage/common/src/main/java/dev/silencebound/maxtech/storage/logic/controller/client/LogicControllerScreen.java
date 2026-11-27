package dev.silencebound.maxtech.storage.logic.controller.client;

import com.mojang.blaze3d.platform.Window;
import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.lib.api.client.gui.Accent;
import dev.silencebound.maxtech.lib.api.client.gui.AccentButton;
import dev.silencebound.maxtech.lib.api.client.gui.AccentInset;
import dev.silencebound.maxtech.lib.api.client.gui.AccentPanel;
import dev.silencebound.maxtech.lib.api.client.gui.AccentReadout;
import dev.silencebound.maxtech.lib.api.client.gui.AccentRow;
import dev.silencebound.maxtech.lib.api.client.gui.AccentStatus;
import dev.silencebound.maxtech.lib.api.client.gui.AccentTabRow;
import dev.silencebound.maxtech.lib.api.client.gui.AccentTag;
import dev.silencebound.maxtech.lib.api.client.gui.AccentToast;
import dev.silencebound.maxtech.lib.api.client.gui.ScrollBar;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.api.client.gui.TextFit;
import dev.silencebound.maxtech.storage.devicetab.client.DeviceScreen;
import dev.silencebound.maxtech.storage.label.LabelPayloads;
import dev.silencebound.maxtech.storage.logic.CardEdits;
import dev.silencebound.maxtech.storage.logic.GraphText;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.LogicNode;
import dev.silencebound.maxtech.storage.logic.NodeType;
import dev.silencebound.maxtech.storage.logic.RuleCard;
import dev.silencebound.maxtech.storage.logic.RuleCards;
import dev.silencebound.maxtech.storage.logic.Sequences;
import dev.silencebound.maxtech.storage.logic.SettingKind;
import dev.silencebound.maxtech.storage.logic.SettingSpec;
import dev.silencebound.maxtech.storage.logic.SettingValue;
import dev.silencebound.maxtech.storage.logic.controller.CardPayloads;
import dev.silencebound.maxtech.storage.logic.controller.ControllerLog;
import dev.silencebound.maxtech.storage.logic.controller.ControllerTexts;
import dev.silencebound.maxtech.storage.logic.controller.EditorPayloads;
import dev.silencebound.maxtech.storage.logic.controller.EditorTexts;
import dev.silencebound.maxtech.storage.logic.controller.HistoryEntry;
import dev.silencebound.maxtech.storage.logic.controller.GraphSlot;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerMenu;
import dev.silencebound.maxtech.storage.logic.controller.LogicEditing;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.logic.controller.LogicWorker;
import dev.silencebound.maxtech.storage.logic.controller.Plural;
import dev.silencebound.maxtech.storage.logic.controller.SharePayloads;
import dev.silencebound.maxtech.storage.logic.editor.LiveLine;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import dev.silencebound.maxtech.storage.logic.controller.PinHistory;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The Logic Controller's screen in the Accent Frame look (M7L2 chunk 10, moved from the casing look to Lib's
 * {@code lib.client.gui.accent} pieces in B09 item 21e). An Accent panel titled with the block's name and the
 * controller's label, with the device side tabs ({@link DeviceScreen}: the label, the status and power priority) on its
 * right, two page tabs and readouts (rules on of all, the team's checks and action points this second of its two
 * budgets, this controller's checks and action points per second) along its top:
 *
 * <ul>
 * <li>RULES: the rule list on the left (search with a count, the selected rule marked, a status dot per rule, rules
 * that are off dimmed, long names cut with an ellipsis and shown whole as a tooltip), with + NEW RULE (in the card view
 * a new rule starts as a card with blanks, switched off until it has a trigger and an action) and the buttons that add
 * a rule switched off (Paste, Import, From card, Templates) under it; the selected rule's detail on the right (name,
 * status tag, a SIMULATE tag while it only simulates, and a CARD / NODES switch; CARD reads the rule as a card
 * ({@link RuleCards}, with live values after runs; its words edit the rule: gold ones cycle a choice or take a number
 * or text, cyan ones pick an item or label, a line's x removes it, + TRIGGER / + CONDITION / + ACTION add a node
 * ({@link CardEdits}, {@link CardPopup}), each edit saved by the server like an editor save; lines the card cannot
 * change offer the editor), NODES draws the same graph read-only as nodes and wires ({@link NodeDiagram}, scaled to
 * fit, dragged to pan when it is too large; a click on a node opens the editor there) with the cost per run, when it
 * last ran and what came of it on one line above it and its errors or its last run's why chain and its pinned values
 * under it) with Edit (the node editor), On/Off, Simulate, Revert (asks for a second click before it puts back the last
 * working version) and the buttons that share it (Copy, Export, To card). The card view asks the server for the
 * selected rule's graph once per selection ({@link CardPayloads}); NODES uses the same graph.</li>
 * <li>LOG: the controller's log ({@link LogView}: rules paused by errors, each rule's last run, the network's notices)
 * with a filter toggle per kind, and the selected entry's why chain; Show rule jumps to its rule. The tab's badge
 * counts the error entries.</li>
 * </ul>
 *
 * The panel takes the mockup's size and shrinks to fit a small window (down to the smallest automatic GUI scale), and
 * every width comes from measured text, so it works at every GUI scale and in every language. The log comes from the
 * server only when something changed ({@link ControllerLog}); the rest travels as menu data slots. What a share did
 * shows as a toast. Client only.
 */
public final class LogicControllerScreen extends DeviceScreen<LogicControllerMenu> {
    private static final int GAP = 4;
    /** Room inside an inset screen's glass. */
    private static final int PAD = 3;
    private static final int TAB_RULES = 0;
    private static final int TAB_LOG = 1;
    /** How long a share's answer shows. */
    private static final int MESSAGE_MILLIS = 8000;
    /** How long Revert waits for the second click that confirms it. */
    private static final int REVERT_MILLIS = 4000;
    /** The widest values the readouts are sized for, so they do not jump as numbers change. */
    private static final long LCD_SAMPLE = 99_999;
    /** The filter chips' words, in {@link LogView.Kind} order. */
    private static final String[] KIND_KEYS = {ControllerTexts.KIND_ACTIONS, ControllerTexts.KIND_SKIPPED,
            ControllerTexts.KIND_WARNINGS, ControllerTexts.KIND_ERRORS};
    private static final int SEARCH_FILL = Accent.SLOT;
    private static final int SEARCH_EDGE = Accent.BEVEL;
    private static final int CONNECTOR = Accent.SLOT_EDGE;
    /** The detail's first view. */
    private static final int VIEW_CARD = 0;
    /** The detail's second view: the rule as a node diagram with its run details. */
    private static final int VIEW_NODES = 1;
    /** The detail's third view: the rule's change history, newest first (M7L2 chunk 9). */
    private static final int VIEW_HISTORY = 2;
    /** How far each ALL OF / ANY OF level indents a card line. */
    private static final int INDENT = 8;
    /** The words in front of card lines, for the keyword column's width. */
    private static final List<String> KEYWORDS = List.of(RuleCards.SECTION_REMEMBER, RuleCards.SECTION_LET,
            RuleCards.SECTION_WHEN, RuleCards.SECTION_IF, RuleCards.SECTION_THEN, RuleCards.SECTION_OTHER,
            RuleCards.WORD_OR, RuleCards.WORD_AND);

    private final AccentToast toast = new AccentToast();
    /** The panel's size, fitted to the window in {@code init}; never above the size the screen was made with. */
    private int frameWidth;
    private int frameHeight;
    // Kept across init, so a rebuild (tab switch, coming back from the editor) keeps the view.
    private int tab = TAB_RULES;
    private int selected;
    private String search = "";
    private int listScroll;
    private final EnumSet<LogView.Kind> shown = EnumSet.allOf(LogView.Kind.class);
    private int logSelected;
    private int logScroll;
    private boolean rebuild;
    /** Scroll the rule list so the selected rule shows (after Show rule). */
    private boolean reveal;
    private ControllerLog.@Nullable Update seenLog;
    private List<LogView.Entry> entries = List.of();
    private int tabErrors = -1;
    // The detail's CARD / NODES switch and the card view.
    private int view = VIEW_CARD;
    private int cardScroll;
    /** The rule whose card the screen last asked for; -1 before the first. */
    private int requested = -1;
    private LogicControllerMenu.@Nullable CardView seenCard;
    private @Nullable RuleCard ruleCard;
    private Map<String, Long> cardValues = Map.of();
    private final NodeDiagram diagram = new NodeDiagram();
    // Editing on the card: what can be clicked this frame, the popup, which lines can be removed, and the edit on its
    // way (sent at most every save interval; the server's answer, then the card it sends again, end it).
    private final List<CardHit> cardHits = new ArrayList<>();
    private @Nullable CardPopup popup;
    private boolean[] removable = new boolean[0];
    private int cardX;
    private int cardTop;
    private int cardRoom;
    private int cardBottom;
    private CardPayloads.@Nullable Edit queuedEdit;
    private boolean awaiting;
    private boolean answered;
    private long awaitingSince;
    private long lastEditSent = Long.MIN_VALUE / 2;
    private LogicControllerMenu.@Nullable CardView cardAtSend;
    /** A rule the card view just started, selected once the row count shows it; -1 for none. */
    private int pendingSelect = -1;
    /** A left press started on the diagram; a release without much movement is a click on a node. */
    private boolean diagramPress;
    private double diagramMoved;

    // Laid out in init.
    private @Nullable AccentPanel frame;
    private @Nullable AccentTabRow pages;
    private String subtitle = "";
    /** The server answered this screen's label ask, so {@link LabelPayloads#shown} is this controller's label. */
    private boolean labelSeen;
    private final int[] lcdX = new int[4];
    private final int[] lcdWidth = new int[4];
    private int lcdY;
    private int tabX;
    private int tabY;
    private int bodyTop;
    private int bodyBottom;
    // RULES
    private int listX;
    private int listWidth;
    private int detailX;
    private int detailWidth;
    private int searchY;
    private int rowsTop;
    private int rowsBottom;
    private int detailButtonsTop;
    private @Nullable AccentTabRow views;
    private int viewTop;
    private int keywordColumn;
    private @Nullable EditBox searchBox;
    private final List<AccentButton> addButtons = new ArrayList<>();
    private @Nullable AccentButton edit;
    private @Nullable AccentButton toggle;
    private @Nullable AccentButton simulate;
    private @Nullable AccentButton revert;
    /** "Copy with values", or "Detach" for a copy (M7L2 row 19). */
    private @Nullable AccentButton copyButton;
    /** Revert was clicked once for this rule; a second click before {@link #revertUntil} confirms it. */
    private int revertRow = -1;
    private long revertUntil;
    private final List<AccentButton> shareButtons = new ArrayList<>();
    // LOG
    private int logTop;
    private int logListWidth;
    private int whyX;
    private int whyWidth;
    private int showRuleTop;
    /** Whether the selected log entry has a rule to show, as drawn this frame. */
    private boolean canShowRule;

    public LogicControllerScreen(LogicControllerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, preferred(true), preferred(false));
        this.titleLabelY = -100;
        this.inventoryLabelY = -100;
    }

    /** The frame's size in the window as it is now, side tabs left out; it caps the frame later. */
    private static int preferred(boolean width) {
        Window window = Minecraft.getInstance().getWindow();
        return width
                ? ControllerLayout.fit(window.getGuiScaledWidth() - ControllerLayout.SIDE_TABS,
                        ControllerLayout.PREFERRED_WIDTH, ControllerLayout.MIN_WIDTH, ControllerLayout.PREFERRED_WIDTH)
                : ControllerLayout.fit(window.getGuiScaledHeight(), ControllerLayout.PREFERRED_HEIGHT,
                        ControllerLayout.MIN_HEIGHT, ControllerLayout.PREFERRED_HEIGHT);
    }

    // ---- layout

    @Override
    protected void init() {
        // The panel sizes to the window, leaving room for the side tabs; DeviceScreen lays them out beside it.
        frameWidth = ControllerLayout.fit(width - ControllerLayout.SIDE_TABS, ControllerLayout.PREFERRED_WIDTH,
                ControllerLayout.MIN_WIDTH, imageWidth);
        frameHeight = ControllerLayout.fit(height, ControllerLayout.PREFERRED_HEIGHT, ControllerLayout.MIN_HEIGHT,
                imageHeight);
        super.init();
        subtitle = subtitle();
        AccentPanel f = AccentPanel.of(leftPos, topPos, frameWidth, frameHeight, title, subtitle);
        frame = f;
        layoutHeader(f);
        bodyBottom = f.contentBottom();
        addButtons.clear();
        shareButtons.clear();
        searchBox = null;
        edit = null;
        toggle = null;
        simulate = null;
        revert = null;
        views = null;
        cardHits.clear();
        popup = new CardPopup(font, new CardPopup.Host() {
            @Override
            public EditBox addBox(EditBox box) {
                addRenderableWidget(box);
                setFocused(box);
                return box;
            }

            @Override
            public void removeBox(EditBox box) {
                removeWidget(box);
            }

            @Override
            public void pickType(CardEdits.Add add, NodeType type) {
                sendEdit(selected, CardPayloads.OP_ADD, 0, add.name(), "", null, type.id());
            }

            @Override
            public void setValue(int node, String setting, SettingValue<LogicKey> value) {
                sendEdit(selected, CardPayloads.OP_SET, node, "", setting, value, "");
            }

            @Override
            public ItemStack held() {
                return minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getMainHandItem();
            }
        });
        if (tab == TAB_RULES) {
            initRules(f);
        } else {
            initLog(f);
        }
    }

    /** The panel and its side tabs centred together, the title tab kept on the window. */
    @Override
    protected void placePanel() {
        leftPos = ControllerLayout.left(width, frameWidth, ControllerLayout.SIDE_TABS);
        topPos = Accent.top(height, frameHeight);
    }

    @Override
    protected int panelWidth() {
        return frameWidth;
    }

    @Override
    protected int panelHeight() {
        return frameHeight;
    }

    /**
     * Places the page tabs at the top left of the panel and the readouts right to left in the same row; a readout that
     * would meet the tabs is left out (the narrowest windows).
     */
    private void layoutHeader(AccentPanel f) {
        int lcdHeight = AccentReadout.height(font);
        lcdWidth[0] = AccentReadout.minWidth(font.width(I18n.get(ControllerTexts.LCD_RULES)), font
                .width(I18n.get(ControllerTexts.LCD_RULES_VALUE, LogicControllerMenu.ROWS, LogicControllerMenu.ROWS)));
        lcdWidth[1] = AccentReadout.minWidth(font.width(I18n.get(ControllerTexts.LCD_TEAM)),
                font.width(InfoFormat.number(LCD_SAMPLE) + " / " + InfoFormat.number(LCD_SAMPLE)));
        lcdWidth[2] = AccentReadout.minWidth(font.width(I18n.get(ControllerTexts.LCD_OWN)), font.width(
                I18n.get(ControllerTexts.LCD_OWN_VALUE, InfoFormat.number(LCD_SAMPLE), InfoFormat.number(LCD_SAMPLE))));
        lcdWidth[3] = AccentReadout.minWidth(font.width(I18n.get(ControllerTexts.LCD_ACTIONS)),
                font.width(InfoFormat.number(LCD_SAMPLE) + " / " + InfoFormat.number(LCD_SAMPLE)));
        tabX = f.contentLeft();
        tabY = f.contentTop();
        AccentTabRow row = tabRow(tabX, tabY);
        lcdY = tabY + (row.height() - lcdHeight) / 2;
        bodyTop = tabY + Math.max(row.height(), lcdHeight) + GAP;
        int limit = tabX + row.width() + 2 * GAP;
        // Right to left.
        int x = f.contentRight();
        int[] order = {3, 1, 0, 2};
        for (int i : order) {
            lcdX[i] = Integer.MIN_VALUE;
        }
        for (int i = 0; i < order.length; i++) {
            int index = order[i];
            if (x - lcdWidth[index] < limit) {
                break;
            }
            x -= lcdWidth[index];
            lcdX[index] = x;
            x -= GAP;
        }
        pages = row;
    }

    private AccentTabRow tabRow(int x, int y) {
        int errors = errorCount();
        tabErrors = errors;
        AccentTabRow row = new AccentTabRow(font, x, y,
                List.of(AccentTabRow.Tab.text(I18n.get(ControllerTexts.TAB_RULES)),
                        AccentTabRow.Tab.text(I18n.get(ControllerTexts.TAB_LOG)).withCount(errors)));
        row.setActive(tab);
        return row;
    }

    private void initRules(AccentPanel f) {
        listWidth = ControllerLayout.listWidth(f.contentWidth());
        listX = f.contentLeft();
        detailX = listX + listWidth + GAP;
        detailWidth = Math.max(0, f.contentRight() - detailX);
        int buttonHeight = AccentButton.COMPACT_HEIGHT;

        // The list: search box, header, rows, then the buttons that add a rule.
        int glassX = listX + AccentInset.BORDER;
        int glassWidth = AccentInset.innerWidth(listWidth);
        searchY = bodyTop + AccentInset.BORDER + PAD;
        int boxHeight = font.lineHeight + 3;
        EditBox box = new EditBox(font, glassX + PAD + 3, searchY + 2, glassWidth - 2 * PAD - 6, font.lineHeight,
                Component.translatable(ControllerTexts.SEARCH));
        box.setBordered(false);
        box.setMaxLength(GraphSlot.MAX_NAME);
        box.setTextColor(Accent.TEXT);
        box.setHint(Component.translatable(ControllerTexts.SEARCH).withColor(Accent.DIM));
        box.setValue(search);
        box.setResponder(value -> {
            search = value;
            listScroll = 0;
        });
        searchBox = addRenderableWidget(box);
        rowsTop = searchY + boxHeight + PAD + font.lineHeight + 1;

        String[] addKeys = {ControllerTexts.PASTE, ControllerTexts.IMPORT, ControllerTexts.FROM_CARD,
                ControllerTexts.TEMPLATES};
        String[] addTips = {ControllerTexts.PASTE_TIP, ControllerTexts.IMPORT_TIP, ControllerTexts.FROM_CARD_TIP,
                ControllerTexts.TEMPLATES_TIP};
        Runnable[] addActions = {this::paste, this::importGraph, this::loadCard,
                () -> minecraft.setScreen(new LogicTemplateScreen(this))};
        int room = glassWidth - 2 * PAD;
        int[] widths = new int[addKeys.length];
        for (int i = 0; i < addKeys.length; i++) {
            widths[i] = AccentButton.width(font, Component.translatable(addKeys[i]));
        }
        int[] xs = new int[addKeys.length];
        int[] rows = new int[addKeys.length];
        int addRows = ControllerLayout.flow(widths, room, 2, xs, rows);
        int newRuleY = bodyBottom - AccentInset.BORDER - PAD - AccentButton.HEIGHT;
        int addTop = newRuleY - 2 - addRows * (buttonHeight + 2);
        for (int i = 0; i < addKeys.length; i++) {
            addButtons
                    .add(button(glassX + PAD + xs[i], addTop + rows[i] * (buttonHeight + 2), Math.min(widths[i], room),
                            buttonHeight, addKeys[i], addTips[i], addActions[i]).enabled(this::hasRoom));
        }
        button(glassX + PAD, newRuleY, room, AccentButton.HEIGHT, ControllerTexts.NEW_RULE,
                ControllerTexts.NEW_RULE_TIP, this::newRule).enabled(this::hasRoom);
        rowsBottom = addTop - PAD;

        // The detail: text from the top, its buttons at the bottom.
        int innerX = detailX + AccentInset.BORDER + PAD;
        int innerWidth = AccentInset.innerWidth(detailWidth) - 2 * PAD;
        String[] keys = {EditorTexts.EDIT, ControllerTexts.ON, ControllerTexts.SIMULATE, ControllerTexts.REVERT,
                ControllerTexts.COPY_WITH_VALUES, ControllerTexts.COPY, ControllerTexts.EXPORT,
                ControllerTexts.TO_CARD};
        int[] detailWidths = new int[keys.length];
        for (int i = 0; i < keys.length; i++) {
            detailWidths[i] = AccentButton.width(font, Component.translatable(keys[i]));
        }
        // The buttons whose label changes keep one width for all of their labels.
        Component on = Component.translatable(ControllerTexts.ON);
        Component off = Component.translatable(ControllerTexts.OFF);
        Component simulateLabel = Component.translatable(ControllerTexts.SIMULATE);
        Component simulateStop = Component.translatable(ControllerTexts.SIMULATE_STOP);
        Component revertLabel = Component.translatable(ControllerTexts.REVERT);
        Component revertConfirm = Component.translatable(ControllerTexts.REVERT_CONFIRM);
        Component copyLabel = Component.translatable(ControllerTexts.COPY_WITH_VALUES);
        Component detach = Component.translatable(ControllerTexts.DETACH);
        detailWidths[1] = AccentButton.width(font, on, off);
        detailWidths[2] = AccentButton.width(font, simulateLabel, simulateStop);
        detailWidths[3] = AccentButton.width(font, revertLabel, revertConfirm);
        detailWidths[4] = AccentButton.width(font, copyLabel, detach);
        int[] dxs = new int[keys.length];
        int[] drows = new int[keys.length];
        int detailRows = ControllerLayout.flow(detailWidths, innerWidth, 2, dxs, drows);
        detailButtonsTop = bodyBottom - AccentInset.BORDER - PAD - detailRows * (buttonHeight + 2) + 2;
        Runnable[] detailActions = {() -> edit(selected), this::toggleSelected, this::simulateSelected,
                this::revertSelected, this::copySelected, () -> export(SharePayloads.TO_GAME),
                () -> export(SharePayloads.TO_CLIPBOARD), () -> export(SharePayloads.TO_CARD)};
        String[] detailTips = {ControllerTexts.EDIT_TIP, ControllerTexts.TOGGLE_TIP, ControllerTexts.SIMULATE_TIP,
                ControllerTexts.REVERT_TIP, ControllerTexts.COPY_WITH_VALUES_TIP, ControllerTexts.COPY_TIP,
                ControllerTexts.EXPORT_TIP, ControllerTexts.TO_CARD_TIP};
        List<AccentButton> made = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            made.add(button(innerX + dxs[i], detailButtonsTop + drows[i] * (buttonHeight + 2),
                    Math.min(detailWidths[i], innerWidth), buttonHeight, keys[i], detailTips[i], detailActions[i]));
        }
        edit = made.get(0);
        toggle = made.get(1).label(() -> hasRule() && menu.status(selected) == LogicWorker.Status.DISABLED ? off : on);
        simulate = made.get(2).label(() -> hasRule() && menu.simulating(selected) ? simulateStop : simulateLabel);
        revert = made.get(3).label(() -> revertArmed() ? revertConfirm : revertLabel)
                .enabled(() -> hasRule() && menu.hasPrevious(selected))
                .tooltip(() -> revertArmed() ? 2 : hasRule() && menu.hasPrevious(selected) ? 1 : 0,
                        key -> Component.translatable(key == 2
                                ? ControllerTexts.REVERT_CONFIRM_TIP
                                : key == 1 ? ControllerTexts.REVERT_TIP : ControllerTexts.REVERT_NONE_TIP));
        copyButton = made.get(4).label(() -> hasRule() && menu.isCopy(selected) ? detach : copyLabel)
                .enabled(() -> hasRule() && (menu.isCopy(selected) || menu.hasParameters(selected)))
                .tooltip(() -> !hasRule() ? 0 : menu.isCopy(selected) ? 2 : menu.hasParameters(selected) ? 1 : 0,
                        key -> Component.translatable(key == 2
                                ? ControllerTexts.DETACH_TIP
                                : key == 1
                                        ? ControllerTexts.COPY_WITH_VALUES_TIP
                                        : ControllerTexts.COPY_NO_PARAMS_TIP));
        shareButtons.addAll(made.subList(5, made.size()));

        // The CARD / NODES switch under the rule's name.
        viewTop = bodyTop + AccentInset.BORDER + PAD + AccentTag.height(font) + GAP;
        AccentTabRow row = new AccentTabRow(font, innerX, viewTop,
                List.of(AccentTabRow.Tab.text(I18n.get(ControllerTexts.VIEW_CARD)),
                        AccentTabRow.Tab.text(I18n.get(ControllerTexts.VIEW_NODES)),
                        AccentTabRow.Tab.text(I18n.get(ControllerTexts.VIEW_HISTORY))));
        row.setActive(view);
        views = row;
        List<String> words = new ArrayList<>(KEYWORDS.size());
        for (String key : KEYWORDS) {
            words.add(I18n.get(key));
        }
        keywordColumn = TextFit.widest(words, font::width) + GAP;
    }

    /** The log's filters: a toggle per kind, lit while the kind shows. */
    private void initLog(AccentPanel f) {
        int toggleHeight = AccentButton.COMPACT_HEIGHT;
        int[] widths = new int[KIND_KEYS.length];
        for (int i = 0; i < KIND_KEYS.length; i++) {
            widths[i] = AccentButton.width(font, Component.translatable(KIND_KEYS[i]));
        }
        int[] xs = new int[widths.length];
        int[] rows = new int[widths.length];
        int chipRows = ControllerLayout.flow(widths, f.contentWidth(), GAP, xs, rows);
        LogView.Kind[] kinds = LogView.Kind.values();
        for (int i = 0; i < KIND_KEYS.length; i++) {
            LogView.Kind kind = kinds[i];
            addRenderableWidget(new AccentButton(f.contentLeft() + xs[i], bodyTop + rows[i] * (toggleHeight + 2),
                    widths[i], toggleHeight, Component.translatable(KIND_KEYS[i]), () -> {
                        if (!shown.remove(kind)) {
                            shown.add(kind);
                        }
                        logScroll = 0;
                    }).lit(() -> shown.contains(kind)));
        }
        logTop = bodyTop + chipRows * (toggleHeight + 2) + 2;
        logListWidth = Math.max(ControllerLayout.LIST_MIN, f.contentWidth() * 3 / 5);
        whyX = f.contentLeft() + logListWidth + GAP;
        whyWidth = Math.max(0, f.contentRight() - whyX);
        int buttonHeight = AccentButton.HEIGHT;
        int innerWidth = AccentInset.innerWidth(whyWidth) - 2 * PAD;
        Component label = Component.translatable(ControllerTexts.SHOW_RULE);
        addRenderableWidget(
                new AccentButton(whyX + AccentInset.BORDER + PAD, bodyBottom - AccentInset.BORDER - PAD - buttonHeight,
                        Math.min(AccentButton.width(font, label), innerWidth), buttonHeight, label, this::showRule)
                        .enabled(() -> canShowRule));
        showRuleTop = bodyBottom - AccentInset.BORDER - PAD - buttonHeight;
    }

    private AccentButton button(int x, int y, int width, int height, String key, String tip, Runnable action) {
        AccentButton made = new AccentButton(x, y, width, height, Component.translatable(key), action);
        made.tooltip(Component.translatable(tip));
        return addRenderableWidget(made);
    }

    /** Whether a rule is selected. */
    private boolean hasRule() {
        return selected < count();
    }

    /** Whether the controller has room for another rule. */
    private boolean hasRoom() {
        return menu.count() < LogicControllerMenu.ROWS;
    }

    /**
     * The title's second part: the controller's label once the server named it, else nothing (the label has its own
     * side tab).
     */
    private String subtitle() {
        String label = labelSeen ? LabelPayloads.shown().text() : "";
        return label.isEmpty() ? "" : I18n.get(ControllerTexts.SUBTITLE_LABEL, label.toUpperCase(Locale.ROOT));
    }

    // ---- actions

    /** Opens the node editor on graph {@code graph}; the graph count starts a new one. */
    private void edit(int graph) {
        minecraft.setScreen(new LogicEditorScreen(this, menu, graph));
    }

    /** + NEW RULE: in the card view a new rule starts as a card with blanks; otherwise the editor opens on one. */
    private void newRule() {
        if (view == VIEW_CARD && menu.count() < LogicControllerMenu.ROWS) {
            sendEdit(menu.count(), CardPayloads.OP_NEW, 0, "", "", null, "");
        } else {
            edit(Math.min(menu.count(), LogicControllerMenu.ROWS - 1));
        }
    }

    /**
     * Sends one card edit of rule {@code index}; while the last one is still on its way the click does nothing, so
     * every edit applies to the card the player sees.
     */
    private void sendEdit(int index, int op, int node, String part, String setting,
            @Nullable SettingValue<LogicKey> value, String type) {
        if (awaiting || queuedEdit != null || minecraft.level == null) {
            return;
        }
        CompoundTag tag = value == null
                ? new CompoundTag()
                : LogicEditing.encodeValue(minecraft.level.registryAccess(), value);
        queuedEdit = new CardPayloads.Edit(menu.containerId, index, op, node, part, setting, tag, type);
        flushEdit();
    }

    /** Sends the queued edit once the server's save interval since the last one has passed. */
    private void flushEdit() {
        CardPayloads.Edit edit = queuedEdit;
        if (edit == null || minecraft.level == null) {
            return;
        }
        long now = minecraft.level.getGameTime();
        if (now - lastEditSent <= LogicEditing.SAVE_INTERVAL) {
            return;
        }
        queuedEdit = null;
        lastEditSent = now;
        awaiting = true;
        answered = false;
        awaitingSince = now;
        cardAtSend = menu.card().orElse(null);
        Platform.get().network().sendToServer(edit);
    }

    /** Whether a card arrived since the last edit was sent. */
    @SuppressWarnings("ReferenceEquality") // each card the server sends is a new object
    private boolean cardChanged() {
        return menu.card().orElse(null) != cardAtSend;
    }

    /** The server's answer to a card edit: a refusal shows as a toast; a new rule is selected once it is listed. */
    void cardEdited(CardPayloads.Edited payload) {
        answered = true;
        if (!payload.done()) {
            awaiting = false;
            if (!payload.refusal().isEmpty()) {
                show(payload.refusal(), payload.args(), false);
            }
            return;
        }
        if (payload.index() != selected) {
            pendingSelect = payload.index();
            requested = payload.index();
            view = VIEW_CARD;
            cardScroll = 0;
            rebuild = true;
        }
    }

    private void toggleSelected() {
        if (minecraft.gameMode != null && selected < count()) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, selected);
        }
    }

    /** Adds a copy of the selected rule with its own values, or detaches the selected copy (M7L2 row 19). */
    private void copySelected() {
        if (minecraft.gameMode != null && selected < count()
                && (menu.isCopy(selected) || menu.hasParameters(selected))) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, LogicControllerMenu.BUTTON_COPY + selected);
        }
    }

    /** Switches simulate mode of the selected rule. */
    private void simulateSelected() {
        if (minecraft.gameMode != null && selected < count()) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId,
                    LogicControllerMenu.BUTTON_SIMULATE + selected);
        }
    }

    /**
     * Revert asks first: the first click arms it for {@link #REVERT_MILLIS} (the button reads "Sure? Revert"), the
     * second puts back the selected rule's last working version.
     */
    private void revertSelected() {
        if (minecraft.gameMode == null || selected >= count() || !menu.hasPrevious(selected)) {
            return;
        }
        long now = Util.getMillis();
        if (revertRow == selected && now < revertUntil) {
            revertRow = -1;
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId,
                    LogicControllerMenu.BUTTON_REVERT + selected);
        } else {
            revertRow = selected;
            revertUntil = now + REVERT_MILLIS;
        }
    }

    /** Whether Revert waits for its confirming click on the selected rule. */
    private boolean revertArmed() {
        return revertRow == selected && Util.getMillis() < revertUntil;
    }

    /** Asks the server for the selected graph: as text for a clipboard ({@link LogicShareClient}) or on a card. */
    private void export(int target) {
        Platform.get().network().sendToServer(new SharePayloads.Export(menu.containerId, selected, target));
    }

    /**
     * Sends the system clipboard's text to the server, which checks and adds it; an empty clipboard or a text too long
     * for any server is answered here without sending.
     */
    private void importGraph() {
        String text = minecraft.keyboardHandler.getClipboard();
        if (text.isBlank()) {
            show(GraphText.EMPTY, List.of(), false);
        } else {
            send(text);
        }
    }

    /** Sends the in-game clipboard's text to the server, like Import. */
    private void paste() {
        String text = LogicShareClient.clipboard();
        if (text == null) {
            show(ControllerTexts.SHARE_PASTE_EMPTY, List.of(), false);
        } else {
            send(text);
        }
    }

    private void send(String text) {
        if (text.length() > GraphText.HARD_MAX_LENGTH) {
            show(GraphText.TOO_LONG, List.of(String.valueOf(text.length()), String.valueOf(GraphText.HARD_MAX_LENGTH)),
                    false);
        } else {
            Platform.get().network().sendToServer(new SharePayloads.Import(menu.containerId, text));
            show(ControllerTexts.SHARE_CHECKING, List.of(), true);
        }
    }

    private void loadCard() {
        Platform.get().network().sendToServer(new SharePayloads.CardLoad(menu.containerId));
        show(ControllerTexts.SHARE_CHECKING, List.of(), true);
    }

    /** Shows {@code key} with {@code args} as a toast, green when {@code good}. */
    void show(String key, List<String> args, boolean good) {
        AccentPanel f = frame;
        int room = f == null ? 200 : f.contentWidth() / 2;
        String text = TextFit.fit(I18n.get(key, args.toArray()), room, font::width);
        toast.show(Component.literal(text), Component.empty(), good ? AccentToast.Kind.INFO : AccentToast.Kind.ERROR,
                Util.getMillis(), MESSAGE_MILLIS);
    }

    /** Jumps from the selected log entry to its rule on the RULES tab. */
    private void showRule() {
        LogView.Entry entry = selectedEntry();
        if (entry != null && entry.rule() >= 0) {
            selected = entry.rule();
            cardScroll = 0;
            search = "";
            reveal = true;
            tab = TAB_RULES;
            rebuild = true;
        }
    }

    // ---- data

    private int count() {
        return Math.min(menu.count(), LogicControllerMenu.ROWS);
    }

    /** A graph's name as the list shows it: its name, or its row number when it has none. */
    private String shownName(int row) {
        return menu.name(row).isEmpty() ? "#" + (row + 1) : menu.name(row);
    }

    /** The rules the search matches, in order. */
    private List<Integer> matching() {
        String query = search.strip().toLowerCase(Locale.ROOT);
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < count(); i++) {
            if (query.isEmpty() || shownName(i).toLowerCase(Locale.ROOT).contains(query)) {
                out.add(i);
            }
        }
        return out;
    }

    private int visibleRules() {
        return ControllerLayout.visibleRows(rowsBottom - rowsTop, AccentRow.height(font));
    }

    private static AccentStatus status(LogicWorker.Status status) {
        return switch (status) {
            case RUNNING -> AccentStatus.OK;
            case WAITING -> AccentStatus.WARN;
            case PAUSED -> AccentStatus.ERROR;
            case DISABLED, IDLE -> AccentStatus.OFF;
        };
    }

    /** A log entry kind's colour: its edge in the list and the last dot of its why chain. */
    private static int colour(LogView.Kind kind) {
        return switch (kind) {
            case ACTION -> Accent.GREEN;
            case SKIPPED -> Accent.LABEL;
            case WARNING -> Accent.AMBER;
            case ERROR -> Accent.RED;
        };
    }

    private static int tagColour(LogicWorker.Status status) {
        return switch (status) {
            case RUNNING -> Accent.GREEN;
            case WAITING, DISABLED -> Accent.AMBER;
            case PAUSED -> Accent.RED;
            case IDLE -> Accent.DIM;
        };
    }

    private ControllerLog.@Nullable Rule rule(int index) {
        ControllerLog.Update log = menu.log().orElse(null);
        return log == null || index < 0 || index >= log.rules().size() ? null : log.rules().get(index);
    }

    /** The log's entries, made again only when a new log arrived. */
    @SuppressWarnings("ReferenceEquality") // each log the server sends is a new object; the same one needs nothing new
    private List<LogView.Entry> entries() {
        ControllerLog.Update log = menu.log().orElse(null);
        if (log != seenLog) {
            seenLog = log;
            entries = log == null ? List.of() : LogView.entries(log);
        }
        return entries;
    }

    private int errorCount() {
        return LogView.count(entries(), LogView.Kind.ERROR);
    }

    private List<LogView.Entry> shownEntries() {
        return LogView.filter(entries(), shown);
    }

    private LogView.@Nullable Entry selectedEntry() {
        List<LogView.Entry> list = shownEntries();
        if (list.isEmpty()) {
            return null;
        }
        logSelected = Math.clamp(logSelected, 0, list.size() - 1);
        return list.get(logSelected);
    }

    /** A log line in words: the node's name, then the reason or action with its values. */
    private static Component line(ControllerLog.Line line) {
        Component item = line.itemKey().isEmpty()
                ? Component.literal(line.itemText())
                : Component.translatable(line.itemKey());
        MutableComponent text = Component.translatable(line.key(), line.a(), line.b(), line.c(), item);
        return line.node().isEmpty() ? text : Component.literal(I18n.get(line.node()) + ": ").append(text);
    }

    /** What a log entry says in its list row. */
    private Component entryText(LogView.Entry entry) {
        ControllerLog.Line first = entry.chain().isEmpty() ? null : entry.chain().get(0);
        NetworkNotices.Notice notice = entry.notice();
        if (notice != null) {
            return notice.line();
        }
        return switch (entry.kind()) {
            case ERROR ->
                Component.translatable(ControllerTexts.LOG_PAUSED, first == null ? Component.empty() : line(first));
            case ACTION -> {
                ControllerLog.Rule rule = rule(entry.rule());
                int actions = rule == null ? 0 : rule.actions();
                Component text = first == null ? Component.translatable(ControllerTexts.DETAIL_NOTHING) : line(first);
                yield actions > 1 ? Component.translatable(ControllerTexts.LOG_ACTIONS_MORE, text, actions - 1) : text;
            }
            case SKIPPED, WARNING ->
                first == null ? Component.translatable(ControllerTexts.DETAIL_NOTHING) : line(first);
        };
    }

    /** "12 s ago" from the client level's clock, or "now" for an entry without a time. */
    private String ago(long time) {
        if (time < 0 || minecraft.level == null) {
            return I18n.get(ControllerTexts.LOG_NOW);
        }
        long ticks = Math.max(0, minecraft.level.getGameTime() - time);
        return I18n.get(ControllerTexts.AGO, InfoFormat.number(ticks / 20));
    }

    // ---- drawing

    @Override
    protected void extractPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        if (rebuild) {
            rebuild = false;
            rebuildWidgets();
        }
        AccentPanel f = frame;
        AccentTabRow t = pages;
        if (f == null || t == null) {
            return;
        }
        if (errorCount() != tabErrors) {
            t = tabRow(tabX, tabY);
            pages = t;
        }
        f.draw(graphics, font, mouseX, mouseY);
        t.draw(graphics, font, mouseX, mouseY);
        drawReadouts(graphics, mouseX, mouseY);
        if (tab == TAB_RULES) {
            drawRules(graphics, mouseX, mouseY);
        } else {
            drawLog(graphics, mouseX, mouseY);
        }
    }

    private void drawReadouts(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int on = 0;
        for (int i = 0; i < count(); i++) {
            LogicWorker.Status status = menu.status(i);
            if (status == LogicWorker.Status.RUNNING || status == LogicWorker.Status.WAITING) {
                on++;
            }
        }
        String[] labels = {I18n.get(ControllerTexts.LCD_RULES), I18n.get(ControllerTexts.LCD_TEAM),
                I18n.get(ControllerTexts.LCD_OWN), I18n.get(ControllerTexts.LCD_ACTIONS)};
        String[] values = {I18n.get(ControllerTexts.LCD_RULES_VALUE, on, count()),
                InfoFormat.number(menu.teamUsed()) + " / " + InfoFormat.number(menu.teamLimit()),
                I18n.get(ControllerTexts.LCD_OWN_VALUE, InfoFormat.number(menu.ownChecks()),
                        InfoFormat.number(menu.ownPoints())),
                InfoFormat.number(menu.teamPointsUsed()) + " / " + InfoFormat.number(menu.teamPointsLimit())};
        String[] tips = {"", ControllerTexts.LCD_TEAM_TIP, ControllerTexts.LCD_OWN_TIP,
                ControllerTexts.LCD_ACTIONS_TIP};
        for (int i = 0; i < labels.length; i++) {
            if (lcdX[i] == Integer.MIN_VALUE) {
                continue;
            }
            AccentReadout.draw(graphics, font, lcdX[i], lcdY, lcdWidth[i], labels[i], values[i]);
            if (!tips[i].isEmpty()
                    && Accent.contains(lcdX[i], lcdY, lcdWidth[i], AccentReadout.height(font), mouseX, mouseY)) {
                graphics.setTooltipForNextFrame(font, Component.translatable(tips[i]), mouseX, mouseY);
            }
        }
    }

    private void drawRules(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int count = count();
        if (pendingSelect >= 0 && pendingSelect < count) {
            selected = pendingSelect;
            pendingSelect = -1;
            reveal = true;
        }
        if (count > 0) {
            selected = Math.clamp(selected, 0, count - 1);
        } else {
            selected = 0;
        }
        boolean hasRule = selected < count;
        // Labels, tooltips and whether they can act follow the selection inside the buttons; only showing is here.
        for (AccentButton button : shareButtons) {
            button.visible = hasRule;
        }
        showIf(edit, hasRule);
        showIf(toggle, hasRule);
        showIf(simulate, hasRule);
        showIf(revert, hasRule);
        showIf(copyButton, hasRule);

        // The list.
        int glassX = listX + AccentInset.BORDER;
        int glassWidth = AccentInset.innerWidth(listWidth);
        AccentInset.begin(graphics, listX, bodyTop, listWidth, bodyBottom - bodyTop);
        int boxHeight = font.lineHeight + 3;
        graphics.fill(glassX + PAD, searchY, glassX + glassWidth - PAD, searchY + boxHeight, SEARCH_EDGE);
        graphics.fill(glassX + PAD + 1, searchY + 1, glassX + glassWidth - PAD - 1, searchY + boxHeight - 1,
                SEARCH_FILL);
        List<Integer> rules = matching();
        int headerY = searchY + boxHeight + PAD;
        TextScale.text(graphics, font, I18n.get(ControllerTexts.ALL_RULES), glassX + AccentRow.TEXT_X, headerY,
                Accent.CYAN, false);
        String counted = rules.size() == count
                ? I18n.get(Plural.key(ControllerTexts.RULE_COUNT, count), count)
                : I18n.get(ControllerTexts.RULE_COUNT_FILTERED, rules.size(), count);
        TextScale.text(graphics, font, counted, glassX + glassWidth - AccentRow.MARGIN - font.width(counted), headerY,
                Accent.LABEL, false);
        int rowHeight = AccentRow.height(font);
        int visible = visibleRules();
        if (reveal) {
            reveal = false;
            listScroll = ControllerLayout.scrollTo(listScroll, rules.indexOf(selected), visible, rules.size());
        }
        listScroll = Math.clamp(listScroll, 0, Math.max(0, rules.size() - visible));
        if (count == 0) {
            TextScale.text(graphics, font,
                    TextFit.fit(I18n.get(ControllerTexts.NO_GRAPHS), glassWidth - 2 * PAD, font::width),
                    glassX + AccentRow.TEXT_X, rowsTop + 2, Accent.LABEL, false);
        }
        for (int i = 0; i < visible && listScroll + i < rules.size(); i++) {
            int g = rules.get(listScroll + i);
            LogicWorker.Status status = menu.status(g);
            AccentRow.draw(graphics, font, glassX, ControllerLayout.rowY(rowsTop, i, rowHeight), glassWidth,
                    shownName(g), g == selected,
                    status == LogicWorker.Status.DISABLED || status == LogicWorker.Status.IDLE, status(status), mouseX,
                    mouseY);
        }
        AccentInset.end(graphics);

        // The detail.
        AccentInset.begin(graphics, detailX, bodyTop, detailWidth, bodyBottom - bodyTop);
        int x = detailX + AccentInset.BORDER + PAD;
        int room = AccentInset.innerWidth(detailWidth) - 2 * PAD;
        int y = bodyTop + AccentInset.BORDER + PAD;
        if (!hasRule) {
            TextScale.text(graphics, font, TextFit.fit(I18n.get(ControllerTexts.NO_GRAPHS), room, font::width), x, y,
                    Accent.LABEL, false);
            AccentInset.end(graphics);
            return;
        }
        LogicWorker.Status status = menu.status(selected);
        String chip = I18n.get(ControllerTexts.chipKey(status));
        int chipWidth = AccentTag.width(font.width(chip));
        AccentTag.draw(graphics, font, x + room - chipWidth, y, chipWidth, chip, tagColour(status));
        if (menu.simulating(selected)) {
            // A rule that only simulates says so beside its status.
            String sim = I18n.get(ControllerTexts.CHIP_SIMULATE);
            int simWidth = AccentTag.width(font.width(sim));
            AccentTag.draw(graphics, font, x + room - chipWidth - GAP - simWidth, y, simWidth, sim, Accent.CYAN);
            chipWidth += GAP + simWidth;
        }
        if (menu.isCopy(selected)) {
            // A copy with its own values (M7L2 row 19) says so too.
            String copy = I18n.get(ControllerTexts.CHIP_COPY);
            int copyWidth = AccentTag.width(font.width(copy));
            AccentTag.draw(graphics, font, x + room - chipWidth - GAP - copyWidth, y, copyWidth, copy, Accent.CYAN);
            chipWidth += GAP + copyWidth;
        }
        String number = String.format(Locale.ROOT, "#%02d", selected + 1);
        TextScale.text(graphics, font, number, x, y + 2, Accent.LABEL, false);
        int nameX = x + font.width(number) + GAP;
        fitted(graphics, shownName(selected), nameX, y + 2, room - chipWidth - GAP - (nameX - x), Accent.TEXT, mouseX,
                mouseY);
        y += AccentTag.height(font) + GAP;
        AccentTabRow switcher = views;
        if (switcher != null) {
            switcher.draw(graphics, font, mouseX, mouseY);
            y = viewTop + switcher.height() + GAP;
        }
        if (view == VIEW_CARD) {
            drawCard(graphics, x, y, room, status == LogicWorker.Status.DISABLED, mouseX, mouseY);
            AccentInset.end(graphics);
            return;
        }
        if (view == VIEW_HISTORY) {
            drawHistory(graphics, x, y, room, mouseX, mouseY);
            AccentInset.end(graphics);
            return;
        }

        drawNodes(graphics, x, y, room, status, mouseX, mouseY);
        AccentInset.end(graphics);
    }

    private static void showIf(@Nullable AccentButton button, boolean visible) {
        if (button != null) {
            button.visible = visible;
        }
    }

    /**
     * A line under the NODES diagram: a heading (no dot) or a step, error or pinned value with its dot; a pinned value
     * with recorded history has its sparkline ({@link PinHistory}).
     */
    private record DetailRow(String text, int colour, int dot, @Nullable LongList spark) {
        DetailRow(String text, int colour, int dot) {
            this(text, colour, dot, null);
        }
    }

    /** Width of a pinned value's sparkline, one pixel per point. */
    private static final int SPARK_WIDTH = PinHistory.SPARK_POINTS;

    /**
     * Draws {@code points} as a one-pixel line {@code SPARK_WIDTH} wide and a text line high, scaled between its lowest
     * and highest point; gaps stay open. Read-only.
     */
    private void sparkline(GuiGraphicsExtractor graphics, LongList points, int x, int y) {
        long low = Long.MAX_VALUE;
        long high = Long.MIN_VALUE;
        for (int i = 0; i < points.size(); i++) {
            long value = points.getLong(i);
            if (value != Long.MIN_VALUE) {
                low = Math.min(low, value);
                high = Math.max(high, value);
            }
        }
        if (low > high) {
            return;
        }
        int height = Accent.textHeight(font) - 1;
        graphics.fill(x, y + height, x + SPARK_WIDTH, y + height + 1, Accent.DIM);
        double span = Math.max(1, (double) high - low);
        int start = x + SPARK_WIDTH - points.size();
        int lastY = Integer.MIN_VALUE;
        for (int i = 0; i < points.size(); i++) {
            long value = points.getLong(i);
            if (value == Long.MIN_VALUE) {
                lastY = Integer.MIN_VALUE;
                continue;
            }
            int py = high == low
                    ? y + height / 2
                    : y + height - 1 - (int) Math.round((value - low) / span * (height - 1));
            int from = lastY == Integer.MIN_VALUE ? py : lastY;
            graphics.fill(start + i, Math.min(from, py), start + i + 1, Math.max(from, py) + 1, Accent.CYAN);
            lastY = py;
        }
    }

    /**
     * The NODES view: the cost, last run and result on one line (whole in its tooltip), the rule's graph as a read-only
     * diagram, then the errors or the last run step by step and the pinned values, as many lines as fit in two fifths
     * of the room; the rest are counted in a last line whose tooltip lists them.
     */
    private void drawNodes(GuiGraphicsExtractor graphics, int x, int top, int room, LogicWorker.Status status,
            int mouseX, int mouseY) {
        int bottom = detailButtonsTop - GAP;
        ControllerLog.Rule rule = rule(selected);
        String lastRun;
        String result;
        int resultColour = Accent.TEXT;
        if (rule == null) {
            lastRun = I18n.get(ControllerTexts.DETAIL_LOADING);
            result = lastRun;
        } else if (rule.lastRun() < 0) {
            lastRun = I18n.get(ControllerTexts.DETAIL_NEVER);
            result = "";
        } else {
            lastRun = ago(rule.lastRun());
            result = rule.actions() > 0
                    ? I18n.get(Plural.key(ControllerTexts.DETAIL_ACTIONS, rule.actions()), rule.actions())
                    : rule.lines().isEmpty()
                            ? I18n.get(ControllerTexts.DETAIL_NOTHING)
                            : line(rule.lines().get(0)).getString();
        }
        if (status == LogicWorker.Status.WAITING || status == LogicWorker.Status.IDLE) {
            result = I18n.get(status == LogicWorker.Status.IDLE
                    ? ControllerTexts.DETAIL_IDLE
                    : menu.waitsForActions(selected)
                            ? ControllerTexts.DETAIL_WAITING_ACTIONS
                            : ControllerTexts.DETAIL_WAITING);
            resultColour = Accent.AMBER;
        }
        String costText = ControllerTexts.costText(menu.lastCost(selected), menu.lastActionPoints(selected))
                .getString();
        summaryLine(graphics, x, top, room, costText, lastRun, result, resultColour, mouseX, mouseY);
        int y = top + font.lineHeight + 2;

        List<DetailRow> rows = detailRows(rule);
        int step = font.lineHeight + 1;
        int total = Math.max(0, bottom - y);
        int shownRows = Math.min(rows.size(), total * 2 / 5 / step);
        int diagramHeight = total - (shownRows == 0 ? 0 : shownRows * step + GAP);
        LogicControllerMenu.CardView shown = menu.card().orElse(null);
        if (shown == null || shown.index() != selected) {
            fitted(graphics, I18n.get(ControllerTexts.DETAIL_LOADING), x, y + 2, room, Accent.LABEL, mouseX, mouseY);
        } else {
            diagram.show(font, shown.graph(), shown.values());
            graphics.fill(x - 1, y - 1, x + room + 1, y + diagramHeight + 1, CONNECTOR);
            long now = minecraft.level == null ? 0 : minecraft.level.getGameTime();
            diagram.draw(graphics, font, x, y, room, diagramHeight, minecraft.getWindow().getGuiScale(), now, mouseX,
                    mouseY);
            if (diagram.empty()) {
                fitted(graphics, I18n.get(ControllerTexts.NODES_EMPTY), x + 2, y + 2, room - 4, Accent.LABEL, mouseX,
                        mouseY);
            }
        }
        y += diagramHeight + GAP;
        for (int i = 0; i < shownRows; i++) {
            int rowY = y + i * step;
            if (i == shownRows - 1 && rows.size() > shownRows) {
                // The lines that do not fit: counted here, listed in the tooltip.
                int hidden = rows.size() - i;
                String more = I18n.get(ControllerTexts.NODES_MORE, hidden);
                TextScale.text(graphics, font, TextFit.fit(more, room, font::width), x, rowY, Accent.LABEL, false);
                if (Accent.contains(x, rowY - 1, room, step, mouseX, mouseY)) {
                    List<Component> tip = new ArrayList<>(hidden);
                    for (int k = i; k < rows.size(); k++) {
                        tip.add(Component.literal(rows.get(k).text()).withColor(rows.get(k).colour()));
                    }
                    graphics.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
                }
                break;
            }
            DetailRow row = rows.get(i);
            int textX = x;
            if (row.dot() != 0) {
                AccentStatus.dot(graphics, x, AccentStatus.dotY(rowY, Accent.textHeight(font)), row.dot());
                textX = x + AccentStatus.SIZE + GAP;
            }
            // The whole row is the hover area, so a cut line shows whole when the mouse is on its dot too.
            String text = row.text();
            int textRoom = room - (textX - x);
            LongList spark = row.spark();
            if (spark != null && textRoom > SPARK_WIDTH * 2) {
                sparkline(graphics, spark, x + room - SPARK_WIDTH, rowY);
                textRoom -= SPARK_WIDTH + GAP;
            }
            if (textRoom > 0) {
                TextScale.text(graphics, font, TextFit.fit(text, textRoom, font::width), textX, rowY, row.colour(),
                        false);
                if (Accent.contains(x, rowY - 1, room, step, mouseX, mouseY)
                        && TextFit.cut(text, textRoom, font::width)) {
                    graphics.setTooltipForNextFrame(font, Component.literal(text), mouseX, mouseY);
                }
            }
        }
    }

    /**
     * The cost, last run and result on one line, the result in its colour; the line is cut to fit and its tooltip names
     * each part.
     */
    private void summaryLine(GuiGraphicsExtractor graphics, int x, int y, int room, String cost, String lastRun,
            String result, int resultColour, int mouseX, int mouseY) {
        String separator = " · ";
        String head = cost + separator + lastRun + (result.isEmpty() ? "" : separator);
        int headWidth = font.width(head);
        if (headWidth < room) {
            TextScale.text(graphics, font, head, x, y, Accent.LABEL, false);
            if (!result.isEmpty()) {
                TextScale.text(graphics, font, TextFit.fit(result, room - headWidth, font::width), x + headWidth, y,
                        resultColour, false);
            }
        } else {
            TextScale.text(graphics, font, TextFit.fit(head + result, room, font::width), x, y, Accent.LABEL, false);
        }
        if (Accent.contains(x, y - 1, room, font.lineHeight + 1, mouseX, mouseY)) {
            List<Component> tip = new ArrayList<>(3);
            tip.add(Component.translatable(ControllerTexts.DETAIL_COST).withColor(Accent.LABEL)
                    .append(Component.literal(" " + cost).withColor(Accent.TEXT)));
            tip.add(Component.translatable(ControllerTexts.DETAIL_LAST_RUN).withColor(Accent.LABEL)
                    .append(Component.literal(" " + lastRun).withColor(Accent.TEXT)));
            if (!result.isEmpty()) {
                tip.add(Component.translatable(ControllerTexts.DETAIL_RESULT).withColor(Accent.LABEL)
                        .append(Component.literal(" " + result).withColor(resultColour)));
            }
            graphics.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
        }
    }

    /** The lines under the diagram: the errors or the last run's why chain, then the pinned values. */
    private List<DetailRow> detailRows(ControllerLog.@Nullable Rule rule) {
        List<DetailRow> rows = new ArrayList<>();
        if (rule == null) {
            return rows;
        }
        boolean errors = !rule.errors().isEmpty();
        List<ControllerLog.Line> lines = errors ? rule.errors() : rule.lines();
        if (!lines.isEmpty()) {
            rows.add(new DetailRow(I18n.get(errors ? ControllerTexts.DETAIL_ERRORS : ControllerTexts.DETAIL_WHY),
                    errors ? Accent.RED : Accent.CYAN, 0));
            for (ControllerLog.Line line : lines) {
                rows.add(new DetailRow(line(line).getString(), Accent.TEXT, errors ? Accent.RED : Accent.LABEL));
            }
        }
        if (!rule.pins().isEmpty()) {
            rows.add(new DetailRow(I18n.get(ControllerTexts.DETAIL_PINNED), Accent.CYAN, 0));
            for (int i = 0; i < rule.pins().size(); i++) {
                ControllerLog.Pin pin = rule.pins().get(i);
                String node = pin.node().isEmpty() ? "?" : I18n.get(pin.node());
                rows.add(new DetailRow(node + ": " + pinText(pin), Accent.TEXT, Accent.CYAN, menu.spark(selected, i)));
            }
        }
        return rows;
    }

    /**
     * A pinned value as the node's live line reads in the editor; a countdown counts on the client's clock, so it moves
     * although the server sends it only when it changes.
     */
    private String pinText(ControllerLog.Pin pin) {
        long value = pin.value();
        if (value == Long.MIN_VALUE) {
            return I18n.get(ControllerTexts.PIN_UNKNOWN);
        }
        return switch (LiveLine.Format.byId(pin.format())) {
            case CROSSING -> LiveLine.crossing(pin.below(), value, pin.limit());
            case DUE -> {
                long now = minecraft.level == null ? 0 : minecraft.level.getGameTime();
                yield value == 0
                        ? I18n.get(LogicLang.LIVE_IDLE)
                        : I18n.get(pin.key(), LiveLine.secondsLeft(LiveLine.ticksLeft(value, now)));
            }
            case NUMBER -> I18n.get(pin.key(), InfoFormat.number(value));
            case YES_NO -> I18n.get(pin.key(), I18n.get(value != 0 ? EditorTexts.VALUE_YES : EditorTexts.VALUE_NO));
        };
    }

    /**
     * The selected rule's change history, newest first: per entry a heading (day, date and time, player) and what
     * changed on one line, cut with an ellipsis and shown whole as a tooltip; scrolled with the wheel by entry.
     */
    private void drawHistory(GuiGraphicsExtractor graphics, int x, int top, int room, int mouseX, int mouseY) {
        int bottom = detailButtonsTop - GAP;
        List<HistoryEntry> history = menu.history(selected);
        if (history.isEmpty()) {
            fitted(graphics, I18n.get(EditorTexts.HISTORY_NONE), x, top, room, Accent.LABEL, mouseX, mouseY);
            return;
        }
        int step = 2 * (font.lineHeight + 1) + 2;
        int size = history.size();
        int visible = Math.max(1, (bottom - top) / step);
        cardScroll = Math.clamp(cardScroll, 0, Math.max(0, size - visible));
        for (int i = 0; i < visible && cardScroll + i < size; i++) {
            HistoryEntry entry = history.get(size - 1 - cardScroll - i);
            int rowY = top + i * step;
            fitted(graphics, HistoryText.heading(entry).getString(), x, rowY, room, Accent.CYAN, mouseX, mouseY);
            fitted(graphics, HistoryText.summary(entry).getString(), x, rowY + font.lineHeight + 1, room, Accent.TEXT,
                    mouseX, mouseY);
        }
    }

    /** A clickable part of the card drawn this frame: a word, a line's remove mark, a blank or a "+" link. */
    private record CardHit(int x, int y, int width, int height, Runnable action) {
    }

    /** {@link #cardRows} entry: the blank WHEN line of a rule without a trigger. */
    private static final int BLANK_TRIGGER = -1;
    /** {@link #cardRows} entry: the blank THEN line of a rule without an action. */
    private static final int BLANK_ACTION = -2;

    /**
     * The selected rule as a card ({@link RuleCards}): the keyword column, lines indented by their depth with a live
     * value on the right, scrolled with the wheel. Hovering a line names its nodes and shows a cut text whole. The
     * words that show a setting are gold (a click cycles a choice or opens a box) or cyan (a click picks an item or
     * label); a line that can go has an x, a line the card cannot change offers the editor. Under the lines: + TRIGGER,
     * + CONDITION and + ACTION, or for a custom graph the editor; a rule without a trigger or action shows a blank to
     * fill. While a popup is open it takes the clicks and the card shows no hover.
     */
    private void drawCard(GuiGraphicsExtractor graphics, int x, int top, int room, boolean off, int mouseX,
            int mouseY) {
        cardHits.clear();
        int bottom = detailButtonsTop - GAP;
        cardX = x;
        cardTop = top;
        cardRoom = room;
        cardBottom = bottom;
        LogicControllerMenu.CardView shown = menu.card().orElse(null);
        if (shown == null || shown.index() != selected) {
            TextScale.text(graphics, font, TextFit.fit(I18n.get(ControllerTexts.DETAIL_LOADING), room, font::width), x,
                    top, Accent.LABEL, false);
            return;
        }
        RuleCard card = card(shown);
        LogicGraph<LogicKey> graph = shown.graph();
        CardPopup open = popup;
        boolean popupOpen = open != null && open.open();
        // No hover under the popup.
        int mx = popupOpen ? Integer.MIN_VALUE / 2 : mouseX;
        int my = popupOpen ? Integer.MIN_VALUE / 2 : mouseY;
        boolean draft = !CardEdits.hasTrigger(graph) || !CardEdits.hasAction(graph);
        int y = top;
        if (card.custom() && !draft) {
            fitted(graphics, I18n.get(RuleCards.CUSTOM, card.nodes()), x, y, room, Accent.AMBER, mx, my);
            y += font.lineHeight + 2;
        }
        List<Integer> rows = cardRows(card, graph);
        int step = font.lineHeight + 2;
        int size = rows.size();
        int visible = Math.max(1, (bottom - y - step - 2) / step);
        cardScroll = Math.clamp(cardScroll, 0, Math.max(0, size - visible));
        boolean bar = size > visible;
        int width = bar ? room - 3 : room;
        String remove = "×";
        int removeWidth = font.width(remove) + GAP;
        for (int i = 0; i < visible && cardScroll + i < size; i++) {
            int row = rows.get(cardScroll + i);
            int rowY = y + i * step;
            boolean hover = Accent.contains(x, rowY - 1, width, step, mx, my);
            if (hover) {
                AccentRow.hover(graphics, x - 1, rowY - 1, width + 1, step);
            }
            if (row < 0) {
                drawBlank(graphics, row, x, rowY, width, mx, my);
                continue;
            }
            RuleCard.Line line = card.lines().get(row);
            String key = card.keywordKey(row);
            int depth = line.depth();
            if (!key.isEmpty()) {
                TextScale.text(graphics, font, TextFit.fit(I18n.get(key), keywordColumn - GAP, font::width), x, rowY,
                        colour(line.section()), false);
            } else if (depth > 0) {
                TextScale.text(graphics, font, "·", x + keywordColumn + (depth - 1) * INDENT, rowY, Accent.DIM, false);
            }
            int textX = x + keywordColumn + depth * INDENT;
            // The right end: the remove mark, or the way to the editor for a line the card cannot change.
            int end = x + width;
            String tipExtra = "";
            if (row < removable.length && removable[row]) {
                int markX = end - font.width(remove);
                boolean over = Accent.contains(markX - 1, rowY - 1, font.width(remove) + 2, step, mx, my);
                TextScale.text(graphics, font, remove, markX, rowY,
                        over ? Accent.RED : hover ? Accent.LABEL : Accent.DIM, false);
                RuleCard.Line removed = line;
                cardHits.add(new CardHit(markX - 1, rowY - 1, font.width(remove) + 2, step, () -> sendEdit(selected,
                        CardPayloads.OP_REMOVE, removed.node(), removed.section().name(), "", null, "")));
                if (over) {
                    tipExtra = I18n.get(ControllerTexts.CARD_REMOVE_TIP);
                }
                end -= removeWidth;
            } else if (line.section() == RuleCard.Section.OTHER) {
                String link = I18n.get(ControllerTexts.CARD_OPEN_EDITOR);
                int linkWidth = Math.min(font.width(link), width / 3);
                int linkX = end - linkWidth;
                boolean over = Accent.contains(linkX, rowY - 1, linkWidth, step, mx, my);
                TextScale.text(graphics, font, TextFit.fit(link, linkWidth, font::width), linkX, rowY,
                        over ? Accent.BRIGHT : Accent.CYAN, false);
                int node = line.node();
                cardHits.add(new CardHit(linkX, rowY - 1, linkWidth, step,
                        () -> minecraft.setScreen(new LogicEditorScreen(this, menu, selected, node))));
                if (over) {
                    tipExtra = I18n.get(ControllerTexts.CARD_OPEN_EDITOR_TIP);
                }
                end = linkX - GAP;
            }
            String live = liveText(line, graph);
            int liveWidth = live.isEmpty() ? 0 : Math.min(font.width(live), width / 3);
            int textRoom = end - textX - (liveWidth > 0 ? liveWidth + GAP : 0);
            String fieldTip = "";
            if (textRoom > 0) {
                fieldTip = drawLineText(graphics, line, graph, shown, textX, rowY, textRoom,
                        off ? Accent.LABEL : Accent.TEXT, mx, my);
            }
            if (liveWidth > 0) {
                TextScale.text(graphics, font, TextFit.fit(live, liveWidth, font::width), end - liveWidth, rowY,
                        Accent.CYAN, false);
            }
            if (hover) {
                List<Component> tip = new ArrayList<>();
                if (!tipExtra.isEmpty() || !fieldTip.isEmpty()) {
                    tip.add(Component.literal(tipExtra.isEmpty() ? fieldTip : tipExtra).withColor(Accent.GOLD));
                }
                if (TextFit.cut(line.text(), Math.max(0, textRoom), font::width)) {
                    tip.add(Component.literal(line.text()));
                }
                if (liveWidth > 0 && TextFit.cut(live, liveWidth, font::width)) {
                    tip.add(Component.literal(live).withColor(Accent.CYAN));
                }
                tip.add(Component.literal(nodeNames(line, graph)).withColor(Accent.LABEL));
                graphics.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
            }
        }
        if (bar) {
            // A thin scroll bar: where the shown lines sit among all of them.
            ScrollBar.draw(graphics, x + room - 1, y, 1, visible * step - 2, size, visible, cardScroll, false);
        }
        drawAddRow(graphics, x, y + Math.min(size, visible) * step + 2, room, graph, shown, card.custom() && !draft, mx,
                my);
        if (open != null && popupOpen) {
            open.draw(graphics, mouseX, mouseY);
            String tip = open.tooltip(mouseX, mouseY);
            if (!tip.isEmpty()) {
                graphics.setTooltipForNextFrame(font, Component.literal(tip), mouseX, mouseY);
            }
        }
    }

    /**
     * The card's rows: its lines, with a blank WHEN line before the first IF or THEN line of a rule without a trigger
     * and a blank THEN line last for a rule without an action.
     */
    private static List<Integer> cardRows(RuleCard card, LogicGraph<LogicKey> graph) {
        List<Integer> rows = new ArrayList<>(card.lines().size() + 2);
        boolean triggerShown = CardEdits.hasTrigger(graph);
        for (int i = 0; i < card.lines().size(); i++) {
            RuleCard.Section section = card.lines().get(i).section();
            if (!triggerShown && section != RuleCard.Section.REMEMBER && section != RuleCard.Section.LET
                    && section != RuleCard.Section.STEPS) {
                rows.add(BLANK_TRIGGER);
                triggerShown = true;
            }
            rows.add(i);
        }
        if (!triggerShown) {
            rows.add(BLANK_TRIGGER);
        }
        if (!CardEdits.hasAction(graph)) {
            rows.add(BLANK_ACTION);
        }
        return rows;
    }

    /** A blank line: WHEN "pick a trigger…" or THEN "pick an action…"; a click opens the picker. */
    private void drawBlank(GuiGraphicsExtractor graphics, int row, int x, int rowY, int width, int mx, int my) {
        boolean trigger = row == BLANK_TRIGGER;
        graphics.text(
                font, TextFit.fit(I18n.get(trigger ? RuleCards.SECTION_WHEN : RuleCards.SECTION_THEN),
                        keywordColumn - GAP, font::width),
                x, rowY, colour(trigger ? RuleCard.Section.WHEN : RuleCard.Section.THEN), false);
        String text = I18n.get(trigger ? ControllerTexts.CARD_PICK_TRIGGER : ControllerTexts.CARD_PICK_ACTION);
        int textX = x + keywordColumn;
        int textWidth = Math.min(font.width(text), x + width - textX);
        boolean over = Accent.contains(textX, rowY - 1, textWidth, font.lineHeight + 2, mx, my);
        TextScale.text(graphics, font, TextFit.fit(text, x + width - textX, font::width), textX, rowY,
                over ? Accent.BRIGHT : Accent.LABEL, false);
        graphics.fill(textX, rowY + font.lineHeight - 1, textX + textWidth, rowY + font.lineHeight,
                over ? Accent.BRIGHT : Accent.DIM);
        CardEdits.Add add = trigger ? CardEdits.Add.TRIGGER : CardEdits.Add.ACTION;
        cardHits.add(new CardHit(textX, rowY - 1, textWidth, font.lineHeight + 2, () -> openTypes(add, rowY)));
    }

    /**
     * A card line's text with its setting words coloured and clickable (gold for numbers, choices, yes/no and text,
     * cyan for items and labels), cut to {@code room} with an ellipsis. Returns the hovered word's tip, or empty.
     */
    private String drawLineText(GuiGraphicsExtractor graphics, RuleCard.Line line, LogicGraph<LogicKey> graph,
            LogicControllerMenu.CardView shown, int textX, int rowY, int room, int base, int mx, int my) {
        String text = line.text();
        String fitted = TextFit.fit(text, room, font::width);
        boolean cut = !fitted.equals(text);
        String kept = cut && fitted.endsWith(TextFit.ELLIPSIS) ? fitted.substring(0, fitted.length() - 1) : fitted;
        if (cut && !text.startsWith(kept)) {
            TextScale.text(graphics, font, fitted, textX, rowY, base, false);
            return "";
        }
        int shownChars = kept.length();
        int pen = textX;
        int at = 0;
        String tip = "";
        int step = font.lineHeight + 2;
        for (RuleCard.Field field : line.fields()) {
            if (field.start() >= shownChars || field.start() < at) {
                continue;
            }
            if (field.start() > at) {
                String plain = text.substring(at, field.start());
                TextScale.text(graphics, font, plain, pen, rowY, base, false);
                pen += font.width(plain);
            }
            int end = Math.min(field.end(), shownChars);
            String word = text.substring(field.start(), end);
            int wordWidth = font.width(word);
            LogicNode<LogicKey> node = graph.node(field.node());
            SettingSpec spec = node == null ? null : node.type().setting(field.setting()).orElse(null);
            if (node == null || spec == null) {
                TextScale.text(graphics, font, word, pen, rowY, base, false);
            } else {
                boolean picked = spec.kind().holdsKey() || spec.kind() == SettingKind.LABEL;
                int colour = picked ? Accent.CYAN : Accent.GOLD;
                boolean over = Accent.contains(pen, rowY - 1, wordWidth, step, mx, my);
                TextScale.text(graphics, font, word, pen, rowY, over ? Accent.BRIGHT : colour, false);
                graphics.fill(pen, rowY + font.lineHeight - 1, pen + wordWidth, rowY + font.lineHeight,
                        over ? colour : Accent.withAlpha(colour, 0x50));
                int wordX = pen;
                cardHits.add(
                        new CardHit(pen, rowY - 1, wordWidth, step, () -> clickField(node, spec, shown, wordX, rowY)));
                if (over) {
                    boolean cycles = spec.kind() == SettingKind.CHOICE || spec.kind() == SettingKind.FLAG;
                    tip = I18n.get(cycles ? ControllerTexts.CARD_CYCLE_TIP : ControllerTexts.CARD_CHANGE_TIP,
                            I18n.get(spec.nameKey()));
                }
            }
            pen += wordWidth;
            at = end;
        }
        String rest = text.substring(Math.min(at, shownChars), shownChars) + (cut ? TextFit.ELLIPSIS : "");
        TextScale.text(graphics, font, rest, pen, rowY, base, false);
        return tip;
    }

    /** A click on a setting word: a choice or yes/no takes its next value, the rest open their popup. */
    private void clickField(LogicNode<LogicKey> node, SettingSpec spec, LogicControllerMenu.CardView shown, int wordX,
            int rowY) {
        if (spec.kind() == SettingKind.CHOICE || spec.kind() == SettingKind.FLAG) {
            CardEdits.next(node, spec.name())
                    .ifPresent(value -> sendEdit(selected, CardPayloads.OP_SET, node.id(), "", spec.name(), value, ""));
            return;
        }
        CardPopup open = popup;
        if (open == null || awaiting || queuedEdit != null) {
            return;
        }
        int width = Math.min(cardRoom, 200);
        int left = Math.clamp(wordX, cardX, cardX + cardRoom - width);
        int below = rowY + font.lineHeight + 3;
        int roomBelow = cardBottom - below;
        int roomAbove = rowY - 2 - cardTop;
        if (roomBelow >= 80 || roomBelow >= roomAbove) {
            open.openSetting(node, spec, shown.labels(), left, below, width, roomBelow);
        } else {
            int height = Math.min(roomAbove, 140);
            open.openSetting(node, spec, shown.labels(), left, rowY - 2 - height, width, height);
        }
    }

    /** Opens the type picker of {@code add} over the card, under the row at {@code rowY} when there is room. */
    private void openTypes(CardEdits.Add add, int rowY) {
        CardPopup open = popup;
        if (open == null || awaiting || queuedEdit != null) {
            return;
        }
        int width = Math.min(cardRoom, 180);
        int below = rowY + font.lineHeight + 3;
        int roomBelow = cardBottom - below;
        if (roomBelow >= 90) {
            open.openTypes(add, cardX + keywordColumn > cardX + cardRoom - width ? cardX : cardX + keywordColumn, below,
                    width, roomBelow);
        } else {
            int top = Math.max(cardTop, rowY - 2 - 150);
            open.openTypes(add, cardX + Math.max(0, Math.min(keywordColumn, cardRoom - width)), top, width,
                    rowY - 2 - top);
        }
    }

    /**
     * The links under the card: + TRIGGER, + CONDITION and + ACTION (a condition needs an action; none past the node
     * limit), or for a custom graph the way to the editor.
     */
    private void drawAddRow(GuiGraphicsExtractor graphics, int x, int y, int room, LogicGraph<LogicKey> graph,
            LogicControllerMenu.CardView shown, boolean custom, int mx, int my) {
        int step = font.lineHeight + 2;
        if (custom) {
            String link = I18n.get(ControllerTexts.CARD_OPEN_EDITOR);
            int linkWidth = Math.min(font.width(link), room);
            boolean over = Accent.contains(x, y - 1, linkWidth, step, mx, my);
            TextScale.text(graphics, font, TextFit.fit(link, room, font::width), x, y,
                    over ? Accent.BRIGHT : Accent.CYAN, false);
            cardHits.add(new CardHit(x, y - 1, linkWidth, step, () -> edit(selected)));
            if (over) {
                graphics.setTooltipForNextFrame(font, Component.translatable(ControllerTexts.CARD_CUSTOM_TIP), mx, my);
            }
            return;
        }
        String[] keys = {ControllerTexts.CARD_ADD_TRIGGER, ControllerTexts.CARD_ADD_CONDITION,
                ControllerTexts.CARD_ADD_ACTION};
        CardEdits.Add[] adds = CardEdits.Add.values();
        int[] colours = {Accent.AMBER, Accent.GOLD, Accent.GREEN};
        boolean full = graph.nodes().size() >= shown.maxNodes();
        int pen = x;
        for (int i = 0; i < keys.length; i++) {
            String text = I18n.get(keys[i]);
            int w = font.width(text);
            if (pen + w > x + room) {
                break;
            }
            boolean usable = !full && (adds[i] != CardEdits.Add.CONDITION || CardEdits.hasAction(graph));
            boolean over = Accent.contains(pen, y - 1, w, step, mx, my);
            TextScale.text(graphics, font, text, pen, y, !usable ? Accent.DIM : over ? Accent.BRIGHT : colours[i],
                    false);
            if (usable) {
                CardEdits.Add add = adds[i];
                cardHits.add(new CardHit(pen, y - 1, w, step, () -> openTypes(add, y)));
            }
            if (over) {
                Component tip = full
                        ? Component.translatable(ControllerTexts.CARD_FULL_TIP, shown.maxNodes())
                        : Component.translatable(
                                usable ? ControllerTexts.CARD_ADD_TIP : ControllerTexts.CARD_NEED_ACTION_TIP);
                graphics.setTooltipForNextFrame(font, tip, mx, my);
            }
            pen += w + 2 * GAP;
        }
    }

    /** The card of {@code shown}, made again only when the server sent a new card or new values. */
    @SuppressWarnings("ReferenceEquality") // each card or value update is a new object
    private RuleCard card(LogicControllerMenu.CardView shown) {
        RuleCard made = ruleCard;
        if (shown != seenCard || made == null) {
            Language language = Language.getInstance();
            made = RuleCards.build(shown.graph(), language::getOrDefault, LogicEditorScreen::keyName);
            Map<String, Long> values = new HashMap<>();
            for (EditorPayloads.LiveValue value : shown.values()) {
                values.put(value.node() + "/" + value.output(), value.value());
            }
            // Which lines the card can remove; only made again with the graph.
            if (seenCard == null || seenCard.graph() != shown.graph() || removable.length != made.lines().size()) {
                boolean[] can = new boolean[made.lines().size()];
                for (int i = 0; i < can.length; i++) {
                    RuleCard.Line line = made.lines().get(i);
                    RuleCard.Section section = line.section();
                    can[i] = (section == RuleCard.Section.WHEN || section == RuleCard.Section.IF
                            || section == RuleCard.Section.THEN)
                            && CardEdits.remove(shown.graph(), section, line.node()).graph().isPresent();
                }
                removable = can;
            }
            seenCard = shown;
            ruleCard = made;
            cardValues = values;
        }
        return made;
    }

    /** A card line's live value in words; empty when it has none or none arrived. */
    private String liveText(RuleCard.Line line, LogicGraph<LogicKey> graph) {
        if (line.live() == RuleCard.Live.STEP) {
            return stepText(line, graph);
        }
        Long value = cardValues.get(line.liveNode() + "/" + line.liveEntry());
        if (value == null || line.live() == RuleCard.Live.NONE) {
            return "";
        }
        long v = value;
        return switch (line.live()) {
            case NONE -> "";
            case NUMBER -> InfoFormat.number(v);
            case YES_NO -> I18n.get(v != 0 ? LogicLang.WORD_YES : LogicLang.WORD_NO);
            case CROSSING -> {
                LogicNode<LogicKey> node = graph.node(line.liveNode());
                yield node == null
                        ? InfoFormat.number(v)
                        : LiveLine.crossing(LiveLine.below(node.type(), node.settings()), v,
                                LiveLine.threshold(node.type(), node.settings()));
            }
            case DUE -> v <= 0 || minecraft.level == null
                    ? I18n.get(LogicLang.LIVE_IDLE)
                    : I18n.get(LogicLang.LIVE_DUE,
                            LiveLine.secondsLeft(LiveLine.ticksLeft(v, minecraft.level.getGameTime())));
            // Answered by stepText above.
            case STEP -> "";
        };
    }

    /**
     * A STEPS line's live value: on the sequence's header ("step") the current step's name, on step k's line ("step:k")
     * a mark while the sequence is at it; empty otherwise.
     */
    private String stepText(RuleCard.Line line, LogicGraph<LogicKey> graph) {
        Long value = cardValues.get(line.liveNode() + "/" + Sequences.STEP);
        LogicNode<LogicKey> node = graph.node(line.liveNode());
        if (value == null || node == null || value < 1 || value > Sequences.steps(node.settings())) {
            return "";
        }
        String entry = line.liveEntry();
        if (entry.equals(Sequences.STEP)) {
            return Sequences.shownName(node.settings(), value.intValue(),
                    number -> I18n.get(LogicLang.STEP_DEFAULT_NAME, number));
        }
        return entry.equals(Sequences.STEP + ":" + value) ? I18n.get(RuleCards.STEP_NOW) : "";
    }

    /** "Node: Compare" or "Nodes: Compare, Item count": the types of the nodes a card line reads, each once. */
    private static String nodeNames(RuleCard.Line line, LogicGraph<LogicKey> graph) {
        List<String> names = new ArrayList<>();
        for (int id : line.nodes()) {
            LogicNode<LogicKey> node = graph.node(id);
            if (node != null) {
                String name = I18n.get(node.type().nameKey());
                if (!names.contains(name)) {
                    names.add(name);
                }
            }
        }
        return I18n.get(names.size() == 1 ? ControllerTexts.CARD_NODE : ControllerTexts.CARD_NODES,
                String.join(", ", names));
    }

    /** A card section's keyword colour. */
    private static int colour(RuleCard.Section section) {
        return switch (section) {
            case REMEMBER, LET, STEPS -> Accent.CYAN;
            case WHEN -> Accent.AMBER;
            case EACH, IF -> Accent.GOLD;
            case THEN -> Accent.GREEN;
            case OTHER -> Accent.LABEL;
        };
    }

    /** Draws {@code text} cut to {@code width}; a cut text shows whole as a tooltip while the mouse is on it. */
    private void fitted(GuiGraphicsExtractor graphics, String text, int x, int y, int width, int colour, int mouseX,
            int mouseY) {
        if (width <= 0) {
            return;
        }
        TextScale.text(graphics, font, TextFit.fit(text, width, font::width), x, y, colour, false);
        if (Accent.contains(x, y - 1, width, font.lineHeight, mouseX, mouseY)
                && TextFit.cut(text, width, font::width)) {
            graphics.setTooltipForNextFrame(font, Component.literal(text), mouseX, mouseY);
        }
    }

    private int entryHeight() {
        return 2 * font.lineHeight + 4;
    }

    private int visibleEntries() {
        return ControllerLayout.visibleRows(bodyBottom - logTop - 2 * AccentInset.BORDER - 2, entryHeight());
    }

    private void drawLog(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        AccentPanel f = frame;
        if (f == null) {
            return;
        }
        List<LogView.Entry> list = shownEntries();
        LogView.Entry chosen = selectedEntry();
        canShowRule = chosen != null && chosen.rule() >= 0;

        // The entries.
        int logX = f.contentLeft();
        int glassX = logX + AccentInset.BORDER;
        int glassWidth = AccentInset.innerWidth(logListWidth);
        AccentInset.begin(graphics, logX, logTop, logListWidth, bodyBottom - logTop);
        int top = logTop + AccentInset.BORDER + 1;
        int height = entryHeight();
        int visible = visibleEntries();
        logScroll = Math.clamp(logScroll, 0, Math.max(0, list.size() - visible));
        if (list.isEmpty()) {
            TextScale.text(graphics, font,
                    TextFit.fit(I18n.get(ControllerTexts.LOG_EMPTY), glassWidth - 2 * PAD, font::width), glassX + PAD,
                    top + 2, Accent.LABEL, false);
        }
        for (int i = 0; i < visible && logScroll + i < list.size(); i++) {
            int index = logScroll + i;
            LogView.Entry entry = list.get(index);
            int y = ControllerLayout.rowY(top, i, height);
            int colour = colour(entry.kind());
            if (index == logSelected) {
                AccentRow.select(graphics, glassX, y, glassWidth, height - 1);
            } else if (Accent.contains(glassX, y, glassWidth, height - 1, mouseX, mouseY)) {
                AccentRow.hover(graphics, glassX, y, glassWidth, height - 1);
            }
            graphics.fill(glassX + 1, y, glassX + 3, y + height - 1, colour);
            int textX = glassX + 6;
            int room = glassWidth - 6 - PAD;
            String source = entry.rule() >= 0 ? shownName(entry.rule()) : I18n.get(ControllerTexts.LOG_NETWORK);
            fitted(graphics, ago(entry.time()) + " · " + source, textX, y + 1, room, Accent.LABEL, mouseX, mouseY);
            fitted(graphics, entryText(entry).getString(), textX, y + 1 + font.lineHeight, room,
                    entry.kind() == LogView.Kind.SKIPPED ? Accent.LABEL : Accent.TEXT, mouseX, mouseY);
        }
        AccentInset.end(graphics);

        // The why chain.
        AccentInset.begin(graphics, whyX, logTop, whyWidth, bodyBottom - logTop);
        int x = whyX + AccentInset.BORDER + PAD;
        int room = AccentInset.innerWidth(whyWidth) - 2 * PAD;
        int y = logTop + AccentInset.BORDER + PAD;
        if (chosen != null) {
            int colour = colour(chosen.kind());
            TextScale.text(graphics, font,
                    TextFit.fit(I18n.get(ControllerTexts.LOG_WHY, ago(chosen.time())), room, font::width), x, y,
                    Accent.LABEL, false);
            y += font.lineHeight + 1;
            fitted(graphics, entryText(chosen).getString(), x, y, room, colour, mouseX, mouseY);
            y += font.lineHeight + GAP;
            int bottom = showRuleTop - GAP;
            List<ControllerLog.Line> chain = chosen.chain();
            if (chain.isEmpty()) {
                fitted(graphics, I18n.get(ControllerTexts.LOG_NO_CHAIN), x, y, room, Accent.LABEL, mouseX, mouseY);
            }
            int step = font.lineHeight + 4;
            int shownSteps = Math.min(chain.size(), ControllerLayout.visibleRows(bottom - y, step));
            for (int k = 0; k < shownSteps; k++) {
                boolean last = k == shownSteps - 1;
                int dotY = AccentStatus.dotY(y, Accent.textHeight(font));
                if (!last) {
                    graphics.fill(x + 2, dotY + AccentStatus.SIZE, x + 3, dotY + step, CONNECTOR);
                }
                AccentStatus.dot(graphics, x, dotY, last ? colour : Accent.LABEL);
                int textX = x + AccentStatus.SIZE + GAP;
                fitted(graphics, line(chain.get(k)).getString(), textX, y, room - (textX - x), Accent.TEXT, mouseX,
                        mouseY);
                y += step;
            }
        }
        AccentInset.end(graphics);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        AccentPanel f = frame;
        if (f != null) {
            toast.draw(graphics, font, f.contentRight() - 2, f.contentBottom() - 2, Util.getMillis());
        }
    }

    /** Everything is drawn in {@link #extractBackground}; the vanilla title and inventory labels are not used. */
    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (LabelPayloads.shownFor(menu.containerId)) {
            labelSeen = true;
        }
        if (!subtitle.equals(subtitle())) {
            rebuild = true;
        }
        flushEdit();
        if (awaiting && minecraft.level != null) {
            // Done when the server answered and sent the card again, or after two seconds whatever happened.
            if ((answered && cardChanged()) || minecraft.level.getGameTime() - awaitingSince > 40) {
                awaiting = false;
            }
        }
        // The card and nodes views ask for the selected rule's graph once per selection; the server sends it again
        // after a save.
        if (tab == TAB_RULES && view != VIEW_HISTORY && selected < count() && selected != requested) {
            Platform.get().network().sendToServer(new CardPayloads.Select(menu.containerId, selected));
            requested = selected;
        }
    }

    // ---- input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // An open card popup takes every click but those on its text box.
        CardPopup open = popup;
        if (open != null && open.open()) {
            EditBox box = open.box();
            if (box != null && box.isMouseOver(event.x(), event.y())) {
                return super.mouseClicked(event, doubleClick);
            }
            return open.click(event.x(), event.y());
        }
        // AbstractContainerScreen.mouseClicked returns true for every click it gets (it treats each as a slot click),
        // so the screen's own tabs and lists are tested first; only a click on a widget, the side tabs or their open
        // flyout (which may lie over the panel on a small window) goes to super before them.
        if (getChildAt(event.x(), event.y()).isEmpty() && !tabs.contains(event.x(), event.y()) && clickScreen(event)) {
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** Handles a click on the tabs, the view switch or a list row; false when it hit none of them. */
    private boolean clickScreen(MouseButtonEvent event) {
        AccentTabRow t = pages;
        int clicked = t == null ? -1 : t.tabAt(event.x(), event.y());
        if (clicked >= 0) {
            if (clicked != tab) {
                tab = clicked;
                rebuild = true;
            }
            return true;
        }
        if (tab == TAB_RULES) {
            AccentTabRow switcher = views;
            int picked = switcher == null || selected >= count() ? -1 : switcher.tabAt(event.x(), event.y());
            if (picked >= 0 && switcher != null) {
                view = picked;
                switcher.setActive(picked);
                cardScroll = 0;
                return true;
            }
            if (view == VIEW_CARD && event.button() == 0) {
                for (CardHit hit : List.copyOf(cardHits)) {
                    if (Accent.contains(hit.x(), hit.y(), hit.width(), hit.height(), event.x(), event.y())) {
                        hit.action().run();
                        return true;
                    }
                }
            }
            if (view == VIEW_NODES && event.button() == 0 && selected < count()
                    && diagram.contains(event.x(), event.y())) {
                diagramPress = true;
                diagramMoved = 0;
                return true;
            }
            List<Integer> rules = matching();
            int row = ControllerLayout.rowAt(event.x(), event.y(), listX + AccentInset.BORDER, rowsTop,
                    AccentInset.innerWidth(listWidth), AccentRow.height(font), visibleRules(), listScroll,
                    rules.size());
            if (row >= 0) {
                if (selected != rules.get(row)) {
                    cardScroll = 0;
                }
                // containerTick asks the server for the card view's graph once it sees the new selection.
                selected = rules.get(row);
                return true;
            }
        } else {
            AccentPanel f = frame;
            if (f != null) {
                int index = ControllerLayout.rowAt(event.x(), event.y(), f.contentLeft() + AccentInset.BORDER,
                        logTop + AccentInset.BORDER + 1, AccentInset.innerWidth(logListWidth), entryHeight(),
                        visibleEntries(), logScroll, shownEntries().size());
                if (index >= 0) {
                    logSelected = index;
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (diagramPress) {
            diagramMoved += Math.abs(dx) + Math.abs(dy);
            diagram.drag(dx, dy);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    /** A press and release on the diagram without dragging is a click: on a node it opens the editor there. */
    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (diagramPress) {
            diagramPress = false;
            int node = diagramMoved < 3 ? diagram.nodeAt(event.x(), event.y()) : -1;
            // Only while the diagram shows the selected rule's graph (not one left from the last selection).
            boolean current = menu.card().map(card -> card.index() == selected).orElse(false);
            if (node >= 0 && current && selected < count()) {
                minecraft.setScreen(new LogicEditorScreen(this, menu, selected, node));
            }
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        int step = scrollY > 0 ? -1 : scrollY < 0 ? 1 : 0;
        CardPopup open = popup;
        if (open != null && open.open()) {
            return open.scroll(x, y, step);
        }
        AccentPanel f = frame;
        if (step != 0 && tab == TAB_RULES && Accent.contains(listX, bodyTop, listWidth, bodyBottom - bodyTop, x, y)) {
            listScroll += step;
            return true;
        }
        if (step != 0 && tab == TAB_RULES && (view == VIEW_CARD || view == VIEW_HISTORY)
                && Accent.contains(detailX, bodyTop, detailWidth, detailButtonsTop - bodyTop, x, y)) {
            cardScroll = Math.max(0, cardScroll + step);
            return true;
        }
        if (step != 0 && tab == TAB_LOG && f != null
                && Accent.contains(f.contentLeft(), logTop, logListWidth, bodyBottom - logTop, x, y)) {
            logScroll += step;
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    /**
     * While the search box has focus it gets every key but Escape, so typing "e" or a number does not close the screen
     * or swap hotbar items.
     */
    @Override
    public boolean keyPressed(KeyEvent event) {
        CardPopup open = popup;
        if (open != null && open.open()) {
            return open.key(event);
        }
        EditBox box = searchBox;
        if (box != null && box.isFocused() && !event.isEscape()) {
            box.keyPressed(event);
            return true;
        }
        return super.keyPressed(event);
    }
}
