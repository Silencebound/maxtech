package dev.silencebound.maxtech.storage.neoforge.datagen;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.display.DisplayContent;
import dev.silencebound.maxtech.storage.display.DisplayPanelBlock;
import java.util.List;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.MultiVariant;
import net.minecraft.client.data.models.blockstates.ConditionBuilder;
import net.minecraft.client.data.models.blockstates.MultiPartGenerator;
import net.minecraft.client.data.models.model.ItemModelUtils;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.data.models.model.TextureSlot;
import net.minecraft.client.renderer.block.dispatch.VariantMutator;
import net.minecraft.client.renderer.block.dispatch.multipart.CombinedCondition;
import net.minecraft.client.renderer.block.dispatch.multipart.Condition;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.neoforged.neoforge.client.model.generators.template.ExtendedModelTemplateBuilder;
import org.jspecify.annotations.Nullable;

/**
 * The display panel's models, drawn facing north and turned to all six facings. The glass is a 2 px plate against the
 * back, edge to edge, so the panels of a wall show one continuous screen; the frame is 2 px wide, 1 px proud strips
 * drawn only on the edges whose {@code frame_*} property is set (the screen's outer edges, {@link DisplayPanelBlock}),
 * with a corner piece wherever either edge next to it is framed. Faces other than the glass and the frame's front are
 * plain casing. The glass is not full-bright: its picture is the block entity renderer's emissive overlay. The item is
 * a fully framed panel. The part's item model is {@link PartModels}'.
 */
final class DisplayModels {
    private static final TextureSlot SCREEN = TextureSlot.create("screen");
    private static final TextureSlot FRAME = TextureSlot.create("frame");
    private static final TextureSlot EDGE = TextureSlot.create("edge");
    private static final TextureSlot BACK = TextureSlot.create("back");
    /** The glass; its front is 2 px from the back, where the renderer draws the picture. */
    private static final float[][] GLASS = {{0, 0, 14}, {16, 16, 16}};
    // Frame pieces in front of the glass. Facing north the viewer's left is +x (east).
    private static final float[][] LEFT = {{14, 2, 13}, {16, 14, 14}};
    private static final float[][] RIGHT = {{0, 2, 13}, {2, 14, 14}};
    private static final float[][] BOTTOM = {{2, 0, 13}, {14, 2, 14}};
    private static final float[][] TOP = {{2, 14, 13}, {14, 16, 14}};
    private static final float[][] BOTTOM_LEFT = {{14, 0, 13}, {16, 2, 14}};
    private static final float[][] BOTTOM_RIGHT = {{0, 0, 13}, {2, 2, 14}};
    private static final float[][] TOP_LEFT = {{14, 14, 13}, {16, 16, 14}};
    private static final float[][] TOP_RIGHT = {{0, 14, 13}, {2, 16, 14}};

    private DisplayModels() {
    }

    static void generate(BlockModelGenerators blockModels) {
        TextureMapping textures = new TextureMapping().put(SCREEN, texture("screen")).put(FRAME, texture("frame"))
                .put(EDGE, texture("edge")).put(BACK, texture("side")).put(TextureSlot.PARTICLE, texture("side"));
        Identifier glass = model(blockModels, "display_panel", textures, GLASS);
        Identifier left = model(blockModels, "display_panel_frame_left", textures, LEFT);
        Identifier right = model(blockModels, "display_panel_frame_right", textures, RIGHT);
        Identifier bottom = model(blockModels, "display_panel_frame_bottom", textures, BOTTOM);
        Identifier top = model(blockModels, "display_panel_frame_top", textures, TOP);
        Identifier bottomLeft = model(blockModels, "display_panel_corner_bottom_left", textures, BOTTOM_LEFT);
        Identifier bottomRight = model(blockModels, "display_panel_corner_bottom_right", textures, BOTTOM_RIGHT);
        Identifier topLeft = model(blockModels, "display_panel_corner_top_left", textures, TOP_LEFT);
        Identifier topRight = model(blockModels, "display_panel_corner_top_right", textures, TOP_RIGHT);

        Block block = DisplayContent.PANEL.get();
        MultiPartGenerator states = MultiPartGenerator.multiPart(block);
        for (Direction facing : Direction.values()) {
            VariantMutator turn = turn(facing);
            states.with(new ConditionBuilder().term(DisplayPanelBlock.FACING, facing), variant(glass, turn));
            states.with(edge(facing, DisplayPanelBlock.FRAME_LEFT), variant(left, turn));
            states.with(edge(facing, DisplayPanelBlock.FRAME_RIGHT), variant(right, turn));
            states.with(edge(facing, DisplayPanelBlock.FRAME_BOTTOM), variant(bottom, turn));
            states.with(edge(facing, DisplayPanelBlock.FRAME_TOP), variant(top, turn));
            states.with(corner(facing, DisplayPanelBlock.FRAME_BOTTOM, DisplayPanelBlock.FRAME_LEFT),
                    variant(bottomLeft, turn));
            states.with(corner(facing, DisplayPanelBlock.FRAME_BOTTOM, DisplayPanelBlock.FRAME_RIGHT),
                    variant(bottomRight, turn));
            states.with(corner(facing, DisplayPanelBlock.FRAME_TOP, DisplayPanelBlock.FRAME_LEFT),
                    variant(topLeft, turn));
            states.with(corner(facing, DisplayPanelBlock.FRAME_TOP, DisplayPanelBlock.FRAME_RIGHT),
                    variant(topRight, turn));
        }
        blockModels.blockStateOutput.accept(states);

        Identifier item = model(blockModels, "display_panel_framed", textures, GLASS, LEFT, RIGHT, BOTTOM, TOP,
                BOTTOM_LEFT, BOTTOM_RIGHT, TOP_LEFT, TOP_RIGHT);
        blockModels.itemModelOutput.accept(block.asItem(), ItemModelUtils.plainModel(item));
    }

