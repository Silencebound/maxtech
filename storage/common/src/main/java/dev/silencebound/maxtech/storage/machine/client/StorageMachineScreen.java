package dev.silencebound.maxtech.storage.machine.client;

import net.minecraft.network.chat.CommonComponents;
import dev.silencebound.maxtech.lib.client.gui.TextScale;
import dev.silencebound.maxtech.lib.client.gui.ScreenComponents;
import dev.silencebound.maxtech.storage.devicetab.DeviceTabTexts;
import dev.silencebound.maxtech.storage.devicetab.client.DeviceScreen;
import dev.silencebound.maxtech.storage.machine.MachineKind;
import dev.silencebound.maxtech.storage.machine.MachineLayout;
import dev.silencebound.maxtech.storage.machine.MachineTexts;
import dev.silencebound.maxtech.storage.machine.ModuleContent;
import dev.silencebound.maxtech.storage.machine.StorageMachineMenu;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * The GUI of every Storage machine in the Accent Frame style: slots, a progress track (the generator's burn), the FE
 * gauge, and for machines with modes the mode tab (B09 item 21b) with the mode's name shown under the progress track.
 * Everything it shows comes from the menu's data slots. Client only.
 */
public final class StorageMachineScreen extends DeviceScreen<StorageMachineMenu> {
    private final MachineLayout layout;

    public StorageMachineScreen(StorageMachineMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, MachineLayout.WIDTH, MachineLayout.HEIGHT);
        this.layout = MachineLayout.of(menu.kind());
        this.inventoryLabelY = MachineLayout.INVENTORY_Y - 11;
        this.titleLabelY = -100;
        if (!menu.kind().modes().isEmpty()) {
            // B09 item 21b: the mode is in the mode tab.
            tabs.mode().setting(Component.translatable(DeviceTabTexts.MODE_CAPTION_KEY), this::modeName,
                    () -> MenuClick.send(menu, StorageMachineMenu.CYCLE_MODE), null, menu::modeIndex,
                    index -> modeName());
            tabs.modeChanged(() -> menu.modeIndex() != 0);
        }
    }

    @Override
    protected void init() {
        super.init();
        if (menu.kind().processes()) {
            // The label first in the tooltip: a narrow button may show it shortened.
            addRenderableWidget(MenuClick.button(leftPos + MachineLayout.EXPORT_X, topPos + MachineLayout.EXPORT_Y,
                    MachineLayout.EXPORT_WIDTH, MachineLayout.EXPORT_HEIGHT,
                    () -> Component.translatable(MachineTexts.EXPORT_KEY), menu, StorageMachineMenu.EXPORT_PATTERN)
                    .enabled(menu::exportable)
                    .tooltip(CommonComponents.joinLines(Component.translatable(MachineTexts.EXPORT_KEY),
                            Component.translatable(MachineTexts.EXPORT_TOOLTIP_KEY))));
        }
    }

    @Override
    protected void extractPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        AccentFrame.panel(graphics, font, leftPos, topPos, imageWidth, imageHeight, title, mouseX, mouseY);
        for (Slot slot : menu.slots) {
            if (slot.isActive()) {
                AccentFrame.slot(graphics, leftPos + slot.x, topPos + slot.y);
            }
        }
        if (!menu.kind().modes().isEmpty()) {
            Component mode = modeName();
            int modeWidth = Math.min(font.width(mode), MachineLayout.BUTTON_WIDTH);
            AccentFrame.text(graphics, font, mode,
                    leftPos + layout.buttonX() + (MachineLayout.BUTTON_WIDTH - modeWidth) / 2,
                    topPos + layout.buttonY() + 2, modeWidth, AccentFrame.LABEL, mouseX, mouseY);
        }
        float progress = menu.duration() > 0 ? (float) menu.progress() / menu.duration() : 0.0F;
        if (menu.kind() == MachineKind.FUEL_GENERATOR) {
            // The burn left, draining like a furnace flame, beside the fuel slot.
            AccentFrame.verticalBar(graphics, leftPos + layout.inputX(0) + 22, topPos + layout.inputY(0) - 1, 4, 18,
                    menu.duration() > 0 ? 1.0F - progress : 0.0F);
        } else {
            AccentFrame.horizontalBar(graphics, leftPos + layout.arrowX(), topPos + layout.arrowY(),
                    MachineLayout.ARROW_WIDTH, MachineLayout.ARROW_HEIGHT, progress);
        }
        AccentFrame.verticalBar(graphics, leftPos + MachineLayout.ENERGY_X, topPos + MachineLayout.ENERGY_Y,
                MachineLayout.ENERGY_WIDTH, MachineLayout.ENERGY_HEIGHT,
                menu.capacity() > 0 ? (float) menu.energy() / menu.capacity() : 0.0F);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        // The state sits on the inventory label's line, right-aligned, clear of every layout's slots.
        Component state = Component.translatable(menu.state().translationKey());
        int stateLeft = inventoryLabelX + font.width(playerInventoryTitle) + 6;
        int stateWidth = Math.min(font.width(state), imageWidth - 8 - stateLeft);
        AccentFrame.text(graphics, font, state, imageWidth - 8 - stateWidth, inventoryLabelY + 1, stateWidth,
                AccentFrame.TEXT, xm, ym);
        TextScale.text(graphics, font, playerInventoryTitle, inventoryLabelX, inventoryLabelY + 1, AccentFrame.LABEL,
                false);
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractTooltip(graphics, mouseX, mouseY);
        Slot hovered = hoveredSlot;
        if (hovered != null && !hovered.hasItem() && menu.getCarried().isEmpty() && menu.isModuleSlot(hovered)) {
            ScreenComponents.tooltip(graphics, font, List.of(Component.translatable(ModuleContent.SLOT_KEY)), mouseX,
                    mouseY);
            return;
        }
        if (hovered != null || !menu.getCarried().isEmpty()) {
            return;
        }
        if (menu.kind().processes()
                && ScreenComponents.isHovering(leftPos + MachineLayout.EXPORT_X, topPos + MachineLayout.EXPORT_Y,
                        MachineLayout.EXPORT_WIDTH, MachineLayout.EXPORT_HEIGHT, mouseX, mouseY)) {
            // The export button has its own tooltip.
            return;
        }
        if (ScreenComponents.isHovering(leftPos + MachineLayout.ENERGY_X, topPos + MachineLayout.ENERGY_Y,
                MachineLayout.ENERGY_WIDTH, MachineLayout.ENERGY_HEIGHT, mouseX, mouseY)) {
            ScreenComponents.tooltip(graphics, font,
                    List.of(Component.translatable(MachineTexts.ENERGY_KEY, menu.energy(), menu.capacity())), mouseX,
                    mouseY);
        } else if (menu.kind() != MachineKind.FUEL_GENERATOR
                && ScreenComponents.isHovering(leftPos + layout.arrowX(), topPos + layout.arrowY() - 2,
                        MachineLayout.ARROW_WIDTH, MachineLayout.ARROW_HEIGHT + 4, mouseX, mouseY)) {
            ScreenComponents.tooltip(graphics, font,
                    List.of(Component.translatable(MachineTexts.PROGRESS_KEY, menu.progress(), menu.duration())),
                    mouseX, mouseY);
        }
    }

    private Component modeName() {
        List<String> modes = menu.kind().modes();
        int index = Math.clamp(menu.modeIndex(), 0, modes.size() - 1);
        return Component.translatable(menu.kind().modeKey(modes.get(index)));
    }
}
