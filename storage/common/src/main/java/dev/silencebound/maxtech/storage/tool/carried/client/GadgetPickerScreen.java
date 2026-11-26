package dev.silencebound.maxtech.storage.tool.carried.client;

import dev.silencebound.maxtech.lib.client.gui.TextScale;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.tool.carried.CarriedContent;
import dev.silencebound.maxtech.storage.tool.carried.CarriedGadget;
import dev.silencebound.maxtech.storage.tool.carried.CarriedOpen;
import dev.silencebound.maxtech.storage.tool.carried.GadgetPick;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The carried-gadget picker (owner decision 2026-10-09): a small Accent Frame panel listing the kinds of gadget the
 * player carries, each with its number, icon and name. A click on a row or its number key opens it
 * ({@link CarriedOpen.Request}) and closes the picker; Escape closes it. The server sends the choices only when more
 * than one kind is carried ({@link GadgetPick}). Does not pause the game. Client only.
 */
public final class GadgetPickerScreen extends Screen {
    private static final int WIDTH = 150;
    private static final int PAD = 6;
    private static final int TOP = 8;
    /** Room under the rows for the hint line. */
    private static final int HINT = 12;

    private final List<CarriedGadget> gadgets;
    private int left;
    private int top;

    private GadgetPickerScreen(List<CarriedGadget> gadgets) {
        super(Component.translatable(CarriedContent.PICKER_TITLE));
        this.gadgets = gadgets;
    }

    /** Shows the picker for the gadget indices the server sent, unless another screen is open meanwhile. */
    public static void show(List<Integer> indices) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.player == null) {
            return;
        }
        List<CarriedGadget> gadgets = new ArrayList<>(indices.size());
        for (int index : indices) {
            CarriedGadget gadget = CarriedGadget.byIndex(index);
            if (gadget != null && gadgets.size() < GadgetPick.MAX_ROWS) {
                gadgets.add(gadget);
            }
        }
        if (!gadgets.isEmpty()) {
            minecraft.setScreen(new GadgetPickerScreen(gadgets));
        }
    }

    private int panelHeight() {
        return TOP + GadgetPick.height(gadgets.size()) + HINT + PAD;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        left = (width - WIDTH) / 2;
        top = AccentFrame.top(height, panelHeight());
    }

    private int rowsTop() {
        return top + TOP;
    }

    private int rowAt(double mouseX, double mouseY) {
        if (mouseX < left + PAD || mouseX >= left + WIDTH - PAD) {
            return -1;
        }
        return GadgetPick.rowAt(mouseY - rowsTop(), gadgets.size());
    }

    private void choose(int row) {
        Platform.get().network().sendToServer(new CarriedOpen.Request(gadgets.get(row).index()));
        onClose();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int row = event.button() == 0 ? rowAt(event.x(), event.y()) : -1;
        if (row >= 0) {
            choose(row);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int row = GadgetPick.rowForKey(event.key(), gadgets.size());
        if (row >= 0) {
            choose(row);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        AccentFrame.panel(graphics, font, left, top, WIDTH, panelHeight(), title, mouseX, mouseY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        int hovered = rowAt(mouseX, mouseY);
        for (int i = 0; i < gadgets.size(); i++) {
            int y = rowsTop() + i * GadgetPick.ROW_HEIGHT;
            ItemStack icon = new ItemStack(gadgets.get(i).icon());
            AccentFrame.button(graphics, font, left + PAD, y, WIDTH - 2 * PAD, GadgetPick.ROW_HEIGHT - 2,
                    Component.empty(), i == hovered);
            TextScale.text(graphics, font, Component.literal(Integer.toString(i + 1)), left + PAD + 4, y + 5,
                    AccentFrame.LABEL, false);
            graphics.item(icon, left + PAD + 14, y + 1);
            TextScale.text(graphics, font, icon.getHoverName(), left + PAD + 34, y + 5, AccentFrame.TEXT, false);
        }
        TextScale.text(graphics, font, Component.translatable(CarriedContent.PICKER_HINT), left + PAD,
                rowsTop() + GadgetPick.height(gadgets.size()) + 2, AccentFrame.LABEL, false);
    }
}
