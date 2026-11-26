package dev.silencebound.maxtech.lib.content;

import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/** One item chain; see {@link Content#item}. Nothing is registered until {@link #register()}. */
public final class ItemBuilder<I extends Item> {
    private final Content content;
    private final String name;
    private final Function<Item.Properties, ? extends I> factory;
    private UnaryOperator<Item.Properties> properties = UnaryOperator.identity();
    private @Nullable ItemModel model;
    private @Nullable String lang;
    private final List<TagKey<Item>> tags = new ArrayList<>();
    private final List<ContentRecipe<? super I>> recipes = new ArrayList<>();
    private boolean creativeTab;

    ItemBuilder(Content content, String name, Function<Item.Properties, ? extends I> factory) {
        this.content = content;
        this.name = name;
        this.factory = factory;
    }

    /** Adjusts the item properties; calls apply in order. */
    public ItemBuilder<I> properties(UnaryOperator<Item.Properties> adjust) {
        UnaryOperator<Item.Properties> before = properties;
        properties = p -> adjust.apply(before.apply(p));
        return this;
    }

    public ItemBuilder<I> model(ItemModel model) {
        this.model = model;
        return this;
    }

    /** The English name, written to {@code en_us}. */
    public ItemBuilder<I> lang(String name) {
        this.lang = name;
        return this;
    }

    public ItemBuilder<I> tag(TagKey<Item> tag) {
        tags.add(tag);
        return this;
    }

    public ItemBuilder<I> recipe(ContentRecipe<? super I> recipe) {
        recipes.add(recipe);
        return this;
    }

    /** Lists the item in the mod's creative tab, in registration order. */
    public ItemBuilder<I> creativeTab() {
        creativeTab = true;
        return this;
    }

    /** Queues the item for registration and records its declarations. */
    public RegistryEntry<Item, I> register() {
        Function<Item.Properties, ? extends I> make = factory;
        UnaryOperator<Item.Properties> adjust = properties;
        RegistryEntry<Item, I> entry = content.itemRegistrar().register(name,
                id -> make.apply(adjust.apply(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id)))));
        content.declare(new ItemDeclaration<>(entry, model, lang, List.copyOf(tags), List.copyOf(recipes)),
                creativeTab);
        return entry;
    }
}
