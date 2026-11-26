package dev.silencebound.maxtech.storage.neoforge.datagen;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.machine.MachineContent;
import dev.silencebound.maxtech.storage.machine.MachineKind;
import dev.silencebound.maxtech.storage.machine.StorageMachineBlock;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.blockstates.MultiVariantGenerator;
import net.minecraft.client.data.models.model.ModelLocationUtils;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.data.models.model.TextureSlot;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import java.util.Set;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.model.generators.template.ExtendedModelTemplateBuilder;

/**
 * The machines' models: a casing cube with the machine's front on the north face, turned to the block's facing, and
 * what stands on its top ({@link #topPart}): the lithograph's raised lens, the fabricator's press housing, the
 * assembler's top plate; the generator's vents are in its top texture. The active model draws the machine's static
 * working overlay over the front at full brightness through the face's NeoForge {@code light_emission}, so it glows
 * without lighting the area and without a block entity renderer.
 */
final class MachineModels {
    private static final TextureSlot FRONT = TextureSlot.FRONT;
    private static final TextureSlot SIDE = TextureSlot.SIDE;
    private static final TextureSlot TOP = TextureSlot.TOP;
    private static final TextureSlot GLOW = TextureSlot.create("glow");
    private static final TextureSlot HOUSING = TextureSlot.create("housing");
    private static final TextureSlot LENS = TextureSlot.create("lens");
    private static final int FULL_BRIGHT = 15;
    private static final float OVERLAY_OFFSET = 0.01F;

    private MachineModels() {
    }

    static void register(BlockModelGenerators blockModels) {
        for (MachineKind kind : MachineKind.values()) {
            Block block = MachineContent.block(kind);
            Identifier front = texture(kind.id() + "_front");
            TextureMapping textures = new TextureMapping().put(FRONT, new Material(front))
                    .put(SIDE, new Material(texture("casing_side")))
                    .put(TOP,
                            new Material(
                                    texture(kind == MachineKind.FUEL_GENERATOR ? "fuel_generator_top" : "casing_top")))
                    .put(HOUSING, new Material(texture("housing"))).put(LENS, new Material(texture("lithograph_lens")))
                    .put(TextureSlot.PARTICLE, new Material(texture("casing_side")));
            Identifier idle = model(kind, false).create(block, textures, blockModels.modelOutput);
            Identifier active = model(kind, true).create(ModelLocationUtils.getModelLocation(block, "_active"),
                    textures.copyAndUpdate(GLOW, new Material(texture(kind.id() + "_front_active"))),
                    blockModels.modelOutput);
            blockModels.blockStateOutput.accept(MultiVariantGenerator.dispatch(block)
                    .with(BlockModelGenerators.createBooleanModelDispatch(StorageMachineBlock.ACTIVE,
                            BlockModelGenerators.plainVariant(active), BlockModelGenerators.plainVariant(idle)))
                    .with(BlockModelGenerators.ROTATION_HORIZONTAL_FACING));
        }
    }

    private static net.minecraft.client.data.models.model.ModelTemplate model(MachineKind kind, boolean active) {
        ExtendedModelTemplateBuilder builder = ExtendedModelTemplateBuilder.builder()
                .parent(Identifier.withDefaultNamespace("block/block")).requiredTextureSlot(FRONT)
                .requiredTextureSlot(SIDE).requiredTextureSlot(TOP).requiredTextureSlot(HOUSING)
                .requiredTextureSlot(LENS).requiredTextureSlot(TextureSlot.PARTICLE)
                .element(element -> element.from(0, 0, 0).to(16, 16, 16)
                        .allFaces((face, faceBuilder) -> faceBuilder
                                .texture(face == Direction.NORTH ? FRONT : face.getAxis().isVertical() ? TOP : SIDE)
                                .cullface(face)));
        topPart(builder, kind);
        if (active) {
            // Just in front of the base face, so the two coplanar faces do not z-fight.
            builder.requiredTextureSlot(GLOW)
                    .element(element -> element.from(0, 0, -OVERLAY_OFFSET).to(16, 16, 16).face(Direction.NORTH,
                            faceBuilder -> faceBuilder.texture(GLOW).cullface(Direction.NORTH)
                                    .lightEmission(FULL_BRIGHT)));
        }
        return builder.build();
    }

    /**
     * What stands on a machine's top: the lithograph's raised lens (10 x 10, 2 high), the fabricator's press housing (6
     * x 6, 3 high) and the assembler's top plate (12 x 12, 1 high); the generator has its vents in the top texture.
     * They stand above the block's own shape, so the outline and collision stay a full block.
     */
    private static void topPart(ExtendedModelTemplateBuilder builder, MachineKind kind) {
        switch (kind) {
            case LITHOGRAPH -> raised(builder, 3, 2, LENS);
            case FABRICATOR -> raised(builder, 5, 3, TOP);
            case ASSEMBLER -> raised(builder, 2, 1, TOP);
            case FUEL_GENERATOR -> {
            }
        }
    }

    /**
     * A box on the top, {@code inset} px in from every edge and {@code height} px high, its top showing {@code top}.
     */
    private static void raised(ExtendedModelTemplateBuilder builder, int inset, int height, TextureSlot top) {
        float lo = inset;
        float hi = 16 - inset;
        builder.element(element -> element.from(lo, 16, lo).to(hi, 16 + height, hi).allFacesExcept((face, f) -> {
            if (face == Direction.UP) {
                f.texture(top).uvs(lo, lo, hi, hi);
            } else {
                f.texture(HOUSING).uvs(lo, 0, hi, height);
            }
        }, Set.of(Direction.DOWN)));
    }

    private static Identifier texture(String name) {
        return MaxtechStorage.id("block/machine/" + name);
    }
}
