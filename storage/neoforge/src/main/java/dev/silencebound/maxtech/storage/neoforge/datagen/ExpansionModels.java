package dev.silencebound.maxtech.storage.neoforge.datagen;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.controller.module.ExpansionBlock;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.ExpansionItem;
import java.util.Set;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.MultiVariant;
import net.minecraft.client.data.models.blockstates.MultiVariantGenerator;
import net.minecraft.client.data.models.blockstates.PropertyDispatch;
import net.minecraft.client.data.models.model.ModelLocationUtils;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.data.models.model.TextureSlot;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.model.generators.template.ExtendedModelTemplateBuilder;
import org.jspecify.annotations.Nullable;

/**
 * The models of the blocks that were controller expansions (M7R item 8). A network device has two: idle
 * ({@link ExpansionBlock#LOAD} 0) shows its own face on the four sides (casing on top and bottom); active (any other
 * band) adds an overlay that lights the edge strips and its symbol ({@code block/controller/<kind>_glow_1}) on an
 * element with vanilla's {@code light_emission} 15; the item shows it active. A retired expansion block has one dark
 * model for every band, since it never lights up.
 */
final class ExpansionModels {
    private static final TextureSlot GLOW = TextureSlot.create("glow");
    private static final Identifier TOP = MaxtechStorage.id("block/storage_controller_side");

    private ExpansionModels() {
    }

    static void generate(BlockModelGenerators blockModels) {
        for (var entry : ExpansionContent.ALL) {
            Block block = entry.get();
            Identifier side = MaxtechStorage.id("block/controller/" + entry.get().kind().getSerializedName());
            MultiVariant dark = BlockModelGenerators
                    .plainVariant(model(blockModels, ModelLocationUtils.getModelLocation(block), side, TOP, null));
            blockModels.blockStateOutput.accept(MultiVariantGenerator.dispatch(block)
                    .with(PropertyDispatch.initial(ExpansionBlock.LOAD).generate(band -> dark)));
        }
        for (var entry : ExpansionContent.DEVICES) {
            Block block = entry.get();
            String kind = entry.get().kind().getSerializedName();
            Identifier side = MaxtechStorage.id("block/controller/" + kind);
            MultiVariant idle = BlockModelGenerators.plainVariant(
                    model(blockModels, ModelLocationUtils.getModelLocation(block, "_load_0"), side, TOP, null));
            MultiVariant active = BlockModelGenerators
                    .plainVariant(model(blockModels, ModelLocationUtils.getModelLocation(block), side, TOP,
                            MaxtechStorage.id("block/controller/" + kind + "_glow_1")));
            blockModels.blockStateOutput.accept(MultiVariantGenerator.dispatch(block)
                    .with(PropertyDispatch.initial(ExpansionBlock.LOAD).generate(band -> band == 0 ? idle : active)));
        }
    }

    /**
     * A full cube with {@code side} on the four sides and {@code end} on top and bottom, and the full-bright
     * {@code glow} overlay on the sides only; no overlay for a null glow.
     */
    private static Identifier model(BlockModelGenerators blockModels, Identifier id, Identifier side, Identifier end,
            @Nullable Identifier glow) {
        ExtendedModelTemplateBuilder builder = ExtendedModelTemplateBuilder.builder()
                .parent(Identifier.withDefaultNamespace("block/block")).requiredTextureSlot(TextureSlot.SIDE)
                .requiredTextureSlot(TextureSlot.END).requiredTextureSlot(TextureSlot.PARTICLE)
                .element(element -> element.from(0, 0, 0).to(16, 16, 16)
                        .allFaces((face, faceBuilder) -> faceBuilder
                                .texture(face.getAxis() == Direction.Axis.Y ? TextureSlot.END : TextureSlot.SIDE)
                                .cullface(face)));
        TextureMapping textures = new TextureMapping().put(TextureSlot.SIDE, new Material(side))
                .put(TextureSlot.END, new Material(end)).put(TextureSlot.PARTICLE, new Material(side));
        if (glow != null) {
            Set<Direction> skipped = Set.of(Direction.UP, Direction.DOWN);
            builder = builder.requiredTextureSlot(GLOW)
                    .element(element -> element.from(0, 0, 0).to(16, 16, 16)
                            .allFacesExcept((face, faceBuilder) -> faceBuilder.texture(GLOW).cullface(face), skipped)
                            .lightEmission(15));
            textures.put(GLOW, new Material(glow));
        }
        return builder.build().create(id, textures, blockModels.modelOutput);
    }
}
