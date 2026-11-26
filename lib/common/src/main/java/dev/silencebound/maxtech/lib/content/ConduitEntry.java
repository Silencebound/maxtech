package dev.silencebound.maxtech.lib.content;

import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.conduit.ConduitItem;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * A conduit registered through {@link ConduitBuilder}: its type, its block and the item it is placed from, which share
 * their registry name.
 */
public record ConduitEntry<T extends ConduitType>(RegistryEntry<ConduitType, T> type,
        RegistryEntry<Block, ConduitBlock> block, RegistryEntry<Item, ConduitItem> item) {
}
