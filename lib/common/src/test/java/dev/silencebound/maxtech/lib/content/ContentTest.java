package dev.silencebound.maxtech.lib.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.conduit.ConduitTypes;
import dev.silencebound.maxtech.lib.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.junit.jupiter.api.Test;

/** Declarations the builder records; nothing is constructed, the registrars only note names. */
class ContentTest {
    private static final Identifier TEXTURE = Identifier.withDefaultNamespace("block/stone");

    private final FakeRegistrar<Block> blocks = new FakeRegistrar<>(Registries.BLOCK);
    private final FakeRegistrar<Item> items = new FakeRegistrar<>(Registries.ITEM);
    private final FakeRegistrar<BlockEntityType<?>> blockEntityTypes = new FakeRegistrar<>(
            Registries.BLOCK_ENTITY_TYPE);
    private final Content content = new Content("test", blocks, items, blockEntityTypes);

    @Test
    void blockChainRecordsBlockAndItemDeclarations() {
        ContentRecipe<Item> recipe = (recipes, item) -> {
        };
        BlockEntry<Block, ?> entry = content.block("casing", Block::new).simpleItem().model(BlockModel.cubeAll(TEXTURE))
                .lang("Casing").loot(BlockLoot.SELF).tag(BlockTags.MINEABLE_WITH_PICKAXE).recipe(recipe).creativeTab()
                .register();

        assertEquals(List.of("casing"), blocks.names);
        assertEquals(List.of("casing"), items.names);
        BlockDeclaration<?> block = content.blocks().getFirst();
        assertSame(entry.id(), block.entry().id());
        assertEquals(BlockModel.cubeAll(TEXTURE), block.model());
        assertEquals("Casing", block.lang());
        assertSame(BlockLoot.SELF, block.loot());
        assertEquals(List.of(BlockTags.MINEABLE_WITH_PICKAXE), block.tags());
        ItemDeclaration<?> item = content.items().getFirst();
        assertEquals(entry.item().id(), item.entry().id());
        assertNull(item.model(), "block items use their block's model");
        assertNull(item.lang(), "block items use their block's name");
        assertEquals(List.of(recipe), item.recipes());
        assertEquals(List.of(item.entry()), content.creativeTabItems());
    }

    /**
     * One conduit chain registers the type, its block and its item under one name, and declares the block's conduit
     * model, self loot and pickaxe tag and the item's model, tag and recipe (ADR-0012).
     */
    @Test
    void conduitChainRegistersTypeBlockAndItem() {
        FakeRegistrar<ConduitType> types = new FakeRegistrar<>(ConduitTypes.REGISTRY_KEY);
        Content withConduits = new Content("test", blocks, items, blockEntityTypes, types);
        ContentRecipe<Item> recipe = (recipes, item) -> {
        };
        ItemModel model = ItemModel.tintedFlat(TEXTURE, 0x336699);
        ConduitEntry<ConduitType> entry = withConduits
                .conduit("tin_single_cable",
                        item -> new ConduitType(Identifier.fromNamespaceAndPath("test", "energy"), "lv", 0, item))
                .model(model).look(ConduitLook.wire()).lang("Tin Cable").tag(ItemTags.PLANKS).recipe(recipe)
                .creativeTab().register();

        assertEquals(List.of("tin_single_cable"), types.names);
        assertEquals(List.of("tin_single_cable"), blocks.names);
        assertEquals(List.of("tin_single_cable"), items.names);
        BlockDeclaration<?> block = withConduits.blocks().getFirst();
        assertSame(entry.block().id(), block.entry().id());
        assertEquals(new BlockModel.Conduit(ConduitLook.wire()), block.model());
        assertEquals("Tin Cable", block.lang());
        assertSame(BlockLoot.SELF, block.loot(), "a conduit block drops itself");
        assertEquals(List.of(BlockTags.MINEABLE_WITH_PICKAXE), block.tags());
        ItemDeclaration<?> item = withConduits.items().getFirst();
        assertSame(entry.item().id(), item.entry().id());
        assertEquals(model, item.model(), "the block's model needs block entity data, so the item has its own");
        assertNull(item.lang(), "the item uses the block's name");
        assertEquals(List.of(ItemTags.PLANKS), item.tags());
        assertEquals(List.of(recipe), item.recipes());
        assertEquals(List.of(item.entry()), withConduits.creativeTabItems());
    }

    @Test
    void conduitChainNeedsAnItemModelAndATypeRegistrar() {
        Content withConduits = new Content("test", blocks, items, blockEntityTypes,
                new FakeRegistrar<>(ConduitTypes.REGISTRY_KEY));
        assertThrows(IllegalStateException.class,
                () -> withConduits
                        .conduit("bare",
                                item -> new ConduitType(Identifier.fromNamespaceAndPath("test", "x"), "x", 0, item))
                        .register());
        assertThrows(IllegalStateException.class, () -> content.conduitWithoutItem("no_registrar",
                () -> new ConduitType(Identifier.fromNamespaceAndPath("test", "x"), "x", 0, () -> {
                    throw new UnsupportedOperationException();
                })));
    }

