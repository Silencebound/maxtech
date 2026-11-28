package dev.silencebound.maxtech.lib.shim.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The part of 26.1's text collector Lib uses: a scrolling, centred label in a box, drawn right away with 1.21.1's
 * {@link AbstractWidget#renderScrollingString}.
 */
@OnlyIn(Dist.CLIENT)
public final class ActiveTextCollector {
    private final GuiGraphicsExtractor graphics;
    private final Font font;
    private final int colour;

    ActiveTextCollector(GuiGraphicsExtractor graphics, Font font, int colour) {
        this.graphics = graphics;
        this.font = font;
        this.colour = colour;
    }

    /** Draws {@code message} centred between {@code left} and {@code right}, scrolling when it is wider. */
    public void acceptScrollingWithDefaultCenter(Component message, int left, int right, int top, int bottom) {
        AbstractWidget.renderScrollingString(graphics.graphics(), font, message, left, top, right, bottom, colour);
    }
}
