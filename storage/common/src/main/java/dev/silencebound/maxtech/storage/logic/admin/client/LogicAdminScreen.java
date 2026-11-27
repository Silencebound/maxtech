package dev.silencebound.maxtech.storage.logic.admin.client;

import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.lib.api.client.gui.Accent;
import dev.silencebound.maxtech.lib.api.client.gui.AccentButton;
import dev.silencebound.maxtech.lib.api.client.gui.AccentInset;
import dev.silencebound.maxtech.lib.api.client.gui.AccentPanel;
import dev.silencebound.maxtech.lib.api.client.gui.AccentRow;
import dev.silencebound.maxtech.lib.api.client.gui.AccentStatus;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.lib.api.text.TextFit;
import dev.silencebound.maxtech.storage.logic.admin.AdminList;
import dev.silencebound.maxtech.storage.logic.admin.AdminRow;
import dev.silencebound.maxtech.storage.logic.admin.AdminTexts;
import dev.silencebound.maxtech.storage.logic.admin.LogicAdmin;
import dev.silencebound.maxtech.storage.logic.controller.LogicWorker;
import dev.silencebound.maxtech.storage.logic.controller.client.ControllerLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The admin overview screen (M7L2 chunk 23), opened by {@code /maxtech logic screen}: a table of the loaded rules on
 * the server in the Accent Frame look (B09 item 21e), built from Lib's accent pieces and {@link TextFit}. It starts
 * with the costliest rules; the OWNER, CHECKS/S and ERR headings sort by owner, cost and errors, a button picks one
 * dimension and a box filters by owner name. The selected rule's buttons run the operator commands ({@code tp},
 * {@code /maxtech logic info}, {@code pause} and {@code resume}), so the server checks the permission each time;
 * Refresh asks for the screen again. The data is what the server sent when the screen opened. Sort and filters are kept
 * while the game runs. Client only.
 */
public final class LogicAdminScreen extends Screen {
    private static final int PREFERRED_WIDTH = 460;
    private static final int PREFERRED_HEIGHT = 280;
    private static final int MIN_WIDTH = 300;
    private static final int MIN_HEIGHT = 190;
    private static final int GAP = 4;
    private static final int PAD = 3;
    private static final int CELL_GAP = 4;
    private static final int DOT_ROOM = AccentStatus.SIZE + 4;
    /**
     * Column widths as parts of the row: rule, owner, where, checks per second, action points per second, last run,
     * errors.
     */
    private static final int[] WEIGHTS = {25, 14, 23, 11, 11, 9, 7};
    private static final String[] HEADINGS = {AdminTexts.COLUMN_RULE, AdminTexts.COLUMN_OWNER, AdminTexts.COLUMN_WHERE,
            AdminTexts.COLUMN_CHECKS, AdminTexts.COLUMN_ACTIONS, AdminTexts.COLUMN_RAN, AdminTexts.COLUMN_ERRORS};
    /** The sort order a heading picks, or null when the column does not sort. */
    private static final AdminList.@Nullable Sort[] SORTS = {null, AdminList.Sort.OWNER, null, AdminList.Sort.COST,
            AdminList.Sort.ACTIONS, null, AdminList.Sort.ERRORS};

    // Kept across screens, so Refresh and a reopen keep what the operator chose.
    private static AdminList.Sort sort = AdminList.Sort.COST;
    private static String dimension = "";
    private static String owner = "";

    private final LogicAdmin.View view;
    private final List<String> dimensions;
    private List<AdminRow> shown = List.of();
    private @Nullable AdminRow selected;
    private int scroll;

    // Laid out in init.
    private @Nullable AccentPanel frame;
    private int tableTop;
    private int tableBottom;
    private int headerY;
    private int rowsTop;
    private final int[] columnX = new int[WEIGHTS.length];
    private final int[] columnWidth = new int[WEIGHTS.length];
    private int buttonY;
    private int buttonsLeft;
    private int boxX;
    private int boxY;
    private int boxWidth;
    private @Nullable AccentButton toggle;
    private @Nullable AccentButton teleport;
    private @Nullable AccentButton info;

