package dev.silencebound.maxtech.storage.tool.carried;

/**
 * The carried-gadget picker's rules (owner decision 2026-10-09): what one press of the gadget key does with the gadgets
 * found, and the picker's rows, mouse and number keys. Pure arithmetic over row counts; any thread.
 */
public final class GadgetPick {
    /** Most rows the picker shows; there are fewer gadget kinds than this. */
    public static final int MAX_ROWS = 9;
    /** Height of one row in pixels: an item and a gap. */
    public static final int ROW_HEIGHT = 20;
    /** The GLFW key code of the "1" key; "2" to "9" follow it. */
    public static final int KEY_1 = 49;

    /** What a press of the gadget key does. */
    public enum Outcome {
        /** Nothing carried: say so. */
        NONE,
        /** One carried: open it at once. */
        OPEN,
        /** Several carried: show the picker. */
        CHOOSE
    }

    private GadgetPick() {
    }

    /** The outcome for {@code found} kinds of gadget carried. */
    public static Outcome outcome(int found) {
        return found <= 0 ? Outcome.NONE : found == 1 ? Outcome.OPEN : Outcome.CHOOSE;
    }

    /** The row index at {@code y} pixels below the first row's top, or -1 above or below the {@code rows} rows. */
    public static int rowAt(double y, int rows) {
        if (y < 0) {
            return -1;
        }
        int row = (int) (y / ROW_HEIGHT);
        return row < Math.min(rows, MAX_ROWS) ? row : -1;
    }

    /** The row the number key {@code key} (GLFW code) picks among {@code rows}, or -1 for another key. */
    public static int rowForKey(int key, int rows) {
        int row = key - KEY_1;
        return row >= 0 && row < Math.min(rows, MAX_ROWS) ? row : -1;
    }

    /** The picker's inner height for {@code rows} rows. */
    public static int height(int rows) {
        return Math.min(rows, MAX_ROWS) * ROW_HEIGHT;
    }
}
