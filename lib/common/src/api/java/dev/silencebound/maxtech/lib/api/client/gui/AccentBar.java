package dev.silencebound.maxtech.lib.api.client.gui;

import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * What the Accent bars share: they read their values every frame, show their numbers as a tooltip while hovered and
 * narrate the same text, take no clicks, and can be focused with the keyboard so the narrator reaches them.
 */
abstract class AccentBar extends AbstractWidget {
    /** The outline colour while hovered or focused comes from here. */
    protected final AccentTheme theme;

    AccentBar(int x, int y, int width, int height, AccentTheme theme) {
        super(x, y, width, height, Component.empty());
        this.theme = theme;
    }

    /** The tooltip lines for the current values; also what the bar narrates. */
    public abstract List<Component> tooltip();

    /** Draws the bar; hovered or focused bars draw their outline in the theme's accent. */
    protected abstract void draw(GuiGraphicsExtractor graphics, boolean lit);

    @Override
    protected final void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        draw(graphics, isHoveredOrFocused());
        if (isHovered()) {
            graphics.setComponentTooltipForNextFrame(Minecraft.getInstance().font, tooltip(), mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, CommonComponents.joinForNarration(tooltip().toArray(Component[]::new)));
    }

    /** The outline colour: the theme's accent when lit, the slot edge otherwise. */
    protected final int edge(boolean lit) {
        return lit ? theme.accent() : Accent.SLOT_EDGE;
    }

    /**
     * How many of {@code size} pixels show {@code amount} of {@code capacity}: rounded down, so only a full bar looks
     * full, but at least one pixel for any amount above 0, so a nearly empty one never looks empty. 0 for amounts or
     * capacities of 0 or less; never more than {@code size}.
     */
    static int fill(long amount, long capacity, int size) {
        if (amount <= 0 || capacity <= 0 || size <= 0) {
            return 0;
        }
        if (amount >= capacity) {
            return size;
        }
        int pixels = (int) Math.min(size - 1L, (long) Math.floor((double) amount / capacity * size));
        return Math.max(1, pixels);
    }

    /** {@code value} with its thousands grouped by commas, whatever the client's language ("1,250"). */
    static String grouped(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