    public LogicAdminScreen(LogicAdmin.View view) {
        super(Component.translatable(AdminTexts.TITLE));
        this.view = view;
        List<String> found = new ArrayList<>();
        for (AdminRow row : view.rows()) {
            if (!found.contains(row.dimension())) {
                found.add(row.dimension());
            }
        }
        found.sort(null);
        this.dimensions = found;
        refilter();
    }

    // ---- data

    private void refilter() {
        if (!dimension.isEmpty() && !dimensions.contains(dimension)) {
            // The dimension picked before has no loaded rules now.
            dimension = "";
        }
        shown = AdminList.filter(AdminList.sorted(view.rows(), sort), dimension, owner);
        AdminRow current = selected;
        selected = null;
        if (current != null) {
            for (AdminRow row : shown) {
                if (same(row, current)) {
                    selected = row;
                    break;
                }
            }
        }
    }

    private static boolean same(AdminRow a, AdminRow b) {
        return a.graph() == b.graph() && a.pos().equals(b.pos()) && a.dimension().equals(b.dimension());
    }

    private static String shortDimension(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    private static String where(AdminRow row) {
        return shortDimension(row.dimension()) + " " + row.pos().getX() + " " + row.pos().getY() + " "
                + row.pos().getZ();
    }

    private static AccentStatus status(LogicWorker.Status status) {
        return switch (status) {
            case RUNNING -> AccentStatus.OK;
            case WAITING, DISABLED -> AccentStatus.WARN;
            case PAUSED -> AccentStatus.ERROR;
            case IDLE -> AccentStatus.OFF;
        };
    }

    // ---- layout

    @Override
    protected void init() {
        int frameWidth = ControllerLayout.fit(width, PREFERRED_WIDTH, MIN_WIDTH, PREFERRED_WIDTH);
        int frameHeight = ControllerLayout.fit(height, PREFERRED_HEIGHT, MIN_HEIGHT, PREFERRED_HEIGHT);
        String subtitle = I18n.get(AdminTexts.SUBTITLE, InfoFormat.number(view.total()),
                InfoFormat.number(view.controllers()));
        AccentPanel f = AccentPanel.of((width - frameWidth) / 2, Accent.top(height, frameHeight), frameWidth,
                frameHeight, title, subtitle);
        frame = f;
        int buttonHeight = AccentButton.HEIGHT;

        // Controls: dimension, owner filter, refresh.
        int y = f.contentTop();
        Component dimensionLabel = Component
                .literal(dimension.isEmpty() ? I18n.get(AdminTexts.ALL_DIMENSIONS) : shortDimension(dimension));
        int dimensionWidth = Math.min(f.contentWidth() / 3, AccentButton.width(font, dimensionLabel));
        AccentButton dimensionButton = new AccentButton(f.contentLeft(), y, dimensionWidth, buttonHeight,
                dimensionLabel, this::nextDimension);
        dimensionButton.tooltip(Component.translatable(AdminTexts.DIMENSION_TIP));
        addRenderableWidget(dimensionButton);
        Component refreshLabel = Component.translatable(AdminTexts.REFRESH);
        int refreshWidth = AccentButton.width(font, refreshLabel);
        addRenderableWidget(new AccentButton(f.contentRight() - refreshWidth, y, refreshWidth, buttonHeight,
                refreshLabel, () -> command("maxtech logic screen")));
        boxX = f.contentLeft() + dimensionWidth + GAP;
        boxY = y;
        boxWidth = Math.max(0, f.contentRight() - refreshWidth - GAP - boxX);
        EditBox box = new EditBox(font, boxX + PAD, boxY + (buttonHeight - font.lineHeight) / 2 + 1,
                Math.max(0, boxWidth - 2 * PAD), font.lineHeight, Component.translatable(AdminTexts.OWNER_FILTER));
        box.setBordered(false);
        box.setMaxLength(16);
        box.setTextColor(Accent.TEXT);
        box.setHint(Component.translatable(AdminTexts.OWNER_FILTER).withColor(Accent.DIM));
        box.setValue(owner);
        box.setResponder(value -> {
            owner = value.strip();
            scroll = 0;
            refilter();
        });
        addRenderableWidget(box);

        // The table.
        tableTop = y + buttonHeight + GAP;
        buttonY = f.contentBottom() - buttonHeight;
        tableBottom = buttonY - GAP;
        int glassX = f.contentLeft() + AccentInset.BORDER;
        int glassWidth = AccentInset.innerWidth(f.contentWidth());
        headerY = tableTop + AccentInset.BORDER + 2;
        rowsTop = headerY + font.lineHeight + 2;
        int left = glassX + DOT_ROOM;
        int room = Math.max(0, glassWidth - DOT_ROOM - PAD);
        int total = 0;
        for (int weight : WEIGHTS) {
            total += weight;
        }
        int x = left;
        for (int i = 0; i < WEIGHTS.length; i++) {
            int w = i == WEIGHTS.length - 1 ? left + room - x : room * WEIGHTS[i] / total;
            columnX[i] = x;
            columnWidth[i] = Math.max(0, w - CELL_GAP);
            x += w;
        }

        // The selected rule's buttons, right to left.
        Component pause = Component.translatable(AdminTexts.PAUSE);
        Component resume = Component.translatable(AdminTexts.RESUME);
        Component infoLabel = Component.translatable(AdminTexts.INFO);
        Component teleportLabel = Component.translatable(AdminTexts.TELEPORT);
        int toggleWidth = AccentButton.width(font, pause, resume);
        int bx = f.contentRight() - toggleWidth;
        toggle = addRenderableWidget(
                new AccentButton(bx, buttonY, toggleWidth, buttonHeight, pause, this::toggleSelected).label(() -> {
                    AdminRow row = selected;
                    return row != null && LogicWorker.Status.byId(row.status()) == LogicWorker.Status.DISABLED
                            ? resume
                            : pause;
                }));
        int infoWidth = AccentButton.width(font, infoLabel);
        bx -= infoWidth + GAP;
        info = addRenderableWidget(
                new AccentButton(bx, buttonY, infoWidth, buttonHeight, infoLabel, this::infoSelected));
        int teleportWidth = AccentButton.width(font, teleportLabel);
        bx -= teleportWidth + GAP;
        teleport = addRenderableWidget(
                new AccentButton(bx, buttonY, teleportWidth, buttonHeight, teleportLabel, this::teleportSelected));
        buttonsLeft = bx;
    }

    // ---- actions

    private void nextDimension() {
        if (dimensions.isEmpty()) {
            dimension = "";
        } else {
            int at = dimension.isEmpty() ? -1 : dimensions.indexOf(dimension);
            dimension = at + 1 >= dimensions.size() ? "" : dimensions.get(at + 1);
        }
        scroll = 0;
        refilter();
        rebuildWidgets();
    }

    private void command(String command) {
        ClientPacketListener connection = minecraft.getConnection();
        if (connection != null) {
            connection.sendCommand(command);
        }
    }

    private static String target(AdminRow row) {
        return row.pos().getX() + " " + row.pos().getY() + " " + row.pos().getZ() + " " + (row.graph() + 1);
    }

    private void teleportSelected() {
        AdminRow row = selected;
        if (row != null) {
            command("execute in " + row.dimension() + " run tp @s " + row.pos().getX() + " " + (row.pos().getY() + 1)
                    + " " + row.pos().getZ());
            onClose();
        }
    }

    private void infoSelected() {
        AdminRow row = selected;
        if (row != null) {
            command("execute in " + row.dimension() + " run maxtech logic info " + target(row));
            onClose();
        }
    }

    private void toggleSelected() {
        AdminRow row = selected;
        if (row != null) {
            boolean off = LogicWorker.Status.byId(row.status()) == LogicWorker.Status.DISABLED;
            command("execute in " + row.dimension() + " run maxtech logic " + (off ? "resume " : "pause ")
                    + target(row));
            command("maxtech logic screen");
        }
    }

    // ---- drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        AccentPanel f = frame;
        if (f == null) {
            super.extractRenderState(graphics, mouseX, mouseY, a);
            return;
        }
        f.draw(graphics, font, mouseX, mouseY);
        AccentInset.draw(graphics, boxX, boxY, boxWidth, AccentButton.HEIGHT);
        updateButtons();
        drawTable(graphics, f, mouseX, mouseY);
        AdminRow row = selected;
        if (row != null) {
            LogicWorker.Status status = LogicWorker.Status.byId(row.status());
            int room = buttonsLeft - GAP - f.contentLeft() - DOT_ROOM;
            AccentStatus.dot(graphics, f.contentLeft() + 1, buttonY + (AccentButton.HEIGHT - AccentStatus.SIZE) / 2,
                    status(status).colour());
            fitted(graphics, row.shownName() + " · " + where(row), f.contentLeft() + DOT_ROOM,
                    buttonY + (AccentButton.HEIGHT - font.lineHeight) / 2 + 1, room, Accent.TEXT, mouseX, mouseY);
        }
        super.extractRenderState(graphics, mouseX, mouseY, a);
    }

