package dev.silencebound.maxtech.lib.content;

import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

/** A block registered through {@link BlockBuilder}, with its item if the chain declared one. */
public final class BlockEntry<B extends Block, I extends Item> implements RegistryEntry<Block, B> {
    private final RegistryEntry<Block, B> block;
    private final @Nullable RegistryEntry<Item, I> item;

    BlockEntry(RegistryEntry<Block, B> block, @Nullable RegistryEntry<Item, I> item) {
        this.block = block;
        this.item = item;
    }

    /** The block's item; throws if the chain declared none. */
    public RegistryEntry<Item, I> item() {
        if (item == null) {
            throw new IllegalStateException(block.id() + " has no item");
        }
        return item;
    }

    @Override
    public B get() {
        return block.get();
    }

    @Override
    public Identifier id() {
        return block.id();
    }

    @Override
    public ResourceKey<Block> key() {
        return block.key();
    }

    @Override
    public Holder<Block> holder() {
        return block.holder();
    }
}
