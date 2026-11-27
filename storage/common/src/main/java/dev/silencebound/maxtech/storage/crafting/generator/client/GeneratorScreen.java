package dev.silencebound.maxtech.storage.crafting.generator.client;

import org.jspecify.annotations.Nullable;
import dev.silencebound.maxtech.storage.crafting.job.JobNotice;
import dev.silencebound.maxtech.lib.client.gui.accent.SideTabs;
import dev.silencebound.maxtech.storage.devicetab.client.DeviceScreen;
import net.minecraft.network.chat.CommonComponents;
import dev.silencebound.maxtech.storage.machine.client.MenuClick;
import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.storage.crafting.generator.GeneratorContent;
import dev.silencebound.maxtech.storage.crafting.generator.GeneratorMenu;
import dev.silencebound.maxtech.storage.crafting.provider.StuckReason;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * The generator provider's GUI in the Accent Frame style: the item and fluid ghost slots, the signal strength (minus,
 * value, plus) and side buttons, the state lines and the priority tab. Everything it shows comes from the menu. Client
 * only.
 */
public final class GeneratorScreen extends DeviceScreen<GeneratorMenu> {
    private static final int SMALL = 14;
    private static final int STRENGTH_WIDTH = 44;
    private static final int SIDES_X = GeneratorMenu.ITEM_X + SMALL * 2 + STRENGTH_WIDTH + 8;
    private static final int SIDES_WIDTH = 80;

    public GeneratorScreen(GeneratorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, GeneratorMenu.WIDTH, GeneratorMenu.HEIGHT);
        tabs.priority(menu, () -> true).badge(() -> menu.stuckCode() * 2 + (menu.claimBlocked() ? 1 : 0), this::badge);
        this.inventoryLabelY = GeneratorMenu.INVENTORY_Y - 11;
        this.titleLabelY = -100;
    }

    /**
     * The generator's badge (B09 item 21d): red for a land claim or a stuck reason, amber for no power; the device tabs
     * build it again only when the menu's stuck or claim data slot changes.
     */
    private SideTabs.@Nullable Badge badge() {
        StuckReason reason = menu.stuck().orElse(null);
        boolean claim = menu.claimBlocked();
        JobNotice.Alert alert = claim
                ? JobNotice.Alert.ERROR
                : JobNotice.stuck(reason != null, reason == StuckReason.NO_POWER);
        if (alert == JobNotice.Alert.NONE) {
            return null;
        }
        Component why = claim || reason == null
                ? Component.translatable(NetworkInfo.CLAIM_BLOCKED_KEY)
                : Component.translatable(GeneratorContent.STUCK_KEY, Component.translatable(reason.translationKey()));
        return new SideTabs.Badge(alert == JobNotice.Alert.ERROR ? SideTabs.Level.ERROR : SideTabs.Level.WARN, why);
    }

    private Component sidesLabel() {
        return Component
                .translatable(menu.allSides() ? GeneratorContent.SIDES_ALL_KEY : GeneratorContent.SIDES_FRONT_KEY);
    }

    private int minusX() {
        return GeneratorMenu.ITEM_X;
    }

    private int plusX() {
        return GeneratorMenu.ITEM_X + SMALL + STRENGTH_WIDTH;
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
        int width = imageWidth - 2 * GeneratorMenu.ITEM_X;
        AccentFrame.text(graphics, font, Component.translatable(GeneratorContent.HINT_KEY), GeneratorMenu.ITEM_X, 12,
                width, AccentFrame.LABEL, xm, ym);
        TextScale.text(graphics, font, Component.translatable(GeneratorContent.ITEM_KEY), GeneratorMenu.ITEM_X + 20,
                GeneratorMenu.SLOT_Y + 5, AccentFrame.TEXT, false);
        TextScale.text(graphics, font, Component.translatable(GeneratorContent.FLUID_KEY), GeneratorMenu.FLUID_X + 20,
                GeneratorMenu.SLOT_Y + 5, AccentFrame.TEXT, false);
        Component strength = Component.translatable(GeneratorContent.STRENGTH_KEY, menu.strength());
        TextScale.text(graphics, font, strength, minusX() + SMALL + (STRENGTH_WIDTH - font.width(strength)) / 2,
                GeneratorMenu.BUTTON_Y + 3, AccentFrame.TEXT, false);
        int y = GeneratorMenu.STATE_Y;
        Component state = menu.remaining() > 0
                ? Component.translatable(GeneratorContent.WORKING_KEY, menu.remaining())
                : Component.translatable(GeneratorContent.IDLE_KEY);
        AccentFrame.text(graphics, font, state, GeneratorMenu.ITEM_X, y, width, AccentFrame.TEXT, xm, ym);
        Component rate = menu.ratePerMinute() > 0
                ? Component.translatable(GeneratorContent.RATE_KEY, menu.ratePerMinute())
                : Component.translatable(GeneratorContent.RATE_UNKNOWN_KEY);
        AccentFrame.text(graphics, font, rate, GeneratorMenu.ITEM_X, y + 11, width, AccentFrame.LABEL, xm, ym);
        Optional<StuckReason> stuck = menu.stuck();
        if (stuck.isPresent()) {
            AccentFrame.text(graphics, font,
                    Component.translatable(GeneratorContent.STUCK_KEY,
                            Component.translatable(stuck.get().translationKey())),
                    GeneratorMenu.ITEM_X, y + 22, width, AccentFrame.TEXT, xm, ym);
        }
        Component bottom = menu.claimBlocked()
                ? Component.translatable(NetworkInfo.CLAIM_BLOCKED_KEY)
                : playerInventoryTitle;
        AccentFrame.text(graphics, font, bottom, inventoryLabelX, inventoryLabelY + 1, imageWidth - 2 * inventoryLabelX,
                AccentFrame.LABEL, xm, ym);
    }

    @Override
    protected void init() {
        super.init();
        int y = topPos + GeneratorMenu.BUTTON_Y;
        addRenderableWidget(MenuClick.button(leftPos + minusX(), y, SMALL, SMALL, () -> Component.literal("-"), menu,
                GeneratorMenu.STRENGTH_DOWN));
        addRenderableWidget(MenuClick.button(leftPos + plusX(), y, SMALL, SMALL, () -> Component.literal("+"), menu,
                GeneratorMenu.STRENGTH_UP));
        addRenderableWidget(MenuClick
                .button(leftPos + SIDES_X, y, SIDES_WIDTH, SMALL, this::sidesLabel, menu, GeneratorMenu.TOGGLE_SIDES)
                .tooltip(() -> menu.allSides() ? 1 : 0, sides -> CommonComponents.joinLines(sidesLabel(),
                        Component.translatable(GeneratorContent.SIDES_HINT_KEY))));
    }

}
