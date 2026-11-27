package dev.silencebound.maxtech.storage.controller.recorder.client;

import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.resource.StackKeys;
import dev.silencebound.maxtech.storage.analyzer.AnalyzerTexts;
import dev.silencebound.maxtech.storage.controller.module.AlarmSettings;
import dev.silencebound.maxtech.storage.controller.module.LedgerBook;
import dev.silencebound.maxtech.storage.controller.recorder.ControllerRecorders;
import dev.silencebound.maxtech.storage.controller.recorder.RecordTexts;
import dev.silencebound.maxtech.storage.controller.recorder.RecordsPayloads;
import dev.silencebound.maxtech.storage.controller.recorder.RecordsView;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.palette.StatusPalette;
import dev.silencebound.maxtech.storage.terminal.AmountFormat;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The controller's recorder views (M7R item 3) as one panel with three pages, Graphs (telemetry), Ledger and Redstone
 * (the alarm rules), drawn by the controller's History tab and by the terminal's history tab alike. It asks the server
 * for the shown page when it opens, when the page changes and every second while it shows ({@link RecordsPayloads}),
 * and draws the last answer: a locked page names its research, a page the config turns off says so. The ledger's filter
 * button cycles everyone, then each player and automation, as the ledger module's screen did; the alarm rules carry the
 * alarm module's condition button and threshold steps, one row per rule, with a remove and an add button. Client only.
 */
public final class RecordsPanel {
    private static final int PAGE_HEIGHT = 14;
    private static final int GRAPH_HEIGHT = 48;
    private static final int LEDGER_ROW = 18;
    private static final int LEDGER_ROWS = 6;
    private static final int RULE_ROW = 26;
    private static final int RULE_ROWS = 4;
    private static final int BUTTON_HEIGHT = 12;
    private static final int TYPE_WIDTH = 100;
    private static final int STEP_WIDTH = 22;
    private static final int REMOVE_WIDTH = 14;
    private static final int ADD_WIDTH = 70;
    private static final int REFRESH_TICKS = 20;
    private static final int HEADER_COLOUR = AccentFrame.ACCENT | 0xFF000000;

    private final int containerId;
    private int page = RecordsView.TELEMETRY;
    private int ledgerView;
    private int ledgerScroll;
    private int ruleScroll;
    private int ticks;
    private int x;
    private int y;
    private int width;

    public RecordsPanel(int containerId) {
        this.containerId = containerId;
    }

    /** Places the panel; call before drawing and on every resize. */
    public void layout(int left, int top, int panelWidth) {
        this.x = left;
        this.y = top;
        this.width = panelWidth;
    }

    /** Asks for the shown page now. */
    public void ask() {
        Platform.get().network().sendToServer(new RecordsPayloads.Ask(containerId, page));
    }

    /** Asks again every second while the panel shows; call from the screen's tick. */
    public void tick() {
        if (++ticks % REFRESH_TICKS == 0) {
            ask();
        }
    }

    private @Nullable RecordsView view() {
        RecordsView view = RecordsPayloads.received(containerId);
        return view != null && view.page() == page ? view : null;
    }

