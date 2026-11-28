package dev.silencebound.maxtech.lib.shim.client.gui;

import dev.silencebound.maxtech.lib.shim.client.gui.input.CharacterEvent;
import dev.silencebound.maxtech.lib.shim.client.gui.input.KeyEvent;
import dev.silencebound.maxtech.lib.shim.client.gui.input.MouseButtonEvent;
import dev.silencebound.maxtech.lib.shim.client.gui.input.MouseButtonInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 26.1's {@code AbstractWidget} API on 1.21.1's: Lib's widgets extend this instead of AbstractWidget. 1.21.1's render
 * and input callbacks are final here and call the 26.1 methods, whose default bodies are 26.1's.
 */
@OnlyIn(Dist.CLIENT)
public abstract class ExtractWidget extends AbstractWidget {
    protected ExtractWidget(int x, int y, int width, int height, Component message) {
        super(x, y, width, height, message);
    }

    @Override
    protected final void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        extractWidgetRenderState(GuiGraphicsExtractor.of(graphics), mouseX, mouseY, partialTick);
    }

    protected abstract void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a);

    /** 26.1's accessor of the widget's alpha. */
    public float getAlpha() {
        return alpha;
    }

    public static void playButtonClickSound(SoundManager soundManager) {
        soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    // 1.21.1's callbacks

    @Override
    public final boolean mouseClicked(double x, double y, int button) {
        return mouseClicked(Inputs.mouse(x, y, button), Inputs.doubleClick());
    }

    @Override
    public final boolean mouseReleased(double x, double y, int button) {
        return mouseReleased(Inputs.mouse(x, y, button));
    }

    @Override
    public final boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return mouseDragged(Inputs.mouse(x, y, button), dx, dy);
    }

    @Override
    public final boolean keyPressed(int key, int scancode, int modifiers) {
        return keyPressed(new KeyEvent(key, scancode, modifiers));
    }

    @Override
    public final boolean keyReleased(int key, int scancode, int modifiers) {
        return keyReleased(new KeyEvent(key, scancode, modifiers));
    }

    @Override
    public final boolean charTyped(char codepoint, int modifiers) {
        return charTyped(new CharacterEvent(codepoint));
    }

    @Override
    public final void onClick(double x, double y) {
        onClick(Inputs.mouse(x, y, 0), false);
    }

    @Override
    public final void onRelease(double x, double y) {
        onRelease(Inputs.mouse(x, y, 0));
    }

    @Override
    protected final void onDrag(double x, double y, double dx, double dy) {
        onDrag(Inputs.mouse(x, y, 0), dx, dy);
    }

    @Override
    protected final boolean isValidClickButton(int button) {
        return isValidClickButton(Inputs.button(button));
    }

    // 26.1's methods

    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!isActive()) {
            return false;
        }
        if (isValidClickButton(event.buttonInfo()) && isMouseOver(event.x(), event.y())) {
            playDownSound(Minecraft.getInstance().getSoundManager());
            onClick(event, doubleClick);
            return true;
        }
        return false;
    }

    public boolean mouseReleased(MouseButtonEvent event) {
        if (isValidClickButton(event.buttonInfo())) {
            onRelease(event);
            return true;
        }
        return false;
    }

    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (isValidClickButton(event.buttonInfo())) {
            onDrag(event, dx, dy);
            return true;
        }
        return false;
    }

    public boolean keyPressed(KeyEvent event) {
        return false;
    }

    public boolean keyReleased(KeyEvent event) {
        return false;
    }

    public boolean charTyped(CharacterEvent event) {
        return false;
    }

    public void onClick(MouseButtonEvent event, boolean doubleClick) {
    }

    public void onRelease(MouseButtonEvent event) {
    }

    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
    }

    protected boolean isValidClickButton(MouseButtonInfo buttonInfo) {
        return buttonInfo.button() == 0;
    }
}
