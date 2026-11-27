package dev.silencebound.maxtech.storage.research.puzzle.client;

import dev.silencebound.maxtech.lib.api.client.gui.AccentButton;
import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.lib.client.gui.ScreenComponents;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzlePayloads;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleTexts;
import dev.silencebound.maxtech.storage.research.puzzle.PuzzleType;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The frame every puzzle screen shares, in the Accent Frame style: title tab, difficulty and move count, the board a
 * subclass draws, a help line, the status line and a Submit button that is active once the configuration solves the
 * puzzle locally. Submitting sends only the configuration; the server's answer arrives through {@link #showResult}.
 * After it accepts, the board is locked. Client only.
 *
 * @param <S>
 *            the puzzle the type generates
 */
public abstract class PuzzleScreen<S> extends Screen {
    protected static final int MARGIN = 8;
    protected static final int LIT = 0xFF7FD6E4;
    protected static final int DIM = 0xFF5A6067;
    protected static final int AMBER = 0xFFD89A3C;
    protected static final int GREEN = 0xFF5CAE7A;
    protected static final int RED = 0xFFD0645A;
    private static final int HEADER = 16;
    private static final int FOOTER = 50;
    private static final int HELP_LINES = 2;
    private static final int MIN_WIDTH = 230;
    private static final int BUTTON_WIDTH = 56;
    private static final int BUTTON_HEIGHT = 16;

    protected final PuzzleType<S> type;
    protected final S puzzle;
    /** The player's configuration, in the type's solution format; subclasses change it and call {@link #changed}. */
    protected final int[] config;
    private final int session;
    private final int difficulty;
    private int moves;
    private State state = State.OPEN;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;

    protected PuzzleScreen(PuzzleType<S> type, PuzzlePayloads.Open open) {
        super(Component.translatable(PuzzleTexts.title(type.id())));
        this.type = type;
        this.puzzle = type.generate(open.seed(), open.difficulty());
        this.config = type.start(puzzle);
        this.session = open.session();
        this.difficulty = open.difficulty();
    }

    protected abstract int boardWidth();

    protected abstract int boardHeight();

    /** Draws the board with its top left corner at ({@code x}, {@code y}); mouse coordinates are absolute. */
    protected abstract void extractBoard(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY);

    /** A click at ({@code x}, {@code y}) relative to the board; only called while the board is not locked. */
    protected abstract boolean clickBoard(int x, int y, MouseButtonEvent event);

    /** A key while the board is not locked. */
    protected boolean keyBoard(KeyEvent event) {
        return false;
    }

    /** The wheel over the board while it is not locked; {@code amount} is positive away from the player. */
    protected boolean scrollBoard(int x, int y, double amount) {
        return false;
    }

    /** What the status line says while unsolved. */
    protected abstract Component progress();

    /** How to play, in at most two lines. */
    protected abstract Component help();

    public final int session() {
        return session;
    }

    /** The server's answer to the last submission. */
    public final void showResult(boolean accepted) {
        state = accepted ? State.ACCEPTED : State.REJECTED;
    }

    protected final boolean locked() {
        return state == State.PENDING || state == State.ACCEPTED;
    }

    /** Counts a move and clears a refusal shown for an older configuration. */
    protected final void changed() {
        moves++;
        if (state == State.REJECTED) {
            state = State.OPEN;
        }
    }

    protected final boolean solvedLocally() {
        return type.validate(puzzle, config);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        panelWidth = Math.max(MIN_WIDTH, boardWidth() + 2 * MARGIN);
        panelHeight = HEADER + boardHeight() + FOOTER + MARGIN;
        left = (width - panelWidth) / 2;
        top = AccentFrame.top(height, panelHeight);
        addRenderableWidget(new AccentButton(buttonX(), buttonY(), BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.translatable(PuzzleTexts.SUBMIT), this::canSubmit, this::submit));
    }

    private int boardX() {
        return left + (panelWidth - boardWidth()) / 2;
    }

    private int boardY() {
        return top + HEADER;
    }

    private int buttonX() {
        return left + panelWidth - MARGIN - BUTTON_WIDTH;
    }

    private int buttonY() {
        return top + panelHeight - MARGIN - BUTTON_HEIGHT;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        AccentFrame.panel(graphics, font, left, top, panelWidth, panelHeight, title, mouseX, mouseY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        Component header = Component.translatable(PuzzleTexts.DIFFICULTY,
                Component.translatable(PuzzleTexts.difficulty(difficulty)));
        TextScale.text(graphics, font, header, left + MARGIN, top + 6, AccentFrame.LABEL, false);
        Component moveText = Component.translatable(PuzzleTexts.MOVES, moves);
        TextScale.text(graphics, font, moveText, left + panelWidth - MARGIN - font.width(moveText), top + 6,
                AccentFrame.LABEL, false);
        extractBoard(graphics, boardX(), boardY(), mouseX, mouseY);

        int helpY = boardY() + boardHeight() + 4;
        ScreenComponents.fittedLines(graphics, font, help(), left + MARGIN, helpY, panelWidth - 2 * MARGIN, HELP_LINES,
                10, AccentFrame.LABEL, mouseX, mouseY);

        Component status;
        int colour;
        switch (state) {
            case ACCEPTED -> {
                status = Component.translatable(PuzzleTexts.ACCEPTED);
                colour = GREEN;
            }
            case REJECTED -> {
                status = Component.translatable(PuzzleTexts.REJECTED);
                colour = RED;
            }
            case PENDING -> {
                status = Component.translatable(PuzzleTexts.WAITING);
                colour = AccentFrame.TEXT;
            }
            default -> {
                status = progress();
                colour = AccentFrame.TEXT;
            }
        }
        AccentFrame.text(graphics, font, status, left + MARGIN, buttonY() + 4, buttonX() - 6 - left - MARGIN, colour,
                mouseX, mouseY);
    }

    private boolean canSubmit() {
        return state != State.PENDING && state != State.ACCEPTED && solvedLocally();
    }

    private void submit() {
        state = State.PENDING;
        Platform.get().network().sendToServer(new PuzzlePayloads.Submit(session, IntArrayList.wrap(config.clone())));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int x = (int) Math.floor(event.x()) - boardX();
        int y = (int) Math.floor(event.y()) - boardY();
        if (!locked() && x >= 0 && y >= 0 && x < boardWidth() && y < boardHeight() && clickBoard(x, y, event)) {
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int x = (int) Math.floor(mouseX) - boardX();
        int y = (int) Math.floor(mouseY) - boardY();
        if (!locked() && x >= 0 && y >= 0 && x < boardWidth() && y < boardHeight() && scrollBoard(x, y, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!event.isEscape() && !locked() && keyBoard(event)) {
            return true;
        }
        return super.keyPressed(event);
    }

    /** A small flat button inside the board, cyan edged while hovered and not locked. */
    protected final void smallButton(GuiGraphicsExtractor graphics, int x, int y, int w, int h, Component label,
            int mouseX, int mouseY) {
        boolean hovered = !locked() && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        AccentFrame.button(graphics, font, x, y, w, h, label, hovered, mouseX, mouseY);
    }

    private enum State {
        OPEN, PENDING, ACCEPTED, REJECTED
    }
}
