package dev.silencebound.maxtech.storage.neoforge.datagen;

import static dev.silencebound.maxtech.storage.neoforge.datagen.ModelBoxes.at;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.blockstates.MultiVariantGenerator;
import net.minecraft.client.data.models.model.ModelTemplate;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.data.models.model.TextureSlot;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.model.generators.template.ExtendedModelTemplateBuilder;

/**
 * The item terminal's models. The block is a full cube with a full-bright screen on its north face and the bezel on the
 * other five, turned to the terminal's horizontal facing. The part's item model is {@link PartModels}'.
 */
final class TerminalModels {
    private static final TextureSlot SCREEN = TextureSlot.create("screen");
    private static final TextureSlot BEZEL = TextureSlot.create("bezel");
    private static final int FULL_BRIGHT = 15;

    private TerminalModels() {
    }

    static void generate(BlockModelGenerators blockModels) {
        TextureMapping textures = new TextureMapping()
                .put(SCREEN, new Material(MaxtechStorage.id("block/terminal/screen")))
                .put(BEZEL, new Material(MaxtechStorage.id("block/terminal/bezel")))
                .put(TextureSlot.PARTICLE, new Material(MaxtechStorage.id("block/terminal/bezel")));
        Block block = TerminalContent.TERMINAL.get();
        Identifier model = panel(at(0, 0, 0), at(16, 16, 16)).create(block, textures, blockModels.modelOutput);
        blockModels.blockStateOutput
                .accept(MultiVariantGenerator.dispatch(block, BlockModelGenerators.plainVariant(model))
                        .with(BlockModelGenerators.ROTATION_HORIZONTAL_FACING));
    }

    /** One box from {@code from} to {@code to}: the screen on its north face, the bezel elsewhere. */
    private static ModelTemplate panel(float[] from, float[] to) {
        ExtendedModelTemplateBuilder builder = ExtendedModelTemplateBuilder.builder()
                .parent(Identifier.withDefaultNamespace("block/block")).requiredTextureSlot(SCREEN)
                .requiredTextureSlot(BEZEL).requiredTextureSlot(TextureSlot.PARTICLE);
        builder.element(
                element -> element.from(from[0], from[1], from[2]).to(to[0], to[1], to[2]).allFaces((face, f) -> {
                    if (face == Direction.NORTH) {
                        f.texture(SCREEN).uvs(0, 0, 16, 16).lightEmission(FULL_BRIGHT);
                    } else {
                        float[] uv = ModelBoxes.faceUv(face, from, to);
                        f.texture(BEZEL).uvs(uv[0], uv[1], uv[2], uv[3]);
                    }
                    if (onBlockFace(face, from, to)) {
                        f.cullface(face);
                    }
                }));
        return builder.build();
    }

    private static boolean onBlockFace(Direction face, float[] from, float[] to) {
        return switch (face) {
            case DOWN -> from[1] <= 0;
            case UP -> to[1] >= 16;
            case NORTH -> from[2] <= 0;
            case SOUTH -> to[2] >= 16;
            case WEST -> from[0] <= 0;
            case EAST -> to[0] >= 16;
        };
    }
}
