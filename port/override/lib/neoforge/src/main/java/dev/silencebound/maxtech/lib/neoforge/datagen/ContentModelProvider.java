// port-override: base=f01f9aaa01d61be8
package dev.silencebound.maxtech.lib.neoforge.datagen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.serialization.JsonOps;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.content.BlockDeclaration;
import dev.silencebound.maxtech.lib.content.BlockModel;
import dev.silencebound.maxtech.lib.content.Content;
import dev.silencebound.maxtech.lib.content.ItemDeclaration;
import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.content.ModelLayer;
import dev.silencebound.maxtech.lib.neoforge.client.ConduitModel;
import dev.silencebound.maxtech.lib.shim.client.render.Transparency;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.model.generators.BlockModelBuilder;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.CustomLoaderBuilder;
import net.neoforged.neoforge.client.model.generators.ItemModelBuilder;
import net.neoforged.neoforge.client.model.generators.ModelBuilder;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.client.model.generators.loaders.DynamicFluidContainerModelBuilder;
import net.neoforged.neoforge.client.model.generators.loaders.ItemLayerModelBuilder;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforgespi.language.IModFileInfo;

/**
 * Block states and models for a mod's declared content. A subclass adding hand-written models calls
 * {@code super.registerStatesAndModels} first.
 * <p>
 * 1.21.1 port of 26.1's model generators: block states name model files, item models live under {@code models/item}
 * (no item model definitions), and baked tints are written into the models ({@code neoforge_data} colours), since
 * 1.21.1 models cannot name a constant tint. A model's chunk layer is its {@code render_type}, picked from its
 * textures' pixels as 26.1 picks a quad's (cutout for transparent pixels, translucent for partly transparent ones).
 * Like 26.1's model provider it fails when a declared block has no block state or a declared item no model.
 */
public class ContentModelProvider extends BlockStateProvider {
    /** The texture slots of a generated item model; vanilla's item model generator reads up to five. */
    private static final String[] ITEM_LAYERS = {"layer0", "layer1", "layer2", "layer3", "layer4"};
    private static final ResourceLocation BLOCK = ResourceLocation.withDefaultNamespace("block/block");
    private static final ResourceLocation GENERATED = ResourceLocation.withDefaultNamespace("item/generated");

    private final Content content;
    private final Set<Item> itemModelsWritten = new HashSet<>();
    private Predicate<ResourceLocation> textureExists = texture -> {
        throw new IllegalStateException("No resources to look up optional model layer " + texture + " in");
    };
    private Function<ResourceLocation, Optional<InputStream>> textureFiles = texture -> Optional.empty();
    private final Map<ResourceLocation, Transparency> transparencies = new HashMap<>();

    public ContentModelProvider(PackOutput output, Content content) {
        // Validation of parents and textures off, as 26.1's model provider has none.
        super(output, content.modId(), new ExistingFileHelper(List.of(), Set.of(), false, null, null));
        this.content = content;
    }

    /**
     * Where optional model layers ({@link dev.silencebound.maxtech.lib.content.ModelLayer#optional()}) and textures'
     * pixels are looked up: the datagen run's client resources, which hold the generating mod's own textures
     * ({@code --existing}), and the jar of the loaded mod whose id is the texture's namespace. The run's resources leave
     * out other mods, so without the second an addon's models of another mod's materials would lose the overlays that
     * mod ships.
     */
    void textures(ResourceManager resources) {
        this.textureExists = texture -> {
            ResourceLocation file = texture.withPath(path -> "textures/" + path + ".png");
            return resources.getResource(file).isPresent() || shippedBy(texture.getNamespace(), file).isPresent();
        };
        this.textureFiles = texture -> {
            ResourceLocation file = texture.withPath(path -> "textures/" + path + ".png");
            try {
                Optional<Resource> resource = resources.getResource(file);
                if (resource.isPresent()) {
                    return Optional.of(resource.get().open());
                }
                Optional<Path> shipped = shippedBy(texture.getNamespace(), file);
                return shipped.isPresent() ? Optional.of(Files.newInputStream(shipped.get())) : Optional.empty();
            } catch (IOException e) {
                throw new UncheckedIOException("Reading texture " + texture, e);
            }
        };
    }

    private static Optional<Path> shippedBy(String modId, ResourceLocation file) {
        IModFileInfo mod = ModList.get().getModFileById(modId);
        if (mod == null) {
            return Optional.empty();
        }
        Path path = mod.getFile().findResource("assets", modId, file.getPath());
        return Files.exists(path) ? Optional.of(path) : Optional.empty();
    }

    @Override
    protected void registerStatesAndModels() {
        itemModelsWritten.clear();
        models(textureExists);
        validate();
    }

