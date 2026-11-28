package dev.silencebound.maxtech.lib.shim.client.gui;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The 26.1 GUI pipelines Lib's screens name when they draw through {@link GuiGraphicsExtractor}. 1.21.1 has no render
 * pipelines: {@link GuiGraphicsExtractor} draws every one of these with GuiGraphics' blended textured quads, which is
 * what both 26.1 pipelines are.
 */
@OnlyIn(Dist.CLIENT)
public final class RenderPipelines {
    /** Textured, alpha blended GUI quads. */
    public static final Object GUI_TEXTURED = new Object();
    /** Untextured, alpha blended GUI quads. */
    public static final Object GUI = new Object();

    private RenderPipelines() {
    }
}