    /** Draws the page buttons and the shown page. */
    public void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        String[] names = {RecordTexts.PAGE_TELEMETRY, RecordTexts.PAGE_LEDGER, RecordTexts.PAGE_ALARMS};
        int pageWidth = (width - 2 * (RecordsView.PAGES - 1)) / RecordsView.PAGES;
        for (int i = 0; i < RecordsView.PAGES; i++) {
            int bx = x + i * (pageWidth + 2);
            AccentFrame.button(graphics, font, bx, y, pageWidth, PAGE_HEIGHT, Component.translatable(names[i]),
                    i == page || over(mouseX, mouseY, bx, y, pageWidth, PAGE_HEIGHT), mouseX, mouseY);
        }
        int top = y + PAGE_HEIGHT + 4;
        RecordsView view = view();
        if (view == null) {
            AccentFrame.text(graphics, font, Component.translatable(RecordTexts.WAITING), x, top, width,
                    AccentFrame.LABEL, mouseX, mouseY);
            return;
        }
        Component blocked = blocked(view);
        if (blocked != null) {
            AccentFrame.text(graphics, font, blocked, x, top, width, AccentFrame.LABEL, mouseX, mouseY);
            return;
        }
        switch (page) {
            case RecordsView.LEDGER -> ledger(graphics, font, view.ledger(), top, mouseX, mouseY);
            case RecordsView.ALARMS -> alarms(graphics, font, view, top, mouseX, mouseY);
            default -> telemetry(graphics, font, view.telemetry(), top, mouseX, mouseY);
        }
    }

    /** Why the shown page has no content, or null when it has. */
    private @Nullable Component blocked(RecordsView view) {
        if (view.has(RecordsView.NO_CONTROLLER)) {
            return Component.translatable(RecordTexts.NO_CONTROLLER);
        }
        if (view.locked(page)) {
            return Component.translatable(switch (page) {
                case RecordsView.LEDGER -> RecordTexts.LOCKED_LEDGER;
                case RecordsView.ALARMS -> RecordTexts.LOCKED_ALARMS;
                default -> RecordTexts.LOCKED_TELEMETRY;
            });
        }
        if (view.off(page)) {
            return Component.translatable(switch (page) {
                case RecordsView.LEDGER -> RecordTexts.OFF_LEDGER;
                case RecordsView.ALARMS -> RecordTexts.OFF_ALARMS;
                default -> RecordTexts.OFF_TELEMETRY;
            });
        }
        return null;
    }

    private void telemetry(GuiGraphicsExtractor graphics, Font font, RecordsView.Telemetry telemetry, int top,
            int mouseX, int mouseY) {
        AccentFrame.text(graphics, font,
                Component.translatable(AnalyzerTexts.HISTORY_SPAN, InfoFormat.number(telemetry.operations().size()),
                        InfoFormat.number(telemetry.sampleTicks() / 20)),
                x, top, width, AccentFrame.LABEL, mouseX, mouseY);
        graph(graphics, font, top + 12, AnalyzerTexts.HISTORY_OPERATIONS, telemetry.operations());
        graph(graphics, font, top + 12 + GRAPH_HEIGHT + 14, AnalyzerTexts.HISTORY_ENERGY, telemetry.energyIn());
    }

    /** A bar per sample, scaled to the peak; the newest on the right. */
    private void graph(GuiGraphicsExtractor graphics, Font font, int top, String key, LongList values) {
        long peak = 1;
        for (int i = 0; i < values.size(); i++) {
            peak = Math.max(peak, values.getLong(i));
        }
        TextScale.text(graphics, font, Component.translatable(key, InfoFormat.number(peak)), x, top, AccentFrame.TEXT,
                false);
        int boxTop = top + 10;
        AccentFrame.box(graphics, x, boxTop, width, GRAPH_HEIGHT);
        int inner = width - 4;
        int count = values.size();
        if (count == 0) {
            return;
        }
        int bar = Math.max(1, inner / count);
        int start = x + 2 + inner - bar * Math.min(count, inner / bar);
        int first = Math.max(0, count - inner / bar);
        for (int i = first; i < count; i++) {
            int height = (int) ((GRAPH_HEIGHT - 4) * values.getLong(i) / peak);
            int bx = start + (i - first) * bar;
            if (height > 0) {
                graphics.fill(bx, boxTop + GRAPH_HEIGHT - 2 - height, bx + Math.max(1, bar - 1),
                        boxTop + GRAPH_HEIGHT - 2, AccentFrame.BAR_TOP);
            }
        }
    }

    private void ledger(GuiGraphicsExtractor graphics, Font font, RecordsView.Ledger ledger, int top, int mouseX,
            int mouseY) {
        List<RecordsView.LedgerView> views = ledger.views();
        if (views.isEmpty()) {
            return;
        }
        ledgerView = Math.clamp(ledgerView, 0, views.size() - 1);
        RecordsView.LedgerView shown = views.get(ledgerView);
        AccentFrame.button(graphics, font, x, top, width, PAGE_HEIGHT, viewName(shown),
                over(mouseX, mouseY, x, top, width, PAGE_HEIGHT), mouseX, mouseY);
        List<LedgerBook.Row<StackKey>> rows = shown.rows();
        AccentFrame.text(graphics, font,
                Component.translatable(RecordTexts.LEDGER_HEADER, Integer.toString(ledger.days()),
                        Integer.toString(rows.size())),
                x, top + PAGE_HEIGHT + 3, width, AccentFrame.LABEL, mouseX, mouseY);
        int listTop = top + PAGE_HEIGHT + 15;
        AccentFrame.box(graphics, x - 2, listTop - 2, width + 4, LEDGER_ROWS * LEDGER_ROW + 4);
        if (rows.isEmpty()) {
            TextScale.text(graphics, font, Component.translatable(RecordTexts.LEDGER_EMPTY), x, listTop + 4,
                    AccentFrame.LABEL, false);
        }
        ledgerScroll = Math.clamp(ledgerScroll, 0, Math.max(0, rows.size() - LEDGER_ROWS));
        int right = x + width;
        for (int i = 0; i < LEDGER_ROWS && ledgerScroll + i < rows.size(); i++) {
            LedgerBook.Row<StackKey> row = rows.get(ledgerScroll + i);
            int rowTop = listTop + i * LEDGER_ROW;
            if (row.key() instanceof ItemKey item) {
                graphics.item(item.toStack(1), x, rowTop);
            }
            int textY = rowTop + 4;
            boolean fluid = row.key() instanceof FluidKey;
            Component in = Component.translatable(RecordTexts.LEDGER_IN, amount(row.in(), fluid));
            Component out = Component.translatable(RecordTexts.LEDGER_OUT, amount(row.out(), fluid));
            int outX = right - font.width(out);
            int inX = outX - 6 - font.width(in);
            AccentFrame.text(graphics, font, Component.literal(name(row.key())), x + 20, textY, inX - (x + 20) - 4,
                    AccentFrame.TEXT, mouseX, mouseY);
            TextScale.text(graphics, font, in, inX, textY, AccentFrame.BAR_TOP, false);
            TextScale.text(graphics, font, out, outX, textY, AccentFrame.LABEL, false);
        }
        if (ledger.unrecorded() > 0) {
            AccentFrame.text(graphics, font,
                    Component.translatable(RecordTexts.LEDGER_UNRECORDED, Long.toString(ledger.unrecorded())), x,
                    listTop + LEDGER_ROWS * LEDGER_ROW + 4, width, AccentFrame.LABEL, mouseX, mouseY);
        }
    }

    private static Component viewName(RecordsView.LedgerView view) {
        if (view.player() == null) {
            return Component.translatable(RecordTexts.LEDGER_EVERYONE);
        }
        return view.automation()
                ? Component.translatable(RecordTexts.LEDGER_AUTOMATION)
                : Component.literal(view.name());
    }

    private static String amount(long amount, boolean fluid) {
        return fluid ? AmountFormat.fluid(amount) : AmountFormat.compact(amount);
    }

    private static String name(StackKey key) {
        return switch (key) {
            case ItemKey item -> {
                ItemStack stack = item.toStack(1);
                yield stack.getHoverName().getString();
            }
            case FluidKey fluid -> StackKeys.toResource(fluid).getHoverName().getString();
        };
    }

    private void alarms(GuiGraphicsExtractor graphics, Font font, RecordsView view, int top, int mouseX, int mouseY) {
        RecordsView.Alarms alarms = view.alarms();
        boolean may = view.has(RecordsView.MAY_CONFIGURE);
        List<RecordsView.Rule> rules = alarms.rules();
        AccentFrame.text(graphics, font, Component.translatable(RecordTexts.ALARM_HEADER,
                InfoFormat.number(rules.size()), InfoFormat.number(alarms.max())), x, top, width, HEADER_COLOUR, mouseX,
                mouseY);
        int listTop = top + 12;
        if (rules.isEmpty()) {
            AccentFrame.text(graphics, font, Component.translatable(RecordTexts.ALARM_NONE), x, listTop, width,
                    AccentFrame.LABEL, mouseX, mouseY);
        }
        ruleScroll = Math.clamp(ruleScroll, 0, Math.max(0, rules.size() - RULE_ROWS));
        for (int i = 0; i < RULE_ROWS && ruleScroll + i < rules.size(); i++) {
            RecordsView.Rule rule = rules.get(ruleScroll + i);
            int rowTop = listTop + i * RULE_ROW;
            AlarmSettings settings = rule.settings();
            button(graphics, font, x, rowTop, TYPE_WIDTH, Component.translatable(settings.type().translationKey()), may,
                    mouseX, mouseY);
            for (int s = 0; s < ControllerRecorders.STEP_COUNT; s++) {
                int bx = x + TYPE_WIDTH + 4 + s * (STEP_WIDTH + 2);
                button(graphics, font, bx, rowTop, STEP_WIDTH, Component.literal(ControllerRecorders.stepLabel(s)), may,
                        mouseX, mouseY);
            }
            button(graphics, font, x + width - REMOVE_WIDTH, rowTop, REMOVE_WIDTH,
                    Component.translatable(RecordTexts.ALARM_REMOVE), may, mouseX, mouseY);
            Component side = Component.translatable(
                    settings.type().below() ? RecordTexts.ALARM_BELOW : RecordTexts.ALARM_ABOVE,
                    Integer.toString(settings.threshold()));
            Component line = Component.translatable(RecordTexts.ALARM_THRESHOLD, side).copy().append("  ")
                    .append(Component.translatable(RecordTexts.ALARM_VALUE, Integer.toString(rule.value())))
                    .append("  ")
                    .append(Component.translatable(rule.output() ? RecordTexts.ALARM_ON : RecordTexts.ALARM_OFF));
            AccentFrame.text(graphics, font, line, x, rowTop + BUTTON_HEIGHT + 2, width,
                    rule.output() ? StatusPalette.warn() : AccentFrame.LABEL, mouseX, mouseY);
        }
        int below = listTop + RULE_ROWS * RULE_ROW + 2;
        button(graphics, font, x, below, ADD_WIDTH, Component.translatable(RecordTexts.ALARM_ADD),
                may && rules.size() < alarms.max(), mouseX, mouseY);
        if (!may) {
            AccentFrame.text(graphics, font, Component.translatable(RecordTexts.ALARM_NOT_ALLOWED), x,
                    below + BUTTON_HEIGHT + 4, width, StatusPalette.warn(), mouseX, mouseY);
        }
    }

    private static void button(GuiGraphicsExtractor graphics, Font font, int bx, int by, int bw, Component label,
            boolean enabled, int mouseX, int mouseY) {
        if (enabled) {
            AccentFrame.button(graphics, font, bx, by, bw, BUTTON_HEIGHT, label,
                    over(mouseX, mouseY, bx, by, bw, BUTTON_HEIGHT), mouseX, mouseY);
        } else {
            AccentFrame.disabledButton(graphics, font, bx, by, bw, BUTTON_HEIGHT, label);
        }
    }

    /** A left click: a page button, the ledger's filter or a rule's button. Returns whether the panel took it. */
    public boolean click(double mouseX, double mouseY) {
        int pageWidth = (width - 2 * (RecordsView.PAGES - 1)) / RecordsView.PAGES;
        for (int i = 0; i < RecordsView.PAGES; i++) {
            if (over(mouseX, mouseY, x + i * (pageWidth + 2), y, pageWidth, PAGE_HEIGHT)) {
                if (page != i) {
                    page = i;
                    ask();
                }
                return true;
            }
        }
        RecordsView view = view();
        if (view == null || blocked(view) != null) {
            return false;
        }
        int top = y + PAGE_HEIGHT + 4;
        if (page == RecordsView.LEDGER) {
            if (over(mouseX, mouseY, x, top, width, PAGE_HEIGHT) && !view.ledger().views().isEmpty()) {
                ledgerView = (ledgerView + 1) % view.ledger().views().size();
                ledgerScroll = 0;
                return true;
            }
            return false;
        }
        if (page != RecordsView.ALARMS || !view.has(RecordsView.MAY_CONFIGURE)) {
            return false;
        }
        int listTop = top + 12;
        List<RecordsView.Rule> rules = view.alarms().rules();
        for (int i = 0; i < RULE_ROWS && ruleScroll + i < rules.size(); i++) {
            int rowTop = listTop + i * RULE_ROW;
            int index = ruleScroll + i;
            if (over(mouseX, mouseY, x, rowTop, TYPE_WIDTH, BUTTON_HEIGHT)) {
                return edit(index, ControllerRecorders.OP_CYCLE);
            }
            for (int s = 0; s < ControllerRecorders.STEP_COUNT; s++) {
                int bx = x + TYPE_WIDTH + 4 + s * (STEP_WIDTH + 2);
                if (over(mouseX, mouseY, bx, rowTop, STEP_WIDTH, BUTTON_HEIGHT)) {
                    return edit(index, ControllerRecorders.OP_FIRST_STEP + s);
                }
            }
            if (over(mouseX, mouseY, x + width - REMOVE_WIDTH, rowTop, REMOVE_WIDTH, BUTTON_HEIGHT)) {
                return edit(index, ControllerRecorders.OP_REMOVE);
            }
        }
        int below = listTop + RULE_ROWS * RULE_ROW + 2;
        if (over(mouseX, mouseY, x, below, ADD_WIDTH, BUTTON_HEIGHT) && rules.size() < view.alarms().max()) {
            return edit(-1, ControllerRecorders.OP_ADD);
        }
        return false;
    }

    private boolean edit(int rule, int op) {
        Platform.get().network().sendToServer(new RecordsPayloads.Edit(containerId, rule, op));
        return true;
    }

    /** The mouse wheel scrolls the ledger's rows or the rules. */
    public boolean scroll(double scrollY) {
        int step = scrollY > 0 ? -1 : scrollY < 0 ? 1 : 0;
        if (page == RecordsView.LEDGER) {
            ledgerScroll = Math.max(0, ledgerScroll + step);
            return true;
        }
        if (page == RecordsView.ALARMS) {
            ruleScroll = Math.max(0, ruleScroll + step);
            return true;
        }
        return false;
    }

    private static boolean over(double mouseX, double mouseY, int bx, int by, int bw, int bh) {
        return mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh;
    }
}
