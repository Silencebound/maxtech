package dev.silencebound.maxtech.lib.shim.client.gui;

import dev.silencebound.maxtech.lib.shim.client.gui.input.InputWithModifiers;
import dev.silencebound.maxtech.lib.shim.client.gui.input.KeyEvent;
import dev.silencebound.maxtech.lib.shim.client.gui.input.MouseButtonEvent;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;

/**
 * 26.1's {@code AbstractButton} (with {@code AbstractWidget.WithInactiveMessage}) on {@link ExtractWidget}: Lib's
 * buttons extend this instead of 1.21.1's AbstractButton, whose drawing and {@code onPress()} differ.
 */
@OnlyIn(Dist.CLIENT)
public abstract class ExtractButton extends ExtractWidget {
    protected static final int TEXT_MARGIN = 2;
    protected static final WidgetSprites SPRITES = new WidgetSprites(
            ResourceLocation.withDefaultNamespace("widget/button"),
            ResourceLocation.withDefaultNamespace("widget/button_disabled"),
            ResourceLocation.withDefaultNamespace("widget/button_highlighted"));
    private Component inactiveMessage;
    private @Nullable Supplier<Boolean> overrideRenderHighlightedSprite;

    protected ExtractButton(int x, int y, int width, int height, Component message) {
        super(x, y, width, height, message);
        this.inactiveMessage = defaultInactiveMessage(message);
    }

    public static Component defaultInactiveMessage(Component activeMessage) {
        return ComponentUtils.mergeStyles(activeMessage.copy(), Style.EMPTY.withColor(-6250336));
    }

    @Override
    public Component getMessage() {
        return active ? super.getMessage() : inactiveMessage;
    }

    @Override
    public void setMessage(Component message) {
        super.setMessage(message);
        this.inactiveMessage = defaultInactiveMessage(message);
    }

    public abstract void onPress(InputWithModifiers input);

    @Override
    protected final void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        extractContents(graphics, mouseX, mouseY, a);
    }

    protected abstract void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a);

    protected void extractDefaultLabel(ActiveTextCollector output) {
        Component message = getMessage();
        if (getFGColor() != UNSET_FG_COLOR) {
            int colour = getFGColor();
            message = message.copy().withStyle(style -> style.withColor(colour));
        }
        output.acceptScrollingWithDefaultCenter(message, getX() + TEXT_MARGIN, getX() + getWidth() - TEXT_MARGIN,
                getY(), getY() + getHeight());
    }

    protected final void extractDefaultSprite(GuiGraphicsExtractor graphics) {
        boolean highlighted = overrideRenderHighlightedSprite != null ? overrideRenderHighlightedSprite.get()
                : isHoveredOrFocused();
        int alpha = Math.round(this.alpha * 255.0F);
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SPRITES.get(active, highlighted), getX(), getY(),
                getWidth(), getHeight(), (alpha << 24) | 0xFFFFFF);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        onPress(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!isActive()) {
            return false;
        }
        if (event.isSelection()) {
            playDownSound(Minecraft.getInstance().getSoundManager());
            onPress(event);
            return true;
        }
        return false;
    }

    public void setOverrideRenderHighlightedSprite(Supplier<Boolean> overrideRenderHighlightedSprite) {
        this.overrideRenderHighlightedSprite = overrideRenderHighlightedSprite;
    }
}