    /** Fails like 26.1's model provider when a declared block or item got nothing. */
    private void validate() {
        List<ResourceLocation> blocks = new ArrayList<>();
        for (Holder<Block> block : ContentDatagen.knownBlocks(content)) {
            if (!registeredBlocks.containsKey(block.value())) {
                blocks.add(BuiltInRegistries.BLOCK.getKey(block.value()));
            }
        }
        if (!blocks.isEmpty()) {
            throw new IllegalStateException("Missing blockstate definitions for: " + blocks);
        }
        List<ResourceLocation> items = new ArrayList<>();
        for (Holder<Item> holder : ContentDatagen.knownItems(content)) {
            Item item = holder.value();
            if (!itemModelsWritten.contains(item)) {
                if (item instanceof BlockItem blockItem) {
                    // 26.1 draws a block item without a model of its own with its block's model.
                    ResourceLocation block = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock());
                    itemModel(item).parent(new ModelFile.UncheckedModelFile(block.withPrefix("block/")));
                } else {
                    items.add(BuiltInRegistries.ITEM.getKey(item));
                }
            }
        }
        if (!items.isEmpty()) {
            throw new IllegalStateException("Missing item model definitions for: " + items);
        }
    }

    /**
     * Writes the declared block and item models; {@code textureExists} decides whether an optional layer
     * ({@link ModelLayer#optional()}) is drawn.
     */
    private void models(Predicate<ResourceLocation> textureExists) {
        for (BlockDeclaration<?> declaration : ContentDatagen.blocks(content)) {
            BlockModel model = declaration.model();
            if (model == null) {
                continue;
            }
            Block block = declaration.entry().get();
            switch (model) {
                case BlockModel.CubeAll(ResourceLocation texture) ->
                    simpleBlock(block, layered(blockModel(block).parent(existing("block/cube_all"))
                            .texture("all", texture), texture));
                case BlockModel.TintedCubeAll(ResourceLocation texture, int rgb) -> tintedCube(block, texture, rgb);
                case BlockModel.LayeredCube(List<ModelLayer> layers) ->
                    layeredCube(block, present(layers, textureExists));
                case BlockModel.Faces(Map<Direction, List<ModelLayer>> faces, boolean horizontalFacing) ->
                    faces(block, faces, horizontalFacing, textureExists);
                case BlockModel.FacingColumn(ResourceLocation side, ResourceLocation end) ->
                    directionalBlock(block, layered(blockModel(block).parent(existing("block/cube_column"))
                            .texture("side", side).texture("end", end), side, end));
                case BlockModel.TintedColumn(ResourceLocation side, ResourceLocation end, ResourceLocation particle, int rgb) ->
                    tintedColumn(block, side, end, particle, rgb);
                case BlockModel.Orientable(ResourceLocation front, ResourceLocation side, ResourceLocation top) ->
                    horizontalBlock(block, layered(blockModel(block).parent(existing("block/orientable"))
                            .texture("front", front).texture("side", side).texture("top", top), front, side, top));
                case BlockModel.ParticleOnly(ResourceLocation particle) ->
                    simpleBlock(block, blockModel(block).texture("particle", particle));
                case BlockModel.Existing(ResourceLocation existing) ->
                    simpleBlock(block, new ModelFile.UncheckedModelFile(existing));
                case BlockModel.Child(ResourceLocation parent) ->
                    simpleBlock(block, blockModel(block).parent(new ModelFile.UncheckedModelFile(parent)));
                // Every conduit block's model names Lib's conduit model loader with its look; the model draws it from
                // block entity data.
                case BlockModel.Conduit(ConduitLook look) ->
                    simpleBlock(block, blockModel(block).customLoader((parent, files) -> new ConduitLoader(parent,
                            files, look)).end());
            }
        }
        Map<ResourceLocation, List<ResourceLocation>> sharedModels = new HashMap<>();
        for (ItemDeclaration<?> declaration : ContentDatagen.items(content)) {
            ItemModel model = declaration.model();
            if (model == null) {
                continue;
            }
            Item item = declaration.entry().get();
            switch (model) {
                case ItemModel.Flat(ResourceLocation texture) ->
                    itemModel(item).parent(existing(GENERATED)).texture("layer0", texture);
                case ItemModel.Existing(ResourceLocation existing) -> {
                    // 26.1's item model definition names the model; here the item's own model file is a child of it,
                    // unless it is that file.
                    if (!existing.equals(itemModelId(item))) {
                        itemModel(item).parent(new ModelFile.UncheckedModelFile(existing));
                    } else {
                        itemModelsWritten.add(item);
                    }
                }
                case ItemModel.TintedFlat(ResourceLocation texture, int rgb) ->
                    itemModel(item).parent(existing(GENERATED)).texture("layer0", texture)
                            .customLoader(ItemLayerModelBuilder::begin).color(0xFF000000 | rgb, 0).end();
                case ItemModel.Layered layered ->
                    layeredItem(sharedModels, item, layered, present(layered.layers(), textureExists));
                // The fluid's tint needs DynamicFluidContainerModel.Colors registered for the item on 1.21.1.
                case ItemModel.FluidContainer container -> itemModel(item).parent(existing(GENERATED))
                        .texture("base", container.base()).texture("fluid", container.fluidMask())
                        .customLoader(DynamicFluidContainerModelBuilder::begin).fluid(container.fluid().get())
                        .flipGas(container.flipGas()).coverIsMask(true).applyFluidLuminosity(true).applyTint(true)
                        .end();
            }
        }
    }

    private BlockModelBuilder blockModel(Block block) {
        return models().getBuilder(BuiltInRegistries.BLOCK.getKey(block).withPrefix("block/").toString());
    }

    private static ResourceLocation itemModelId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).withPrefix("item/");
    }

    private ItemModelBuilder itemModel(Item item) {
        itemModelsWritten.add(item);
        return itemModels().getBuilder(itemModelId(item).toString());
    }

    private static ModelFile existing(String vanilla) {
        return existing(ResourceLocation.withDefaultNamespace(vanilla));
    }

    private static ModelFile existing(ResourceLocation model) {
        return new ModelFile.UncheckedModelFile(model);
    }

    /** {@code model} drawn in the chunk layer {@code textures}' pixels call for. */
    private <T extends ModelBuilder<T>> T layered(T model, ResourceLocation... textures) {
        Transparency transparency = Transparency.NONE;
        for (ResourceLocation texture : textures) {
            transparency = transparency.or(transparency(texture));
        }
        if (transparency.hasTranslucent()) {
            model.renderType("minecraft:translucent");
        } else if (transparency.hasTransparent()) {
            model.renderType("minecraft:cutout");
        }
        return model;
    }

    private Transparency transparency(ResourceLocation texture) {
        Transparency known = transparencies.get(texture);
        if (known == null) {
            Optional<InputStream> file = textureFiles.apply(texture);
            known = Transparency.NONE;
            if (file.isPresent()) {
                try (InputStream in = file.get(); NativeImage image = NativeImage.read(in)) {
                    known = Transparency.of(image);
                } catch (IOException e) {
                    throw new UncheckedIOException("Reading texture " + texture, e);
                }
            }
            transparencies.put(texture, known);
        }
        return known;
    }

    /** A cube with the colour baked into its quads (NeoForge face data), like the machine casing. */
    private void tintedCube(Block block, ResourceLocation texture, int rgb) {
        int argb = 0xFF000000 | rgb;
        BlockModelBuilder model = blockModel(block).parent(existing(BLOCK)).texture("all", texture)
                .texture("particle", texture);
        model.element().from(0, 0, 0).to(16, 16, 16)
                .allFaces((side, face) -> face.texture("#all").cullface(side).color(argb));
        simpleBlock(block, layered(model, texture));
    }

    /** A column with the colour baked into its quads, the end texture on top and bottom. */
    private void tintedColumn(Block block, ResourceLocation side, ResourceLocation end, ResourceLocation particle,
            int rgb) {
        int argb = 0xFF000000 | rgb;
        BlockModelBuilder model = blockModel(block).parent(existing(BLOCK)).texture("side", side)
                .texture("end", end).texture("particle", particle);
        model.element().from(0, 0, 0).to(16, 16, 16).allFaces((face, builder) -> builder
                .texture(face.getAxis().isVertical() ? "#end" : "#side").cullface(face).color(argb));
        simpleBlock(block, layered(model, side, end));
    }

    /** {@code layers} without the optional ones whose texture does not exist. */
    private static List<ModelLayer> present(List<ModelLayer> layers, Predicate<ResourceLocation> textureExists) {
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
     * name the same textures. 26.1 keeps the tints per item in its item model definition; here an item with tints gets
     * its own model with the colours baked into its layers, and an untinted one a child of the shared model.
     */
    private void layeredItem(Map<ResourceLocation, List<ResourceLocation>> written, Item item,
            ItemModel.Layered layered, List<ModelLayer> layers) {
        List<ResourceLocation> textures = new ArrayList<>(layers.size());
        for (ModelLayer layer : layers) {
            textures.add(layer.texture());
        }
        ResourceLocation shared = layered.sharedModel();
        if (shared != null) {
            List<ResourceLocation> previous = written.putIfAbsent(shared, textures);
            if (previous == null) {
                layeredItemModel(itemModels().getBuilder(shared.toString()), textures);
            } else if (!previous.equals(textures)) {
                throw new IllegalStateException(
                        "Shared item model " + shared + " declared with textures " + previous + " and " + textures);
            }
        }
        // A layer's tint index is its position (ItemModelGenerator), so a layer's colour goes on that index; layers
        // without a tint stay white.
        boolean tinted = false;
        for (ModelLayer layer : layers) {
            tinted |= layer.tint().isPresent();
        }
        if (!tinted && shared != null) {
            if (!shared.equals(itemModelId(item))) {
                itemModel(item).parent(existing(shared));
            } else {
                itemModelsWritten.add(item);
            }
            return;
        }
        ItemModelBuilder model = layeredItemModel(itemModel(item), textures);
        if (tinted) {
            ItemLayerModelBuilder<ItemModelBuilder> loader = model.customLoader(ItemLayerModelBuilder::begin);
            for (int i = 0; i < layers.size(); i++) {
                int index = i;
                layers.get(i).tint().ifPresent(rgb -> loader.color(0xFF000000 | rgb, index));
            }
        }
    }

    private static ItemModelBuilder layeredItemModel(ItemModelBuilder model, List<ResourceLocation> textures) {
        model.parent(existing(GENERATED));
        for (int i = 0; i < textures.size(); i++) {
            model.texture(ITEM_LAYERS[i], textures.get(i));
        }
        return model;
    }

    /**
     * Full cubes drawn over each other, one per layer, with each layer's tint baked into its quads like
     * {@link #tintedCube}. The first layer is the particle.
     */
    private void layeredCube(Block block, List<ModelLayer> layers) {
        BlockModelBuilder model = blockModel(block).parent(existing(BLOCK)).texture("particle",
                layers.getFirst().texture());
        List<ResourceLocation> textures = new ArrayList<>();
        for (int i = 0; i < layers.size(); i++) {
            ModelLayer layer = layers.get(i);
            String slot = "layer" + i;
            model.texture(slot, layer.texture());
            textures.add(layer.texture());
            model.element().from(0, 0, 0).to(16, 16, 16).allFaces((side, face) -> {
                face.texture("#" + slot).cullface(side);
                layer.tint().ifPresent(rgb -> face.color(0xFF000000 | rgb));
            });
        }
        simpleBlock(block, layered(model, textures.toArray(new ResourceLocation[0])));
    }

    /**
     * Full cubes drawn over each other: the n-th element has the n-th layer of every face that has that many, so a
     * face's overlays sit on its base like {@link #layeredCube}'s. With {@code horizontalFacing}, the north face is the
     * front and the block state turns it.
     */
    private void faces(Block block, Map<Direction, List<ModelLayer>> declared, boolean horizontalFacing,
            Predicate<ResourceLocation> textureExists) {
        Direction[] directions = Direction.values();
        Map<Direction, List<ModelLayer>> faces = new EnumMap<>(Direction.class);
        int elements = 0;
        for (Direction face : directions) {
            List<ModelLayer> layers = present(declared.getOrDefault(face, List.of()), textureExists);
            faces.put(face, layers);
            elements = Math.max(elements, layers.size());
        }
        BlockModelBuilder model = blockModel(block).parent(existing(BLOCK)).texture("particle",
                faces.getOrDefault(Direction.EAST, List.of()).getFirst().texture());
        List<ResourceLocation> textures = new ArrayList<>();
        for (int i = 0; i < elements; i++) {
            BlockModelBuilder.ElementBuilder element = model.element().from(0, 0, 0).to(16, 16, 16);
            for (Direction face : directions) {
                List<ModelLayer> faceLayers = faces.getOrDefault(face, List.of());
                if (faceLayers.size() > i) {
                    ModelLayer layer = faceLayers.get(i);
                    String slot = face.getSerializedName() + i;
                    model.texture(slot, layer.texture());
                    textures.add(layer.texture());
                    BlockModelBuilder.ElementBuilder.FaceBuilder builder = element.face(face).texture("#" + slot)
                            .cullface(face);
                    layer.tint().ifPresent(rgb -> builder.color(0xFF000000 | rgb));
                }
            }
        }
        layered(model, textures.toArray(new ResourceLocation[0]));
        if (horizontalFacing) {
            horizontalBlock(block, model);
        } else {
            simpleBlock(block, model);
        }
    }

    /** {@code {"loader": "maxtech_lib:conduit", <the look's fields>}}, read by {@link ConduitModel.Unbaked#LOADER}. */
    private static final class ConduitLoader extends CustomLoaderBuilder<BlockModelBuilder> {
        private final ConduitLook look;

        ConduitLoader(BlockModelBuilder parent, ExistingFileHelper files, ConduitLook look) {
            super(ConduitModel.Unbaked.ID, parent, files, false);
            this.look = look;
        }

        @Override
        public JsonObject toJson(JsonObject json) {
            json = super.toJson(json);
            JsonElement fields = ConduitLook.CODEC.codec().encodeStart(JsonOps.INSTANCE, look).getOrThrow();
            for (Map.Entry<String, JsonElement> field : fields.getAsJsonObject().entrySet()) {
                json.add(field.getKey(), field.getValue());
            }
            return json;
        }
    }
}
