package dev.silencebound.maxtech.storage.cell;

import net.minecraft.world.item.Item;

/**
 * An empty cell casing. Fitting a controller chip in the cell workbench turns it into its casing's cell item; a cell
 * stripped of every part turns back into the casing.
 */
public final class CellCasingItem extends Item {
    private final CellCasing casing;

    public CellCasingItem(Properties properties, CellCasing casing) {
        super(properties);
        this.casing = casing;
    }

    public CellCasing casing() {
        return casing;
    }
}
