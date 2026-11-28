package dev.silencebound.maxtech.lib.shim.client.gui;

import dev.silencebound.maxtech.lib.shim.client.gui.input.CharacterEvent;
import dev.silencebound.maxtech.lib.shim.client.gui.input.KeyEvent;
import dev.silencebound.maxtech.lib.shim.client.gui.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 26.1's {@code AbstractContainerScreen} API on 1.21.1's: Lib's container screens extend this. 1.21.1's
 * {@code render} draws 26.1's background ({@link #extractBackground}), then the contents
 * ({@link #extractRenderState}: widgets, slots, labels, carried item, slot tooltip). 26.1 has no {@code renderBg}; the
 * panel is drawn in {@link #extractBackground}.
 */
@OnlyIn(Dist.CLIENT)
public abstract class ExtractContainerScreen<T extends AbstractContainerMenu> extends AbstractContainerScreen<T> {
    /** True while vanilla's render runs for {@link #extractRenderState}, which must not draw the background again. */
    private boolean inContents;

    protected ExtractContainerScreen(T menu, Inventory inventory, Component title) {
        this(menu, inventory, title, 176, 166);
    }

    protected ExtractContainerScreen(T menu, Inventory inventory, Component title, int imageWidth, int imageHeight) {
        super(menu, inventory, title);
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.inventoryLabelY = imageHeight - 94;
    }

    @Override
    public final void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        GuiGraphicsExtractor extractor = GuiGraphicsExtractor.of(graphics);
        extractBackground(extractor, mouseX, mouseY, partialTick);
        extractRenderState(extractor, mouseX, mouseY, partialTick);
    }

    @Override
    public final void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!inContents) {
            extractBackground(GuiGraphicsExtractor.of(graphics), mouseX, mouseY, partialTick);
        }
    }

    @Override
    protected final void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
    }

    @Override
    protected final void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        extractLabels(GuiGraphicsExtractor.of(graphics), mouseX, mouseY);
    }

    /** 26.1's container background: the dimmed world behind the panel. */
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        renderTransparentBackground(graphics.graphics());
    }

    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        inContents = true;
        try {
            super.render(graphics.graphics(), mouseX, mouseY, a);
        } finally {
            inContents = false;
        }
        renderTooltip(graphics.graphics(), mouseX, mouseY);
    }

    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.renderLabels(graphics.graphics(), mouseX, mouseY);
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
