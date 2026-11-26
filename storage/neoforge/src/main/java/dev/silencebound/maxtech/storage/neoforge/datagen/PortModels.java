package dev.silencebound.maxtech.storage.neoforge.datagen;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.port.PortShapes;
import dev.silencebound.maxtech.storage.port.PortTier;
import java.util.List;
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
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.model.generators.template.ExtendedModelTemplateBuilder;

/**
 * The ports' models, one per tier and {@link PortMode}: the block is a plain full cube (owner, 2026-10-17) with the
 * tier's and mode's arrow front on the inventory's side, the connector on the back where the port links to the network
 * and the plain side on the other four. Drawn facing north and turned by the block state like an observer, so the front
 * sits on {@link PortBlock#FACING}. The item shows the import model. The cable parts keep their 3D steps
 * ({@link PortShapes}, {@link #steps}).
 */
final class PortModels {
    static final TextureSlot STEP = TextureSlot.create("step");
    static final TextureSlot TIP = TextureSlot.create("tip");
    static final Identifier STEP_TIP = texture("step_tip");
    /** Where a thin side of a grey step samples its texture: the plain inset in the top-left corner. */
    static final float[] STEP_EDGE_UV = {0, 0, 2, 2};

    private PortModels() {
    }

    static void generate(BlockModelGenerators blockModels) {
        for (PortTier tier : PortTier.values()) {
            generate(blockModels, tier);
        }
    }

    private static void generate(BlockModelGenerators blockModels, PortTier tier) {
        Block block = PortContent.block(tier).get();
        MultiVariant importing = BlockModelGenerators
                .plainVariant(model(blockModels, ModelLocationUtils.getModelLocation(block), tier, PortMode.IMPORT));
        MultiVariant exporting = BlockModelGenerators.plainVariant(
                model(blockModels, ModelLocationUtils.getModelLocation(block, "_export"), tier, PortMode.EXPORT));
        MultiVariant storing = BlockModelGenerators.plainVariant(
                model(blockModels, ModelLocationUtils.getModelLocation(block, "_storage"), tier, PortMode.STORAGE));
        blockModels.blockStateOutput.accept(MultiVariantGenerator.dispatch(block)
                .with(PropertyDispatch.initial(PortBlock.MODE).generate(mode -> switch (mode) {
                    case IMPORT -> importing;
                    case EXPORT -> exporting;
                    case STORAGE -> storing;
                })).with(BlockModelGenerators.ROTATION_FACING));
    }

    private static Identifier model(BlockModelGenerators blockModels, Identifier id, PortTier tier, PortMode mode) {
        ExtendedModelTemplateBuilder builder = ExtendedModelTemplateBuilder.builder()
                .parent(Identifier.withDefaultNamespace("block/block")).requiredTextureSlot(TextureSlot.FRONT)
                .requiredTextureSlot(TextureSlot.SIDE).requiredTextureSlot(TextureSlot.BACK)
                .requiredTextureSlot(TextureSlot.PARTICLE);
        builder.element(element -> element.from(0, 0, 0).to(16, 16, 16).allFaces((face, f) -> {
            TextureSlot slot = switch (face) {
                case NORTH -> TextureSlot.FRONT;
                case SOUTH -> TextureSlot.BACK;
                default -> TextureSlot.SIDE;
            };
            f.texture(slot).uvs(0, 0, 16, 16).cullface(face);
        }));
        TextureMapping textures = new TextureMapping().put(TextureSlot.FRONT, new Material(front(tier, mode)))
                .put(TextureSlot.SIDE, new Material(texture("side")))
                .put(TextureSlot.BACK, new Material(FacingModels.BACK))
                .put(TextureSlot.PARTICLE, new Material(texture("side")));
        return builder.build().create(id, textures, blockModels.modelOutput);
    }

    /**
     * Adds {@code steps} (north-facing, from the block face inwards) as elements: the tip in {@link #TIP}, the others
     * in {@link #STEP}, front and back faces one texel per pixel, thin sides the step texture's plain corner. Faces
     * lying wholly against a wider step are left out.
     */
    static void steps(ExtendedModelTemplateBuilder builder, List<AABB> steps) {
        for (int i = 0; i < steps.size(); i++) {
            AABB box = steps.get(i);
            int index = i;
            TextureSlot slot = PortShapes.tip(box) ? TIP : STEP;
            builder.element(element -> {
                element.from((float) box.minX, (float) box.minY, (float) box.minZ).to((float) box.maxX,
                        (float) box.maxY, (float) box.maxZ);
                for (Direction face : Direction.values()) {
                    boolean front = face == Direction.NORTH;
                    if ((front || face == Direction.SOUTH) && PortShapes.hidden(steps, index, front)) {
                        continue;
                    }
                    element.face(face, f -> {
                        f.texture(slot);
                        if (face.getAxis() == Direction.Axis.Z) {
                            f.uvs((float) box.minX, (float) box.minY, (float) box.maxX, (float) box.maxY);
                        } else {
                            f.uvs(STEP_EDGE_UV[0], STEP_EDGE_UV[1], STEP_EDGE_UV[2], STEP_EDGE_UV[3]);
                        }
                        if (front && box.minZ <= 0) {
                            f.cullface(face);
                        }
                    });
                }
            });
        }
    }

    /** The block's front texture of {@code tier}'s port in {@code mode}: tier 1 has none of the tier pips. */
    static Identifier front(PortTier tier, PortMode mode) {
        return texture("front_" + mode.getSerializedName() + suffix(tier));
    }

    /** The grey step texture of {@code tier}'s cable part, with the tier's pips from tier 2. */
    static Identifier partStep(PortTier tier) {
        return texture("part_step" + suffix(tier));
    }

    private static String suffix(PortTier tier) {
        return tier == PortTier.TIER_1 ? "" : "_tier_" + tier.number();
    }

    private static Identifier texture(String name) {
        return MaxtechStorage.id("block/port/" + name);
    }
}
