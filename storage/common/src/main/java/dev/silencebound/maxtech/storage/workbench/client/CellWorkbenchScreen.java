package dev.silencebound.maxtech.storage.workbench.client;

import net.minecraft.network.chat.CommonComponents;
import dev.silencebound.maxtech.lib.api.client.gui.AccentButton;
import dev.silencebound.maxtech.storage.machine.client.MenuClick;
import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.lib.client.gui.ScreenComponents;
import dev.silencebound.maxtech.storage.cell.CellAssembly;
import dev.silencebound.maxtech.storage.cell.CellCasing;
import dev.silencebound.maxtech.storage.cell.CellCasingItem;
import dev.silencebound.maxtech.storage.cell.CellData;
import dev.silencebound.maxtech.storage.cell.CellFilter;
import dev.silencebound.maxtech.storage.cell.CellTypes;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.terminal.AmountFormat;
import dev.silencebound.maxtech.storage.workbench.CellWorkbenchMenu;
import dev.silencebound.maxtech.storage.workbench.WorkbenchContent;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.jspecify.annotations.Nullable;

/**
 * The cell workbench in the Accent Frame style: casing and chip on the left, memory and add-on rows in the middle with
 * the filter beside them, stats on the right (type, a bytes bar, kinds, bulk and the last refusal). The fuzzy and the
 * allow/deny buttons show only with a filter add-on, the clear-unknown button under the stats only while that filter
 * keeps entries of a removed mod. Everything it shows comes from the synced cell. Client only.
 */
public final class CellWorkbenchScreen extends AbstractContainerScreen<CellWorkbenchMenu> {
    private static final int STATS_X = 184;
    private static final int STATS_Y = 22;
    private static final int STATS_WIDTH = 64;
    private static final int TEXT_WARN = 0xFFD9A05B;
    /** The allow/deny button's offset below the filter's top, under the fuzzy button. */
    private static final int DENY_DY = 70;
    /** The stats panel's height; it ends above the inventory and holds the clear-unknown button under the notice. */
    private static final int STATS_HEIGHT = 88;
    /** The clear-unknown button's offset below the stats' top, under the notice line. */
    private static final int CLEAR_UNKNOWN_DY = 69;

    private @Nullable AccentButton fuzzy;
    private @Nullable AccentButton deny;
    private @Nullable AccentButton clearUnknown;

