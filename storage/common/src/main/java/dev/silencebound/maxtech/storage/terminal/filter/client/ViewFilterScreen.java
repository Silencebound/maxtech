package dev.silencebound.maxtech.storage.terminal.filter.client;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.client.gui.ScreenComponents;
import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.lib.api.client.gui.AccentButton;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.cell.CellFilter;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.machine.client.MenuClick;
import dev.silencebound.maxtech.storage.terminal.filter.ViewFilterContent;
import dev.silencebound.maxtech.storage.terminal.filter.ViewFilterMenu;
import dev.silencebound.maxtech.storage.workbench.WorkbenchContent;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.jspecify.annotations.Nullable;

/**
 * The view filter's GUI in the Accent Frame style: the nine entries on the left, the exact/fuzzy and show/hide buttons
 * beside them, a clear-unknown button only while the list keeps entries of a removed mod, and the player's inventory.
 * Everything it shows comes from the held filter as the slot sync brought it. Client only.
 */
public final class ViewFilterScreen extends AbstractContainerScreen<ViewFilterMenu> {
    private static final int BUTTON_X = ViewFilterMenu.ENTRY_X + 3 * 18 + 8;
    private static final int BUTTON_WIDTH = ViewFilterMenu.WIDTH - 8 - BUTTON_X;

    private @Nullable AccentButton fuzzy;
    private @Nullable AccentButton deny;
    private @Nullable AccentButton clearUnknown;

    public ViewFilterScreen(ViewFilterMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, ViewFilterMenu.WIDTH, ViewFilterMenu.HEIGHT);
        this.inventoryLabelX = ViewFilterMenu.INVENTORY_X;
        this.inventoryLabelY = ViewFilterMenu.INVENTORY_Y - 11;
        this.titleLabelY = -100;
    }

    @Override
    protected void init() {
        super.init();
        int y = topPos + ViewFilterMenu.ENTRY_Y;
        fuzzy = addRenderableWidget(new AccentButton(leftPos + BUTTON_X, y, BUTTON_WIDTH, AccentButton.HEIGHT,
                Component.empty(), () -> MenuClick.send(menu, ViewFilterMenu.FUZZY)));
        deny = addRenderableWidget(new AccentButton(leftPos + BUTTON_X, y + 18, BUTTON_WIDTH, AccentButton.HEIGHT,
                Component.empty(), () -> MenuClick.send(menu, ViewFilterMenu.DENY))
                .tooltip(Component.translatable(ViewFilterContent.MODE_HINT_KEY)));
        clearUnknown = addRenderableWidget(new AccentButton(leftPos + BUTTON_X, y + 36, BUTTON_WIDTH,
                AccentButton.HEIGHT, Component.translatable(WorkbenchContent.CLEAR_UNKNOWN_KEY),
                () -> MenuClick.send(menu, ViewFilterMenu.CLEAR_UNKNOWN)).tooltip(() -> menu.filter().kept().size(),
                        count -> Component.translatable(WorkbenchContent.CLEAR_UNKNOWN_HINT_KEY, count)));
        containerTick();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        CellFilter filter = menu.filter();
        if (fuzzy != null) {
            fuzzy.setMessage(
                    Component.translatable(filter.fuzzy() ? WorkbenchContent.FUZZY_KEY : WorkbenchContent.EXACT_KEY));
        }
        if (deny != null) {
            deny.setMessage(
                    Component.translatable(filter.deny() ? ViewFilterContent.HIDE_KEY : ViewFilterContent.SHOW_KEY));
        }
        if (clearUnknown != null) {
            clearUnknown.visible = !filter.kept().isEmpty();
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        AccentFrame.panel(graphics, font, leftPos, topPos, imageWidth, imageHeight, title, mouseX, mouseY);
        for (Slot slot : menu.slots) {
            AccentFrame.slot(graphics, leftPos + slot.x, topPos + slot.y);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        TextScale.text(graphics, font, playerInventoryTitle, inventoryLabelX, inventoryLabelY + 1, AccentFrame.LABEL,
                false);
        TextScale.text(graphics, font, Component.translatable(WorkbenchContent.FILTER_KEY), ViewFilterMenu.ENTRY_X,
                ViewFilterMenu.ENTRY_Y - 10, AccentFrame.LABEL, false);
        List<CellFilter.Entry> entries = menu.filter().entries();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).tag().isPresent()) {
                TextScale.text(graphics, font, "#", ViewFilterMenu.ENTRY_X + (i % 3) * 18 + 11,
                        ViewFilterMenu.ENTRY_Y + (i / 3) * 18 - 1, AccentFrame.TEXT, true);
            }
        }
    }

    /** Over a tag entry, names its tag; over an empty entry with an empty cursor, says how entries work. */
    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractTooltip(graphics, mouseX, mouseY);
        int index = entryAt(mouseX, mouseY);
        if (index < 0 || !menu.getCarried().isEmpty()) {
            return;
        }
        List<CellFilter.Entry> entries = menu.filter().entries();
        if (index < entries.size() && entries.get(index).tag().isPresent()) {
            ScreenComponents.tooltip(graphics, font, List
                    .of(Component.translatable(WorkbenchContent.TAG_KEY, entries.get(index).tag().get().toString())),
                    mouseX, mouseY);
        } else if (index >= entries.size()) {
            ScreenComponents.tooltip(graphics, font, List.of(Component.translatable(ViewFilterContent.ENTRY_HINT_KEY)),
                    mouseX, mouseY);
        }
    }

    /** The entry under the mouse, or -1. */
    private int entryAt(int mouseX, int mouseY) {
        if (!ScreenComponents.isHovering(leftPos + ViewFilterMenu.ENTRY_X, topPos + ViewFilterMenu.ENTRY_Y, 54, 54,
                mouseX, mouseY)) {
            return -1;
        }
        int column = (mouseX - leftPos - ViewFilterMenu.ENTRY_X) / 18;
        int row = (mouseY - topPos - ViewFilterMenu.ENTRY_Y) / 18;
        return row * 3 + column;
    }

    /** Where entry {@code index} is on the screen, for a recipe viewer's drag. */
    public Rect2i entryArea(int index) {
        return new Rect2i(leftPos + ViewFilterMenu.ENTRY_X + (index % 3) * 18,
                topPos + ViewFilterMenu.ENTRY_Y + (index / 3) * 18, 16, 16);
    }

    /** A recipe viewer's ingredient dropped on entry {@code index}: the server sets the entry; no item moves. */
    public void dropOnEntry(int index, StackKey key) {
        Platform.get().network().sendToServer(new ViewFilterMenu.Ghost(menu.containerId, index, key));
    }
}
