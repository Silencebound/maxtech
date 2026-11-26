package dev.silencebound.maxtech.storage.cell;

import java.util.function.LongSupplier;
import net.minecraft.world.item.Item;

/**
 * A memory module, a cell part whose bytes add to its cell's capacity (PRD 7.2, the cell builder). The bytes come from
 * config, so changing them changes every cell built with the module the next time it enters a drive.
 */
public final class MemoryModuleItem extends Item {
    private final LongSupplier bytes;

    public MemoryModuleItem(Properties properties, LongSupplier bytes) {
        super(properties);
        this.bytes = bytes;
    }

    /** Bytes the module adds; reads the server config. */
    public long bytes() {
        return bytes.getAsLong();
    }
}
