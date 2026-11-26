package dev.silencebound.maxtech.lib.content;

import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.conduit.ConduitTypes;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.conduit.ConduitItem;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.material.Fluid;
import org.jspecify.annotations.Nullable;

/**
 * One mod's content builder. A mod creates one during construction (from the static initialiser of a class its
 * entrypoint touches, like any registrar) and declares its blocks, items and material forms through it; its datagen
 * providers and its creative tab read the declarations back. Declarations are collected on the thread that runs class
 * init and are not changed afterwards.
 */
public final class Content {
    private final String modId;
    private final RegistryHelper.Registrar<Block> blockRegistrar;
    private final RegistryHelper.Registrar<Item> itemRegistrar;
    private final RegistryHelper.Registrar<BlockEntityType<?>> blockEntityTypeRegistrar;
    private final RegistryHelper.@Nullable Registrar<ConduitType> conduitTypeRegistrar;
    private final List<BlockDeclaration<?>> blocks = new ArrayList<>();
    private final List<ItemDeclaration<?>> items = new ArrayList<>();
    private final List<ItemTagDeclaration> itemTags = new ArrayList<>();
    private final List<FluidTagDeclaration> fluidTags = new ArrayList<>();
    private final Map<String, String> translations = new LinkedHashMap<>();
    // Lang keys declared by every mod's content, so an addon's material form item can use a name pattern another mod
    // declared.
    private static final Set<String> DECLARED_KEYS = ConcurrentHashMap.newKeySet();
    private final List<RegistryEntry<Item, ?>> creativeTabItems = new ArrayList<>();
    private final List<Supplier<List<RegistryEntry<Item, ?>>>> creativeTabSections = new ArrayList<>();

    public Content(String modId, RegistryHelper.Registrar<Block> blockRegistrar,
            RegistryHelper.Registrar<Item> itemRegistrar,
            RegistryHelper.Registrar<BlockEntityType<?>> blockEntityTypeRegistrar) {
        this(modId, blockRegistrar, itemRegistrar, blockEntityTypeRegistrar, null);
    }

    /** As the four-argument constructor, plus the registrar {@link #conduit} chains register their types with. */
    public Content(String modId, RegistryHelper.Registrar<Block> blockRegistrar,
            RegistryHelper.Registrar<Item> itemRegistrar,
            RegistryHelper.Registrar<BlockEntityType<?>> blockEntityTypeRegistrar,
            RegistryHelper.@Nullable Registrar<ConduitType> conduitTypeRegistrar) {
        this.modId = modId;
        this.blockRegistrar = blockRegistrar;
        this.itemRegistrar = itemRegistrar;
        this.blockEntityTypeRegistrar = blockEntityTypeRegistrar;
        this.conduitTypeRegistrar = conduitTypeRegistrar;
    }

    /** A builder registering through {@link Platform#registry()}; call during mod construction. */
    public static Content create(String modId) {
        RegistryHelper registry = Platform.get().registry();
        return new Content(modId, registry.registrar(modId, Registries.BLOCK),
                registry.registrar(modId, Registries.ITEM), registry.registrar(modId, Registries.BLOCK_ENTITY_TYPE),
                registry.registrar(modId, ConduitTypes.REGISTRY_KEY));
    }

    public String modId() {
        return modId;
    }

    /**
     * Starts a block chain. {@code factory} gets properties that already carry the block's id; the chain's
     * {@link BlockBuilder#properties} adjust them first.
     */
    public <B extends Block> BlockBuilder<B, Item> block(String name,
            Function<BlockBehaviour.Properties, ? extends B> factory) {
        return new BlockBuilder<>(this, name, factory);
    }

    /** Starts an item chain; {@code factory} gets properties that already carry the item's id. */
    public <I extends Item> ItemBuilder<I> item(String name, Function<Item.Properties, ? extends I> factory) {
        return new ItemBuilder<>(this, name, factory);
    }

    /**
     * Starts a conduit chain (ADR-0012): the conduit type made by {@code type}, its {@link ConduitBlock} and the
     * {@link ConduitItem} it is placed from, all under {@code name}, with the block's model, loot (it drops itself) and
     * pickaxe tag declared for datagen. {@code type} gets the item, resolved lazily, since items register before
     * conduit types but the type is built first.
     */
    public <T extends ConduitType> ConduitBuilder<T> conduit(String name, Function<Supplier<ItemLike>, T> type) {
        return new ConduitBuilder<>(this, name, type);
    }

    /**
     * Registers a conduit type made by {@code type} and its block, with no item and nothing declared for datagen: for
     * GameTest conduit types, which name any vanilla item. Content always uses {@link #conduit}.
     */
    public <T extends ConduitType> RegistryEntry<ConduitType, T> conduitWithoutItem(String name,
            Supplier<? extends T> type) {
        return conduitWithoutItem(name, type, ConduitBlock::new);
    }

