package dev.silencebound.maxtech.storage.world.client;

import net.minecraft.network.chat.CommonComponents;
import dev.silencebound.maxtech.storage.devicetab.DeviceTabTexts;
import dev.silencebound.maxtech.storage.devicetab.client.DeviceScreen;
import dev.silencebound.maxtech.storage.machine.client.MenuClick;
import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.lib.client.gui.ScreenComponents;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.world.WorldContent;
import dev.silencebound.maxtech.storage.world.WorldFilter;
import dev.silencebound.maxtech.storage.world.WorldKind;
import dev.silencebound.maxtech.storage.world.WorldMenu;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A world device's settings in the Accent Frame style: the 3 x 3 ghost filter with a mark on slots that match a tag,
 * the mode's name and the placer's action (changed in the mode tab, B09 items 21b and 25), an "Open" button for a
 * device that holds something (the network chest), the tool slot, and an amber line in place of the inventory label
 * while the owner's team lacks the device's research. Everything it shows comes from the menu. Client only.
 */
public final class WorldScreen extends DeviceScreen<WorldMenu> {
    private static final int BUTTON_X = 8;
    private static final int BUTTON_WIDTH = 48;
    private static final int BUTTON_HEIGHT = 16;
    private static final int AMBER = 0xFFC48A3A;

    public WorldScreen(WorldMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WorldMenu.WIDTH, WorldMenu.HEIGHT);
        this.inventoryLabelY = WorldMenu.INVENTORY_Y - 11;
        this.titleLabelY = -100;
        WorldKind kind = menu.kind();
        if (kind.modeCount() > 1) {
            // B09 item 21b: the mode (pulse or continuous, or the chest's rows) is in the mode tab.
            tabs.mode().setting(Component.translatable(DeviceTabTexts.MODE_CAPTION_KEY),
                    () -> Component.translatable(kind.modeKey(menu.mode())), () -> step(WorldMenu.CYCLE_MODE, 1),
                    () -> step(WorldMenu.CYCLE_MODE, kind.modeCount() - 1), menu::mode,
                    mode -> kind.redstoneModes()
                            ? CommonComponents.joinLines(Component.translatable(kind.modeKey(mode)),
                                    Component.translatable(kind.modeHintKey(mode)))
                            : Component.translatable(kind.modeKey(mode)));
        }
        if (kind.actionCount() > 1) {
            // B09 item 25: the placer places blocks or drops items; next to the mode in the mode tab.
            tabs.mode().setting(Component.translatable(WorldContent.ACTION_CAPTION_KEY),
                    () -> Component.translatable(kind.actionKey(menu.action())), () -> step(WorldMenu.CYCLE_ACTION, 1),
                    () -> step(WorldMenu.CYCLE_ACTION, kind.actionCount() - 1), menu::action,
                    action -> CommonComponents.joinLines(Component.translatable(kind.actionKey(action)),
                            Component.translatable(kind.actionHintKey(action))));
        }
        if (kind.modeCount() > 1 || kind.actionCount() > 1) {
            tabs.modeChanged(() -> menu.mode() != 0 || menu.action() != 0);
        }
    }

    /** The menu only cycles forward; a step back is count - 1 steps forward. */
    private void step(int button, int steps) {
        for (int i = 0; i < steps; i++) {
            MenuClick.send(menu, button);
        }
    }

    @Override
    protected void init() {
        super.init();
        WorldKind kind = menu.kind();
        int y = WorldMenu.FILTER_Y;
        if (kind.modeCount() > 1) {
            // The mode's name, where its button was; the mode tab changes it.
            y += BUTTON_HEIGHT + 4;
        }
        if (kind.hasContents()) {
            addRenderableWidget(MenuClick.button(leftPos + BUTTON_X, topPos + y, BUTTON_WIDTH, BUTTON_HEIGHT,
                    () -> Component.translatable(WorldContent.CONTENTS_KEY), menu, WorldMenu.OPEN_CONTENTS));
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
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        TextScale.text(graphics, font, Component.translatable(WorldContent.FILTER_LABEL_KEY), WorldMenu.FILTER_X,
                WorldMenu.FILTER_Y - 11, AccentFrame.LABEL, false);
        if (menu.hasTool()) {
            TextScale.text(graphics, font, Component.translatable(WorldContent.TOOL_LABEL_KEY), WorldMenu.TOOL_X,
                    WorldMenu.TOOL_Y - 11, AccentFrame.LABEL, false);
        }
        if (menu.kind().modeCount() > 1) {
            AccentFrame.text(graphics, font, Component.translatable(menu.kind().modeKey(menu.mode())), BUTTON_X,
                    WorldMenu.FILTER_Y + 4, WorldMenu.FILTER_X - 4 - BUTTON_X, AccentFrame.TEXT, xm, ym);
        }
        if (menu.kind().actionCount() > 1) {
            AccentFrame.text(graphics, font, Component.translatable(menu.kind().actionKey(menu.action())), BUTTON_X,
                    WorldMenu.FILTER_Y + 4 + BUTTON_HEIGHT + 4, WorldMenu.FILTER_X - 4 - BUTTON_X, AccentFrame.TEXT, xm,
                    ym);
        }
        for (int i = 0; i < WorldFilter.SIZE; i++) {
            if (menu.tagIndex(i) != WorldFilter.EXACT) {
                Slot slot = menu.slots.get(i);
                TextScale.text(graphics, font, "#", slot.x + 11, slot.y - 1, AccentFrame.TEXT, true);
            }
        }
        if (menu.researched()) {
            TextScale.text(graphics, font, playerInventoryTitle, inventoryLabelX, inventoryLabelY + 1,
                    AccentFrame.LABEL, false);
        } else {
            AccentFrame.text(graphics, font, Component.translatable(WorldContent.NEEDS_RESEARCH_LABEL_KEY, title),
                    inventoryLabelX, inventoryLabelY + 1, imageWidth - 2 * inventoryLabelX, AMBER, xm, ym);
        }
    }

    /** Over a named filter slot: the tag it matches and how to change it; over a blank chest filter: what it does. */
    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractTooltip(graphics, mouseX, mouseY);
        if (!menu.getCarried().isEmpty()) {
            return;
        }
        boolean blank = true;
        for (int i = 0; i < WorldFilter.SIZE; i++) {
            Slot slot = menu.slots.get(i);
            ItemStack stack = slot.getItem();
            blank &= stack.isEmpty();
            if (!stack.isEmpty() && isHovering(slot.x, slot.y, 16, 16, mouseX, mouseY)) {
                int index = menu.tagIndex(i);
                List<TagKey<Item>> tags = WorldFilter.tagsOf(stack);
                Component line = index >= 0 && index < tags.size()
                        ? Component.translatable(WorldContent.MATCHES_TAG_KEY, tags.get(index).location().toString())
                        : Component.translatable(WorldContent.TAG_HINT_KEY);
                ScreenComponents.tooltip(graphics, font, List.of(stack.getHoverName(), line), mouseX, mouseY);
                return;
            }
        }
        if (blank && menu.kind() == WorldKind.NETWORK_CHEST && ScreenComponents.isHovering(leftPos + WorldMenu.FILTER_X,
                topPos + WorldMenu.FILTER_Y, 54, 54, mouseX, mouseY)) {
            ScreenComponents.tooltip(graphics, font, List.of(Component.translatable(WorldContent.BLANK_CHEST_KEY)),
                    mouseX, mouseY);
        }
    }
}
