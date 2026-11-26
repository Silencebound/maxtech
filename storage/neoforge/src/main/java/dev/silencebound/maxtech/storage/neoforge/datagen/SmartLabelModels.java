package dev.silencebound.maxtech.storage.neoforge.datagen;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.smartlabel.SmartLabelBlock;
import dev.silencebound.maxtech.storage.smartlabel.SmartLabelContent;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.MultiVariant;
import net.minecraft.client.data.models.blockstates.ConditionBuilder;
import net.minecraft.client.data.models.blockstates.MultiPartGenerator;
import net.minecraft.client.data.models.model.ItemModelUtils;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.data.models.model.TextureSlot;
import net.minecraft.client.renderer.block.dispatch.VariantMutator;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.model.generators.template.ExtendedModelTemplateBuilder;
import org.jspecify.annotations.Nullable;

/**
 * The smart label's model: a plain 12 x 8 x 1 px plate against the back of its block space, drawn facing north (front
 * at z 15) and turned to all six facings as the display panel is, so its up and right match {@code DisplayWalls} and
 * the renderer's text. The front is a dark plate with a thin edge; the rest is the side texture. The item uses the
 * block model.
 */
final class SmartLabelModels {
    private static final TextureSlot FRONT = TextureSlot.FRONT;
    private static final TextureSlot SIDE = TextureSlot.SIDE;

    private SmartLabelModels() {
    }

    static void generate(BlockModelGenerators blockModels) {
        float halfWidth = SmartLabelBlock.WIDTH / 2F;
        float halfHeight = SmartLabelBlock.HEIGHT / 2F;
        float[] from = {8 - halfWidth, 8 - halfHeight, 16 - SmartLabelBlock.DEPTH};
        float[] to = {8 + halfWidth, 8 + halfHeight, 16};
        ExtendedModelTemplateBuilder builder = ExtendedModelTemplateBuilder.builder()
                .parent(Identifier.withDefaultNamespace("block/block")).requiredTextureSlot(FRONT)
                .requiredTextureSlot(SIDE).requiredTextureSlot(TextureSlot.PARTICLE);
        builder.element(element -> {
            element.from(from[0], from[1], from[2]).to(to[0], to[1], to[2]);
            for (Direction face : Direction.values()) {
                element.face(face, f -> {
                    float[] uv = ModelBoxes.faceUv(face, from, to);
                    f.texture(face == Direction.NORTH ? FRONT : SIDE).uvs(uv[0], uv[1], uv[2], uv[3]);
                    if (face == Direction.SOUTH) {
                        // Against the block the label sits on.
                        f.cullface(face);
                    }
                });
            }
        });
        TextureMapping textures = new TextureMapping().put(FRONT, texture("front")).put(SIDE, texture("side"))
                .put(TextureSlot.PARTICLE, texture("side"));
        Identifier model = builder.build().create(MaxtechStorage.id("block/smart_label"), textures,
                blockModels.modelOutput);
        Block block = SmartLabelContent.LABEL.get();
        MultiPartGenerator states = MultiPartGenerator.multiPart(block);
        for (Direction facing : Direction.values()) {
            VariantMutator turn = turn(facing);
            MultiVariant variant = BlockModelGenerators.plainVariant(model);
            states.with(new ConditionBuilder().term(SmartLabelBlock.FACING, facing),
                    turn == null ? variant : variant.with(turn));
        }
        blockModels.blockStateOutput.accept(states);
        blockModels.itemModelOutput.accept(block.asItem(), ItemModelUtils.plainModel(model));
    }

    /** As {@code DisplayModels.turn}: up needs a half turn about y after x 270 to keep its top to the north. */
    private static @Nullable VariantMutator turn(Direction facing) {
        return switch (facing) {
            case NORTH -> null;
            case SOUTH -> BlockModelGenerators.Y_ROT_180;
            case WEST -> BlockModelGenerators.Y_ROT_270;
            case EAST -> BlockModelGenerators.Y_ROT_90;
            case DOWN -> BlockModelGenerators.X_ROT_90;
            case UP -> BlockModelGenerators.X_ROT_270.then(BlockModelGenerators.Y_ROT_180);
        };
    }

    private static Material texture(String name) {
        return new Material(MaxtechStorage.id("block/smart_label/" + name));
    }
}
