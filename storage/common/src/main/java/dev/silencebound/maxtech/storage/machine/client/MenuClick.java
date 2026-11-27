package dev.silencebound.maxtech.storage.machine.client;

import dev.silencebound.maxtech.lib.api.client.gui.AccentButton;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * Menu button clicks from Storage's screens: most of their buttons only ask the server to act on the open menu
 * ({@code clickMenuButton}), so the logic stays in the menu. Client only.
 */
public final class MenuClick {
    private MenuClick() {
    }

    /** Sends menu button {@code id} for {@code menu} to the server. */
    public static void send(AbstractContainerMenu menu, int id) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    /** An Accent button with a label read every frame that sends menu button {@code id}. */
    public static AccentButton button(int x, int y, int width, int height, Supplier<Component> label,
            AbstractContainerMenu menu, int id) {
        return new AccentButton(x, y, width, height, label.get(), () -> send(menu, id)).label(label);
    }
}
