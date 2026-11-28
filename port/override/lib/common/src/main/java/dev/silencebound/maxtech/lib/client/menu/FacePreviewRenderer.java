// port-override: base=4d54956de8e26c59
package dev.silencebound.maxtech.lib.client.menu;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.silencebound.maxtech.lib.api.transfer.PortFaces;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Draws a faces node's block preview ({@link FacePreviewState}): the host's block model as block displays draw it (no
 * neighbours), or a plain grey block for a host without one, turned by the state's angles, with each face that pushes
 * or pulls covered in its mode's colour and the hovered face lightened. Only vanilla rendering. Client render thread.
 * <p>
 * 1.21.1 port: 26.1 draws into a picture-in-picture texture that the GUI blits; 1.21.1 has no such renderers, so
 * {@link #draw} draws straight into the screen inside the state's bounds, with the same turn and scale. Whoever draws
 * the GUI hands each state to {@link #draw}.
 */
public final class FacePreviewRenderer {
    private static final int FULL_BRIGHT = 15728880;
    /** How far the mode tints sit outside the block, so they never fight its faces for depth. */
    private static final float OUTSET = 0.002F;
    private static final int PLAIN = 0xFF6B7178;
    private static final int HOVER = 0x60FFFFFF;
    /** Depth of the block's centre: above the screen's background, below tooltips, as GUI items are drawn. */
    private static final float DEPTH = 150.0F;

    /** {@code bufferSource} is unused on 1.21.1, kept so registrations written for 26.1 still compile. */
    public FacePreviewRenderer(MultiBufferSource.BufferSource bufferSource) {
    }

    public Class<FacePreviewState> getRenderStateClass() {
        return FacePreviewState.class;
    }

    /** Draws {@code state} into {@code graphics} at its place on screen, clipped to its bounds. */
    public void draw(GuiGraphics graphics, FacePreviewState state) {
        if (state.bounds() == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        graphics.flush();
        graphics.enableScissor(state.x0(), state.y0(), state.x1(), state.y1());
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate((state.x0() + state.x1()) / 2.0F, (state.y0() + state.y1()) / 2.0F, DEPTH);
        // 26.1's picture scale, then y up and z towards the viewer, then the preview's turn about the block's centre.
        pose.scale(state.scale(), state.scale(), -state.scale());
        pose.scale(1.0F, -1.0F, -1.0F);
        pose.mulPose(Axis.XP.rotationDegrees(state.pitch()));
        pose.mulPose(Axis.YP.rotationDegrees(state.yaw()));
        pose.translate(-0.5F, -0.5F, -0.5F);
        Lighting.setupFor3DItems();
        MultiBufferSource.BufferSource buffers = graphics.bufferSource();
        if (state.blockId() >= 0) {
            minecraft.getBlockRenderer().renderSingleBlock(Block.stateById(state.blockId()), pose, buffers,
                    FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
        } else {
            VertexConsumer buffer = buffers.getBuffer(RenderType.debugQuads());
            for (Direction face : Direction.values()) {
                quad(pose.last(), buffer, face, 0.0F, PLAIN);
            }
        }
        // The tints blend over the block, so it must be drawn first.
        buffers.endBatch();
        VertexConsumer buffer = buffers.getBuffer(RenderType.debugQuads());
        for (Direction face : Direction.values()) {
            int colour = FacesWidget.tint(PortFaces.get(state.modes(), face));
            if (colour != 0) {
                quad(pose.last(), buffer, face, OUTSET, (colour & 0x00FFFFFF) | 0x90000000);
            }
            if (face.get3DDataValue() == state.hovered()) {
                quad(pose.last(), buffer, face, 2 * OUTSET, HOVER);
            }
        }
        buffers.endBatch();
        pose.popPose();
        graphics.disableScissor();
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
}
