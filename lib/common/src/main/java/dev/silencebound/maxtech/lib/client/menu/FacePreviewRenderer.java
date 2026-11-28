package dev.silencebound.maxtech.lib.client.menu;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.silencebound.maxtech.lib.api.transfer.PortFaces;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * Draws a faces node's block preview ({@link FacePreviewState}) into its picture: the host's block model as block
 * displays draw it (no neighbours), or a plain grey block for a host without one, turned by the state's angles, with
 * each face that pushes or pulls covered in its mode's colour and the hovered face lightened. Only vanilla rendering;
 * Lib's NeoForge client registers it. Client render thread.
 */
public final class FacePreviewRenderer extends PictureInPictureRenderer<FacePreviewState> {
    private static final BlockDisplayContext CONTEXT = BlockDisplayContext.create();
    private static final int FULL_BRIGHT = 15728880;
    /** How far the mode tints sit outside the block, so they never fight its faces for depth. */
    private static final float OUTSET = 0.002F;
    private static final int PLAIN = 0xFF6B7178;
    private static final int HOVER = 0x60FFFFFF;

    private final BlockModelRenderState block = new BlockModelRenderState();
    private @Nullable FacePreviewState drawn;

    public FacePreviewRenderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
    }

    @Override
    public Class<FacePreviewState> getRenderStateClass() {
        return FacePreviewState.class;
    }

    @Override
    protected boolean textureIsReadyToBlit(FacePreviewState state) {
        return state.equals(drawn);
    }

    @Override
    protected float getTranslateY(int height, int guiScale) {
        return height / 2.0F;
    }

    @Override
    protected void renderToTexture(FacePreviewState state, PoseStack pose) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.gameRenderer.getLighting().setupFor(Lighting.Entry.ITEMS_3D);
        // y up and z towards the viewer, then the preview's turn about the block's centre.
        pose.scale(1.0F, -1.0F, -1.0F);
        pose.mulPose(Axis.XP.rotationDegrees(state.pitch()));
        pose.mulPose(Axis.YP.rotationDegrees(state.yaw()));
        pose.translate(-0.5F, -0.5F, -0.5F);
        FeatureRenderDispatcher features = minecraft.gameRenderer.getFeatureRenderDispatcher();
        var nodes = features.getSubmitNodeStorage();
        if (state.blockId() >= 0) {
            minecraft.getBlockModelResolver().update(block, Block.stateById(state.blockId()), CONTEXT);
            block.submit(pose, nodes, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
        } else {
            nodes.submitCustomGeometry(pose, RenderTypes.debugQuads(), (at, buffer) -> {
                for (Direction face : Direction.values()) {
                    quad(at, buffer, face, 0.0F, PLAIN);
                }
            });
        }
        nodes.submitCustomGeometry(pose, RenderTypes.debugQuads(), (at, buffer) -> {
            for (Direction face : Direction.values()) {
                int colour = FacesWidget.tint(PortFaces.get(state.modes(), face));
                if (colour != 0) {
                    quad(at, buffer, face, OUTSET, (colour & 0x00FFFFFF) | 0x90000000);
                }
                if (face.get3DDataValue() == state.hovered()) {
                    quad(at, buffer, face, 2 * OUTSET, HOVER);
                }
            }
        });
        features.renderAllFeatures();
        drawn = state;
    }

    /**
     * One face of the unit block, {@code outset} outside it, in both windings so it shows whatever the pipeline culls.
     */
    private static void quad(PoseStack.Pose at, VertexConsumer buffer, Direction face, float outset, int argb) {
        float lo = -outset;
        float hi = 1.0F + outset;
        float[][] corners = switch (face) {
            case DOWN -> new float[][]{{lo, lo, lo}, {hi, lo, lo}, {hi, lo, hi}, {lo, lo, hi}};
            case UP -> new float[][]{{lo, hi, lo}, {lo, hi, hi}, {hi, hi, hi}, {hi, hi, lo}};
            case NORTH -> new float[][]{{lo, lo, lo}, {lo, hi, lo}, {hi, hi, lo}, {hi, lo, lo}};
            case SOUTH -> new float[][]{{lo, lo, hi}, {hi, lo, hi}, {hi, hi, hi}, {lo, hi, hi}};
            case WEST -> new float[][]{{lo, lo, lo}, {lo, lo, hi}, {lo, hi, hi}, {lo, hi, lo}};
            case EAST -> new float[][]{{hi, lo, lo}, {hi, hi, lo}, {hi, hi, hi}, {hi, lo, hi}};
        };
        for (int i = 0; i < 4; i++) {
            buffer.addVertex(at, corners[i][0], corners[i][1], corners[i][2]).setColor(argb);
        }
        for (int i = 3; i >= 0; i--) {
            buffer.addVertex(at, corners[i][0], corners[i][1], corners[i][2]).setColor(argb);
        }
    }

    @Override
    protected String getTextureLabel() {
        return "maxtech_lib face preview";
    }
}
