package dev.silencebound.maxtech.storage.neoforge.datagen;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.assembler.AssemblerContent;
import dev.silencebound.maxtech.storage.crafting.crafter.CrafterContent;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.blockstates.MultiVariantGenerator;
import net.minecraft.client.data.models.model.ModelLocationUtils;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.data.models.model.TextureSlot;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.model.generators.template.ExtendedModelTemplateBuilder;

/**
 * Models of the two blocks with glass windows (B09 item 29): the crafting assembler (glass on its four sides, the
 * crafting grid on top and bottom) and the crafter (glass on its front, drawn north and turned to its horizontal
 * facing; the grid on top, plain casing on the rest). The glass panes are transparent pixels, so behind them each model
 * has inside walls: flat faces 1 px in from every side, facing inwards. Faces facing away from the camera are culled,
 * so through any window one sees the opposite walls, never the world behind, and the turning item in the middle. The
 * outer faces cull against neighbours as usual; the blocks themselves do not occlude ({@code noOcclusion}), so light
 * reaches the inside.
 */
final class CrafterModels {
    private static final TextureSlot WINDOW = TextureSlot.create("window");
    private static final TextureSlot INNER = TextureSlot.create("inner");
    private static final TextureSlot GRID = TextureSlot.create("grid");
    private static final TextureSlot CASE = TextureSlot.create("case");

    private CrafterModels() {
    }

    static void generate(BlockModelGenerators blockModels) {
        Identifier assembler = model(blockModels, ModelLocationUtils.getModelLocation(AssemblerContent.ASSEMBLER.get()),
                id("crafting_assembler_window"), id("crafting_assembler_window"), true);
        blockModels.blockStateOutput.accept(MultiVariantGenerator.dispatch(AssemblerContent.ASSEMBLER.get(),
                BlockModelGenerators.plainVariant(assembler)));
        Identifier crafter = model(blockModels, ModelLocationUtils.getModelLocation(CrafterContent.CRAFTER.get()),
                id("crafter_front"), id("storage_controller_side"), false);
        blockModels.blockStateOutput.accept(
                MultiVariantGenerator.dispatch(CrafterContent.CRAFTER.get(), BlockModelGenerators.plainVariant(crafter))
                        .with(BlockModelGenerators.ROTATION_HORIZONTAL_FACING));
    }

    /**
     * The model {@code id}: {@code window} on north (and with {@code allSides} on east, south and west too), the
     * crafting grid on top and bottom ({@code allSides}) or on top only, {@code casing} elsewhere, and the inside
     * walls.
     */
    private static Identifier model(BlockModelGenerators blockModels, Identifier id, Identifier window,
            Identifier casing, boolean allSides) {
        ExtendedModelTemplateBuilder builder = ExtendedModelTemplateBuilder.builder()
                .parent(Identifier.withDefaultNamespace("block/block")).requiredTextureSlot(WINDOW)
                .requiredTextureSlot(INNER).requiredTextureSlot(GRID).requiredTextureSlot(CASE)
                .requiredTextureSlot(TextureSlot.PARTICLE);
        builder.element(element -> element.from(0, 0, 0).to(16, 16, 16).allFaces((face, f) -> {
            TextureSlot slot;
            if (face == Direction.NORTH || (allSides && face.getAxis().isHorizontal())) {
                slot = WINDOW;
            } else if (face == Direction.UP || (allSides && face == Direction.DOWN)) {
                slot = GRID;
            } else {
                slot = CASE;
            }
            f.texture(slot).uvs(0, 0, 16, 16).cullface(face);
        }));
        // The inside walls, each a flat face 1 px in from its side, facing the middle; every one spans the whole block
        // so no ray through a window passes between two of them.
        inner(builder, 0, 0, 15, 16, 16, 15, Direction.NORTH);
        inner(builder, 1, 0, 0, 1, 16, 16, Direction.EAST);
        inner(builder, 15, 0, 0, 15, 16, 16, Direction.WEST);
        inner(builder, 0, 1, 0, 16, 1, 16, Direction.UP);
        inner(builder, 0, 15, 0, 16, 15, 16, Direction.DOWN);
        if (allSides) {
            inner(builder, 0, 0, 1, 16, 16, 1, Direction.SOUTH);
        }
        TextureMapping textures = new TextureMapping().put(WINDOW, new Material(window))
                .put(INNER, new Material(id("crafting_window_inner"))).put(GRID, new Material(id("crafting_assembler")))
                .put(CASE, new Material(casing)).put(TextureSlot.PARTICLE, new Material(casing));
        return builder.build().create(id, textures, blockModels.modelOutput);
    }

    private static void inner(ExtendedModelTemplateBuilder builder, float x0, float y0, float z0, float x1, float y1,
            float z1, Direction facing) {
        builder.element(element -> element.from(x0, y0, z0).to(x1, y1, z1).face(facing,
                f -> f.texture(INNER).uvs(0, 0, 16, 16)));
    }

    private static Identifier id(String path) {
        return MaxtechStorage.id("block/" + path);
    }
}
