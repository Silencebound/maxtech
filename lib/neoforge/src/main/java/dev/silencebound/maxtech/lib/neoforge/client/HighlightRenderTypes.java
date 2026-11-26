package dev.silencebound.maxtech.lib.neoforge.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.silencebound.maxtech.lib.MaxtechLib;
import java.util.Optional;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.jspecify.annotations.Nullable;

/**
 * Lines drawn over everything: vanilla's line pipeline without a depth test, for highlights that must show through
 * walls. Like vanilla's see-through text it writes to the main target, so Fabulous graphics does not composite it
 * behind blocks. The pipeline is registered in {@code RegisterRenderPipelinesEvent}; the render type is built on first
 * use, after that. Client only.
 */
final class HighlightRenderTypes {
    static final RenderPipeline SEE_THROUGH_LINES_PIPELINE = RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
            .withLocation(MaxtechLib.id("pipeline/see_through_lines")).withDepthStencilState(Optional.empty()).build();

    private static @Nullable RenderType seeThroughLines;

    private HighlightRenderTypes() {
    }

    /** Lines with the vertex format of {@code RenderTypes.lines()}, drawn without a depth test. */
    static RenderType seeThroughLines() {
        RenderType type = seeThroughLines;
        if (type == null) {
            type = RenderType.create("maxtech_lib_see_through_lines",
                    RenderSetup.builder(SEE_THROUGH_LINES_PIPELINE)
                            .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                            .setOutputTarget(OutputTarget.MAIN_TARGET).createRenderSetup());
            seeThroughLines = type;
        }
        return type;
    }
}