    public CellWorkbenchScreen(CellWorkbenchMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, CellWorkbenchMenu.WIDTH, CellWorkbenchMenu.HEIGHT);
        this.inventoryLabelX = CellWorkbenchMenu.INVENTORY_X;
        this.inventoryLabelY = CellWorkbenchMenu.INVENTORY_Y - 11;
        this.titleLabelY = -100;
    }

    @Override
    protected void init() {
        super.init();
        fuzzy = addRenderableWidget(new AccentButton(leftPos + CellWorkbenchMenu.FILTER_X,
                topPos + CellWorkbenchMenu.FILTER_Y + 56, 54, AccentButton.COMPACT_HEIGHT, Component.empty(),
                () -> MenuClick.send(menu, CellWorkbenchMenu.FUZZY)));
        deny = addRenderableWidget(new AccentButton(leftPos + CellWorkbenchMenu.FILTER_X,
                topPos + CellWorkbenchMenu.FILTER_Y + DENY_DY, 54, AccentButton.COMPACT_HEIGHT, Component.empty(),
                () -> MenuClick.send(menu, CellWorkbenchMenu.DENY))
                .tooltip(CommonComponents.joinLines(Component.translatable(WorkbenchContent.ALLOW_HINT_KEY),
                        Component.translatable(WorkbenchContent.DENY_HINT_KEY))));
        clearUnknown = addRenderableWidget(new AccentButton(leftPos + STATS_X, topPos + STATS_Y + CLEAR_UNKNOWN_DY,
                STATS_WIDTH, AccentButton.COMPACT_HEIGHT, Component.translatable(WorkbenchContent.CLEAR_UNKNOWN_KEY),
                () -> MenuClick.send(menu, CellWorkbenchMenu.CLEAR_UNKNOWN)).tooltip(menu::unknownFilterEntries,
                        count -> Component.translatable(WorkbenchContent.CLEAR_UNKNOWN_HINT_KEY, count)));
        containerTick();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        CellData data = menu.data();
        if (fuzzy != null) {
            fuzzy.visible = menu.filterShown();
            fuzzy.setMessage(Component.translatable(
                    data != null && data.filter().fuzzy() ? WorkbenchContent.FUZZY_KEY : WorkbenchContent.EXACT_KEY));
        }
        if (deny != null) {
            deny.visible = menu.filterShown();
            deny.setMessage(Component.translatable(
                    data != null && data.filter().deny() ? WorkbenchContent.DENY_KEY : WorkbenchContent.ALLOW_KEY));
        }
        if (clearUnknown != null) {
            // Only while the filter holds kept entries: they free their slots only when the player asks.
            clearUnknown.visible = menu.filterShown() && menu.unknownFilterEntries() > 0;
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        AccentFrame.panel(graphics, font, leftPos, topPos, imageWidth, imageHeight, title, mouseX, mouseY);
        AccentFrame.box(graphics, leftPos + STATS_X - 4, topPos + STATS_Y - 4, STATS_WIDTH + 8, STATS_HEIGHT);
        for (Slot slot : menu.slots) {
            if (slot.isActive()) {
                AccentFrame.slot(graphics, leftPos + slot.x, topPos + slot.y);
            }
        }
        CellData data = menu.data();
        if (data != null && data.totalBytes() > 0) {
            AccentFrame.horizontalBar(graphics, leftPos + STATS_X, topPos + STATS_Y + 22, STATS_WIDTH, 5,
                    (float) ((double) data.usedBytes() / data.totalBytes()));
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        TextScale.text(graphics, font, playerInventoryTitle, inventoryLabelX, inventoryLabelY + 1, AccentFrame.LABEL,
                false);
        label(graphics, WorkbenchContent.CASING_KEY, CellWorkbenchMenu.CASING_X - 2, CellWorkbenchMenu.CASING_Y - 10);
        CellCasing casing = CellAssembly.casing(menu.cell());
        CellData data = menu.data();
        if (casing == null) {
            stat(graphics, Component.translatable(WorkbenchContent.EMPTY_KEY), 0, AccentFrame.LABEL, xm, ym);
            return;
        }
        if (data == null || menu.cell().getItem() instanceof CellCasingItem) {
            stat(graphics, Component.translatable(WorkbenchContent.CHIP_FIRST_KEY), 0, AccentFrame.LABEL, xm, ym);
            return;
        }
        label(graphics, WorkbenchContent.MEMORY_KEY, CellWorkbenchMenu.MEMORY_X, CellWorkbenchMenu.MEMORY_Y - 10);
        label(graphics, WorkbenchContent.ADDONS_KEY, CellWorkbenchMenu.MEMORY_X, CellWorkbenchMenu.ADDON_Y - 10);
        if (menu.filterShown()) {
            label(graphics, WorkbenchContent.FILTER_KEY, CellWorkbenchMenu.FILTER_X, CellWorkbenchMenu.FILTER_Y - 10);
            List<CellFilter.Entry> entries = data.filter().entries();
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).tag().isPresent()) {
                    TextScale.text(graphics, font, "#", CellWorkbenchMenu.FILTER_X + (i % 3) * 18 + 11,
                            CellWorkbenchMenu.FILTER_Y + (i / 3) * 18 - 1, AccentFrame.TEXT, true);
                }
            }
        }
        stat(graphics, Component.translatable(CellTypes.translationKey(data.type())), 0, AccentFrame.TEXT, xm, ym);
        stat(graphics, Component.translatable(WorkbenchContent.BYTES_KEY, AmountFormat.compact(data.usedBytes()),
                AmountFormat.compact(data.totalBytes())), 1, AccentFrame.LABEL, xm, ym);
        stat(graphics, Component.translatable(WorkbenchContent.KINDS_KEY, data.kinds()), 3, AccentFrame.LABEL, xm, ym);
        if (casing.bulk()) {
            stat(graphics, Component.translatable(WorkbenchContent.BULK_KEY), 4, AccentFrame.LABEL, xm, ym);
        }
        if (data.kept() > 0) {
            stat(graphics, Component.translatable(WorkbenchContent.KEPT_KEY, data.kept()), 5, TEXT_WARN, xm, ym);
        }
        Component notice = notice();
        if (notice != null) {
            stat(graphics, notice, 6, TEXT_WARN, xm, ym);
        }
    }

    private @Nullable Component notice() {
        return switch (menu.notice()) {
            case CellWorkbenchMenu.NOTICE_RESEARCH -> Component.translatable(WorkbenchContent.NOTICE_RESEARCH_KEY);
            case CellWorkbenchMenu.NOTICE_NO_FIT -> Component.translatable(WorkbenchContent.NOTICE_NO_FIT_KEY);
            case CellWorkbenchMenu.NOTICE_IN_USE -> Component.translatable(WorkbenchContent.NOTICE_IN_USE_KEY);
            case CellWorkbenchMenu.NOTICE_NO_ROOM -> Component.translatable(WorkbenchContent.NOTICE_NO_ROOM_KEY);
            default -> null;
        };
    }

    private void label(GuiGraphicsExtractor graphics, String key, int x, int y) {
        TextScale.text(graphics, font, Component.translatable(key), x, y, AccentFrame.LABEL, false);
    }

    /** One line of the stats panel; lines are 10 px apart, cut to the panel's width with the whole as a tooltip. */
    private void stat(GuiGraphicsExtractor graphics, Component text, int line, int colour, int mouseX, int mouseY) {
        AccentFrame.text(graphics, font, text, STATS_X, STATS_Y + line * 10, STATS_WIDTH, colour, mouseX, mouseY);
    }

    /** Over a filter entry, names its tag; over the filter area with an empty cursor, says how it works. */
    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractTooltip(graphics, mouseX, mouseY);
        CellData data = menu.data();
        if (data == null || !menu.filterShown() || !menu.getCarried().isEmpty()
                || !ScreenComponents.isHovering(leftPos + CellWorkbenchMenu.FILTER_X,
                        topPos + CellWorkbenchMenu.FILTER_Y, 54, 54, mouseX, mouseY)) {
            return;
        }
        int column = (mouseX - leftPos - CellWorkbenchMenu.FILTER_X) / 18;
        int row = (mouseY - topPos - CellWorkbenchMenu.FILTER_Y) / 18;
        int index = row * 3 + column;
        List<CellFilter.Entry> entries = data.filter().entries();
        if (index < entries.size() && entries.get(index).tag().isPresent()) {
            ScreenComponents.tooltip(graphics, font, List
                    .of(Component.translatable(WorkbenchContent.TAG_KEY, entries.get(index).tag().get().toString())),
                    mouseX, mouseY);
        } else if (index >= entries.size()) {
            ScreenComponents.tooltip(graphics, font, List.of(Component.translatable(WorkbenchContent.FILTER_HINT_KEY)),
                    mouseX, mouseY);
        }
    }
}
