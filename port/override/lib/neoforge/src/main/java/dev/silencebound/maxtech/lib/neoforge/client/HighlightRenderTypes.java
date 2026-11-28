// port-override: base=3ea7e2a4564f7583
package dev.silencebound.maxtech.lib.neoforge.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import org.jspecify.annotations.Nullable;

/**
 * Lib's overlay render types. Lines drawn over everything: vanilla's {@code lines} without a depth test, for highlights
 * that must show through walls; like vanilla's see-through text they write to the main target, so Fabulous graphics
 * does not composite them behind blocks. And the ghost faces: 26.1's {@code debugQuads} (translucent, depth-tested, no
 * depth write), which 1.21.1's {@code debugQuads} is not, since it writes depth. Built on first use. Client only.
 */
final class HighlightRenderTypes {
    private static @Nullable RenderType seeThroughLines;
    private static @Nullable RenderType ghostFaces;

    private HighlightRenderTypes() {
    }

    /** Lines with the vertex format of {@code RenderType.lines()}, drawn without a depth test. */
    static RenderType seeThroughLines() {
        RenderType type = seeThroughLines;
        if (type == null) {
            type = RenderType.create("maxtech_lib_see_through_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL,
                    VertexFormat.Mode.LINES, 1536,
                    RenderType.CompositeState.builder().setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                            .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.empty()))
                            .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                            .setOutputState(RenderStateShard.MAIN_TARGET)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                            .setCullState(RenderStateShard.NO_CULL).createCompositeState(false));
            seeThroughLines = type;
        }
        return type;
    }

    /** Translucent coloured quads, depth-tested without writing depth, so real blocks hide ghosts behind them. */
    static RenderType ghostFaces() {
        RenderType type = ghostFaces;
        if (type == null) {
            type = RenderType.create("maxtech_lib_ghost_faces", DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS, 1536, false, true,
                    RenderType.CompositeState.builder().setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .setCullState(RenderStateShard.NO_CULL).createCompositeState(false));
            ghostFaces = type;
        }
        return type;
    }
}
