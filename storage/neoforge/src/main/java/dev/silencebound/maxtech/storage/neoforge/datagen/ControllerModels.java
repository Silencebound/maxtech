package dev.silencebound.maxtech.storage.neoforge.datagen;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.model.ModelLocationUtils;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.data.models.model.TextureSlot;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.model.generators.template.ExtendedModelTemplateBuilder;

/**
 * The controller's one model file (M7R item 7): a lone block's face, the framed plate ({@code plate_15}) on the cube
 * and the online glow plane on a cube 0.25 px smaller inside it, lit full-bright with vanilla's per-element
 * {@code light_emission}, so the grooves glow through the cut-outs. The item shows it; in the world the client replaces
 * every state's model with {@code ControllerFaceModel}, which adds connected textures, the status and the outward glow
 * phase, so this file is only its fallback.
 */
final class ControllerModels {
    private static final TextureSlot PLATE = TextureSlot.create("plate");
    private static final TextureSlot GLOW = TextureSlot.create("glow");
    /** The glow plane's distance behind the plate, in pixels; {@code ControllerFaceModel.GLOW_INSET}. */
    private static final float INSET = 0.25F;

    private ControllerModels() {
    }

    static void generate(BlockModelGenerators blockModels) {
        Block block = ControllerContent.CONTROLLER.get();
        Identifier plate = MaxtechStorage.id("block/controller/face/plate_15");
        Identifier model = ExtendedModelTemplateBuilder.builder().parent(Identifier.withDefaultNamespace("block/block"))
                .requiredTextureSlot(PLATE).requiredTextureSlot(GLOW).requiredTextureSlot(TextureSlot.PARTICLE)
                .element(element -> element.from(0, 0, 0).to(16, 16, 16)
                        .allFaces((face, builder) -> builder.texture(PLATE).cullface(face)))
                .element(element -> element.from(INSET, INSET, INSET).to(16 - INSET, 16 - INSET, 16 - INSET)
                        .allFaces((face, builder) -> builder.texture(GLOW).cullface(face)).lightEmission(15))
                .build().create(ModelLocationUtils.getModelLocation(block),
                        new TextureMapping().put(PLATE, new Material(plate))
                                .put(GLOW, new Material(MaxtechStorage.id("block/controller/face/glow_online_0")))
                                .put(TextureSlot.PARTICLE, new Material(plate)),
                        blockModels.modelOutput);
        blockModels.blockStateOutput
                .accept(BlockModelGenerators.createSimpleBlock(block, BlockModelGenerators.plainVariant(model)));
    }
}
