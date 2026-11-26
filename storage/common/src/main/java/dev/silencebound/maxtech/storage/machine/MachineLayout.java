package dev.silencebound.maxtech.storage.machine;

/**
 * Where a machine GUI puts its slots, arrow, energy bar and mode button, relative to the panel: one place the menu and
 * the screen share. Panels are vanilla-sized (176 x 166) with the player inventory at the usual spot; inputs sit left,
 * the arrow in the middle, outputs right, the energy bar at the right edge.
 */
public final class MachineLayout {
    public static final int WIDTH = 176;
    public static final int HEIGHT = 166;
    public static final int INVENTORY_X = 8;
    public static final int INVENTORY_Y = 84;
    public static final int ENERGY_X = 156;
    public static final int ENERGY_Y = 18;
    public static final int ENERGY_WIDTH = 10;
    public static final int ENERGY_HEIGHT = 54;
    public static final int ARROW_WIDTH = 24;
    public static final int ARROW_HEIGHT = 6;
    public static final int BUTTON_WIDTH = 52;
    public static final int BUTTON_HEIGHT = 12;
    /** The export button, top right in the title row, clear of the slots, the second module slot and the energy bar. */
    public static final int EXPORT_X = 100;
    public static final int EXPORT_Y = 5;
    public static final int EXPORT_WIDTH = 52;
    public static final int EXPORT_HEIGHT = 10;

    /** The first upgrade module slot, bottom right under the outputs, clear of the mode button and the state line. */
    public static final int MODULE_X = 134;
    public static final int MODULE_Y = 57;
    /** The second module slot (M6f, for the void module), top right above the outputs. */
    public static final int SECOND_MODULE_Y = 17;

    /** The y of module slot {@code slot}: the first under the outputs, the second above them. */
    public static int moduleY(int slot) {
        return slot == 0 ? MODULE_Y : SECOND_MODULE_Y;
    }

    private static final MachineLayout GENERATOR = new MachineLayout(new int[]{62}, new int[]{44}, 0, 0, new int[]{116},
            44, 0, 0);
    private static final MachineLayout LITHOGRAPH = new MachineLayout(new int[]{30, 30}, new int[]{27, 49}, 56, 38,
            new int[]{116, 134}, 38, 82, 43);
    private static final MachineLayout FABRICATOR = new MachineLayout(new int[]{40, 40}, new int[]{27, 49}, 0, 0,
            new int[]{116, 134}, 38, 74, 43);
    private static final MachineLayout ASSEMBLER = new MachineLayout(new int[]{20, 38, 56, 20, 38, 56},
            new int[]{29, 29, 29, 47, 47, 47}, 0, 0, new int[]{116}, 38, 82, 43);

    private final int[] inputX;
    private final int[] inputY;
    private final int catalystX;
    private final int catalystY;
    private final int[] outputX;
    private final int outputY;
    private final int arrowX;
    private final int arrowY;

    private MachineLayout(int[] inputX, int[] inputY, int catalystX, int catalystY, int[] outputX, int outputY,
            int arrowX, int arrowY) {
        this.inputX = inputX;
        this.inputY = inputY;
        this.catalystX = catalystX;
        this.catalystY = catalystY;
        this.outputX = outputX;
        this.outputY = outputY;
        this.arrowX = arrowX;
        this.arrowY = arrowY;
    }

    public static MachineLayout of(MachineKind kind) {
        return switch (kind) {
            case FUEL_GENERATOR -> GENERATOR;
            case LITHOGRAPH -> LITHOGRAPH;
            case FABRICATOR -> FABRICATOR;
            case ASSEMBLER -> ASSEMBLER;
        };
    }

    public int inputX(int slot) {
        return inputX[slot];
    }

    public int inputY(int slot) {
        return inputY[slot];
    }

    public int catalystX() {
        return catalystX;
    }

    public int catalystY() {
        return catalystY;
    }

    public int outputX(int slot) {
        return outputX[slot];
    }

    public int outputY() {
        return outputY;
    }

    public int arrowX() {
        return arrowX;
    }

    public int arrowY() {
        return arrowY;
    }

    /** The mode button sits under the arrow. */
    public int buttonX() {
        return arrowX + ARROW_WIDTH / 2 - BUTTON_WIDTH / 2;
    }

    public int buttonY() {
        return arrowY + 18;
    }
}
