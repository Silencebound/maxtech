package dev.silencebound.maxtech.storage.cell;

import net.minecraft.world.item.Item;

/** An add-on, a cell part fitted in the cell workbench (PRD 7.2, the cell builder). */
public final class CellAddonItem extends Item {
    private final CellAddon addon;

    public CellAddonItem(Properties properties, CellAddon addon) {
        super(properties);
        this.addon = addon;
    }

    /** What the add-on does. */
    public CellAddon addon() {
        return addon;
    }
}
