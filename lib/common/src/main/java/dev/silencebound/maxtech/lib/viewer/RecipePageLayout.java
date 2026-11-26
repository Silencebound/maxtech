package dev.silencebound.maxtech.lib.viewer;

/**
 * Where a machine recipe sits on a recipe viewer page, computed once per category from its slot and tank counts so
 * every recipe of the category looks the same. Positions are relative to the page's top left; slot positions are those
 * of the 16x16 ingredient (the frame is one pixel further out), as recipe viewers place them.
 *
 * <p>
 * The top row holds the inputs (items, then fluids, as slots), the progress arrow and the outputs, each group a grid of
 * one to three rows like the machine GUI's. Below it, text lines (energy per tick, duration, total, and a mode line for
 * categories whose machines have modes). The page is at least {@value #MIN_WIDTH} wide so the text fits, and the top
 * row is centred in it.
 */
public final class RecipePageLayout {
    /** Narrowest page; wide enough for a seven-digit energy total. */
    public static final int MIN_WIDTH = 150;
    public static final int SLOT_SIZE = 18;
    public static final int ARROW_WIDTH = 24;
    public static final int ARROW_HEIGHT = 17;
    public static final int LINE_HEIGHT = 10;

    private static final int GAP = 4;
    private static final int MAX_ROWS = 3;

    private final int width;
    private final int height;
    private final int[] inputX;
    private final int[] inputY;
    private final int[] outputX;
    private final int[] outputY;
    private final int arrowX;
    private final int arrowY;
    private final int textY;
    private final int textLines;

    private RecipePageLayout(int inputs, int outputs, int textLines) {
        if (inputs < 0 || outputs < 0 || textLines < 0) {
            throw new IllegalArgumentException("Counts must not be negative");
        }
        int inputColumns = columns(inputs);
        int outputColumns = columns(outputs);
        int rows = Math.max(1, Math.max(rows(inputs, inputColumns), rows(outputs, outputColumns)));
        int rowHeight = rows * SLOT_SIZE;
        int inputWidth = inputColumns * SLOT_SIZE;
        int outputWidth = outputColumns * SLOT_SIZE;
        int content = inputWidth + (inputWidth > 0 ? GAP : 0) + ARROW_WIDTH + (outputWidth > 0 ? GAP : 0) + outputWidth;
        this.width = Math.max(MIN_WIDTH, content);
        int x = (width - content) / 2;

        this.inputX = new int[inputs];
        this.inputY = new int[inputs];
        x = placeGrid(inputs, inputColumns, x, rowHeight, inputX, inputY);
        if (inputWidth > 0) {
            x += GAP;
        }
        this.arrowX = x;
        this.arrowY = (rowHeight - ARROW_HEIGHT) / 2;
        x += ARROW_WIDTH + (outputWidth > 0 ? GAP : 0);
        this.outputX = new int[outputs];
        this.outputY = new int[outputs];
        placeGrid(outputs, outputColumns, x, rowHeight, outputX, outputY);

        this.textLines = textLines;
        this.textY = rowHeight + GAP;
        this.height = textY + textLines * LINE_HEIGHT;
    }

    /**
     * The layout for {@code inputs} input slots (items and fluids together), {@code outputs} output slots and
     * {@code textLines} lines of text; throws {@link IllegalArgumentException} for negative counts.
     */
    public static RecipePageLayout of(int inputs, int outputs, int textLines) {
        return new RecipePageLayout(inputs, outputs, textLines);
    }

    /** One to three rows; the columns follow, so four slots make a 2x2 square (the machine GUI's rule). */
    private static int columns(int slots) {
        if (slots == 0) {
            return 0;
        }
        int rows = Math.min(MAX_ROWS, (slots + 2) / 3);
        return (slots + rows - 1) / rows;
    }

    private static int rows(int slots, int columns) {
        return columns == 0 ? 0 : (slots + columns - 1) / columns;
    }

    /** Fills a grid's slot positions from frame x {@code left}, centred in the row height; returns the x after it. */
    private static int placeGrid(int slots, int columns, int left, int rowHeight, int[] xs, int[] ys) {
        if (slots == 0) {
            return left;
        }
        int top = (rowHeight - rows(slots, columns) * SLOT_SIZE) / 2;
        for (int i = 0; i < slots; i++) {
            xs[i] = left + (i % columns) * SLOT_SIZE + 1;
            ys[i] = top + (i / columns) * SLOT_SIZE + 1;
        }
        return left + columns * SLOT_SIZE;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Input slot {@code index}: item inputs first, then fluid inputs. */
    public int inputX(int index) {
        return inputX[index];
    }

    public int inputY(int index) {
        return inputY[index];
    }

    /** Output slot {@code index}: item outputs first, then fluid outputs. */
    public int outputX(int index) {
        return outputX[index];
    }

    public int outputY(int index) {
        return outputY[index];
    }

    /** Top left of the progress arrow's {@value #ARROW_WIDTH}x{@value #ARROW_HEIGHT} box. */
    public int arrowX() {
        return arrowX;
    }

    public int arrowY() {
        return arrowY;
    }

    /** Top of text line {@code line}; lines start at the left edge. */
    public int lineY(int line) {
        return textY + line * LINE_HEIGHT;
    }

    public int textLines() {
        return textLines;
    }

    /** Width the text may use: the whole page. */
    public int textWidth() {
        return width;
    }
}
