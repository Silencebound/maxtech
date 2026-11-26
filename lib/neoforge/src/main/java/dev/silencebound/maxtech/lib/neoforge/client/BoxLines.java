package dev.silencebound.maxtech.lib.neoforge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Writes box outlines and line segments into a lines buffer ({@code RenderTypes.lines()} or
 * {@link HighlightRenderTypes#seeThroughLines()}): two vertices per edge, each with colour, normal and line width. The
 * highlight overlay uses it. Render thread.
 */
final class BoxLines {
    private BoxLines() {
    }

    /**
     * The twelve edges of the box from ({@code x0}, {@code y0}, {@code z0}) to ({@code x1}, {@code y1}, {@code z1}).
     */
    static void box(VertexConsumer buffer, PoseStack.Pose pose, float x0, float y0, float z0, float x1, float y1,
            float z1, int colour, float width) {
        // Four edges along each axis; axis-aligned, so the normal is the unit step along the edge.
        edge(buffer, pose, x0, y0, z0, x1, y0, z0, 1, 0, 0, colour, width);
        edge(buffer, pose, x0, y1, z0, x1, y1, z0, 1, 0, 0, colour, width);
        edge(buffer, pose, x0, y0, z1, x1, y0, z1, 1, 0, 0, colour, width);
        edge(buffer, pose, x0, y1, z1, x1, y1, z1, 1, 0, 0, colour, width);
        edge(buffer, pose, x0, y0, z0, x0, y1, z0, 0, 1, 0, colour, width);
        edge(buffer, pose, x1, y0, z0, x1, y1, z0, 0, 1, 0, colour, width);
        edge(buffer, pose, x0, y0, z1, x0, y1, z1, 0, 1, 0, colour, width);
        edge(buffer, pose, x1, y0, z1, x1, y1, z1, 0, 1, 0, colour, width);
        edge(buffer, pose, x0, y0, z0, x0, y0, z1, 0, 0, 1, colour, width);
        edge(buffer, pose, x1, y0, z0, x1, y0, z1, 0, 0, 1, colour, width);
        edge(buffer, pose, x0, y1, z0, x0, y1, z1, 0, 0, 1, colour, width);
        edge(buffer, pose, x1, y1, z0, x1, y1, z1, 0, 0, 1, colour, width);
    }

    /** One segment of any direction; a zero-length segment is skipped. */
    static void line(VertexConsumer buffer, PoseStack.Pose pose, float x0, float y0, float z0, float x1, float y1,
            float z1, int colour, float width) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float dz = z1 - z0;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-6F) {
            return;
        }
        edge(buffer, pose, x0, y0, z0, x1, y1, z1, dx / length, dy / length, dz / length, colour, width);
    }

    private static void edge(VertexConsumer buffer, PoseStack.Pose pose, float x0, float y0, float z0, float x1,
            float y1, float z1, float nx, float ny, float nz, int colour, float width) {
        buffer.addVertex(pose, x0, y0, z0).setColor(colour).setNormal(pose, nx, ny, nz).setLineWidth(width);
        buffer.addVertex(pose, x1, y1, z1).setColor(colour).setNormal(pose, nx, ny, nz).setLineWidth(width);
    }
}
