package dev.silencebound.maxtech.lib.conduit;

import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import net.minecraft.world.item.BlockItem;

/**
 * The item of one conduit type: a block item of its {@link ConduitBlock}, so it places like any block, uncoloured.
 * Registered with the block by the content builder ({@code Content.conduit}).
 */
public class ConduitItem extends BlockItem {
    public ConduitItem(ConduitBlock block, Properties properties) {
        super(block, properties);
    }

    /** The conduit type this item places. Call only after conduit types are registered. */
    public ConduitType conduitType() {
        return ((ConduitBlock) getBlock()).type();
    }
}