    /**
     * Turns the north-facing model to {@code facing} so its up and right match {@code DisplayWalls.up} and
     * {@code right}. Facing up that needs a half turn about y after x 270, which alone would put the model's top to the
     * south (x is applied before y).
     */
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

    private static MultiVariant variant(Identifier model, @Nullable VariantMutator turn) {
        MultiVariant variant = BlockModelGenerators.plainVariant(model);
        return turn == null ? variant : variant.with(turn);
    }

    private static ConditionBuilder edge(Direction facing, BooleanProperty edge) {
        return new ConditionBuilder().term(DisplayPanelBlock.FACING, facing).term(edge, true);
    }

    /** A corner is drawn when either edge next to it is framed, so a framed edge runs to the panel's corner. */
    private static Condition corner(Direction facing, BooleanProperty a, BooleanProperty b) {
        return new CombinedCondition(CombinedCondition.Operation.OR,
                List.of(edge(facing, a).build(), edge(facing, b).build()));
    }

    /** A model of the boxes given as {from, to} pairs: glass (behind z 14) or frame pieces (in front of it). */
    private static Identifier model(BlockModelGenerators blockModels, String name, TextureMapping textures,
            float[][]... boxes) {
        ExtendedModelTemplateBuilder builder = ExtendedModelTemplateBuilder.builder()
                .parent(Identifier.withDefaultNamespace("block/block")).requiredTextureSlot(SCREEN)
                .requiredTextureSlot(FRAME).requiredTextureSlot(EDGE).requiredTextureSlot(BACK)
                .requiredTextureSlot(TextureSlot.PARTICLE);
        for (float[][] box : boxes) {
            float[] from = box[0];
            float[] to = box[1];
            boolean glass = from[2] >= GLASS[0][2];
            builder.element(element -> {
                element.from(from[0], from[1], from[2]).to(to[0], to[1], to[2]);
                for (Direction face : Direction.values()) {
                    if (face == Direction.SOUTH && !glass) {
                        // Against the glass, never seen.
                        continue;
                    }
                    element.face(face, f -> {
                        float[] uv = ModelBoxes.faceUv(face, from, to);
                        TextureSlot slot = face == Direction.NORTH
                                ? (glass ? SCREEN : FRAME)
                                : face == Direction.SOUTH ? BACK : EDGE;
                        f.texture(slot).uvs(uv[0], uv[1], uv[2], uv[3]);
                        if (onBlockFace(face, from, to)) {
                            f.cullface(face);
                        }
                    });
                }
            });
        }
        return builder.build().create(MaxtechStorage.id("block/" + name), textures, blockModels.modelOutput);
    }

    private static boolean onBlockFace(Direction face, float[] from, float[] to) {
        int axis = switch (face.getAxis()) {
            case X -> 0;
            case Y -> 1;
            case Z -> 2;
        };
        return face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? to[axis] >= 16 : from[axis] <= 0;
    }

    private static Material texture(String name) {
        return new Material(MaxtechStorage.id("block/display/" + name));
    }
}
