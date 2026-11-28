package dev.silencebound.maxtech.lib.shim.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * 26.1's GUI extractor over 1.21.1's {@link GuiGraphics}. 26.1 widgets extract render state that is drawn after the
 * frame is collected; 1.21.1 draws in one pass, so every call here draws right away, in call order, which is the order
 * 26.1 draws one stratum in. Tooltips stay deferred as in 26.1: the first one set in a frame is drawn after the screen
 * (or the HUD), outside any pose and scissor.
 *
 * <p>{@link #pose()} is a 2D view of the GuiGraphics pose stack: reading it gives the current pose's 2D part, and
 * pushing, popping, translating and scaling it changes the pose stack. Scissor rectangles go through the pose as in
 * 26.1; 1.21.1's GuiGraphics would take them unscaled.
 */
@OnlyIn(Dist.CLIENT)
public class GuiGraphicsExtractor {
    private static @Nullable GuiGraphicsExtractor last;
    private static @Nullable PendingTooltip pendingTooltip;
    private static boolean listening;

    private final GuiGraphics graphics;
    private final Minecraft minecraft;
    private final Pose pose;
    /** The scissor rectangles set through this extractor, in screen space ({@link #peekScissorStack()}). */
    private final Deque<ScreenRectangle> scissors = new ArrayDeque<>();

    private GuiGraphicsExtractor(GuiGraphics graphics) {
        this.graphics = graphics;
        this.minecraft = Minecraft.getInstance();
        this.pose = new Pose(graphics.pose());
    }

    /** The extractor over {@code graphics}; the same one for the whole frame, so its scissor record holds. */
    public static GuiGraphicsExtractor of(GuiGraphics graphics) {
        GuiGraphicsExtractor cached = last;
        if (cached != null && cached.graphics == graphics) {
            return cached;
        }
        listen();
        GuiGraphicsExtractor created = new GuiGraphicsExtractor(graphics);
        last = created;
        return created;
    }

    /** The 1.21.1 GuiGraphics everything is drawn into. */
    public GuiGraphics graphics() {
        return graphics;
    }

    public int guiWidth() {
        return graphics.guiWidth();
    }

    public int guiHeight() {
        return graphics.guiHeight();
    }

    public Matrix3x2fStack pose() {
        return pose.sync();
    }

    public void enableScissor(int x0, int y0, int x1, int y1) {
        Matrix4f m = graphics.pose().last().pose();
        float left = m.m00() * x0 + m.m10() * y0 + m.m30();
        float top = m.m01() * x0 + m.m11() * y0 + m.m31();
        float right = m.m00() * x1 + m.m10() * y1 + m.m30();
        float bottom = m.m01() * x1 + m.m11() * y1 + m.m31();
        // 26.1's ScreenRectangle.transformAxisAligned
        int x = Mth.floor(left);
        int y = Mth.floor(top);
        ScreenRectangle rect = new ScreenRectangle(x, y, Mth.floor(right - left), Mth.floor(bottom - top));
        ScreenRectangle outer = scissors.peekLast();
        scissors.addLast(outer == null ? rect
                : Objects.requireNonNullElse(rect.intersection(outer), ScreenRectangle.empty()));
        graphics.enableScissor(rect.left(), rect.top(), rect.right(), rect.bottom());
    }

    public void disableScissor() {
        scissors.pollLast();
        graphics.disableScissor();
    }

    public boolean containsPointInScissor(int x, int y) {
        return graphics.containsPointInScissor(x, y);
    }

    /**
     * The innermost scissor rectangle set through this extractor, or null. Scissors vanilla code sets straight on the
     * GuiGraphics are not seen.
     */
    public @Nullable ScreenRectangle peekScissorStack() {
        return scissors.peekLast();
    }

    public void horizontalLine(int x0, int x1, int y, int col) {
        if (x1 < x0) {
            int tmp = x0;
            x0 = x1;
            x1 = tmp;
        }
        fill(x0, y, x1 + 1, y + 1, col);
    }

    public void verticalLine(int x, int y0, int y1, int col) {
        if (y1 < y0) {
            int tmp = y0;
            y0 = y1;
            y1 = tmp;
        }
        fill(x, y0 + 1, x + 1, y1, col);
    }

    public void fill(int x0, int y0, int x1, int y1, int col) {
        graphics.fill(x0, y0, x1, y1, col);
    }

    /** {@code pipeline} is one of {@link RenderPipelines}; 1.21.1 fills every one as a blended quad. */
    public void fill(Object pipeline, int x0, int y0, int x1, int y1, int col) {
        fill(x0, y0, x1, y1, col);
    }

    public void fillGradient(int x0, int y0, int x1, int y1, int col1, int col2) {
        graphics.fillGradient(x0, y0, x1, y1, col1, col2);
    }

    public void outline(int x, int y, int width, int height, int color) {
        fill(x, y, x + width, y + 1, color);
        fill(x, y + height - 1, x + width, y + height, color);
        fill(x, y + 1, x + 1, y + height - 1, color);
        fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    public void text(Font font, @Nullable String str, int x, int y, int color) {
        text(font, str, x, y, color, true);
    }

    public void text(Font font, @Nullable String str, int x, int y, int color, boolean dropShadow) {
        if (str != null) {
            text(font, Language.getInstance().getVisualOrder(FormattedText.of(str)), x, y, color, dropShadow);
        }
    }

    public void text(Font font, FormattedCharSequence str, int x, int y, int color) {
        text(font, str, x, y, color, true);
    }

    /** As in 26.1, a colour with zero alpha draws nothing (1.21.1's Font would draw it opaque). */
    public void text(Font font, FormattedCharSequence str, int x, int y, int color, boolean dropShadow) {
        if ((color >>> 24) != 0) {
            graphics.drawString(font, str, x, y, color, dropShadow);
        }
    }

    public void text(Font font, Component str, int x, int y, int color) {
        text(font, str, x, y, color, true);
    }

    public void text(Font font, Component str, int x, int y, int color, boolean dropShadow) {
        text(font, str.getVisualOrderText(), x, y, color, dropShadow);
    }

    public void centeredText(Font font, String str, int x, int y, int color) {
        text(font, str, x - font.width(str) / 2, y, color);
    }

    public void centeredText(Font font, Component text, int x, int y, int color) {
        FormattedCharSequence toRender = text.getVisualOrderText();
        text(font, toRender, x - font.width(toRender) / 2, y, color);
    }

    public void centeredText(Font font, FormattedCharSequence text, int x, int y, int color) {
        text(font, text, x - font.width(text) / 2, y, color);
    }

    public void textWithWordWrap(Font font, FormattedText string, int x, int y, int width, int col) {
        textWithWordWrap(font, string, x, y, width, col, true);
    }

    public void textWithWordWrap(Font font, FormattedText string, int x, int y, int width, int col,
            boolean dropShadow) {
        for (FormattedCharSequence line : font.split(string, width)) {
            text(font, line, x, y, col, dropShadow);
            y += 9;
        }
    }

    public void blitSprite(Object pipeline, ResourceLocation location, int x, int y, int width, int height) {
        blitSprite(pipeline, location, x, y, width, height, -1);
    }

    public void blitSprite(Object pipeline, ResourceLocation location, int x, int y, int width, int height,
            float alpha) {
        blitSprite(pipeline, location, x, y, width, height, (Mth.floor(alpha * 255.0F) << 24) | 0xFFFFFF);
    }

    /** A GUI sprite (stretched, tiled or nine-sliced by its metadata) multiplied by the ARGB {@code color}. */
    public void blitSprite(Object pipeline, ResourceLocation location, int x, int y, int width, int height,
            int color) {
        tinted(color, () -> graphics.blitSprite(location, x, y, width, height));
    }

    public void blitSprite(Object pipeline, TextureAtlasSprite sprite, int x, int y, int width, int height) {
        blitSprite(pipeline, sprite, x, y, width, height, -1);
    }

    public void blitSprite(Object pipeline, TextureAtlasSprite sprite, int x, int y, int width, int height,
            int color) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.blit(x, y, 0, width, height, sprite, red(color), green(color), blue(color), alpha(color));
        RenderSystem.disableBlend();
    }

    /** A region of a texture at {@code u, v}, {@code width} by {@code height}, from a texture of the given size. */
    public void blit(Object pipeline, ResourceLocation texture, int x, int y, float u, float v, int width,
            int height, int textureWidth, int textureHeight) {
        blit(pipeline, texture, x, y, u, v, width, height, textureWidth, textureHeight, -1);
    }

    public void blit(Object pipeline, ResourceLocation texture, int x, int y, float u, float v, int width,
            int height, int textureWidth, int textureHeight, int color) {
        tinted(color, () -> graphics.blit(texture, x, y, u, v, width, height, textureWidth, textureHeight));
    }

    public void item(ItemStack itemStack, int x, int y) {
        graphics.renderItem(itemStack, x, y);
    }

    public void item(ItemStack itemStack, int x, int y, int seed) {
        graphics.renderItem(itemStack, x, y, seed);
    }

    public void item(LivingEntity owner, ItemStack itemStack, int x, int y, int seed) {
        graphics.renderItem(owner, itemStack, x, y, seed);
    }

    public void fakeItem(ItemStack itemStack, int x, int y) {
        graphics.renderFakeItem(itemStack, x, y);
    }

    public void fakeItem(ItemStack itemStack, int x, int y, int seed) {
        graphics.renderFakeItem(itemStack, x, y, seed);
    }

    public void itemDecorations(Font font, ItemStack itemStack, int x, int y) {
        graphics.renderItemDecorations(font, itemStack, x, y);
    }

    public void itemDecorations(Font font, ItemStack itemStack, int x, int y, @Nullable String countText) {
        graphics.renderItemDecorations(font, itemStack, x, y, countText);
    }

    /**
     * Draws a picture-in-picture state now, with the drawer the rendering side registered for its class
     * ({@link PictureInPictureRenderers}).
     */
    public void submitPictureInPictureRenderState(Object renderState) {
        PictureInPictureRenderers.draw(graphics, renderState);
    }

    public void setTooltipForNextFrame(Component component, int x, int y) {
        setTooltipForNextFrame(List.of(component.getVisualOrderText()), x, y);
    }

    public void setTooltipForNextFrame(List<FormattedCharSequence> lines, int x, int y) {
        setTooltipForNextFrame(minecraft.font, lines, DefaultTooltipPositioner.INSTANCE, x, y, false);
    }

    public void setTooltipForNextFrame(Font font, ItemStack itemStack, int xo, int yo) {
        defer(false, g -> g.renderTooltip(font, itemStack, xo, yo));
    }

    public void setTooltipForNextFrame(Font font, List<Component> texts, Optional<TooltipComponent> optionalImage,
            int xo, int yo) {
        defer(false, g -> g.renderTooltip(font, texts, optionalImage, xo, yo));
    }

    public void setTooltipForNextFrame(Font font, List<Component> texts, Optional<TooltipComponent> optionalImage,
            ItemStack stack, int xo, int yo) {
        defer(false, g -> g.renderTooltip(font, texts, optionalImage, stack, xo, yo));
    }

    public void setTooltipForNextFrame(Font font, Component text, int xo, int yo) {
        setTooltipForNextFrame(font, List.of(text.getVisualOrderText()), xo, yo);
    }

    public void setTooltipForNextFrame(Font font, List<? extends FormattedCharSequence> lines, int xo, int yo) {
        List<? extends FormattedCharSequence> copy = List.copyOf(lines);
        defer(false, g -> g.renderTooltip(font, copy, xo, yo));
    }

    public void setTooltipForNextFrame(Font font, List<FormattedCharSequence> tooltip,
            ClientTooltipPositioner positioner, int xo, int yo, boolean replaceExisting) {
        List<FormattedCharSequence> copy = List.copyOf(tooltip);
        defer(replaceExisting, g -> g.renderTooltip(font, copy, positioner, xo, yo));
    }

    public void setComponentTooltipForNextFrame(Font font, List<Component> lines, int xo, int yo) {
        List<Component> copy = List.copyOf(lines);
        defer(false, g -> g.renderComponentTooltip(font, copy, xo, yo));
    }

    public void setComponentTooltipForNextFrame(Font font, List<? extends FormattedText> tooltips, int mouseX,
            int mouseY, ItemStack stack) {
        List<? extends FormattedText> copy = List.copyOf(tooltips);
        defer(false, g -> g.renderComponentTooltip(font, copy, mouseX, mouseY, stack));
    }

    /** A text collector that draws a widget's label in white at the widget's alpha. */
    public ActiveTextCollector textRendererForWidget(AbstractWidget owner,
            GuiGraphicsExtractor.HoveredTextEffects hoveredTextEffects) {
        int alpha = Mth.ceil((owner instanceof ExtractWidget widget ? widget.getAlpha() : 1.0F) * 255.0F);
        return new ActiveTextCollector(this, minecraft.font, (alpha << 24) | 0xFFFFFF);
    }

    public ActiveTextCollector textRenderer() {
        return new ActiveTextCollector(this, minecraft.font, -1);
    }

    /** 26.1's hover effects of text; 1.21.1 has no clickable GUI text in widgets, so they don't change drawing. */
    @OnlyIn(Dist.CLIENT)
    public enum HoveredTextEffects {
        NONE(false, false),
        TOOLTIP_ONLY(true, false),
        TOOLTIP_AND_CURSOR(true, true);

        public final boolean allowTooltip;
        public final boolean allowCursorChanges;

        HoveredTextEffects(boolean allowTooltip, boolean allowCursorChanges) {
            this.allowTooltip = allowTooltip;
            this.allowCursorChanges = allowCursorChanges;
        }

        public static GuiGraphicsExtractor.HoveredTextEffects notClickable(boolean canTooltip) {
            return canTooltip ? TOOLTIP_ONLY : NONE;
        }
    }

    private void tinted(int color, Runnable draw) {
        boolean tint = color != -1;
        if (tint) {
            graphics.setColor(red(color), green(color), blue(color), alpha(color));
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        draw.run();
        RenderSystem.disableBlend();
        if (tint) {
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    private static float alpha(int argb) {
        return (argb >>> 24) / 255.0F;
    }

    private static float red(int argb) {
        return (argb >> 16 & 0xFF) / 255.0F;
    }

    private static float green(int argb) {
        return (argb >> 8 & 0xFF) / 255.0F;
    }

    private static float blue(int argb) {
        return (argb & 0xFF) / 255.0F;
    }

    /** 26.1 keeps the first tooltip of a frame unless the new one replaces it. */
    private static void defer(boolean replaceExisting, Consumer<GuiGraphics> draw) {
        if (pendingTooltip == null || replaceExisting) {
            pendingTooltip = new PendingTooltip(draw);
        }
    }

    private static void drawPending(GuiGraphics graphics) {
        PendingTooltip tooltip = pendingTooltip;
        pendingTooltip = null;
        if (tooltip != null) {
            tooltip.draw().accept(graphics);
        }
    }

    /** Draws the frame's tooltip after the screen, or after the HUD when no screen is open. */
    private static void listen() {
        if (listening) {
            return;
        }
        listening = true;
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Render.Post event) -> drawPending(event.getGuiGraphics()));
        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post event) -> {
            if (Minecraft.getInstance().screen == null) {
                drawPending(event.getGuiGraphics());
            }
        });
    }

    private record PendingTooltip(Consumer<GuiGraphics> draw) {
    }

    /** {@link #pose()}: the 2D part of the GuiGraphics pose stack, changed through it. */
    private static final class Pose extends Matrix3x2fStack {
        private final PoseStack stack;

        Pose(PoseStack stack) {
            super(1);
            this.stack = stack;
        }

        Pose sync() {
            Matrix4f m = stack.last().pose();
            super.set(m.m00(), m.m01(), m.m10(), m.m11(), m.m30(), m.m31());
            return this;
        }

        @Override
        public Matrix3x2fStack pushMatrix() {
            stack.pushPose();
            return this;
        }

        @Override
        public Matrix3x2fStack popMatrix() {
            stack.popPose();
            return sync();
        }

        @Override
        public Matrix3x2f translate(float x, float y) {
            stack.translate(x, y, 0.0F);
            return sync();
        }

        @Override
        public Matrix3x2f scale(float x, float y) {
            stack.scale(x, y, 1.0F);
            return sync();
        }

        @Override
        public Matrix3x2f scale(float xy) {
            return scale(xy, xy);
        }

        @Override
        public Matrix3x2f rotate(float angle) {
            stack.mulPose(new org.joml.Quaternionf().rotationZ(angle));
            return sync();
        }
    }
}
