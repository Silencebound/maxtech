package dev.silencebound.maxtech.storage.crafting.provider.client;

import dev.silencebound.maxtech.storage.devicetab.client.DeviceScreen;
import net.minecraft.network.chat.CommonComponents;
import dev.silencebound.maxtech.storage.machine.client.MenuClick;
import dev.silencebound.maxtech.lib.client.gui.TextScale;
import dev.silencebound.maxtech.lib.client.gui.accent.Accent;
import dev.silencebound.maxtech.storage.crafting.provider.LockMode;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderLogic;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderMenu;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.jspecify.annotations.Nullable;

/**
 * The pattern provider's GUI in the Accent Frame style: the working pattern slots, a note when research opens more, the
 * blocking toggle and the crafts in flight; the lock mode sits in the mode tab. Everything it shows comes from the
 * menu. Client only.
 */
public final class ProviderScreen extends DeviceScreen<ProviderMenu> {
    private static final int BUTTON_WIDTH = 90;
    private static final int BUTTON_HEIGHT = 14;

    private @Nullable AbstractWidget blockingButton;

    public ProviderScreen(ProviderMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, ProviderMenu.WIDTH, ProviderMenu.HEIGHT);
        this.inventoryLabelY = ProviderMenu.INVENTORY_Y - 11;
        this.titleLabelY = -100;
        // The lock mode (B09 item 26) in the mode tab: left click steps forward, right click back.
        tabs.mode()
                .setting(Component.translatable(ProviderContent.LOCK_CAPTION_KEY),
                        () -> Component.translatable(menu.lockMode().translationKey()),
                        () -> MenuClick.send(menu, ProviderMenu.LOCK_NEXT),
                        () -> MenuClick.send(menu, ProviderMenu.LOCK_PREVIOUS), () -> menu.lockMode().id(),
                        id -> CommonComponents.joinLines(Component.translatable(LockMode.byId(id).translationKey()),
                                Component.translatable(LockMode.byId(id).hintKey()).withColor(Accent.LABEL)))
                .line(() -> menu.lockHolds()
                        ? Component.translatable(ProviderContent.LOCKED_NOW_KEY,
                                Component.translatable(menu.lockMode().translationKey()))
                        : null, () -> Accent.AMBER, () -> true);
        tabs.modeChanged(() -> menu.lockMode() != LockMode.NONE);
    }

    private Component blockingLabel() {
        return Component
                .translatable(menu.blocking() ? ProviderContent.BLOCKING_ON_KEY : ProviderContent.BLOCKING_OFF_KEY);
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
        TextScale.text(graphics, font, Component.translatable(ProviderContent.PATTERNS_KEY), ProviderMenu.SLOTS_X,
                ProviderMenu.SLOTS_Y - 11, AccentFrame.LABEL, false);
        AbstractWidget button = blockingButton;
        if (button != null) {
            // A crafter has no blocking mode; its flag arrives with the first data sync, after init.
            button.visible = !menu.crafter();
        }
        if (menu.usableSlots() < ProviderLogic.MAX_SLOTS && !menu.crafter()) {
            int row = (menu.usableSlots() + 8) / 9;
            if (row < 4) {
                AccentFrame.text(graphics, font, Component.translatable(ProviderContent.LOCKED_KEY),
                        ProviderMenu.SLOTS_X, ProviderMenu.SLOTS_Y + row * 18 + 4,
                        imageWidth - 2 * ProviderMenu.SLOTS_X, AccentFrame.LABEL, xm, ym);
            }
        }
        int flightX = menu.crafter() ? ProviderMenu.SLOTS_X : ProviderMenu.SLOTS_X + BUTTON_WIDTH + 6;
        AccentFrame.text(graphics, font, Component.translatable(ProviderContent.IN_FLIGHT_KEY, menu.inFlight()),
                flightX, ProviderMenu.BUTTON_Y + 3, imageWidth - 8 - flightX, AccentFrame.TEXT, xm, ym);
        Component bottom = menu.claimBlocked()
                ? Component.translatable(NetworkInfo.CLAIM_BLOCKED_KEY)
                : playerInventoryTitle;
        AccentFrame.text(graphics, font, bottom, inventoryLabelX, inventoryLabelY + 1, imageWidth - 2 * inventoryLabelX,
                AccentFrame.LABEL, xm, ym);
    }

    @Override
    protected void init() {
        super.init();
        blockingButton = addRenderableWidget(MenuClick
                .button(leftPos + ProviderMenu.SLOTS_X, topPos + ProviderMenu.BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT,
                        this::blockingLabel, menu, ProviderMenu.TOGGLE_BLOCKING)
                .tooltip(() -> menu.blocking() ? 1 : 0, blocking -> CommonComponents.joinLines(blockingLabel(),
                        Component.translatable(ProviderContent.BLOCKING_HINT_KEY))));
    }
}
