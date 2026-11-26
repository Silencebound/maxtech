package dev.silencebound.maxtech.lib.content;

import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.jspecify.annotations.Nullable;

/**
 * One block chain, optionally with its item; see {@link Content#block}. The item shares the block's name, uses the
 * block's description id and model, and carries the recipes and creative-tab entry. Nothing is registered until
 * {@link #register()}.
 *
 * @param <I>
 *            the item type; plain {@code Item} until {@link #simpleItem()} or {@link #item} names one
 */
public final class BlockBuilder<B extends Block, I extends Item> {
    private final Content content;
    private final String name;
    private final Function<BlockBehaviour.Properties, ? extends B> factory;
    private UnaryOperator<BlockBehaviour.Properties> properties = UnaryOperator.identity();
    private @Nullable BiFunction<? super B, Item.Properties, ? extends I> itemFactory;
    private @Nullable BlockModel model;
    private @Nullable String lang;
    private @Nullable BlockLoot loot;
    private final List<TagKey<Block>> tags = new ArrayList<>();
    private final List<TagKey<Item>> itemTags = new ArrayList<>();
    private final List<ContentRecipe<? super I>> recipes = new ArrayList<>();
    private boolean creativeTab;

    BlockBuilder(Content content, String name, Function<BlockBehaviour.Properties, ? extends B> factory) {
        this.content = content;
        this.name = name;
        this.factory = factory;
    }

    /** Adjusts the block properties; calls apply in order. */
    public BlockBuilder<B, I> properties(UnaryOperator<BlockBehaviour.Properties> adjust) {
        UnaryOperator<BlockBehaviour.Properties> before = properties;
        properties = p -> adjust.apply(before.apply(p));
        return this;
    }

    /** Gives the block a plain {@link BlockItem}. */
    public BlockBuilder<B, BlockItem> simpleItem() {
        return item(BlockItem::new);
    }

    /**
     * Gives the block an item made by {@code factory}, whose properties already carry the id and the block's
     * description prefix. Call it before {@link #recipe}, since recipes are typed by the item.
     */
    public <J extends Item> BlockBuilder<B, J> item(BiFunction<? super B, Item.Properties, ? extends J> factory) {
        if (!recipes.isEmpty()) {
            throw new IllegalStateException(name + ": declare the item before its recipes");
        }
        BlockBuilder<B, J> next = new BlockBuilder<>(content, name, this.factory);
        next.properties = properties;
        next.itemFactory = factory;
        next.model = model;
        next.lang = lang;
        next.loot = loot;
        next.tags.addAll(tags);
        next.itemTags.addAll(itemTags);
        next.creativeTab = creativeTab;
        return next;
    }

    public BlockBuilder<B, I> model(BlockModel model) {
        this.model = model;
        return this;
    }

    /** The English name, written to {@code en_us} under the block's description id (its item shares it). */
    public BlockBuilder<B, I> lang(String name) {
        this.lang = name;
        return this;
    }

    public BlockBuilder<B, I> loot(BlockLoot loot) {
        this.loot = loot;
        return this;
    }

    public BlockBuilder<B, I> tag(TagKey<Block> tag) {
        tags.add(tag);
        return this;
    }

    /** An item tag for the block's item, e.g. {@code c:storage_blocks/tin}; needs an item. */
    public BlockBuilder<B, I> itemTag(TagKey<Item> tag) {
        itemTags.add(tag);
        return this;
    }

    /** A recipe for the block's item; needs an item. */
    public BlockBuilder<B, I> recipe(ContentRecipe<? super I> recipe) {
        recipes.add(recipe);
        return this;
    }

    /** Lists the block's item in the mod's creative tab, in registration order; needs an item. */
    public BlockBuilder<B, I> creativeTab() {
        creativeTab = true;
        return this;
    }

    /** Queues the block, and its item if any, for registration and records their declarations. */
    public BlockEntry<B, I> register() {
        Function<BlockBehaviour.Properties, ? extends B> make = factory;
        UnaryOperator<BlockBehaviour.Properties> adjust = properties;
        RegistryEntry<Block, B> block = content.blockRegistrar().register(name, id -> make
                .apply(adjust.apply(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id)))));
        content.declare(new BlockDeclaration<>(block, model, lang, loot, List.copyOf(tags)));
        BiFunction<? super B, Item.Properties, ? extends I> makeItem = itemFactory;
        if (makeItem == null) {
            if (!recipes.isEmpty() || !itemTags.isEmpty() || creativeTab) {
                throw new IllegalStateException(name + ": recipes, item tags and the creative tab need an item");
            }
            return new BlockEntry<>(block, null);
        }
        RegistryEntry<Item, I> item = content.itemRegistrar().register(name, id -> makeItem.apply(block.get(),
                new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id)).useBlockDescriptionPrefix()));
        content.declare(new ItemDeclaration<>(item, null, null, List.copyOf(itemTags), List.copyOf(recipes)),
                creativeTab);
        return new BlockEntry<>(block, item);
    }
}
