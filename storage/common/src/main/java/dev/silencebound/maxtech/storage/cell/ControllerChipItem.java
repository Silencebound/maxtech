package dev.silencebound.maxtech.storage.cell;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

/** A controller chip, the cell part that sets its {@link CellType} (PRD 7.2, the cell builder). */
public final class ControllerChipItem extends Item {
    private final Identifier type;

    public ControllerChipItem(Properties properties, Identifier type) {
        super(properties);
        this.type = type;
    }

    /** The registry id of the type a cell with this chip has. */
    public Identifier type() {
        return type;
    }
}
