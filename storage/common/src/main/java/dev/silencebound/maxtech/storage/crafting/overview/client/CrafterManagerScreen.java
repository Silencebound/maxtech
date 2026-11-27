package dev.silencebound.maxtech.storage.crafting.overview.client;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.client.gui.ScrollBar;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.crafting.client.KeyDisplay;
import dev.silencebound.maxtech.storage.crafting.overview.CrafterManager;
import dev.silencebound.maxtech.storage.crafting.overview.CrafterManagerMenu;
import dev.silencebound.maxtech.storage.crafting.overview.PatternOverview;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The crafter manager's screen (B09 item 23) in the Accent Frame style: on the left the network's pattern providers,
 * searched by label or by what their patterns make, each with its pattern count; on the right the picked provider's
 * pattern slots and the player's inventory. A click on a provider the player may change picks it; patterns then move
 * the vanilla way, and one held on the cursor can be put into another provider after picking that one. Client only.
 */
public final class CrafterManagerScreen extends AbstractContainerScreen<CrafterManagerMenu> {
    private static final int SEARCH_Y = 20;
    private static final int LIST_TOP = 36;
    private static final int ROW = 18;
    private static final int ROWS = 9;
    private static final int ROW_WIDTH = CrafterManagerMenu.LIST_WIDTH - ScrollBar.WIDTH - 2;
    private static final int INFO_Y = CrafterManagerMenu.SLOTS_Y + 4 * 18 + 4;

    private String search = "";
    private int scroll;
    private CrafterManager.@Nullable Providers shownFrom;
    private String shownSearch = "";
    private List<CrafterManager.Provider> shown = List.of();

