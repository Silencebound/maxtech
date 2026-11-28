package dev.silencebound.maxtech.lib.shim.client.gui;

import dev.silencebound.maxtech.lib.shim.client.gui.input.CharacterEvent;
import dev.silencebound.maxtech.lib.shim.client.gui.input.KeyEvent;
import dev.silencebound.maxtech.lib.shim.client.gui.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 26.1's {@code Screen} API on 1.21.1's: Lib's screens extend this instead of Screen. 1.21.1's {@code render} draws
 * 26.1's two passes in order, the background ({@link #extractBackground}) and then the contents
 * ({@link #extractRenderState}); the input callbacks call the 26.1 methods, whose default bodies are vanilla's.
 */
@OnlyIn(Dist.CLIENT)
public abstract class ExtractScreen extends Screen {
    protected ExtractScreen(Component title) {
        super(title);
    }

    @Override
    public final void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        GuiGraphicsExtractor extractor = GuiGraphicsExtractor.of(graphics);
        extractBackground(extractor, mouseX, mouseY, partialTick);
        extractRenderState(extractor, mouseX, mouseY, partialTick);
    }

    @Override
    public final void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        extractBackground(GuiGraphicsExtractor.of(graphics), mouseX, mouseY, partialTick);
    }

    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.renderBackground(graphics.graphics(), mouseX, mouseY, a);
    }

    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        for (Renderable renderable : renderables) {
            renderable.render(graphics.graphics(), mouseX, mouseY, a);
        }
    }

    // 1.21.1's callbacks

    @Override
    public final boolean mouseClicked(double x, double y, int button) {
        boolean doubleClick = Inputs.pressOn(this, button);
        boolean outer = Inputs.beginDispatch(doubleClick);
        try {
            return mouseClicked(Inputs.mouse(x, y, button), doubleClick);
        } finally {
            Inputs.endDispatch(outer);
        }
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

    // 26.1's methods

    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return super.mouseClicked(event.x(), event.y(), event.button());
    }

    public boolean mouseReleased(MouseButtonEvent event) {
        return super.mouseReleased(event.x(), event.y(), event.button());
    }

    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return super.mouseDragged(event.x(), event.y(), event.button(), dx, dy);
    }

    public boolean keyPressed(KeyEvent event) {
        return super.keyPressed(event.key(), event.scancode(), event.modifiers());
    }

    public boolean keyReleased(KeyEvent event) {
        return super.keyReleased(event.key(), event.scancode(), event.modifiers());
    }

    /** 1.21.1 passes UTF-16 units, so a character outside the BMP arrives as two events. */
    public boolean charTyped(CharacterEvent event) {
        return super.charTyped((char) event.codepoint(), Inputs.modifiers());
    }
}
