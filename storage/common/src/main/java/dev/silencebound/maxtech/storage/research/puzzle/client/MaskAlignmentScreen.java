package dev.silencebound.maxtech.storage.research.puzzle.client;

import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.research.puzzle.MaskAlignment;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleContent;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzlePayloads;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleTexts;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Mask alignment: the target pattern and the currently exposed one side by side, and below them each mask with its
 * Rotate (and Flip) button. In the exposed grid a cell open in both is cyan, one open only there amber, one the target
 * needs but a mask closes red. Keys 1 to 3 rotate a mask, with shift they flip it. Client only.
 */
final class MaskAlignmentScreen extends PuzzleScreen<MaskAlignment.Puzzle> {
    private static final int N = MaskAlignment.SIZE;
    private static final int BIG = 9;
    private static final int SMALL = 7;
    private static final int LABEL = 11;
    private static final int COLUMN = 64;
    private static final int BUTTON_WIDTH = 44;
    private static final int BUTTON_HEIGHT = 12;
    private static final int TOP_HEIGHT = LABEL + N * BIG + 8;

    MaskAlignmentScreen(PuzzlePayloads.Open open) {
        super(PuzzleContent.MASK_ALIGNMENT, open);
    }

    @Override
    protected int boardWidth() {
        return Math.max(2 * N * BIG + 24, puzzle.maskCount() * COLUMN);
    }

    @Override
    protected int boardHeight() {
        return TOP_HEIGHT + LABEL + N * SMALL + 4 + (puzzle.flipAllowed() ? 2 * BUTTON_HEIGHT + 2 : BUTTON_HEIGHT);
    }

    @Override
    protected void extractBoard(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY) {
        long target = puzzle.target();
        long exposed = puzzle.expose(config);
        int gridWidth = N * BIG;
        int targetX = x + boardWidth() / 2 - gridWidth - 12;
        int currentX = x + boardWidth() / 2 + 12;
        TextScale.text(graphics, font, Component.translatable(PuzzleTexts.MASK_TARGET), targetX, y, AccentFrame.LABEL,
                false);
        TextScale.text(graphics, font, Component.translatable(PuzzleTexts.MASK_CURRENT), currentX, y, AccentFrame.LABEL,
                false);
        for (int bit = 0; bit < N * N; bit++) {
            boolean wanted = (target >>> bit & 1) != 0;
            boolean open = (exposed >>> bit & 1) != 0;
            cell(graphics, targetX, y + LABEL, bit, BIG, wanted ? AccentFrame.ACCENT : AccentFrame.SLOT);
            int colour = open && wanted ? LIT : open ? AMBER : wanted ? RED : AccentFrame.SLOT;
            cell(graphics, currentX, y + LABEL, bit, BIG, colour);
        }

        int maskY = y + TOP_HEIGHT;
        for (int i = 0; i < puzzle.maskCount(); i++) {
            int columnX = maskColumnX(x, i);
            TextScale.text(graphics, font, Component.translatable(PuzzleTexts.MASK_LABEL, i + 1), columnX, maskY,
                    AccentFrame.LABEL, false);
            long oriented = MaskAlignment.transform(puzzle.mask(i), config[i]);
            for (int bit = 0; bit < N * N; bit++) {
                boolean open = (oriented >>> bit & 1) != 0;
                cell(graphics, columnX, maskY + LABEL, bit, SMALL, open ? AccentFrame.BEVEL : AccentFrame.OUTLINE);
            }
            int buttonY = maskY + LABEL + N * SMALL + 4;
            smallButton(graphics, columnX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT,
                    Component.translatable(PuzzleTexts.MASK_ROTATE), mouseX, mouseY);
            if (puzzle.flipAllowed()) {
                smallButton(graphics, columnX, buttonY + BUTTON_HEIGHT + 2, BUTTON_WIDTH, BUTTON_HEIGHT,
                        Component.translatable(PuzzleTexts.MASK_FLIP), mouseX, mouseY);
            }
        }
    }

    private static void cell(GuiGraphicsExtractor graphics, int x, int y, int bit, int size, int colour) {
        int cx = x + bit % N * size;
        int cy = y + bit / N * size;
        graphics.fill(cx, cy, cx + size, cy + size, AccentFrame.SLOT_EDGE);
        graphics.fill(cx + 1, cy + 1, cx + size - 1, cy + size - 1, colour);
    }

    private int maskColumnX(int boardX, int index) {
        int total = puzzle.maskCount() * COLUMN;
        return boardX + (boardWidth() - total) / 2 + index * COLUMN + (COLUMN - BUTTON_WIDTH) / 2;
    }

    @Override
    protected boolean clickBoard(int x, int y, MouseButtonEvent event) {
        int buttonY = TOP_HEIGHT + LABEL + N * SMALL + 4;
        for (int i = 0; i < puzzle.maskCount(); i++) {
            int columnX = maskColumnX(0, i);
            if (x < columnX || x >= columnX + BUTTON_WIDTH) {
                continue;
            }
            if (y >= buttonY && y < buttonY + BUTTON_HEIGHT) {
                rotate(i);
                return true;
            }
            int flipY = buttonY + BUTTON_HEIGHT + 2;
            if (puzzle.flipAllowed() && y >= flipY && y < flipY + BUTTON_HEIGHT) {
                flip(i);
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean keyBoard(KeyEvent event) {
        int digit = event.getDigit();
        if (digit < 1 || digit > puzzle.maskCount()) {
            return false;
        }
        if (event.hasShiftDown() && puzzle.flipAllowed()) {
            flip(digit - 1);
        } else {
            rotate(digit - 1);
        }
        return true;
    }

    private void rotate(int mask) {
        config[mask] = (config[mask] & 4) | (config[mask] + 1 & 3);
        changed();
    }

    private void flip(int mask) {
        config[mask] ^= 4;
        changed();
    }

    @Override
    protected Component progress() {
        int differ = Long.bitCount(puzzle.expose(config) ^ puzzle.target());
        return Component.translatable(PuzzleTexts.MASK_STATUS, differ);
    }

    @Override
    protected Component help() {
        return Component.translatable(puzzle.flipAllowed() ? PuzzleTexts.MASK_HELP_FLIP : PuzzleTexts.MASK_HELP);
    }
}
