package dev.silencebound.maxtech.storage.terminal;

/**
 * Where the terminal GUI puts things, in GUI pixels from the panel's top left: the item grid, the crafting or pattern
 * terminal's panel under it, the status line and the player's inventory. Either panel takes the place of the item
 * grid's last {@link #PANEL_ROWS} rows, so those terminals are as high as a plain one and their base layout fits a 240
 * high window; a taller {@link TerminalSize} adds rows to the item grid only ({@link #extraRows}). The view filter
 * slots ({@link #filterX}) sit in a strip left of the panel, level with its bottom and under the saved search tabs.
 * Slot positions are an item's top left, one pixel inside its 18 x 18 cell.
 *
 * <p>
 * The pattern panel, left to right: the four mode tabs two by two with Substitutions and Advanced under them, the nine
 * inputs, the clear button over the encode arrow with x2 and /2 under it, the three outputs, and the advanced panel's
 * face, returned and tag buttons.
 *
 * @param extraRows
 *            rows a taller size adds (0 in the base layout; below 0 only in a window lower than the base layout)
 * @param panel
 *            whether a crafting or pattern panel is shown
 */
public record TerminalLayout(int extraRows, boolean panel) {
    public static final int WIDTH = 186;
    /** The hotbar ends 8 above the bottom edge, like vanilla's, so the base layout fits a 240 high window. */
    public static final int BASE_HEIGHT = 240;
    public static final int GRID_X = 8;
    public static final int GRID_Y = 24;
    public static final int COLUMNS = 9;
    public static final int BASE_ROWS = 6;
    public static final int CELL = 18;
    /** Item grid rows the crafting panel takes. */
    public static final int PANEL_ROWS = 3;
    public static final int INVENTORY_X = 12;
    public static final int BASE_INVENTORY_Y = 158;
    /** Size of the panel's buttons (Accent's button height). */
    public static final int BUTTON = 16;
    public static final int ARROW_WIDTH = 24;
    public static final int ARROW_HEIGHT = 6;
    /** Pattern slots below this are inputs ({@code PatternDraft.INPUTS}). */
    public static final int PATTERN_INPUTS = 9;
    /** The x of the pattern panel's first input, right of the mode tabs. */
    public static final int PATTERN_INPUT_X = GRID_X + 2 * CELL + 1;
    /** The pattern panel's encode arrow and the x2 and /2 buttons under it. */
    public static final int ENCODE_X = PATTERN_INPUT_X + 3 * CELL + 1;
    public static final int ENCODE_WIDTH = 34;
    public static final int SCALE_WIDTH = 16;
    public static final int SCALE_HEIGHT = 12;
    public static final int PATTERN_OUTPUT_X = ENCODE_X + ENCODE_WIDTH + 3;
    /** The advanced panel's column, right of the outputs. */
    public static final int EDIT_X = PATTERN_OUTPUT_X + CELL + 3;

    /** Rows {@link TerminalSize#TALL} adds to the base layout, when the window has room for them. */
    public static final int TALL_EXTRA_ROWS = 3;
    /** Fewest item grid rows any size keeps, even in a window lower than the base layout. */
    public static final int MIN_ITEM_ROWS = 1;
    /** View filter slots a terminal block or part has (B09 item 24). */
    public static final int FILTER_SLOTS = 4;
    /** The view filter strip's left edge, 2 left of the panel, and its size: one column of slots in a 4 px frame. */
    public static final int FILTER_STRIP_X = -(CELL + 8) - 2;
    public static final int FILTER_STRIP_WIDTH = CELL + 8;
    public static final int FILTER_STRIP_HEIGHT = FILTER_SLOTS * CELL + 8;

    /** The layout for {@code size} in a window {@code windowHeight} GUI pixels high. */
    public static TerminalLayout of(TerminalSize size, int windowHeight, boolean panel) {
        return new TerminalLayout(extraRows(size, windowHeight, panel), panel);
    }

    /**
     * The rows {@code size} adds to the base layout in a window {@code windowHeight} high, so the panel is never taller
     * than the window: small none, tall {@link #TALL_EXTRA_ROWS}, fill every row that fits; fewer when the window has
     * no room for them, and fewer than none when even the base layout is too tall, down to {@link #MIN_ITEM_ROWS} rows
     * of items (only then can the panel be taller than the window).
     */
    public static int extraRows(TerminalSize size, int windowHeight, boolean panel) {
        int fit = Math.floorDiv(windowHeight - BASE_HEIGHT, CELL);
        int wanted = switch (size) {
            case SMALL -> 0;
            case TALL -> TALL_EXTRA_ROWS;
            case FILL -> fit;
        };
        int least = MIN_ITEM_ROWS - BASE_ROWS + (panel ? PANEL_ROWS : 0);
        return Math.max(least, Math.min(wanted, fit));
    }

