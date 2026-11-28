// port-override: base=9cc5d731a964f14c
package dev.silencebound.maxtech.lib.client.menu;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.jspecify.annotations.Nullable;

/**
 * What one faces node's block preview draws this frame: the block, the face modes of the shown kind (packed like
 * {@code PortFaces}), the hovered face and the angles. Client thread.
 * <p>
 * 1.21.1 port: 26.1 hands this to a picture-in-picture renderer; 1.21.1 has none, so {@link FacePreviewRenderer#draw}
 * draws it straight into the screen. Same fields; {@code bounds} as 26.1's {@code PictureInPictureRenderState}.
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
        int y1, float scale, @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds) {
    public FacePreviewState(int blockId, int modes, int hovered, float yaw, float pitch, int x0, int y0, int x1, int y1,
            float scale, @Nullable ScreenRectangle scissorArea) {
        this(blockId, modes, hovered, yaw, pitch, x0, y0, x1, y1, scale, scissorArea,
                bounds(x0, y0, x1, y1, scissorArea));
    }

    private static @Nullable ScreenRectangle bounds(int x0, int y0, int x1, int y1,
            @Nullable ScreenRectangle scissorArea) {
        ScreenRectangle bounds = new ScreenRectangle(x0, y0, x1 - x0, y1 - y0);
        return scissorArea != null ? scissorArea.intersection(bounds) : bounds;
    }
}