    @Test
    void creativeTabKeepsRegistrationOrderAndSkipsUnflaggedItems() {
        content.item("first", Item::new).creativeTab().register();
        content.item("hidden", Item::new).register();
        content.block("second", Block::new).simpleItem().creativeTab().register();
        content.item("third", Item::new).model(ItemModel.flat(TEXTURE)).tag(ItemTags.PLANKS).creativeTab().register();

        List<String> tab = new ArrayList<>();
        for (RegistryEntry<Item, ?> entry : content.creativeTabItems()) {
            tab.add(entry.id().getPath());
        }
        assertEquals(List.of("first", "second", "third"), tab);
        assertEquals(4, content.items().size());
        assertEquals(List.of(ItemTags.PLANKS), content.items().getLast().tags());
    }

    @Test
    void blockWithoutItemRegistersNoItem() {
        BlockEntry<Block, Item> entry = content.block("bundle", Block::new).lang("Bundle").register();

        assertEquals(List.of(), items.names);
        assertEquals(List.of(), content.items());
        assertThrows(IllegalStateException.class, entry::item);
    }

    @Test
    void recipesAndCreativeTabNeedAnItem() {
        assertThrows(IllegalStateException.class, () -> content.block("no_item", Block::new).recipe((recipes, item) -> {
        }).register());
        assertThrows(IllegalStateException.class, () -> content.block("no_tab", Block::new).creativeTab().register());
        assertThrows(IllegalStateException.class,
                () -> content.block("late_item", Block::new).recipe((recipes, item) -> {
                }).simpleItem());
    }

    @Test
    void itemKeepsWhatWasDeclaredBeforeIt() {
        content.block("hatch", Block::new).model(BlockModel.existing(TEXTURE)).lang("Hatch")
                .tag(BlockTags.MINEABLE_WITH_PICKAXE).creativeTab().simpleItem().register();

        BlockDeclaration<?> block = content.blocks().getFirst();
        assertEquals(BlockModel.existing(TEXTURE), block.model());
        assertEquals("Hatch", block.lang());
        assertEquals(List.of(BlockTags.MINEABLE_WITH_PICKAXE), block.tags());
        assertEquals(1, content.creativeTabItems().size());
    }

    @Test
    void facesStartFromOneLookAndLayerOverlaysOnTop() {
        ModelLayer casing = ModelLayer.tinted(TEXTURE, 0x808080);
        ModelLayer front = ModelLayer.plain(Identifier.withDefaultNamespace("block/furnace_front"));
        ModelLayer top = ModelLayer.plain(Identifier.withDefaultNamespace("block/furnace_top"));
        BlockModel.Faces faces = (BlockModel.Faces) BlockModel.faces(List.of(casing))
                .over(Direction.NORTH, List.of(front)).face(Direction.UP, List.of(top)).horizontalFacing().build();

        assertEquals(List.of(casing, front), faces.face(Direction.NORTH));
        assertEquals(List.of(top), faces.face(Direction.UP));
        assertEquals(List.of(casing), faces.face(Direction.WEST));
        assertTrue(faces.horizontalFacing());
        assertThrows(IllegalArgumentException.class,
                () -> BlockModel.faces(List.of(ModelLayer.overlay(TEXTURE))).build(),
                "a face must start with a layer that is always drawn");
    }

    @Test
    void standaloneTagsAndTranslations() {
        content.itemTag(itemTag("dusts/nickel"));
        content.itemTag(itemTag("tools"), itemTag("tools/hammer"), itemTag("tools/wrench"));
        content.translation("material.test.tin", "Tin");

        assertEquals(
                List.of(new ItemTagDeclaration(itemTag("dusts/nickel"), List.of()), new ItemTagDeclaration(
                        itemTag("tools"), List.of(itemTag("tools/hammer"), itemTag("tools/wrench")))),
                content.itemTags());
        assertEquals(Map.of("material.test.tin", "Tin"), content.translations());
        assertThrows(IllegalStateException.class, () -> content.translation("material.test.tin", "Tin"),
                "a lang key declared twice");
    }

    @Test
    void tintedColumnTakesItsParticleFromTheEndUnlessGiven() {
        Identifier side = Identifier.withDefaultNamespace("block/quartz_pillar");
        assertEquals(new BlockModel.TintedColumn(side, TEXTURE, TEXTURE, 0x123456),
                BlockModel.tintedColumn(side, TEXTURE, 0x123456));
        assertEquals(new BlockModel.TintedColumn(side, TEXTURE, side, 0x123456),
                BlockModel.tintedColumn(side, TEXTURE, side, 0x123456));
    }

    private static TagKey<Item> itemTag(String path) {
        return TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("c", path));
    }

    private static final class FakeRegistrar<R> implements RegistryHelper.Registrar<R> {
        private final ResourceKey<? extends Registry<R>> registry;
        final List<String> names = new ArrayList<>();

        FakeRegistrar(ResourceKey<? extends Registry<R>> registry) {
            this.registry = registry;
        }

        @Override
        public <T extends R> RegistryEntry<R, T> register(String name, Supplier<? extends T> factory) {
            return entry(name);
        }

        @Override
        public <T extends R> RegistryEntry<R, T> register(String name, Function<Identifier, ? extends T> factory) {
            return entry(name);
        }

        private <T extends R> RegistryEntry<R, T> entry(String name) {
            names.add(name);
            Identifier id = Identifier.fromNamespaceAndPath("test", name);
            ResourceKey<R> key = ResourceKey.create(registry, id);
            return new RegistryEntry<>() {
                @Override
                public Identifier id() {
                    return id;
                }

                @Override
                public ResourceKey<R> key() {
                    return key;
                }

                @Override
                public Holder<R> holder() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public T get() {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }
}
