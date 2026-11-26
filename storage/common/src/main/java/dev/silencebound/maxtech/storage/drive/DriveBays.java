package dev.silencebound.maxtech.storage.drive;

import dev.silencebound.maxtech.storage.cell.CellBytes;

/**
 * Where the drive's cells sit on its front texture ({@code block/drive/front}) and how the drive model draws one there
 * (MTX, owner 2026-10-17: flush bays). Slots are numbered row by row from the top left of the texture, two columns of
 * four 5 x 2 bays. A cell sprite ({@code block/drive/cell_*}) is {@link #SPRITE} texels square and transparent but for
 * one cell at ({@link #CELL_X}, {@link #CELL_Y}) with its light at the right end. The model copies the front quad (same
 * corners, so flush and without z-fighting in the cutout layer) and maps it onto a 16 x 16 window of the cell sprite
 * that puts the cell over the slot's bay; the right column's window is mirrored, so both lights face the divider. Pure
 * arithmetic, safe from any thread.
 */
public final class DriveBays {
    private static final int[] COLUMNS = {2, 9};
    private static final int[] ROWS = {3, 6, 9, 12};
    public static final int CELL_WIDTH = 5;
    public static final int CELL_HEIGHT = 2;
    /** Width and height of a cell sprite in texels. */
    public static final int SPRITE = 32;
    /** The cell's top left texel on its sprite (matches {@code CELL_AT} in {@code mtx_storage_blocks.py}). */
    public static final int CELL_X = 13;
    public static final int CELL_Y = 15;

    private DriveBays() {
    }

    /** The texture column of slot {@code slot}'s bay's left edge: 2 or 9. */
    public static int column(int slot) {
        return COLUMNS[slot % COLUMNS.length];
    }

    /** The texture row of slot {@code slot}'s bay's top edge: 3, 6, 9 or 12. */
    public static int row(int slot) {
        return ROWS[slot / COLUMNS.length];
    }

    /** True for the right column, whose cell is drawn mirrored so its light sits at the bay's left (inner) end. */
    public static boolean mirrored(int slot) {
        return slot % COLUMNS.length == 1;
    }

    /**
     * The cell sprite's u, 0 to 1, for a point at {@code frontU}, 0 to 1 across the front texture, when slot
     * {@code slot}'s cell is drawn. Stays within 0 to 1 for every slot.
     */
    public static float cellU(int slot, float frontU) {
        int column = column(slot);
        float x = frontU * 16;
        float u = mirrored(slot) ? CELL_X + column + CELL_WIDTH - x : x - column + CELL_X;
        return u / SPRITE;
    }

    /** The cell sprite's v, 0 to 1, for a point at {@code frontV}, 0 to 1 down the front texture. */
    public static float cellV(int slot, float frontV) {
        int row = row(slot);
        return (frontV * 16 - row + CELL_Y) / SPRITE;
    }

    /**
     * The cell sprite (a texture path under {@code maxtech_storage:}) for a slot's band
     * ({@link DriveBlockEntity#band}); empty for {@link DriveBlockEntity#BAND_EMPTY} and unknown bands.
     */
    public static String cellTexture(int band) {
        return switch (band) {
            case CellBytes.BAND_LOW -> "block/drive/cell_low";
            case CellBytes.BAND_HIGH -> "block/drive/cell_high";
            case CellBytes.BAND_FULL -> "block/drive/cell_full";
            case DriveBlockEntity.BAND_INACTIVE -> "block/drive/cell_inactive";
            default -> "";
        };
    }
}
