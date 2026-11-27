package dev.silencebound.maxtech.storage.research.puzzle.client;

import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.research.puzzle.BytePacking;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleContent;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzlePayloads;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleTexts;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Byte packing: the unplaced item kinds as chips (width = bytes) in a tray, the banks as bars below. Click a chip to
 * pick it, then a bank to store it there (refused when it does not fit); click a stored chip to take it back out. Left
 * and right pick the previous or next unplaced chip, a digit stores the picked one in that bank. Client only.
 */
final class BytePackingScreen extends PuzzleScreen<BytePacking.Puzzle> {
    private static final int[] COLOURS = {0xFF2A9FB4, 0xFFD89A3C, 0xFF5CAE7A, 0xFF9A7AD8, 0xFFC9C9CF, 0xFFD0645A,
            0xFF5F8FD8, 0xFFB8A14A};
    private static final int LABEL_WIDTH = 72;
    private static final int ROW = 16;
    private static final int CHIP = 14;
    private final int unit;
    private final int trayRows;
    private int selected = -1;

    BytePackingScreen(PuzzlePayloads.Open open) {
        super(PuzzleContent.BYTE_PACKING, open);
        unit = 160 / puzzle.capacity();
        trayRows = trayLayout(null, null, 0, 0, -1, -1);
    }

    @Override
    protected int boardWidth() {
        return LABEL_WIDTH + puzzle.capacity() * unit;
    }

    @Override
    protected int boardHeight() {
        return trayRows * ROW + 6 + puzzle.banks() * ROW;
    }

    @Override
    protected void extractBoard(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY) {
        trayLayout(graphics, null, x, y, mouseX, mouseY);
        int banksY = y + trayRows * ROW + 6;
        for (int bank = 0; bank < puzzle.banks(); bank++) {
            int rowY = banksY + bank * ROW;
            Component label = Component.translatable(PuzzleTexts.PACKING_BANK, bank + 1, puzzle.used(config, bank),
                    puzzle.capacity());
            TextScale.text(graphics, font, label, x, rowY + 3, AccentFrame.LABEL, false);
            int barX = x + LABEL_WIDTH;
            int barWidth = puzzle.capacity() * unit;
            boolean hovered = mouseX >= barX && mouseX < barX + barWidth && mouseY >= rowY && mouseY < rowY + CHIP;
            graphics.fill(barX - 1, rowY - 1, barX + barWidth + 1, rowY + CHIP + 1,
                    hovered && selected >= 0 ? AccentFrame.ACCENT : AccentFrame.SLOT_EDGE);
            graphics.fill(barX, rowY, barX + barWidth, rowY + CHIP, AccentFrame.SLOT);
            int at = barX;
            for (int item = 0; item < config.length; item++) {
                if (config[item] == bank) {
                    chip(graphics, item, at, rowY, false);
                    at += puzzle.size(item) * unit;
                }
            }
        }
    }

    /**
     * Lays out the unplaced chips over the tray rows, drawing them when {@code graphics} is set or reporting the chip
     * under ({@code px}, {@code py}) through {@code hit}; returns the rows used (at least one), or with every chip
     * unplaced, the most the tray ever needs.
     */
    private int trayLayout(@Nullable GuiGraphicsExtractor graphics, int @Nullable [] hit, int x, int y, int px,
            int py) {
        int width = boardWidth();
        int cx = 0;
        int row = 0;
        boolean layoutOnly = graphics == null && hit == null;
        for (int item = 0; item < puzzle.items(); item++) {
            if (!layoutOnly && config[item] != BytePacking.UNPLACED) {
                continue;
            }
            int w = puzzle.size(item) * unit;
            if (cx > 0 && cx + w > width) {
                cx = 0;
                row++;
            }
            int chipX = x + cx;
            int chipY = y + row * ROW;
            if (graphics != null) {
                chip(graphics, item, chipX, chipY, item == selected);
            } else if (hit != null && px >= chipX && px < chipX + w && py >= chipY && py < chipY + CHIP) {
                hit[0] = item;
            }
            cx += w + 2;
        }
        return row + 1;
    }

    private void chip(GuiGraphicsExtractor graphics, int item, int x, int y, boolean picked) {
        int w = puzzle.size(item) * unit;
        graphics.fill(x, y, x + w - 1, y + CHIP, picked ? LIT : AccentFrame.OUTLINE);
        graphics.fill(x + 1, y + 1, x + w - 2, y + CHIP - 1, COLOURS[item % COLOURS.length]);
        String size = Integer.toString(puzzle.size(item));
        if (font.width(size) <= w - 3) {
            TextScale.text(graphics, font, size, x + (w - 1 - font.width(size)) / 2, y + 3, AccentFrame.OUTLINE, false);
        }
    }

    @Override
    protected boolean clickBoard(int x, int y, MouseButtonEvent event) {
        int[] hit = {-1};
        trayLayout(null, hit, 0, 0, x, y);
        if (hit[0] >= 0) {
            selected = selected == hit[0] ? -1 : hit[0];
            return true;
        }
        int bank = (y - trayRows * ROW - 6) / ROW;
        int barX = x - LABEL_WIDTH;
        if (y < trayRows * ROW + 6 || bank >= puzzle.banks() || barX < 0) {
            return false;
        }
        int at = 0;
        for (int item = 0; item < config.length; item++) {
            if (config[item] == bank) {
                int w = puzzle.size(item) * unit;
                if (barX >= at && barX < at + w) {
                    config[item] = BytePacking.UNPLACED;
                    changed();
                    return true;
                }
                at += w;
            }
        }
        return place(bank);
    }

    private boolean place(int bank) {
        if (selected < 0 || puzzle.used(config, bank) + puzzle.size(selected) > puzzle.capacity()) {
            return false;
        }
        config[selected] = bank;
        selected = -1;
        changed();
        return true;
    }

    @Override
    protected boolean keyBoard(KeyEvent event) {
        if (event.isLeft() || event.isRight()) {
            int step = event.isRight() ? 1 : -1;
            for (int i = 1; i <= config.length; i++) {
                int item = Math.floorMod(selected + step * i, config.length);
                if (config[item] == BytePacking.UNPLACED) {
                    selected = item;
                    return true;
                }
            }
            return false;
        }
        int digit = event.getDigit();
        return digit >= 1 && digit <= puzzle.banks() && place(digit - 1);
    }

    @Override
    protected Component progress() {
        int left = 0;
        for (int bank : config) {
            if (bank == BytePacking.UNPLACED) {
                left++;
            }
        }
        return Component.translatable(PuzzleTexts.PACKING_STATUS, left);
    }

    @Override
    protected Component help() {
        return Component.translatable(PuzzleTexts.PACKING_HELP);
    }
}