    /** The x of view filter slot {@code slot}, in the strip left of the panel. */
    public int filterX(int slot) {
        return FILTER_STRIP_X + 5;
    }

    /** The y of view filter slot {@code slot}: one column, the strip level with the panel's bottom. */
    public int filterY(int slot) {
        return filterStripY() + 5 + slot * CELL;
    }

    /** The top of the view filter strip. */
    public int filterStripY() {
        return height() - FILTER_STRIP_HEIGHT;
    }

    /** Rows of the item grid. */
    public int itemRows() {
        return BASE_ROWS + extraRows - (panel ? PANEL_ROWS : 0);
    }

    /** The panel's height. */
    public int height() {
        return BASE_HEIGHT + extraRows * CELL;
    }

    /** The y of the status line, under the item grid and the crafting panel. */
    public int statusY() {
        return GRID_Y + (BASE_ROWS + extraRows) * CELL + 4;
    }

    /** The y of the player's inventory. */
    public int inventoryY() {
        return BASE_INVENTORY_Y + extraRows * CELL;
    }

    /** The top of the crafting panel: the end of the item grid. */
    public int panelY() {
        return GRID_Y + itemRows() * CELL;
    }

    /** The x of the clear button, left of the crafting grid. */
    public int clearX() {
        return GRID_X;
    }

    public int clearY() {
        return panelY() + 1;
    }

    /** The x of crafting grid cell {@code cell} (0 to 8, row by row). */
    public int cellX(int cell) {
        return GRID_X + CELL + 1 + cell % 3 * CELL;
    }

    public int cellY(int cell) {
        return panelY() + 1 + cell / 3 * CELL;
    }

    /** The x of the arrow between the grid and the result. */
    public int arrowX() {
        return cellX(2) + CELL + 5;
    }

    public int arrowY() {
        return cellY(3) + 6;
    }

    public int resultX() {
        return arrowX() + ARROW_WIDTH + 7;
    }

    public int resultY() {
        return cellY(3);
    }

    /** The x of the request button, under the arrow and the result. */
    public int requestX() {
        return arrowX();
    }

    public int requestY() {
        return panelY() + 3 * CELL - BUTTON;
    }

    /** The room the request button has up to the panel's right edge. */
    public int requestWidth() {
        return WIDTH - 8 - requestX();
    }

    /** The x of pattern mode tab {@code tab} (crafting, processing, stonecutting, smithing), two by two. */
    public int modeX(int tab) {
        return GRID_X + tab % 2 * CELL;
    }

    public int modeY(int tab) {
        return panelY() + 1 + tab / 2 * CELL;
    }

    /** The x of the pattern panel's toggle {@code toggle} under the mode tabs: 0 Substitutions, 1 Advanced. */
    public int toggleX(int toggle) {
        return GRID_X + toggle * CELL;
    }

    public int toggleY() {
        return panelY() + 1 + 2 * CELL;
    }

    /** The x of pattern slot {@code slot}: inputs 0 to 8 row by row, outputs 9 to 11 down a column. */
    public int patternX(int slot) {
        return slot < PATTERN_INPUTS ? PATTERN_INPUT_X + slot % 3 * CELL : PATTERN_OUTPUT_X;
    }

    public int patternY(int slot) {
        return panelY() + 1 + (slot < PATTERN_INPUTS ? slot / 3 : slot - PATTERN_INPUTS) * CELL;
    }

    /** The x of the pattern panel's clear button, centred over the encode arrow. */
    public int patternClearX() {
        return ENCODE_X + (ENCODE_WIDTH - BUTTON) / 2;
    }

    public int patternClearY() {
        return panelY() + 1;
    }

    /** The y of the encode arrow, level with the middle row of inputs. */
    public int encodeY() {
        return panelY() + 1 + CELL;
    }

    /** The x of the x2 ({@code multiply}) or /2 button under the encode arrow. */
    public int scaleX(boolean multiply) {
        return multiply ? ENCODE_X : ENCODE_X + ENCODE_WIDTH - SCALE_WIDTH;
    }

    public int scaleY() {
        return panelY() + 3 + 2 * CELL;
    }

    /** The y of the advanced panel's button {@code row}: 0 face, 1 returned, 2 tag. */
    public int editY(int row) {
        return panelY() + 1 + row * CELL;
    }
}
