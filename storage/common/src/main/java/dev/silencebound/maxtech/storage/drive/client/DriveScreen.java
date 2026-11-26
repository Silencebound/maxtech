package dev.silencebound.maxtech.storage.drive.client;

import dev.silencebound.maxtech.storage.devicetab.client.DeviceScreen;
import dev.silencebound.maxtech.lib.client.gui.TextScale;
import dev.silencebound.maxtech.storage.drive.CellTransfer;
import dev.silencebound.maxtech.storage.drive.DriveContent;
import dev.silencebound.maxtech.storage.cell.CellData;
import dev.silencebound.maxtech.storage.cell.StorageCellItem;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.drive.DriveMenu;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The drive's screen in the Accent Frame style: the eight cell slots in two rows, each with a gauge of how full its
 * cell is (from the cell item's cached stats, which the drive keeps current while the screen is open) and its two
 * transfer buttons (M7C item 12: empty the cell into the network, fill it from the network; a running one is lit and
 * stops on a second click, and the tooltip says how far it is or why it ended), the inventory and the priority tab.
 * Client only.
 */
public final class DriveScreen extends DeviceScreen<DriveMenu> {
    private static final int GAUGE_WIDTH = 4;

    public DriveScreen(DriveMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, DriveMenu.WIDTH, DriveMenu.HEIGHT);
        tabs.priority(menu, () -> true);
        this.titleLabelY = -100;
        this.inventoryLabelX = DriveMenu.INVENTORY_X;
        this.inventoryLabelY = DriveMenu.INVENTORY_Y - 11;
    }

    @Override
    protected void init() {
        super.init();
        for (int i = 0; i < DriveBlockEntity.SLOTS; i++) {
            int x = leftPos + DriveMenu.slotX(i) + DriveMenu.TRANSFER_X;
            int y = topPos + DriveMenu.slotY(i);
            addRenderableWidget(new TransferButton(i, CellTransfer.EMPTY, x, y));
            addRenderableWidget(new TransferButton(i, CellTransfer.FILL, x, y + DriveMenu.TRANSFER_SIZE));
        }
    }

    @Override
    protected void extractPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        AccentFrame.panel(graphics, font, leftPos, topPos, imageWidth, imageHeight, title, mouseX, mouseY);
        for (Slot slot : menu.slots) {
            AccentFrame.slot(graphics, leftPos + slot.x, topPos + slot.y);
        }
        for (int i = 0; i < DriveBlockEntity.SLOTS; i++) {
            Slot slot = menu.slots.get(i);
            ItemStack stack = slot.getItem();
            CellData data = StorageCellItem.data(stack);
            float fraction = data == null || data.totalBytes() <= 0
                    ? 0.0F
                    : (float) ((double) data.usedBytes() / data.totalBytes());
            AccentFrame.verticalBar(graphics, leftPos + slot.x + 19, topPos + slot.y, GAUGE_WIDTH, 16, fraction);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        Component help = Component.translatable(DriveMenu.HELP_KEY);
        int room = imageWidth - 16;
        AccentFrame.text(graphics, font, help, Math.max(8, (imageWidth - font.width(help)) / 2),
                DriveMenu.CELLS_Y + 2 * DriveMenu.ROW_HEIGHT, room, AccentFrame.LABEL, xm, ym);
        TextScale.text(graphics, font, playerInventoryTitle, inventoryLabelX, inventoryLabelY + 1, AccentFrame.LABEL,
                false);
    }

    /** How full {@code slot}'s cell is in percent, from its item's stats; -1 without a cell. */
    private int percent(int slot) {
        CellData data = StorageCellItem.data(menu.slots.get(slot).getItem());
        return data == null ? -1 : data.totalBytes() <= 0 ? 0 : (int) (data.usedBytes() * 100 / data.totalBytes());
    }

    /**
     * One slot's "empty into network" or "fill from network" button, 8 x 8 beside its gauge: an arrow out of the cell
     * or into it, lit while that transfer runs, edged green or amber after it finished or stopped. Clicks go through
     * the vanilla menu button packet; the server checks access and toggles.
     */
    private final class TransferButton extends AbstractButton {
        private static final int DONE = 0xFF5CE08A;
        private static final int BLOCKED = 0xFFF0B44C;
        private final int slot;
        private final int mode;

        TransferButton(int slot, int mode, int x, int y) {
            super(x, y, DriveMenu.TRANSFER_SIZE, DriveMenu.TRANSFER_SIZE,
                    Component.translatable(mode == CellTransfer.EMPTY ? DriveMenu.EMPTY_KEY : DriveMenu.FILL_KEY));
            this.slot = slot;
            this.mode = mode;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            if (minecraft != null && minecraft.gameMode != null && menu.slots.get(slot).hasItem()) {
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, DriveMenu.transferButton(slot, mode));
            }
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            boolean cell = menu.slots.get(slot).hasItem();
            boolean running = menu.transferMode(slot) == mode;
            int result = running || menu.transferMode(slot) != CellTransfer.NONE ? CellTransfer.NONE : lastResult();
            int x = getX();
            int y = getY();
            int edge = result == CellTransfer.FINISHED
                    ? DONE
                    : result == CellTransfer.BLOCKED
                            ? BLOCKED
                            : isHovered() && cell ? AccentFrame.ACCENT : AccentFrame.SLOT_EDGE;
            graphics.fill(x, y, x + width, y + height, edge);
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, running ? AccentFrame.ACCENT : AccentFrame.SLOT);
            int ink = !cell ? AccentFrame.SLOT_EDGE : running ? AccentFrame.OUTLINE : AccentFrame.TEXT;
            // A three-row arrow: up (out of the cell) for emptying, down (into it) for filling.
            for (int row = 0; row < 3; row++) {
                int ry = mode == CellTransfer.EMPTY ? y + 2 + row : y + 4 - row;
                graphics.fill(x + 3 - row, ry, x + 5 + row, ry + 1, ink);
            }
            if (isHovered()) {
                graphics.setComponentTooltipForNextFrame(font, tooltip(running, result), mouseX, mouseY);
            }
        }

        /** This button's own last result: a result belongs to the mode that ended, which the client does not know. */
        private int lastResult() {
            return menu.transferResult(slot) != CellTransfer.NONE && menu.lastTransfer(slot) == mode
                    ? menu.transferResult(slot)
                    : CellTransfer.NONE;
        }

        private List<Component> tooltip(boolean running, int result) {
            List<Component> lines = new ArrayList<>(3);
            lines.add(getMessage());
            int percent = percent(slot);
            if (percent < 0) {
                lines.add(Component.translatable(DriveMenu.NO_CELL_KEY).withColor(AccentFrame.LABEL));
                return lines;
            }
            if (running) {
                lines.add(Component.translatable(DriveMenu.RUNNING_KEY, percent).withColor(AccentFrame.TEXT));
            } else if (result != CellTransfer.NONE) {
                String key = mode == CellTransfer.EMPTY
                        ? result == CellTransfer.FINISHED ? DriveMenu.EMPTY_DONE_KEY : DriveMenu.EMPTY_BLOCKED_KEY
                        : result == CellTransfer.FINISHED ? DriveMenu.FILL_DONE_KEY : DriveMenu.FILL_BLOCKED_KEY;
                lines.add(Component.translatable(key).withColor(result == CellTransfer.FINISHED ? DONE : BLOCKED));
            }
            if (mode == CellTransfer.FILL && !running) {
                lines.add(Component.translatable(DriveMenu.FILL_HINT_KEY).withColor(AccentFrame.LABEL));
            }
            return lines;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
