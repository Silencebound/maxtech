package dev.silencebound.maxtech.storage.logic.remote.client;

import dev.silencebound.maxtech.lib.client.gui.TextScale;
import com.mojang.blaze3d.platform.Window;
import dev.silencebound.maxtech.lib.client.gui.accent.Accent;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentButton;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentInset;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentPanel;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentRow;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentStatus;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentTabRow;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentTag;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentToast;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.text.TextFit;
import dev.silencebound.maxtech.storage.logic.controller.ControllerTexts;
import dev.silencebound.maxtech.storage.logic.controller.LogicWorker;
import dev.silencebound.maxtech.storage.logic.controller.client.ControllerLayout;
import dev.silencebound.maxtech.storage.logic.remote.LogicRemoteMenu;
import dev.silencebound.maxtech.storage.logic.remote.RemoteContent;
import dev.silencebound.maxtech.storage.logic.remote.RemotePayloads;
import dev.silencebound.maxtech.storage.logic.remote.RemoteResult;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem.Reach;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Inventory;
import org.jspecify.annotations.Nullable;

/**
 * The logic remote's screen (M7L2 chunk 12) in the Accent Frame look (B09 item 21e), built from Lib's accent pieces: a
 * title tab with the remote's name and how it reaches its network (in range with its charge, or why not), and two tabs.
 * NOTICES lists the network's notices newest first, with filter toggles for the player's own notices and the warnings
 * (refused or failed actions); out of reach it lists the notices the remote last saw, read only. RULES lists the rules
 * of the network's logic controllers the player has logic access to, a status dot each, and the selected rule's status
 * tag with Pause or Resume. Everything comes from the server ({@link RemotePayloads.View}); a pause or resume is asked
 * for and answered with a toast. Client only.
 */
public final class LogicRemoteScreen extends AbstractContainerScreen<LogicRemoteMenu> {
    private static final int PREFERRED_WIDTH = 280;
    private static final int PREFERRED_HEIGHT = 210;
    private static final int MIN_WIDTH = 220;
    private static final int MIN_HEIGHT = 170;
    private static final int GAP = 4;
    private static final int PAD = 3;
    private static final int TAB_NOTICES = 0;
    private static final int TAB_RULES = 1;
    private static final int MESSAGE_MILLIS = 4000;

    private final AccentToast toast = new AccentToast();
    private int tab = TAB_NOTICES;
    private boolean showNotices = true;
    private boolean showWarnings = true;
    private int noticeScroll;
    private int ruleScroll;
    private int selected;
    private long answersSeen;
    private boolean rebuild;
    private String subtitle = "";

    // Laid out in init.
    private @Nullable AccentPanel frame;
    private @Nullable AccentTabRow tabs;
    private int bodyTop;
    private int bodyBottom;
    private int listTop;
    private @Nullable AccentButton toggle;