    public CrafterManagerScreen(CrafterManagerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, CrafterManagerMenu.WIDTH, CrafterManagerMenu.HEIGHT);
        this.titleLabelY = -100;
        this.inventoryLabelX = CrafterManagerMenu.INVENTORY_X;
        this.inventoryLabelY = CrafterManagerMenu.INVENTORY_Y - 11;
    }

    @Override
    protected void init() {
        super.init();
        EditBox box = new EditBox(font, leftPos + CrafterManagerMenu.MARGIN, topPos + SEARCH_Y,
                CrafterManagerMenu.LIST_WIDTH, 12, Component.translatable(CrafterManager.SEARCH_KEY));
        box.setHint(Component.translatable(CrafterManager.SEARCH_KEY));
        box.setMaxLength(50);
        box.setValue(search);
        box.setResponder(text -> {
            search = text;
            scroll = 0;
        });
        box.setTextColor(AccentFrame.TEXT);
        addRenderableWidget(box);
        addRenderableWidget(
                new ScrollBar(leftPos + CrafterManagerMenu.MARGIN + CrafterManagerMenu.LIST_WIDTH - ScrollBar.WIDTH,
                        topPos + LIST_TOP, ROWS * ROW, new ScrollBar.Model() {
                            @Override
                            public int total() {
                                refilter();
                                return shown.size();
                            }

                            @Override
                            public int visible() {
                                return ROWS;
                            }

                            @Override
                            public int offset() {
                                return scroll;
                            }

                            @Override
                            public void scrollTo(int offset) {
                                scroll = Math.clamp(offset, 0, ScrollBar.maxOffset(shown.size(), ROWS));
                            }
                        }));
    }

    private CrafterManager.@Nullable Providers data() {
        return CrafterManager.received(menu.containerId);
    }

    /** Rebuilds the filtered rows when the list or the search changed. */
    // Each list arrives as a new object; identity is the cheap per-frame check for a new one.
    @SuppressWarnings("ReferenceEquality")
    private void refilter() {
        CrafterManager.Providers data = data();
        if (data == shownFrom && search.equals(shownSearch)) {
            return;
        }
        shownFrom = data;
        shownSearch = search;
        if (data == null) {
            shown = List.of();
            return;
        }
        String needle = search.trim().toLowerCase(Locale.ROOT);
        List<CrafterManager.Provider> rows = new ArrayList<>();
        for (CrafterManager.Provider row : data.providers()) {
            if (needle.isEmpty() || matches(row, needle)) {
                rows.add(row);
            }
        }
        shown = rows;
        scroll = Math.clamp(scroll, 0, ScrollBar.maxOffset(shown.size(), ROWS));
    }

    private static boolean matches(CrafterManager.Provider row, String needle) {
        if (row.label().text().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        for (StackKey output : row.outputs()) {
            if (KeyDisplay.name(output).getString().toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean overList(double x, double y, int left, int top) {
        return x >= left + CrafterManagerMenu.MARGIN && x < left + CrafterManagerMenu.MARGIN + ROW_WIDTH
                && y >= top + LIST_TOP && y < top + LIST_TOP + ROWS * ROW;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (overList(mouseX, mouseY, leftPos, topPos)) {
            refilter();
            scroll = Math.clamp(scroll - (scrollY > 0 ? 1 : scrollY < 0 ? -1 : 0), 0,
                    ScrollBar.maxOffset(shown.size(), ROWS));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (overList(event.x(), event.y(), leftPos, topPos)) {
            refilter();
            int index = scroll + (int) ((event.y() - topPos - LIST_TOP) / ROW);
            if (index < shown.size()) {
                CrafterManager.Provider row = shown.get(index);
                if (row.editable()) {
                    Platform.get().network()
                            .sendToServer(new CrafterManager.Select(menu.containerId, row.pos(), row.facing()));
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        AccentFrame.panel(graphics, font, leftPos, topPos, imageWidth, imageHeight, title, mouseX, mouseY);
        for (Slot slot : menu.slots) {
            if (slot.isActive()) {
                AccentFrame.slot(graphics, leftPos + slot.x, topPos + slot.y);
            }
        }
        refilter();
        CrafterManager.Providers data = data();
        int x = leftPos + CrafterManagerMenu.MARGIN;
        if (data == null || data.providers().isEmpty()) {
            AccentFrame.text(graphics, font, Component.translatable(CrafterManager.EMPTY_KEY), x, topPos + LIST_TOP,
                    ROW_WIDTH, AccentFrame.LABEL, mouseX, mouseY);
            return;
        }
        CrafterManager.Provider picked = data.selectedRow();
        for (int i = 0; i < ROWS && scroll + i < shown.size(); i++) {
            CrafterManager.Provider row = shown.get(scroll + i);
            int y = topPos + LIST_TOP + i * ROW;
            if (row.equals(picked)) {
                graphics.fill(x, y, x + ROW_WIDTH, y + ROW, AccentFrame.ACCENT_DIM);
            } else if (mouseX >= x && mouseX < x + ROW_WIDTH && mouseY >= y && mouseY < y + ROW) {
                graphics.fill(x, y, x + ROW_WIDTH, y + ROW, AccentFrame.SLOT);
            }
            graphics.item(icon(row), x + 1, y + 1);
            int colour = row.editable() ? AccentFrame.TEXT : AccentFrame.LABEL;
            AccentFrame.text(graphics, font, name(row), x + 20, y + 1, ROW_WIDTH - 22, colour, mouseX, mouseY);
            AccentFrame.text(graphics, font,
                    Component.translatable(CrafterManager.PATTERNS_KEY, row.outputs().size(), row.usable()), x + 20,
                    y + 10, ROW_WIDTH - 22, AccentFrame.LABEL, mouseX, mouseY);
            if (!row.editable() && mouseX >= x && mouseX < x + ROW_WIDTH && mouseY >= y && mouseY < y + ROW) {
                graphics.setTooltipForNextFrame(font, Component.translatable(CrafterManager.LOCKED_KEY), mouseX,
                        mouseY);
            }
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        CrafterManager.Providers data = data();
        CrafterManager.Provider picked = data == null ? null : data.selectedRow();
        int x = CrafterManagerMenu.SLOTS_X;
        int width = imageWidth - x - CrafterManagerMenu.MARGIN;
        if (data != null) {
            AccentFrame.text(graphics, font,
                    data.hidden() > 0
                            ? Component.translatable(CrafterManager.HIDDEN_KEY, data.hidden())
                            : Component.translatable(CrafterManager.COUNT_KEY, data.providers().size()),
                    x, SEARCH_Y + 2, width, AccentFrame.LABEL, xm, ym);
        }
        if (picked == null) {
            AccentFrame.text(graphics, font, Component.translatable(CrafterManager.PICK_KEY), x,
                    CrafterManagerMenu.SLOTS_Y + 4, width, AccentFrame.LABEL, xm, ym);
        } else {
            Component where = picked.label().isEmpty()
                    ? Component.translatable(PatternOverview.PROVIDER_KEY, kind(picked), picked.pos().getX(),
                            picked.pos().getY(), picked.pos().getZ())
                    : Component.translatable(PatternOverview.PROVIDER_LABEL_KEY, kind(picked),
                            picked.label().component(), picked.pos().getX(), picked.pos().getY(), picked.pos().getZ());
            AccentFrame.text(graphics, font, where, x, INFO_Y, width, AccentFrame.TEXT, xm, ym);
        }
        AccentFrame.text(graphics, font, playerInventoryTitle, inventoryLabelX, inventoryLabelY + 1, width,
                AccentFrame.LABEL, xm, ym);
    }

    private static ItemStack icon(CrafterManager.Provider row) {
        if (!row.outputs().isEmpty()) {
            return KeyDisplay.icon(row.outputs().get(0));
        }
        return row.part()
                ? new ItemStack(ProviderContent.PROVIDER_PART_ITEM.get())
                : new ItemStack(ProviderContent.PROVIDER.get());
    }

    private static Component kind(CrafterManager.Provider row) {
        return row.part()
                ? ProviderContent.PROVIDER_PART_ITEM.get()
                        .getName(new ItemStack(ProviderContent.PROVIDER_PART_ITEM.get()))
                : ProviderContent.PROVIDER.get().getName();
    }

    private static Component name(CrafterManager.Provider row) {
        if (!row.label().isEmpty()) {
            return row.label().component();
        }
        return Component.empty().append(kind(row))
                .append(" " + row.pos().getX() + " " + row.pos().getY() + " " + row.pos().getZ());
    }
}