    private void updateButtons() {
        AdminRow row = selected;
        boolean on = row != null;
        AccentButton t = toggle;
        if (t != null) {
            t.visible = on;
        }
        if (teleport != null) {
            teleport.visible = on;
        }
        if (info != null) {
            info.visible = on;
        }
    }

    private int visibleRows() {
        return ControllerLayout.visibleRows(tableBottom - AccentInset.BORDER - rowsTop, AccentRow.height(font));
    }

    private void drawTable(GuiGraphicsExtractor graphics, AccentPanel f, int mouseX, int mouseY) {
        AccentInset.begin(graphics, f.contentLeft(), tableTop, f.contentWidth(), tableBottom - tableTop);
        int glassX = f.contentLeft() + AccentInset.BORDER;
        int glassWidth = AccentInset.innerWidth(f.contentWidth());
        for (int i = 0; i < HEADINGS.length; i++) {
            AdminList.Sort by = SORTS[i];
            String heading = I18n.get(HEADINGS[i]) + (by == sort ? " ▼" : "");
            int colour = by == null ? Accent.LABEL : by == sort ? Accent.CYAN : Accent.TEXT;
            TextScale.text(graphics, font, TextFit.fit(heading, columnWidth[i], font::width), columnX[i], headerY,
                    colour, false);
            if (by != null
                    && Accent.contains(columnX[i], headerY - 1, columnWidth[i], font.lineHeight + 1, mouseX, mouseY)) {
                graphics.setTooltipForNextFrame(font, Component.translatable(AdminTexts.SORT_TIP), mouseX, mouseY);
            }
        }
        int rowHeight = AccentRow.height(font);
        int visible = visibleRows();
        scroll = Math.clamp(scroll, 0, Math.max(0, shown.size() - visible));
        if (shown.isEmpty()) {
            String empty = I18n.get(view.rows().isEmpty() ? AdminTexts.EMPTY : AdminTexts.NONE_MATCH);
            TextScale.text(graphics, font, TextFit.fit(empty, glassWidth - 2 * PAD, font::width), glassX + PAD,
                    rowsTop + 2, Accent.LABEL, false);
        }
        for (int i = 0; i < visible && scroll + i < shown.size(); i++) {
            AdminRow row = shown.get(scroll + i);
            int y = ControllerLayout.rowY(rowsTop, i, rowHeight);
            boolean hovered = Accent.contains(glassX, y, glassWidth, rowHeight, mouseX, mouseY);
            AdminRow current = selected;
            if (current != null && same(row, current)) {
                AccentRow.select(graphics, glassX, y, glassWidth, rowHeight);
            } else if (hovered) {
                AccentRow.hover(graphics, glassX, y, glassWidth, rowHeight);
            }
            LogicWorker.Status status = LogicWorker.Status.byId(row.status());
            AccentStatus.dot(graphics, glassX + AccentRow.STRIP + 1, y + (rowHeight - AccentStatus.SIZE) / 2,
                    status(status).colour());
            boolean dim = status == LogicWorker.Status.DISABLED || status == LogicWorker.Status.IDLE;
            int ink = dim ? Accent.DIM : Accent.TEXT;
            int textY = y + 2;
            cell(graphics, 0, row.shownName(), textY, ink, mouseX, mouseY);
            cell(graphics, 1, row.owner().isEmpty() ? "-" : row.owner(), textY, ink, mouseX, mouseY);
            cell(graphics, 2, where(row), textY, ink, mouseX, mouseY);
            cell(graphics, 3, String.format(Locale.ROOT, "%.1f", row.checksPerSecond()), textY, ink, mouseX, mouseY);
            cell(graphics, 4, String.format(Locale.ROOT, "%.1f", row.actionPointsPerSecond()), textY, ink, mouseX,
                    mouseY);
            cell(graphics, 5,
                    row.lastRunAgo() < 0
                            ? I18n.get(AdminTexts.NEVER)
                            : I18n.get(AdminTexts.AGO, InfoFormat.number(row.lastRunAgo() / 20)),
                    textY, ink, mouseX, mouseY);
            cell(graphics, 6, row.errors() == 0 ? "-" : Integer.toString(row.errors()), textY,
                    row.errors() > 0 ? Accent.RED : ink, mouseX, mouseY);
            if (row.errors() > 0 && hovered
                    && Accent.contains(columnX[6], y, columnWidth[6], rowHeight, mouseX, mouseY)) {
                graphics.setTooltipForNextFrame(font, Component.translatable(row.error(), row.errorArg()), mouseX,
                        mouseY);
            }
        }
        if (view.rows().size() < view.total()) {
            String note = I18n.get(AdminTexts.SHOWN, InfoFormat.number(view.rows().size()),
                    InfoFormat.number(view.total()));
            int y = tableBottom - AccentInset.BORDER - font.lineHeight - 1;
            TextScale.text(graphics, font, TextFit.fit(note, glassWidth - 2 * PAD, font::width), glassX + PAD, y,
                    Accent.AMBER, false);
        }
        AccentInset.end(graphics);
    }

    private void cell(GuiGraphicsExtractor graphics, int column, String text, int y, int colour, int mouseX,
            int mouseY) {
        fitted(graphics, text, columnX[column], y, columnWidth[column], colour, mouseX, mouseY);
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

    // ---- input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        AccentPanel f = frame;
        if (f == null) {
            return false;
        }
        for (int i = 0; i < HEADINGS.length; i++) {
            AdminList.Sort by = SORTS[i];
            if (by != null && Accent.contains(columnX[i], headerY - 1, columnWidth[i], font.lineHeight + 1, event.x(),
                    event.y())) {
                sort = by;
                scroll = 0;
                refilter();
                return true;
            }
        }
        int index = ControllerLayout.rowAt(event.x(), event.y(), f.contentLeft(), rowsTop, f.contentWidth(),
                AccentRow.height(font), visibleRows(), scroll, shown.size());
        if (index >= 0) {
            selected = shown.get(index);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        int step = scrollY > 0 ? -1 : scrollY < 0 ? 1 : 0;
        AccentPanel f = frame;
        if (step != 0 && f != null
                && Accent.contains(f.contentLeft(), tableTop, f.contentWidth(), tableBottom - tableTop, x, y)) {
            scroll = Math.max(0, scroll + step);
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
