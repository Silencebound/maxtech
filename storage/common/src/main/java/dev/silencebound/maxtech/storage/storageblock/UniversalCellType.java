package dev.silencebound.maxtech.storage.storageblock;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.cell.CellType;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;

/**
 * The universal cell type ({@code maxtech_storage:universal}): items and fluids in one cell, each kind at its own cell
 * rate ({@code cells.itemsPerByte}, {@code cells.fluidMbPerByte}). Storage blocks use it (B09 item 27); no controller
 * chip builds it into a cell item yet. {@link #kind()} answers items, the first of the two.
 */
public final class UniversalCellType extends CellType {
    public UniversalCellType() {
        super(StackKey.Kind.ITEM, StorageServerConfig.CELL_ITEMS_PER_BYTE);
    }

    @Override
    public boolean accepts(StackKey key) {
        return key.kind() == StackKey.Kind.ITEM || key.kind() == StackKey.Kind.FLUID;
    }

    @Override
    public long unitsPerByte(StackKey.Kind kind) {
        long units = kind == StackKey.Kind.FLUID
                ? StorageServerConfig.CELL_FLUID_MB_PER_BYTE.getAsLong()
                : StorageServerConfig.CELL_ITEMS_PER_BYTE.getAsLong();
        return Math.max(1, units);
    }
}