    /** As {@link #conduitWithoutItem(String, Supplier)}, with the block made by {@code block}. */
    public <T extends ConduitType> RegistryEntry<ConduitType, T> conduitWithoutItem(String name,
            Supplier<? extends T> type, ConduitBuilder.BlockFactory block) {
        RegistryEntry<ConduitType, T> entry = conduitTypeRegistrar().register(name, type);
        ConduitBuilder.registerBlock(this, name, entry, block);
        return entry;
    }

    /**
     * Declares that {@code tag} holds the tags {@code includes}, in order; with none, that it exists though nothing of
     * ours is in it (datagen writes it empty). Declaring the same tag again adds to it.
     */
    @SafeVarargs
    public final void itemTag(TagKey<Item> tag, TagKey<Item>... includes) {
        itemTags.add(new ItemTagDeclaration(tag, List.of(includes)));
    }

    /** Declares that {@code fluid}, one of ours, is in {@code tag}; datagen writes it into the mod's fluid tags. */
    public void fluidTag(TagKey<Fluid> tag, Supplier<? extends Fluid> fluid) {
        fluidTags.add(new FluidTagDeclaration(tag, fluid));
    }

    /**
     * Declares an English lang line under a key of its own, for names that no block or item chain owns (a material
     * name, a form's name pattern). Throws {@link IllegalStateException} if the key was declared before.
     */
    public void translation(String key, String english) {
        declareTranslation(key, english);
    }

    /** Every block declared so far, machine tiers included, in registration order. */
    public List<BlockDeclaration<?>> blocks() {
        return Collections.unmodifiableList(blocks);
    }

    /** Every item declared so far, block and machine items included, in registration order. */
    public List<ItemDeclaration<?>> items() {
        return Collections.unmodifiableList(items);
    }

    /** Every item tag declared on its own, in declaration order. */
    public List<ItemTagDeclaration> itemTags() {
        return Collections.unmodifiableList(itemTags);
    }

    /** Every fluid tag entry declared, in declaration order. */
    public List<FluidTagDeclaration> fluidTags() {
        return Collections.unmodifiableList(fluidTags);
    }

    /**
     * English lang lines declared under keys of their own (machine and material names), by key, in declaration order.
     */
    public Map<String, String> translations() {
        return Collections.unmodifiableMap(translations);
    }

    /**
     * The items that asked for the mod's creative tab (PRD 8.2: by tier, then function): blocks and items in
     * registration order, then each {@link #addCreativeTabSection section} in the order they were added.
     */
    public List<RegistryEntry<Item, ?>> creativeTabItems() {
        List<RegistryEntry<Item, ?>> tab = new ArrayList<>(creativeTabItems);
        for (Supplier<List<RegistryEntry<Item, ?>>> section : creativeTabSections) {
            tab.addAll(section.get());
        }
        return Collections.unmodifiableList(tab);
    }

    /**
     * Adds a section after the blocks and items of {@link #creativeTabItems()}, read each time the tab is listed; a mod
     * adds content of its own kinds this way. Call during mod construction.
     */
    public void addCreativeTabSection(Supplier<List<RegistryEntry<Item, ?>>> section) {
        creativeTabSections.add(section);
    }

    public RegistryHelper.Registrar<Block> blockRegistrar() {
        return blockRegistrar;
    }

    public RegistryHelper.Registrar<Item> itemRegistrar() {
        return itemRegistrar;
    }

    public RegistryHelper.Registrar<BlockEntityType<?>> blockEntityTypeRegistrar() {
        return blockEntityTypeRegistrar;
    }

    RegistryHelper.Registrar<ConduitType> conduitTypeRegistrar() {
        if (conduitTypeRegistrar == null) {
            throw new IllegalStateException(modId + ": this content builder has no conduit type registrar");
        }
        return conduitTypeRegistrar;
    }

    public void declare(BlockDeclaration<?> block) {
        blocks.add(block);
    }

    public void declare(ItemDeclaration<?> item, boolean creativeTab) {
        items.add(item);
        if (creativeTab) {
            creativeTabItems.add(item.entry());
        }
    }

    public void declareTranslation(String key, String name) {
        if (translations.putIfAbsent(key, name) != null) {
            throw new IllegalStateException(modId + ": lang key " + key + " declared twice");
        }
        DECLARED_KEYS.add(key);
    }

    /** Whether any mod's content declared {@code key}. */
    public static boolean declared(String key) {
        return DECLARED_KEYS.contains(key);
    }
}