    public LogicRemoteScreen(LogicRemoteMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, preferred(true), preferred(false));
        this.titleLabelY = -100;
        this.inventoryLabelY = -100;
    }

    private static int preferred(boolean width) {
        Window window = Minecraft.getInstance().getWindow();
        return width
                ? ControllerLayout.fit(window.getGuiScaledWidth(), PREFERRED_WIDTH, MIN_WIDTH, PREFERRED_WIDTH)
                : ControllerLayout.fit(window.getGuiScaledHeight(), PREFERRED_HEIGHT, MIN_HEIGHT, PREFERRED_HEIGHT);
    }

    // ---- layout

    @Override
    protected void init() {
        super.init();
        int frameWidth = ControllerLayout.fit(width, PREFERRED_WIDTH, MIN_WIDTH, imageWidth);
        int frameHeight = ControllerLayout.fit(height, PREFERRED_HEIGHT, MIN_HEIGHT, imageHeight);
        leftPos = (width - frameWidth) / 2;
        topPos = Accent.top(height, frameHeight);
        subtitle = subtitle();
        AccentPanel f = AccentPanel.of(leftPos, topPos, frameWidth, frameHeight, title, subtitle);
        frame = f;
        AccentTabRow row = tabRow(f.contentLeft(), f.contentTop());
        bodyTop = f.contentTop() + row.height() + GAP;
        tabs = row;
        bodyBottom = f.contentBottom();
        toggle = null;
        if (tab == TAB_NOTICES) {
            initNotices(f);
        } else {
            initRules(f);
        }
    }

    private AccentTabRow tabRow(int x, int y) {
        AccentTabRow row = new AccentTabRow(font, x, y,
                List.of(AccentTabRow.Tab.text(I18n.get(RemoteContent.TAB_NOTICES)),
                        AccentTabRow.Tab.text(I18n.get(RemoteContent.TAB_RULES))));
        row.setActive(tab);
        return row;
    }

    /** The filters: toggles lit while their kind shows. */
    private void initNotices(AccentPanel f) {
        Component notices = Component.translatable(RemoteContent.KIND_NOTICES);
        Component warnings = Component.translatable(RemoteContent.KIND_WARNINGS);
        int x = f.contentLeft();
        AccentButton noticesButton = AccentButton.compact(font, x, bodyTop, notices, () -> {
            showNotices = !showNotices;
            noticeScroll = 0;
        });
        addRenderableWidget(noticesButton.lit(() -> showNotices));
        x += noticesButton.getWidth() + GAP;
        addRenderableWidget(AccentButton.compact(font, x, bodyTop, warnings, () -> {
            showWarnings = !showWarnings;
            noticeScroll = 0;
        }).lit(() -> showWarnings));
        listTop = bodyTop + AccentButton.COMPACT_HEIGHT + GAP;
    }

    private void initRules(AccentPanel f) {
        int buttonHeight = AccentButton.HEIGHT;
        Component pause = Component.translatable(RemoteContent.PAUSE);
        Component resume = Component.translatable(RemoteContent.RESUME);
        int buttonWidth = AccentButton.width(font, pause, resume);
        AccentButton button = new AccentButton(f.contentRight() - buttonWidth, bodyBottom - buttonHeight, buttonWidth,
                buttonHeight, pause, this::toggleSelected);
        button.enabled(() -> reach(menu.view()) == Reach.OK).label(() -> {
            RemotePayloads.Rule rule = selectedRule();
            return rule != null && !rule.enabled() ? resume : pause;
        }).tooltip(Component.translatable(RemoteContent.PAUSE_TIP));
        toggle = addRenderableWidget(button);
        listTop = bodyTop;
    }

    /** The title's second part: in range with the charge, or why the remote does not reach its network. */
    private String subtitle() {
        RemotePayloads.View view = menu.view();
        Reach reach = reach(view);
        return reach == Reach.OK
                ? I18n.get(RemoteContent.SUBTITLE_ONLINE, InfoFormat.number(view.energy()))
                : I18n.get(RemoteContent.subtitleKey(reach));
    }

    private static Reach reach(RemotePayloads.View view) {
        return view.reach();
    }

    // ---- data

    /** The notices the filter shows, newest first. */
    private List<NetworkNotices.Notice> shownNotices() {
        List<NetworkNotices.Notice> all = menu.view().notices();
        List<NetworkNotices.Notice> out = new ArrayList<>(all.size());
        for (int i = all.size() - 1; i >= 0; i--) {
            NetworkNotices.Notice notice = all.get(i);
            if (notice.key().isEmpty() ? showNotices : showWarnings) {
                out.add(notice);
            }
        }
        return out;
    }

    private List<RemotePayloads.Rule> rules() {
        return menu.view().rules();
    }

    private RemotePayloads.@Nullable Rule selectedRule() {
        List<RemotePayloads.Rule> rules = rules();
        if (rules.isEmpty()) {
            return null;
        }
        selected = Math.clamp(selected, 0, rules.size() - 1);
        return rules.get(selected);
    }

    private static String ruleText(RemotePayloads.Rule rule) {
        String name = rule.name().isEmpty() ? "#" + (rule.graph() + 1) : rule.name();
        return rule.label().isEmpty() ? name : rule.label().toUpperCase(Locale.ROOT) + " · " + name;
    }

    private String ago(long time) {
        if (minecraft.level == null) {
            return "";
        }
        long ticks = Math.max(0, minecraft.level.getGameTime() - time);
        return I18n.get(RemoteContent.AGO, InfoFormat.number(ticks / 20));
    }

    private void toggleSelected() {
        RemotePayloads.Rule rule = selectedRule();
        if (rule != null && reach(menu.view()) == Reach.OK) {
            Platform.get().network().sendToServer(
                    new RemotePayloads.SetRule(menu.containerId, rule.controller(), rule.graph(), !rule.enabled()));
        }
    }

    private static AccentStatus status(LogicWorker.Status status) {
        return switch (status) {
            case RUNNING -> AccentStatus.OK;
            case WAITING -> AccentStatus.WARN;
            case PAUSED -> AccentStatus.ERROR;
            case DISABLED, IDLE -> AccentStatus.OFF;
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

    // ---- drawing

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        if (rebuild || !subtitle.equals(subtitle())) {
            rebuild = false;
            rebuildWidgets();
        }
        super.extractBackground(graphics, mouseX, mouseY, a);
        AccentPanel f = frame;
        AccentTabRow t = tabs;
        if (f == null || t == null) {
            return;
        }
        f.draw(graphics, font, mouseX, mouseY);
        t.draw(graphics, font, mouseX, mouseY);
        if (tab == TAB_NOTICES) {
            drawNotices(graphics, f, mouseX, mouseY);
        } else {
            drawRules(graphics, f, mouseX, mouseY);
        }
    }

    private int entryHeight() {
        return 2 * font.lineHeight + 4;
    }

    private void drawNotices(GuiGraphicsExtractor graphics, AccentPanel f, int mouseX, int mouseY) {
        boolean live = reach(menu.view()) == Reach.OK;
        int x = f.contentLeft();
        int width = f.contentWidth();
        AccentInset.begin(graphics, x, listTop, width, bodyBottom - listTop);
        int glassX = x + AccentInset.BORDER;
        int glassWidth = AccentInset.innerWidth(width);
        int top = listTop + AccentInset.BORDER + 1;
        int room = glassWidth - 6 - PAD;
        if (!live) {
            TextScale.text(graphics, font,
                    TextFit.fit(I18n.get(RemoteContent.READ_ONLY), glassWidth - 2 * PAD, font::width), glassX + PAD,
                    top + 1, Accent.AMBER, false);
            top += font.lineHeight + 2;
        }
        List<NetworkNotices.Notice> list = shownNotices();
        int height = entryHeight();
        int visible = ControllerLayout.visibleRows(bodyBottom - top - AccentInset.BORDER, height);
        noticeScroll = Math.clamp(noticeScroll, 0, Math.max(0, list.size() - visible));
        if (list.isEmpty()) {
            TextScale.text(graphics, font,
                    TextFit.fit(I18n.get(RemoteContent.NO_NOTICES), glassWidth - 2 * PAD, font::width), glassX + PAD,
                    top + 2, Accent.LABEL, false);
        }
        for (int i = 0; i < visible && noticeScroll + i < list.size(); i++) {
            NetworkNotices.Notice notice = list.get(noticeScroll + i);
            int y = top + i * height;
            if (Accent.contains(glassX, y, glassWidth, height - 1, mouseX, mouseY)) {
                AccentRow.hover(graphics, glassX, y, glassWidth, height - 1);
            }
            graphics.fill(glassX + 1, y, glassX + 3, y + height - 1,
                    notice.key().isEmpty() ? Accent.GREEN : Accent.AMBER);
            int textX = glassX + 6;
            TextScale.text(graphics, font, TextFit.fit(ago(notice.gameTime()), room, font::width), textX, y + 1,
                    Accent.LABEL, false);
            fitted(graphics, notice.line().getString(), textX, y + 1 + font.lineHeight, room,
                    live ? Accent.TEXT : Accent.LABEL, mouseX, mouseY);
        }
        AccentInset.end(graphics);
    }

    private void drawRules(GuiGraphicsExtractor graphics, AccentPanel f, int mouseX, int mouseY) {
        RemotePayloads.View view = menu.view();
        RemotePayloads.Rule rule = selectedRule();
        AccentButton button = toggle;
        boolean live = reach(view) == Reach.OK;
        if (button != null) {
            button.visible = rule != null;
        }
        int x = f.contentLeft();
        int width = f.contentWidth();
        int buttonTop = bodyBottom - AccentButton.HEIGHT;
        int listBottom = buttonTop - GAP;
        AccentInset.begin(graphics, x, listTop, width, listBottom - listTop);
        int glassX = x + AccentInset.BORDER;
        int glassWidth = AccentInset.innerWidth(width);
        int top = listTop + AccentInset.BORDER + 1;
        String empty = !live
                ? I18n.get(RemoteContent.subtitleKey(reach(view)))
                : !view.logic() ? I18n.get(RemoteContent.NEEDS_LOGIC) : I18n.get(RemoteContent.NO_RULES);
        List<RemotePayloads.Rule> rules = rules();
        if (rules.isEmpty()) {
            fitted(graphics, empty, glassX + PAD, top + 2, glassWidth - 2 * PAD, Accent.LABEL, mouseX, mouseY);
        }
        int rowHeight = AccentRow.height(font);
        int visible = ControllerLayout.visibleRows(listBottom - top - AccentInset.BORDER, rowHeight);
        ruleScroll = Math.clamp(ruleScroll, 0, Math.max(0, rules.size() - visible));
        for (int i = 0; i < visible && ruleScroll + i < rules.size(); i++) {
            int index = ruleScroll + i;
            RemotePayloads.Rule shown = rules.get(index);
            LogicWorker.Status status = LogicWorker.Status.byId(shown.status());
            boolean hovered = AccentRow.draw(graphics, font, glassX, top + i * rowHeight, glassWidth, ruleText(shown),
                    index == selected, status == LogicWorker.Status.DISABLED || status == LogicWorker.Status.IDLE,
                    status(status), mouseX, mouseY);
            if (hovered) {
                graphics.setTooltipForNextFrame(font, Component.translatable(RemoteContent.RULE_AT,
                        shown.controller().getX(), shown.controller().getY(), shown.controller().getZ()), mouseX,
                        mouseY);
            }
        }
        AccentInset.end(graphics);
        if (rule != null) {
            LogicWorker.Status status = LogicWorker.Status.byId(rule.status());
            String chip = I18n.get(ControllerTexts.chipKey(status));
            int tagTop = buttonTop + (AccentButton.HEIGHT - AccentTag.height(font)) / 2;
            int chipX = x + AccentTag.draw(graphics, font, x, tagTop, chip, tagColour(status)) + GAP;
            int room = (button == null ? f.contentRight() : button.getX()) - GAP - chipX;
            fitted(graphics, ruleText(rule), chipX, tagTop + 2, room, Accent.TEXT, mouseX, mouseY);
        }
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
        if (menu.answers() != answersSeen) {
            answersSeen = menu.answers();
            RemoteResult result = menu.answer().orElse(RemoteResult.DONE);
            AccentPanel f = frame;
            int room = f == null ? 200 : f.contentWidth() / 2;
            toast.show(Component.literal(TextFit.fit(I18n.get(result.key()), room, font::width)), Component.empty(),
                    result == RemoteResult.DONE ? AccentToast.Kind.INFO : AccentToast.Kind.ERROR, Util.getMillis(),
                    MESSAGE_MILLIS);
        }
    }

    // ---- input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // AbstractContainerScreen.mouseClicked returns true for every click it gets, so the tabs and the rule list are
        // tested first; only a click on a widget goes to super before them.
        if (getChildAt(event.x(), event.y()).isEmpty() && clickScreen(event)) {
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** Handles a click on the tabs or a rule row; false when it hit neither. */
    private boolean clickScreen(MouseButtonEvent event) {
        AccentTabRow t = tabs;
        int clicked = t == null ? -1 : t.tabAt(event.x(), event.y());
        if (clicked >= 0) {
            if (clicked != tab) {
                tab = clicked;
                rebuild = true;
            }
            return true;
        }
        AccentPanel f = frame;
        if (tab == TAB_RULES && f != null) {
            int top = listTop + AccentInset.BORDER + 1;
            int rowHeight = AccentRow.height(font);
            int bottom = bodyBottom - AccentButton.HEIGHT - GAP;
            if (Accent.contains(f.contentLeft(), top, f.contentWidth(), bottom - top, event.x(), event.y())) {
                int index = ruleScroll + (int) ((event.y() - top) / rowHeight);
                if (index >= 0 && index < rules().size()) {
                    selected = index;
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        int step = scrollY > 0 ? -1 : scrollY < 0 ? 1 : 0;
        AccentPanel f = frame;
        if (step != 0 && f != null
                && Accent.contains(f.contentLeft(), listTop, f.contentWidth(), bodyBottom - listTop, x, y)) {
            if (tab == TAB_NOTICES) {
                noticeScroll = Math.max(0, noticeScroll + step);
            } else {
                ruleScroll = Math.max(0, ruleScroll + step);
            }
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }
}
