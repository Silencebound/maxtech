package dev.silencebound.maxtech.storage.research.puzzle.client;

import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleContent;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzlePayloads;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleTexts;
import dev.silencebound.maxtech.storage.research.puzzle.SignalRouting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Signal routing: wire tiles on a grid, lit cyan where the source (amber square) reaches them; outputs are rings that
 * fill when lit. Left click turns a tile clockwise, right or shift click back; the arrow keys move a cursor and Enter
 * or Space turns the tile under it. Client only.
 */
final class SignalRoutingScreen extends PuzzleScreen<SignalRouting.Puzzle> {
    private static final int CELL = 20;
    private static final int WIRE = 4;
    private int cursor;

    SignalRoutingScreen(PuzzlePayloads.Open open) {
        super(PuzzleContent.SIGNAL_ROUTING, open);
        cursor = puzzle.source();
    }

    @Override
    protected int boardWidth() {
        return puzzle.size() * CELL;
    }

    @Override
    protected int boardHeight() {
        return puzzle.size() * CELL;
    }

    @Override
    protected void extractBoard(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY) {
        int n = puzzle.size();
        int[] tiles = puzzle.rotated(config);
        boolean[] lit = SignalRouting.lit(n, puzzle.source(), tiles);
        graphics.fill(x - 1, y - 1, x + n * CELL + 1, y + n * CELL + 1, AccentFrame.OUTLINE);
        int half = CELL / 2;
        for (int cell = 0; cell < tiles.length; cell++) {
            int cx = x + cell % n * CELL;
            int cy = y + cell / n * CELL;
            graphics.fill(cx + 1, cy + 1, cx + CELL - 1, cy + CELL - 1, AccentFrame.SLOT);
            int colour = lit[cell] ? AccentFrame.ACCENT : DIM;
            int mx = cx + half;
            int my = cy + half;
            int w = WIRE / 2;
            if ((tiles[cell] & 1) != 0) {
                graphics.fill(mx - w, cy + 1, mx + w, my + w, colour);
            }
            if ((tiles[cell] & 2) != 0) {
                graphics.fill(mx - w, my - w, cx + CELL - 1, my + w, colour);
            }
            if ((tiles[cell] & 4) != 0) {
                graphics.fill(mx - w, my - w, mx + w, cy + CELL - 1, colour);
            }
            if ((tiles[cell] & 8) != 0) {
                graphics.fill(cx + 1, my - w, mx + w, my + w, colour);
            }
            if (cell == puzzle.source()) {
                graphics.fill(mx - 4, my - 4, mx + 4, my + 4, AMBER);
            } else if (puzzle.isOutput(cell)) {
                graphics.outline(mx - 5, my - 5, 10, 10, lit[cell] ? LIT : 0xFF8A9096);
                graphics.fill(mx - 4, my - 4, mx + 4, my + 4, AccentFrame.SLOT);
                if (lit[cell]) {
                    graphics.fill(mx - 2, my - 2, mx + 2, my + 2, LIT);
                }
            }
            if (cell == cursor && !locked()) {
                graphics.outline(cx + 1, cy + 1, CELL - 2, CELL - 2, AccentFrame.ACCENT_DIM);
            }
        }
    }

    @Override
    protected boolean clickBoard(int x, int y, MouseButtonEvent event) {
        int n = puzzle.size();
        cursor = Math.min(n - 1, y / CELL) * n + Math.min(n - 1, x / CELL);
        turn(cursor, event.button() == 1 || event.hasShiftDown());
        return true;
    }

    @Override
    protected boolean keyBoard(KeyEvent event) {
        int n = puzzle.size();
        int x = cursor % n;
        int y = cursor / n;
        if (event.isLeft() && x > 0) {
            cursor--;
        } else if (event.isRight() && x < n - 1) {
            cursor++;
        } else if (event.isUp() && y > 0) {
            cursor -= n;
        } else if (event.isDown() && y < n - 1) {
            cursor += n;
        } else if (event.isSelection()) {
            turn(cursor, event.hasShiftDown());
        } else {
            return false;
        }
        return true;
    }

    private void turn(int cell, boolean back) {
        config[cell] = (config[cell] + (back ? 3 : 1)) % 4;
        changed();
    }

    @Override
    protected Component progress() {
        return Component.translatable(PuzzleTexts.ROUTING_STATUS, puzzle.litOutputs(config), puzzle.outputCount());
    }

    @Override
    protected Component help() {
        return Component.translatable(PuzzleTexts.ROUTING_HELP);
    }
}
