package dev.silencebound.maxtech.lib.api.conduit;

import dev.silencebound.maxtech.lib.api.registry.RegistryEntry;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.ApiStatus;

/**
 * A conduit registered through {@link Conduits#builder}: its type, the block Lib made for it and the item it is placed
 * from, which share their registry name. The entries resolve once registration has run.
 * <p>
 * Experimental (Lib 0.14.0, for Farms, FM1) until Farms, its first addon user, settles it.
 *
 * @param <T>
 *            the conduit type's class
 */
@ApiStatus.Experimental
public interface ConduitEntry<T extends ConduitType> {
    /** The conduit type, in {@link ConduitTypes#REGISTRY_KEY}. */
    RegistryEntry<ConduitType, T> type();

    /** The conduit's block; Lib's class, so treat it as a plain {@link Block}. */
    RegistryEntry<Block, ? extends Block> block();

    /** The conduit's item, a block item of {@link #block()}. */
    RegistryEntry<Item, ? extends Item> item();
}
