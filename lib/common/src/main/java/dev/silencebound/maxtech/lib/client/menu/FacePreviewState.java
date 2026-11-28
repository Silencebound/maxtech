package dev.silencebound.maxtech.lib.client.menu;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.jspecify.annotations.Nullable;

/**
 * What one faces node's block preview draws this frame: the block, the face modes of the shown kind (packed like
 * {@code PortFaces}), the hovered face and the angles. Value-equal states draw the same picture, so the renderer pool
 * reuses a still picture without drawing it again. Client thread.
 *
 * @param blockId
 *            the block state id, or -1 for a plain block
 * @param modes
 *            the shown kind's face modes, two bits per face
 * @param hovered
 *            the hovered face's {@code get3DDataValue}, or -1
 * @param yaw
 *            degrees about the block's y axis
 * @param pitch
 *            degrees about the view's x axis
 */
public record FacePreviewState(int blockId, int modes, int hovered, float yaw, float pitch, int x0, int y0, int x1,
        int y1, float scale, @Nullable ScreenRectangle scissorArea,
        @Nullable ScreenRectangle bounds) implements PictureInPictureRenderState {
    public FacePreviewState(int blockId, int modes, int hovered, float yaw, float pitch, int x0, int y0, int x1, int y1,
            float scale, @Nullable ScreenRectangle scissorArea) {
        this(blockId, modes, hovered, yaw, pitch, x0, y0, x1, y1, scale, scissorArea,
                PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea));
    }
}
