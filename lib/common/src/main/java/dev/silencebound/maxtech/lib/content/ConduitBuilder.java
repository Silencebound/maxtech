package dev.silencebound.maxtech.lib.content;

import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.conduit.ConduitItem;
import dev.silencebound.maxtech.lib.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import org.jspecify.annotations.Nullable;

/**
 * One conduit chain; see {@link Content#conduit}. Registers the conduit type, its {@link ConduitBlock} and its
 * {@link ConduitItem} under one name, and declares the block's model ({@link BlockModel.Conduit} in its {@link #look}),
 * loot (the block drops itself), pickaxe tag and English name, plus the item's model, tags, recipes and creative-tab
 * entry. Nothing is registered until {@link #register()}.
 */
public final class ConduitBuilder<T extends ConduitType> {
    private final Content content;
    private final String name;
    private final Function<Supplier<ItemLike>, T> type;
    private @Nullable ItemModel model;
    private ConduitLook look = ConduitLook.DEFAULT;
    private @Nullable String lang;
    private final List<TagKey<Item>> itemTags = new ArrayList<>();
    private final List<ContentRecipe<? super ConduitItem>> recipes = new ArrayList<>();
    private boolean creativeTab;
    private BlockFactory block = ConduitBlock::new;

    ConduitBuilder(Content content, String name, Function<Supplier<ItemLike>, T> type) {
        this.content = content;
        this.name = name;
        this.type = type;
    }

    /** The item's model; required, since the block's model is drawn from block entity data an item does not have. */
    public ConduitBuilder<T> model(ItemModel model) {
        this.model = model;
        return this;
    }

    /** How the block is drawn; flat in the type's colour ({@link ConduitLook#DEFAULT}) if not set. */
    public ConduitBuilder<T> look(ConduitLook look) {
        this.look = look;
        return this;
    }

    /** The English name, written to {@code en_us} under the block's description id (its item shares it). */
    public ConduitBuilder<T> lang(String name) {
        this.lang = name;
        return this;
    }

    /** An item tag for the conduit's item, e.g. {@code c:cables/tin}. */
    public ConduitBuilder<T> tag(TagKey<Item> tag) {
        itemTags.add(tag);
        return this;
    }

    public ConduitBuilder<T> recipe(ContentRecipe<? super ConduitItem> recipe) {
        recipes.add(recipe);
        return this;
    }

    /**
     * Makes the block with {@code block} instead of a plain {@link ConduitBlock}: for conduits whose block adds
     * behaviour of its own, such as Storage's cables with parts on their faces. The block entity stays Lib's.
     */
    public ConduitBuilder<T> block(BlockFactory block) {
        this.block = block;
        return this;
    }

    /** Lists the conduit's item in the mod's creative tab, in registration order. */
    public ConduitBuilder<T> creativeTab() {
        creativeTab = true;
        return this;
    }

    /** Queues the type, block and item for registration and records their declarations. */
    public ConduitEntry<T> register() {
        ItemModel itemModel = model;
        if (itemModel == null) {
            throw new IllegalStateException(name + ": a conduit needs an item model");
        }
        AtomicReference<RegistryEntry<Item, ConduitItem>> itemEntry = new AtomicReference<>();
        Supplier<ItemLike> item = () -> Objects.requireNonNull(itemEntry.get(), name).get();
        Function<Supplier<ItemLike>, T> make = type;
        RegistryEntry<ConduitType, T> typeEntry = content.conduitTypeRegistrar().register(name, () -> make.apply(item));
        RegistryEntry<Block, ConduitBlock> block = registerBlock(content, name, typeEntry, this.block);
        content.declare(new BlockDeclaration<>(block, new BlockModel.Conduit(look), lang, BlockLoot.SELF,
                List.of(BlockTags.MINEABLE_WITH_PICKAXE)));
        RegistryEntry<Item, ConduitItem> registered = content.itemRegistrar().register(name, id -> new ConduitItem(
                block.get(),
                new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id)).useBlockDescriptionPrefix()));
        itemEntry.set(registered);
        content.declare(new ItemDeclaration<>(registered, itemModel, null, List.copyOf(itemTags), List.copyOf(recipes)),
                creativeTab);
        return new ConduitEntry<>(typeEntry, block, registered);
    }

    /**
     * Queues the conduit block of {@code type}: metal, quick to break, see-through, and with a shape that comes from
     * its block entity, so the block state must not cache it ({@code dynamicShape}).
     */
    static RegistryEntry<Block, ConduitBlock> registerBlock(Content content, String name,
            Supplier<? extends ConduitType> type, BlockFactory factory) {
        return content.blockRegistrar().register(name,
                id -> factory.create(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id))
                        .mapColor(MapColor.METAL).strength(1.0F).sound(SoundType.METAL).noOcclusion().dynamicShape(),
                        type));
    }

    /** Makes a conduit's block from its properties and its lazily resolved type; {@code ConduitBlock::new} fits. */
    @FunctionalInterface
    public interface BlockFactory {
        ConduitBlock create(BlockBehaviour.Properties properties, Supplier<? extends ConduitType> type);
    }
}
