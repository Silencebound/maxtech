package dev.silencebound.maxtech.storage.crafting.stock.client;

import dev.silencebound.maxtech.storage.machine.client.MenuClick;
import dev.silencebound.maxtech.lib.client.gui.TextScale;
import dev.silencebound.maxtech.storage.crafting.stock.StockRule;
import dev.silencebound.maxtech.storage.devicetab.DeviceTab;
import dev.silencebound.maxtech.storage.devicetab.client.DeviceScreen;
import dev.silencebound.maxtech.storage.devicetab.client.SettingsFlyout;
import dev.silencebound.maxtech.storage.redstone.RedstoneTexts;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperMenu;
import dev.silencebound.maxtech.storage.crafting.stock.StockTexts;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.lib.client.gui.ScreenComponents;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The stock keeper's GUI in the Accent Frame style: the rule picker, the rule's item and its three amounts with the
 * step buttons; the selected rule's CPU tier and redstone mode are in the mode tab and its priority in the priority tab
 * (B09 item 21b). Everything shown comes from the menu's synced data; every button is a menu button click. Client only.
 */
public final class StockKeeperScreen extends DeviceScreen<StockKeeperMenu> {
    private static final int ROW_RULE = 16;
    private static final int ROW_FIELD = 58;
    private static final int ROW_STEPS = 76;
    private static final int HEIGHT = 16;
    /** The on/off button at the end of the item's row. */
    private static final int TOGGLE_X = 142;
    private static final int TOGGLE_WIDTH = 26;

    public StockKeeperScreen(StockKeeperMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, StockKeeperMenu.WIDTH, StockKeeperMenu.HEIGHT);
        this.inventoryLabelY = StockKeeperMenu.INVENTORY_Y - 11;
        this.titleLabelY = -100;
        // B09 item 21b: the selected rule's CPU tier and redstone mode in the mode tab, its priority in the priority
        // tab; each flyout names the rule it edits.
        tabs.mode().line(this::ruleLine, () -> AccentFrame.LABEL, () -> true)
                .setting(Component.translatable(StockTexts.CPU_CAPTION), this::cpuName,
                        () -> MenuClick.send(menu, StockKeeperMenu.CYCLE_CPU), null, menu::cpuTier, tier -> cpuName())
                .setting(Component.translatable(RedstoneTexts.CAPTION_KEY),
                        () -> Component.translatable(menu.redstone().translationKey()),
                        () -> MenuClick.send(menu, StockKeeperMenu.CYCLE_REDSTONE), null, () -> menu.redstone().id(),
                        id -> Component.translatable(StockRule.Redstone.byId(id).translationKey()));
        tabs.modeChanged(() -> menu.cpuTier() != 0 || menu.redstone() != StockRule.Redstone.ALWAYS);
        tabs.priority(
                new SettingsFlyout().line(this::ruleLine, () -> AccentFrame.LABEL, () -> true).setting(
                        Component.translatable(DeviceTab.PRIORITY.titleKey()),
                        () -> Component.literal(Integer.toString(menu.priority())),
                        () -> MenuClick.send(menu, StockKeeperMenu.PRIORITY_UP),
                        () -> MenuClick.send(menu, StockKeeperMenu.PRIORITY_DOWN), () -> 0,
                        ignored -> Component.translatable(StockTexts.PRIORITY_HINT)),
                () -> true, () -> menu.priority() != 0);
    }

    private Component ruleLine() {
        return Component.translatable(StockTexts.RULE, menu.selected() + 1, menu.ruleSlots());
    }

    private Component cpuName() {
        return menu.cpuTier() == 0
                ? Component.translatable(StockTexts.CPU_ANY)
                : Component.translatable(StockTexts.CPU_TIER, menu.cpuTier());
    }

    @Override
    protected void init() {
        super.init();
        add(8, ROW_RULE, 16, StockKeeperMenu.PREVIOUS_RULE, () -> Component.literal("<"));
        add(152, ROW_RULE, 16, StockKeeperMenu.NEXT_RULE, () -> Component.literal(">"));
        add(TOGGLE_X, StockKeeperMenu.SLOT_Y, TOGGLE_WIDTH, StockKeeperMenu.TOGGLE_ON,
                () -> Component.translatable(menu.switchedOff() ? StockTexts.OFF : StockTexts.ON));
        add(8, ROW_FIELD, 76, StockKeeperMenu.CYCLE_FIELD, () -> Component.translatable(menu.field().translationKey()));
        for (int i = 0; i < StockKeeperMenu.STEP_COUNT; i++) {
            String label = StockKeeperMenu.stepLabel(i);
            add(8 + i * 20, ROW_STEPS, 19, StockKeeperMenu.FIRST_STEP + i, () -> Component.literal(label));
        }
    }

    private void add(int x, int y, int width, int id, Supplier<Component> label) {
        addRenderableWidget(MenuClick.button(leftPos + x, topPos + y, width, HEIGHT, label, menu, id));
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
        // The rule's number between the < and > buttons.
        AccentFrame.text(graphics, font, Component.translatable(StockTexts.RULE, menu.selected() + 1, menu.ruleSlots()),
                30, ROW_RULE + 4, 152 - 4 - 30, AccentFrame.LABEL, xm, ym);
        // The state right-aligned on the item's row before the on/off button, the item's name in the room left of it.
        Component state = Component.translatable(menu.switchedOff()
                ? StockTexts.SWITCHED_OFF
                : menu.filling() ? StockTexts.FILLING : StockTexts.WAITING);
        int stateX = TOGGLE_X - 4 - font.width(state);
        TextScale.text(graphics, font, state, stateX, StockKeeperMenu.SLOT_Y + 4, AccentFrame.LABEL, false);
        ItemStack item = menu.ruleItem();
        AccentFrame.text(graphics, font,
                item.isEmpty() ? Component.translatable(StockTexts.NOTHING) : item.getHoverName(), 30,
                StockKeeperMenu.SLOT_Y + 4, stateX - 4 - 30, AccentFrame.LABEL, xm, ym);
        String batch = menu.batch() == 0
                ? Component.translatable(StockTexts.NO_LIMIT).getString()
                : InfoFormat.number(menu.batch());
        AccentFrame.text(graphics, font,
                Component.literal(
                        InfoFormat.number(menu.keep()) + " / " + InfoFormat.number(menu.restart()) + " / " + batch),
                90, ROW_FIELD + 4, imageWidth - 8 - 90, AccentFrame.LABEL, xm, ym);
        if (ScreenComponents.isHovering(leftPos + TOGGLE_X, topPos + StockKeeperMenu.SLOT_Y, TOGGLE_WIDTH, HEIGHT, xm,
                ym)) {
            ScreenComponents.tooltip(graphics, font, List.of(Component.translatable(StockTexts.TOGGLE_TIP)), xm, ym);
        }
        TextScale.text(graphics, font, playerInventoryTitle, inventoryLabelX, inventoryLabelY + 1, AccentFrame.LABEL,
                false);
    }
}
