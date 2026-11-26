package dev.silencebound.maxtech.lib.neoforge.datagen;

import dev.silencebound.maxtech.lib.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.content.BlockDeclaration;
import dev.silencebound.maxtech.lib.content.BlockModel;
import dev.silencebound.maxtech.lib.content.Content;
import dev.silencebound.maxtech.lib.content.FluidTagDeclaration;
import dev.silencebound.maxtech.lib.content.ItemDeclaration;
import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.content.ItemTagDeclaration;
import dev.silencebound.maxtech.lib.content.ModelLayer;
import dev.silencebound.maxtech.lib.content.RecipeContext;
import dev.silencebound.maxtech.lib.content.ContentExtension;
import dev.silencebound.maxtech.lib.neoforge.client.ConduitModel;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.MultiVariant;
import net.minecraft.client.data.models.blockstates.MultiVariantGenerator;
import net.minecraft.client.data.models.model.ItemModelUtils;
import net.minecraft.client.data.models.model.ModelLocationUtils;
import net.minecraft.client.data.models.model.ModelTemplate;
import net.minecraft.client.data.models.model.ModelTemplates;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.data.models.model.TextureSlot;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.neoforged.neoforge.client.model.generators.blockstate.CustomBlockStateModelBuilder;
import net.neoforged.neoforge.client.model.generators.template.ExtendedModelTemplateBuilder;
import net.neoforged.neoforge.client.model.item.DynamicFluidContainerModel;
import net.neoforged.neoforge.common.data.LanguageProvider;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * Turns a mod's {@link Content} declarations into datagen output. A mod's datagen calls {@link #providers} from its
 * {@code GatherDataEvent.Client} handler; each provider writes what the declarations say, and a mod that writes more by
 * hand passes a subclass of the matching {@code Content*Provider} (one model, language and tag provider per mod, since
 * providers of the same kind share a name or an output file).
 */
public final class ContentDatagen {
    /** The texture slots of a generated item model; vanilla's item model generator reads up to five. */
    private static final TextureSlot[] ITEM_LAYERS = {TextureSlot.LAYER0, TextureSlot.LAYER1, TextureSlot.LAYER2,
            TextureSlot.create("layer3"), TextureSlot.create("layer4")};

    private ContentDatagen() {
    }

    /** The providers for {@code content}; override any of them, then {@link Providers#register} them. */
    public static Providers providers(Content content) {
        return new Providers(content);
    }

    /**
     * The builders whose declarations a mod's datagen writes: its own, then those its {@link ContentExtension}s add,
     * such as a material mod's builder for what an addon's materials bring with them. Those items are in the material
     * mod's namespace, but their files ship with the addon, so the material mod's generated files hold only its own.
     */
    static List<Content> declarations(Content content) {
        List<Content> declarations = new ArrayList<>();
        declarations.add(content);
        for (ContentExtension extension : ContentExtension.all()) {
            declarations.addAll(extension.declarations(content.modId()));
        }
        return declarations;
    }

    /** Every declared block; the model provider validates that each gets a block state. */
    static List<Holder<Block>> knownBlocks(Content content) {
        List<Holder<Block>> blocks = new ArrayList<>();
        for (BlockDeclaration<?> block : blocks(content)) {
            blocks.add(block.entry().holder());
        }
        return blocks;
    }

    /** Every declared item, block items included; the model provider validates that each gets a model. */
    static List<Holder<Item>> knownItems(Content content) {
        List<Holder<Item>> items = new ArrayList<>();
        for (ItemDeclaration<?> item : items(content)) {
            items.add(item.entry().holder());
        }
        return items;
    }

    /** The blocks of every builder in {@link #declarations}. */
    static List<BlockDeclaration<?>> blocks(Content content) {
        List<BlockDeclaration<?>> blocks = new ArrayList<>();
        for (Content declaring : declarations(content)) {
            blocks.addAll(declaring.blocks());
        }
        return blocks;
    }

    /** The items of every builder in {@link #declarations}. */
    static List<ItemDeclaration<?>> items(Content content) {
        List<ItemDeclaration<?>> items = new ArrayList<>();
        for (Content declaring : declarations(content)) {
            items.addAll(declaring.items());
        }
        return items;
    }

    /**
     * Writes the declared block and item models; {@code textureExists} decides whether an optional layer
     * ({@link ModelLayer#optional()}) is drawn.
     */
    static void models(Content content, BlockModelGenerators blockModels, ItemModelGenerators itemModels,
            Predicate<Identifier> textureExists) {
        for (BlockDeclaration<?> declaration : blocks(content)) {
            BlockModel model = declaration.model();
            if (model == null) {
                continue;
            }
            Block block = declaration.entry().get();
            switch (model) {
                case BlockModel.CubeAll(Identifier texture) -> cube(blockModels, block, texture);
                case BlockModel.TintedCubeAll(Identifier texture, int rgb) ->
                    tintedCube(blockModels, block, texture, rgb);
                case BlockModel.LayeredCube(List<ModelLayer> layers) ->
                    layeredCube(blockModels, block, present(layers, textureExists));
                case BlockModel.Faces(Map<Direction, List<ModelLayer>> faces, boolean horizontalFacing) ->
                    faces(blockModels, block, faces, horizontalFacing, textureExists);
                case BlockModel.FacingColumn(Identifier side, Identifier end) ->
                    facingColumn(blockModels, block, side, end);
                case BlockModel.TintedColumn(Identifier side, Identifier end, Identifier particle, int rgb) ->
                    tintedColumn(blockModels, block, side, end, particle, rgb);
                case BlockModel.Orientable(Identifier front, Identifier side, Identifier top) ->
                    orientable(blockModels, block, front, side, top);
                case BlockModel.ParticleOnly(Identifier particle) ->
                    simpleBlock(blockModels, block, ModelTemplates.PARTICLE_ONLY.create(block,
                            TextureMapping.particle(new Material(particle)), blockModels.modelOutput));
                case BlockModel.Existing(Identifier existing) -> simpleBlock(blockModels, block, existing);
                case BlockModel.Child(Identifier parent) ->
                    simpleBlock(blockModels, block, new ModelTemplate(Optional.of(parent), Optional.empty())
                            .create(block, new TextureMapping(), blockModels.modelOutput));
                // Every conduit block names Lib's conduit model type with its look; the model draws it from block
                // entity data.
                case BlockModel.Conduit(ConduitLook look) ->
                    blockModels.blockStateOutput.accept(MultiVariantGenerator.dispatch(block,
                            MultiVariant.of(new CustomBlockStateModelBuilder.Simple(new ConduitModel.Unbaked(look)))));
            }
        }
        Map<Identifier, List<Identifier>> sharedModels = new HashMap<>();
        for (ItemDeclaration<?> declaration : items(content)) {
            ItemModel model = declaration.model();
            if (model == null) {
                continue;
            }
            Item item = declaration.entry().get();
            switch (model) {
                case ItemModel.Flat(Identifier texture) -> itemModels.itemModelOutput.accept(item,
                        ItemModelUtils.plainModel(flatModel(itemModels, item, texture)));
                case ItemModel.Existing(Identifier existing) ->
                    itemModels.itemModelOutput.accept(item, ItemModelUtils.plainModel(existing));
                case ItemModel.TintedFlat(Identifier texture, int rgb) ->
                    itemModels.itemModelOutput.accept(item, ItemModelUtils
                            .tintedModel(flatModel(itemModels, item, texture), ItemModelUtils.constantTint(rgb)));
                case ItemModel.Layered layered ->
                    layeredItem(itemModels, sharedModels, item, layered, present(layered.layers(), textureExists));
                case ItemModel.FluidContainer container -> itemModels.itemModelOutput.accept(item,
                        new DynamicFluidContainerModel.Unbaked(
                                new DynamicFluidContainerModel.Textures(Optional.empty(),
                                        Optional.of(new Material(container.base())),
                                        Optional.of(new Material(container.fluidMask())), Optional.empty()),
                                container.fluid().get(), container.flipGas(), true, true));
            }
        }
    }

    static void language(Content content, LanguageProvider language) {
        for (BlockDeclaration<?> block : blocks(content)) {
            String name = block.lang();
            if (name != null) {
                language.add(block.entry().get(), name);
            }
        }
        for (ItemDeclaration<?> item : items(content)) {
            String name = item.lang();
            if (name != null) {
                language.add(item.entry().get(), name);
            }
        }
        for (Content declaring : declarations(content)) {
            for (Map.Entry<String, String> line : declaring.translations().entrySet()) {
                language.add(line.getKey(), line.getValue());
            }
        }
    }

    /** Hands each declared block tag to {@code tag}, in declaration order (tag files keep it). */
    static void blockTags(Content content, BiConsumer<TagKey<Block>, Block> tag) {
        for (BlockDeclaration<?> block : blocks(content)) {
            for (TagKey<Block> key : block.tags()) {
                tag.accept(key, block.entry().get());
            }
        }
    }

    /** Hands each declared item tag to {@code tag}, in declaration order (tag files keep it). */
    static void itemTags(Content content, BiConsumer<TagKey<Item>, Item> tag) {
        for (ItemDeclaration<?> item : items(content)) {
            for (TagKey<Item> key : item.tags()) {
                tag.accept(key, item.entry().get());
            }
        }
    }

    /** Hands each declared fluid tag entry to {@code tag}, in declaration order. */
    static void fluidTags(Content content, BiConsumer<TagKey<Fluid>, Fluid> tag) {
        for (Content declaring : declarations(content)) {
            for (FluidTagDeclaration declaration : declaring.fluidTags()) {
                tag.accept(declaration.tag(), declaration.fluid().get());
            }
        }
    }

    /**
     * Hands each tag declared on its own to {@code tag} with the tags it includes, in declaration order; a tag
     * including none is still handed over, so it is written empty.
     */
    static void standaloneItemTags(Content content, BiConsumer<TagKey<Item>, List<TagKey<Item>>> tag) {
        for (Content declaring : declarations(content)) {
            for (ItemTagDeclaration declaration : declaring.itemTags()) {
                tag.accept(declaration.tag(), declaration.includes());
            }
        }
    }

    static void recipes(Content content, HolderGetter<Item> items, RecipeOutput output) {
        RecipeContext context = new RecipeContext(items, output, RecipeConditions::withoutMod);
        for (ItemDeclaration<?> item : items(content)) {
            item.buildRecipes(context);
        }
    }

    private static void simpleBlock(BlockModelGenerators blockModels, Block block, Identifier model) {
        blockModels.blockStateOutput
                .accept(BlockModelGenerators.createSimpleBlock(block, BlockModelGenerators.plainVariant(model)));
    }

    /** A plain cube; its block item picks the block model up automatically. */
    private static void cube(BlockModelGenerators blockModels, Block block, Identifier texture) {
        simpleBlock(blockModels, block, ModelTemplates.CUBE_ALL.create(block,
                TextureMapping.cube(new Material(texture)), blockModels.modelOutput));
    }

    /** A cube with the colour baked into its quads (NeoForge face data), like the machine casing. */
    private static void tintedCube(BlockModelGenerators blockModels, Block block, Identifier texture, int rgb) {
        int argb = 0xFF000000 | rgb;
        Identifier model = ExtendedModelTemplateBuilder.builder().parent(Identifier.withDefaultNamespace("block/block"))
                .requiredTextureSlot(TextureSlot.ALL).requiredTextureSlot(TextureSlot.PARTICLE)
                .element(element -> element.from(0, 0, 0).to(16, 16, 16)
                        .allFaces((side, face) -> face.texture(TextureSlot.ALL).cullface(side).color(argb)))
                .build().create(block, TextureMapping.cube(new Material(texture)), blockModels.modelOutput);
        simpleBlock(blockModels, block, model);
    }

    /** Vanilla's cube_column, which takes its particle from the side, turned to the block's facing. */
    private static void facingColumn(BlockModelGenerators blockModels, Block block, Identifier side, Identifier end) {
        TextureMapping textures = TextureMapping.column(new Material(side), new Material(end));
        Identifier model = ModelTemplates.CUBE_COLUMN.create(block, textures, blockModels.modelOutput);
        blockModels.blockStateOutput
                .accept(BlockModelGenerators.createSimpleBlock(block, BlockModelGenerators.plainVariant(model))
                        .with(BlockModelGenerators.ROTATIONS_COLUMN_WITH_FACING));
    }

    /** A column with the colour baked into its quads, the end texture on top and bottom. */
    private static void tintedColumn(BlockModelGenerators blockModels, Block block, Identifier side, Identifier end,
            Identifier particle, int rgb) {
        int argb = 0xFF000000 | rgb;
        Identifier model = ExtendedModelTemplateBuilder.builder().parent(Identifier.withDefaultNamespace("block/block"))
                .requiredTextureSlot(TextureSlot.SIDE).requiredTextureSlot(TextureSlot.END)
                .requiredTextureSlot(TextureSlot.PARTICLE)
                .element(element -> element.from(0, 0, 0).to(16, 16, 16)
                        .allFaces((face, builder) -> builder
                                .texture(face.getAxis().isVertical() ? TextureSlot.END : TextureSlot.SIDE)
                                .cullface(face).color(argb)))
                .build().create(block, TextureMapping.column(new Material(side), new Material(end))
                        .put(TextureSlot.PARTICLE, new Material(particle)), blockModels.modelOutput);
        simpleBlock(blockModels, block, model);
    }

    /** Vanilla's orientable cube, turned to the block's horizontal facing. */
    private static void orientable(BlockModelGenerators blockModels, Block block, Identifier front, Identifier side,
            Identifier top) {
        Identifier model = ModelTemplates.CUBE_ORIENTABLE.create(block,
                new TextureMapping().put(TextureSlot.FRONT, new Material(front))
                        .put(TextureSlot.SIDE, new Material(side)).put(TextureSlot.TOP, new Material(top)),
                blockModels.modelOutput);
        blockModels.blockStateOutput
                .accept(BlockModelGenerators.createSimpleBlock(block, BlockModelGenerators.plainVariant(model))
                        .with(BlockModelGenerators.ROTATION_HORIZONTAL_FACING));
    }

    /** {@code layers} without the optional ones whose texture does not exist. */
    private static List<ModelLayer> present(List<ModelLayer> layers, Predicate<Identifier> textureExists) {
        List<ModelLayer> present = new ArrayList<>(layers.size());
        for (ModelLayer layer : layers) {
            if (!layer.optional() || textureExists.test(layer.texture())) {
                present.add(layer);
            }
        }
        return present;
    }

    /**
     * A flat item of several layers. A shared model is written the first time an item names it, and later items must
     * name the same textures; the tints stay per item, in its item model definition.
     */
    private static void layeredItem(ItemModelGenerators itemModels, Map<Identifier, List<Identifier>> written,
            Item item, ItemModel.Layered layered, List<ModelLayer> layers) {
        List<Identifier> textures = new ArrayList<>(layers.size());
        for (ModelLayer layer : layers) {
            textures.add(layer.texture());
        }
        Identifier shared = layered.sharedModel();
        Identifier model;
        if (shared == null) {
            model = layeredItemModel(itemModels, ModelLocationUtils.getModelLocation(item), textures);
        } else {
            List<Identifier> previous = written.putIfAbsent(shared, textures);
            if (previous == null) {
                layeredItemModel(itemModels, shared, textures);
            } else if (!previous.equals(textures)) {
                throw new IllegalStateException(
                        "Shared item model " + shared + " declared with textures " + previous + " and " + textures);
            }
            model = shared;
        }
        // A layer's tint index is its position (ItemModelGenerator), and indexes past the list draw untinted, so the
        // list stops at the last tinted layer and untinted layers below it get white.
        int count = 0;
        for (int i = 0; i < layers.size(); i++) {
            if (layers.get(i).tint().isPresent()) {
                count = i + 1;
            }
        }
        if (count == 0) {
            itemModels.itemModelOutput.accept(item, ItemModelUtils.plainModel(model));
            return;
        }
        ItemTintSource[] tints = new ItemTintSource[count];
        for (int i = 0; i < count; i++) {
            tints[i] = ItemModelUtils.constantTint(layers.get(i).tint().orElse(0xFFFFFF));
        }
        itemModels.itemModelOutput.accept(item, ItemModelUtils.tintedModel(model, tints));
    }

    private static Identifier layeredItemModel(ItemModelGenerators itemModels, Identifier model,
            List<Identifier> textures) {
        TextureSlot[] slots = new TextureSlot[textures.size()];
        TextureMapping mapping = new TextureMapping();
        for (int i = 0; i < slots.length; i++) {
            slots[i] = ITEM_LAYERS[i];
            mapping.put(slots[i], new Material(textures.get(i)));
        }
        return ModelTemplates.createItem("generated", slots).create(model, mapping, itemModels.modelOutput);
    }

    /**
     * Full cubes drawn over each other, one per layer, with each layer's tint baked into its quads like
     * {@link #tintedCube}. The first layer is the particle.
     */
    private static void layeredCube(BlockModelGenerators blockModels, Block block, List<ModelLayer> layers) {
        ExtendedModelTemplateBuilder builder = ExtendedModelTemplateBuilder.builder()
                .parent(Identifier.withDefaultNamespace("block/block")).requiredTextureSlot(TextureSlot.PARTICLE);
        TextureMapping mapping = new TextureMapping().put(TextureSlot.PARTICLE,
                new Material(layers.getFirst().texture()));
        for (int i = 0; i < layers.size(); i++) {
            ModelLayer layer = layers.get(i);
            TextureSlot slot = TextureSlot.create("layer" + i);
            builder.requiredTextureSlot(slot);
            mapping.put(slot, new Material(layer.texture()));
            builder.element(element -> element.from(0, 0, 0).to(16, 16, 16).allFaces((side, face) -> {
                face.texture(slot).cullface(side);
                layer.tint().ifPresent(rgb -> face.color(0xFF000000 | rgb));
            }));
        }
        simpleBlock(blockModels, block, builder.build().create(block, mapping, blockModels.modelOutput));
    }

    /**
     * Full cubes drawn over each other: the n-th element has the n-th layer of every face that has that many, so a
     * face's overlays sit on its base like {@link #layeredCube}'s. With {@code horizontalFacing}, the north face is the
     * front and the block state turns it.
     */
    private static void faces(BlockModelGenerators blockModels, Block block, Map<Direction, List<ModelLayer>> declared,
            boolean horizontalFacing, Predicate<Identifier> textureExists) {
        Direction[] directions = Direction.values();
        Map<Direction, List<ModelLayer>> faces = new EnumMap<>(Direction.class);
        int elements = 0;
        for (Direction face : directions) {
            List<ModelLayer> layers = present(declared.getOrDefault(face, List.of()), textureExists);
            faces.put(face, layers);
            elements = Math.max(elements, layers.size());
        }
        ExtendedModelTemplateBuilder builder = ExtendedModelTemplateBuilder.builder()
                .parent(Identifier.withDefaultNamespace("block/block")).requiredTextureSlot(TextureSlot.PARTICLE);
        TextureMapping mapping = new TextureMapping().put(TextureSlot.PARTICLE,
                new Material(faces.getOrDefault(Direction.EAST, List.of()).getFirst().texture()));
        for (int i = 0; i < elements; i++) {
            Map<Direction, TextureSlot> slots = new EnumMap<>(Direction.class);
            Map<Direction, ModelLayer> layers = new EnumMap<>(Direction.class);
            for (Direction face : directions) {
                List<ModelLayer> faceLayers = faces.getOrDefault(face, List.of());
                if (faceLayers.size() > i) {
                    TextureSlot slot = TextureSlot.create(face.getSerializedName() + i);
                    builder.requiredTextureSlot(slot);
                    mapping.put(slot, new Material(faceLayers.get(i).texture()));
                    slots.put(face, slot);
                    layers.put(face, faceLayers.get(i));
                }
            }
            builder.element(element -> {
                element.from(0, 0, 0).to(16, 16, 16);
                for (Map.Entry<Direction, ModelLayer> face : layers.entrySet()) {
                    TextureSlot slot = Objects.requireNonNull(slots.get(face.getKey()));
                    element.face(face.getKey(), faceBuilder -> {
                        faceBuilder.texture(slot).cullface(face.getKey());
                        face.getValue().tint().ifPresent(rgb -> faceBuilder.color(0xFF000000 | rgb));
                    });
                }
            });
        }
        Identifier model = builder.build().create(block, mapping, blockModels.modelOutput);
        if (horizontalFacing) {
            blockModels.blockStateOutput
                    .accept(BlockModelGenerators.createSimpleBlock(block, BlockModelGenerators.plainVariant(model))
                            .with(BlockModelGenerators.ROTATION_HORIZONTAL_FACING));
        } else {
            simpleBlock(blockModels, block, model);
        }
    }

    private static Identifier flatModel(ItemModelGenerators itemModels, Item item, Identifier texture) {
        return ModelTemplates.FLAT_ITEM.create(ModelLocationUtils.getModelLocation(item),
                TextureMapping.layer0(new Material(texture)), itemModels.modelOutput);
    }

    /**
     * The data providers for one mod's content. By default they write only what the declarations say; a mod passes its
     * own subclass of a provider to add what it writes by hand, and extra loot sub-providers.
     */
    public static final class Providers {
        private final Content content;
        private GatherDataEvent.DataProviderFromOutput<? extends ContentModelProvider> models;
        private GatherDataEvent.DataProviderFromOutput<? extends ContentLanguageProvider> language;
        private GatherDataEvent.DataProviderFromOutputLookup<? extends ContentBlockTagsProvider> blockTags;
        private GatherDataEvent.DataProviderFromOutputLookup<? extends ContentItemTagsProvider> itemTags;
        private GatherDataEvent.DataProviderFromOutputLookup<? extends ContentFluidTagsProvider> fluidTags;
        private final List<LootTableProvider.SubProviderEntry> loot = new ArrayList<>();

        private Providers(Content content) {
            this.content = content;
            this.models = output -> new ContentModelProvider(output, content);
            this.language = output -> new ContentLanguageProvider(output, content);
            this.blockTags = (output, registries) -> new ContentBlockTagsProvider(output, registries, content);
            this.itemTags = (output, registries) -> new ContentItemTagsProvider(output, registries, content);
            this.fluidTags = (output, registries) -> new ContentFluidTagsProvider(output, registries, content);
            this.loot.add(new LootTableProvider.SubProviderEntry(
                    registries -> new ContentBlockLoot(content, registries), LootContextParamSets.BLOCK));
        }

        public Providers models(GatherDataEvent.DataProviderFromOutput<? extends ContentModelProvider> models) {
            this.models = models;
            return this;
        }

        public Providers language(GatherDataEvent.DataProviderFromOutput<? extends ContentLanguageProvider> language) {
            this.language = language;
            return this;
        }

        public Providers blockTags(
                GatherDataEvent.DataProviderFromOutputLookup<? extends ContentBlockTagsProvider> blockTags) {
            this.blockTags = blockTags;
            return this;
        }

        public Providers itemTags(
                GatherDataEvent.DataProviderFromOutputLookup<? extends ContentItemTagsProvider> itemTags) {
            this.itemTags = itemTags;
            return this;
        }

        public Providers fluidTags(
                GatherDataEvent.DataProviderFromOutputLookup<? extends ContentFluidTagsProvider> fluidTags) {
            this.fluidTags = fluidTags;
            return this;
        }

        /** A loot sub-provider for tables the declarations don't cover, written with the declared ones. */
        public Providers loot(LootTableProvider.SubProviderEntry entry) {
            loot.add(entry);
            return this;
        }

        /** Registers models, language, block, item and fluid tags, crafting recipes and loot with {@code event}. */
        public void register(GatherDataEvent.Client event) {
            event.createProvider(models).textures(event.getResourceManager(PackType.CLIENT_RESOURCES));
            event.createProvider(language);
            event.createProvider(blockTags);
            event.createProvider(itemTags);
            event.createProvider(fluidTags);
            event.createProvider((output, registries) -> new ContentRecipeProvider.Runner(output, registries, content));
            List<LootTableProvider.SubProviderEntry> tables = List.copyOf(loot);
            event.createProvider((output, registries) -> new LootTableProvider(output, Set.of(), tables, registries));
        }
    }
}
