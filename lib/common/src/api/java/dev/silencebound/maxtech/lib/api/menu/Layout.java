package dev.silencebound.maxtech.lib.api.menu;

import java.util.List;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * Where a {@link MenuDefinition}'s parts sit inside its panel: a tree of rows and columns over fixed-size leaves, in
 * GUI pixels. Every leaf has a size of its own that no text changes (labels and status lines get a fixed width and fit
 * their text into it, showing the whole text as a tooltip), so {@link LayoutPlan#of} places every box, and with it
 * every slot, the same on the server and on every client whatever their text size.
 * <p>
 * Rows place their children left to right and centre them vertically; columns place them top to bottom and centre them
 * horizontally; odd leftovers go to the right or bottom. Gaps default to {@link #GAP} and are never negative, so boxes
 * never overlap.
 *
 * <pre>{@code
 * Layout.column(
 *         Layout.row(Layout.tank(NUTRIENT, 18, 52), Layout.slots(FUEL, 1, 1),
 *                 Layout.progress(BURN, Layout.ProgressShape.FLAME), Layout.energy(ENERGY, 52)),
 *         Layout.playerInventory());
 * }</pre>
 *
 * Nodes are immutable records; any thread. Pure Java with no Minecraft types, so layouts can be tested in plain JUnit.
 */
public sealed interface Layout {
    /** The side of one slot's cell: the 16 pixel item and its 1 pixel frame on each side. */
    int SLOT = 18;
    /** The width of the player's inventory: nine cells. */
    int PLAYER_INVENTORY_WIDTH = 9 * SLOT;
    /** The height of the player's inventory: three rows, a 4 pixel gap and the hotbar. */
    int PLAYER_INVENTORY_HEIGHT = 3 * SLOT + 4 + SLOT;
    /** The gap between the panel's edge and the layout. */
    int PADDING = 7;
    /** The gap rows and columns put between their children unless told otherwise. */
    int GAP = 4;
    /** A small gap, for parts that belong together (a bar beside its slot). */
    int SMALL_GAP = 2;
    /**
     * The height of a label, value or status line. The box never grows with the client's text size: larger text is
     * drawn smaller to fit it, with the whole text as a tooltip.
     */
    int TEXT_HEIGHT = 10;
    /** The height of a button: the client's {@code AccentButton.HEIGHT}. */
    int BUTTON_HEIGHT = 16;
    /** The width of an energy bar. */
    int ENERGY_WIDTH = 10;
    /** How far the panel's title tab sticks out above the panel. */
    int TITLE_TAB = 11;

    /** Children left to right, {@link #GAP} apart. */
    static Layout row(Layout... children) {
        return new Row(GAP, List.of(children));
    }

    /** Children left to right, {@code gap} apart. */
    static Layout row(int gap, Layout... children) {
        return new Row(gap, List.of(children));
    }

    /** Children top to bottom, {@link #GAP} apart. */
    static Layout column(Layout... children) {
        return new Column(GAP, List.of(children));
    }

    /** Children top to bottom, {@code gap} apart. */
    static Layout column(int gap, Layout... children) {
        return new Column(gap, List.of(children));
    }

    /** The slots of {@code group} in {@code columns} by {@code rows} cells, row by row. */
    static Layout slots(MenuSlots group, int columns, int rows) {
        return new Slots(group, columns, rows);
    }

    /** A tank gauge, {@code width} by {@code height}; clicks with a fluid container fill or drain the tank. */
    static Layout tank(TankValue value, int width, int height) {
        return new Tank(value, width, height);
    }

    /** An energy bar {@link #ENERGY_WIDTH} wide and {@code height} tall. */
    static Layout energy(EnergyValue value, int height) {
        return new Energy(value, height);
    }

    /** A progress arrow or flame of {@code shape}'s size. */
    static Layout progress(ProgressValue value, ProgressShape shape) {
        return new Progress(value, shape);
    }

    /** The text of lang key {@code key}, fitted into {@code width} pixels. */
    static Layout label(String key, int width) {
        return new Label(key, width);
    }

    /**
     * The text of lang key {@code key} with the current {@code value} as its argument ({@code %s} in the text), fitted
     * into {@code width} pixels: live numbers such as a growth percentage.
     */
    static Layout value(IntValue value, String key, int width) {
        return new Value(value, key, width, null);
    }

    /**
     * Like {@link #value(IntValue, String, int)}, but the text is shown only while {@code shownWhenNonZero} is not 0 (a
     * farm hub's sampler percentage while a sampler is in). The box keeps its size and place while hidden, so nothing
     * else moves and the server and every client still place everything the same; the client draws nothing and narrates
     * nothing for a hidden box. {@code shownWhenNonZero} may be {@code value} itself or any other {@link IntValue} of
     * the same definition; it is synced like every value.
     */
    @ApiStatus.Experimental
    static Layout value(IntValue value, String key, int width, IntValue shownWhenNonZero) {
        return new Value(value, key, width, shownWhenNonZero);
    }

    /** A status dot and the text of the current constant of {@code value}, fitted into {@code width} pixels. */
    static <E extends Enum<E> & MenuStatus> Layout status(EnumValue<E> value, int width) {
        return new Status(value, width);
    }

    /**
     * A button {@code width} by {@link #BUTTON_HEIGHT} that shows the current choice of {@code action} and asks the
     * server for the next one on a left click (or Enter), the previous one on a right click (see {@link CycleAction}).
     * Its tooltip is the text of lang key {@code key} with the choice's name as its argument ({@code %s}, e.g.
     * "Redstone: %s") over the choice's hint line; the button narrates that first line. The label is fitted into the
     * width, so the box never moves with the text size.
     */
    static <E extends Enum<E> & MenuChoice> Layout cycleButton(CycleAction<E> action, String key, int width) {
        return new CycleButton(action, key, width);
    }

    /** Empty room, {@code width} by {@code height}. */
    static Layout spacer(int width, int height) {
        return new Spacer(width, height);
    }

    /** The player's main inventory and hotbar, {@link #PLAYER_INVENTORY_WIDTH} by {@link #PLAYER_INVENTORY_HEIGHT}. */
    static Layout playerInventory() {
        return PlayerInventory.INSTANCE;
    }

    /** A progress drawing's shape and size; the same sizes as the client's {@code AccentProgress.Shape}. */
    enum ProgressShape {
        /** An arrow pointing right, 22 by 15. */
        ARROW(22, 15),
        /** A flame burning down, 13 by 14. */
        FLAME(13, 14);

        private final int width;
        private final int height;

        ProgressShape(int width, int height) {
            this.width = width;
            this.height = height;
        }

        /** The width in pixels. */
        public int width() {
            return width;
        }

        /** The height in pixels. */
        public int height() {
            return height;
        }
    }

    /** A node that takes room of its own (everything but rows and columns). */
    sealed interface Leaf extends Layout {
        /** The width in pixels. */
        int width();

        /** The height in pixels. */
        int height();
    }

    /**
     * See {@link Layout#row}.
     *
     * @param gap
     *            pixels between children, not negative
     * @param children
     *            the children, left to right
     */
    record Row(int gap, List<Layout> children) implements Layout {
        /** Copies {@code children}, which must not be empty; {@code gap} must not be negative. */
        public Row {
            notNegative(gap, "gap");
            children = List.copyOf(children);
            if (children.isEmpty()) {
                throw new IllegalArgumentException("A row needs children");
            }
        }
    }

    /**
     * See {@link Layout#column}.
     *
     * @param gap
     *            pixels between children, not negative
     * @param children
     *            the children, top to bottom
     */
    record Column(int gap, List<Layout> children) implements Layout {
        /** Copies {@code children}, which must not be empty; {@code gap} must not be negative. */
        public Column {
            notNegative(gap, "gap");
            children = List.copyOf(children);
            if (children.isEmpty()) {
                throw new IllegalArgumentException("A column needs children");
            }
        }
    }

    /**
     * See {@link Layout#slots}.
     *
     * @param group
     *            the slots shown
     * @param columns
     *            cells per row
     * @param rows
     *            rows of cells
     */
    record Slots(MenuSlots group, int columns, int rows) implements Leaf {
        /** Columns and rows must be at least 1. */
        public Slots {
            positive(columns, "columns");
            positive(rows, "rows");
        }

        @Override
        public int width() {
            return columns * SLOT;
        }

        @Override
        public int height() {
            return rows * SLOT;
        }
    }

    /**
     * See {@link Layout#tank}.
     *
     * @param value
     *            the tank shown
     * @param width
     *            width in pixels
     * @param height
     *            height in pixels
     */
    record Tank(TankValue value, int width, int height) implements Leaf {
        /** Width and height must be at least 1. */
        public Tank {
            positive(width, "width");
            positive(height, "height");
        }
    }

    /**
     * See {@link Layout#energy}.
     *
     * @param value
     *            the buffer shown
     * @param height
     *            height in pixels
     */
    record Energy(EnergyValue value, int height) implements Leaf {
        /** Height must be at least 1. */
        public Energy {
            positive(height, "height");
        }

        @Override
        public int width() {
            return ENERGY_WIDTH;
        }
    }

    /**
     * See {@link Layout#progress}.
     *
     * @param value
     *            the progress shown
     * @param shape
     *            arrow or flame, which sets the size
     */
    record Progress(ProgressValue value, ProgressShape shape) implements Leaf {
        @Override
        public int width() {
            return shape.width();
        }

        @Override
        public int height() {
            return shape.height();
        }
    }

    /**
     * See {@link Layout#label}.
     *
     * @param key
     *            the lang key of the text
     * @param width
     *            width in pixels the text is fitted into
     */
    record Label(String key, int width) implements Leaf {
        /** Width must be at least 1. */
        public Label {
            positive(width, "width");
        }

        @Override
        public int height() {
            return TEXT_HEIGHT;
        }
    }

    /**
     * See {@link Layout#value}.
     *
     * @param value
     *            the number shown
     * @param key
     *            the lang key of the text, with one {@code %s} for the number
     * @param width
     *            width in pixels the text is fitted into
     * @param shownWhenNonZero
     *            the value that must not be 0 for the text to show, or null to always show it
     */
    record Value(IntValue value, String key, int width, @Nullable IntValue shownWhenNonZero) implements Leaf {
        /** Width must be at least 1. */
        public Value {
            positive(width, "width");
        }

        @Override
        public int height() {
            return TEXT_HEIGHT;
        }

        /**
         * Whether the text is shown (drawn and narrated) given the current {@code values}; the box is placed anyway.
         */
        public boolean shownIn(MenuValueView values) {
            return shownWhenNonZero == null || values.get(shownWhenNonZero) != 0;
        }
    }

    /**
     * See {@link Layout#status}.
     *
     * @param value
     *            the enum value shown; its constants must implement MenuStatus (the definition reports a problem
     *            otherwise)
     * @param width
     *            width in pixels the text is fitted into
     */
    record Status(EnumValue<?> value, int width) implements Leaf {
        /** Width must be at least 1. */
        public Status {
            positive(width, "width");
        }

        @Override
        public int height() {
            return TEXT_HEIGHT;
        }
    }

    /**
     * See {@link Layout#cycleButton}.
     *
     * @param action
     *            the server action the button asks for
     * @param key
     *            the lang key of the tooltip's and narration's first line, with one {@code %s} for the choice
     * @param width
     *            width in pixels
     */
    record CycleButton(CycleAction<?> action, String key, int width) implements Leaf {
        /** Width must be at least 1. */
        public CycleButton {
            positive(width, "width");
        }

        @Override
        public int height() {
            return BUTTON_HEIGHT;
        }
    }

    /**
     * See {@link Layout#spacer}.
     *
     * @param width
     *            width in pixels
     * @param height
     *            height in pixels
     */
    record Spacer(int width, int height) implements Leaf {
        /** Width and height must not be negative. */
        public Spacer {
            if (width < 0 || height < 0) {
                throw new IllegalArgumentException("Spacer size must not be negative: " + width + "x" + height);
            }
        }
    }

    /** See {@link Layout#playerInventory}. */
    enum PlayerInventory implements Leaf {
        /** The only one. */
        INSTANCE;

        @Override
        public int width() {
            return PLAYER_INVENTORY_WIDTH;
        }

        @Override
        public int height() {
            return PLAYER_INVENTORY_HEIGHT;
        }
    }

    private static void notNegative(int value, String what) {
        if (value < 0) {
            throw new IllegalArgumentException("Layout " + what + " must not be negative, got " + value);
        }
    }

    private static void positive(int value, String what) {
        if (value < 1) {
            throw new IllegalArgumentException("Layout " + what + " must be at least 1, got " + value);
        }
    }
}
