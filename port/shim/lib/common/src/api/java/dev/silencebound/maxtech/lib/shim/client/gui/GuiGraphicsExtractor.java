package dev.silencebound.maxtech.lib.shim.client.gui;
import com.mojang.blaze3d.platform.Window;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.client.model.Model;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.metadata.gui.GuiMetadataSection;
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling;
import net.minecraft.core.component.DataComponents;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ResultField;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.state.properties.WoodType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;
import org.joml.Quaternionf;
import org.joml.Vector2ic;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
@OnlyIn(Dist.CLIENT)
public class GuiGraphicsExtractor {
    public GuiGraphicsExtractor(Minecraft minecraft, Object guiRenderState, int mouseX, int mouseY) { throw new UnsupportedOperationException("port stub"); }
    public void requestCursor(Object cursorType) { throw new UnsupportedOperationException("port stub"); }
    public void applyCursor(Window window) { throw new UnsupportedOperationException("port stub"); }
    public int guiWidth() { throw new UnsupportedOperationException("port stub"); }
    public int guiHeight() { throw new UnsupportedOperationException("port stub"); }
    public Matrix3x2fStack pose() { throw new UnsupportedOperationException("port stub"); }
    public void nextStratum() { throw new UnsupportedOperationException("port stub"); }
    public void blurBeforeThisStratum() { throw new UnsupportedOperationException("port stub"); }
    public void enableScissor(int x0, int y0, int x1, int y1) { throw new UnsupportedOperationException("port stub"); }
    public void disableScissor() { throw new UnsupportedOperationException("port stub"); }
    public boolean containsPointInScissor(int x, int y) { throw new UnsupportedOperationException("port stub"); }
    public void horizontalLine(int x0, int x1, int y, int col) { throw new UnsupportedOperationException("port stub"); }
    public void verticalLine(int x, int y0, int y1, int col) { throw new UnsupportedOperationException("port stub"); }
    public void fill(int x0, int y0, int x1, int y1, int col) { throw new UnsupportedOperationException("port stub"); }
    public void fill(Object pipeline, int x0, int y0, int x1, int y1, int col) { throw new UnsupportedOperationException("port stub"); }
    public void fillGradient(int x0, int y0, int x1, int y1, int col1, int col2) { throw new UnsupportedOperationException("port stub"); }
    public void fill(Object renderPipeline, Object textureSetup, int x0, int y0, int x1, int y1) { throw new UnsupportedOperationException("port stub"); }
    public void outline(int x, int y, int width, int height, int color) { throw new UnsupportedOperationException("port stub"); }
    public void textHighlight(int x0, int y0, int x1, int y1, boolean invertText) { throw new UnsupportedOperationException("port stub"); }
    public void text(Font font, @Nullable String str, int x, int y, int color) { throw new UnsupportedOperationException("port stub"); }
    public void text(Font font, @Nullable String str, int x, int y, int color, boolean dropShadow) { throw new UnsupportedOperationException("port stub"); }
    public void text(Font font, FormattedCharSequence str, int x, int y, int color) { throw new UnsupportedOperationException("port stub"); }
    public void text(Font font, FormattedCharSequence str, int x, int y, int color, boolean dropShadow) { throw new UnsupportedOperationException("port stub"); }
    public void text(Font font, Component str, int x, int y, int color) { throw new UnsupportedOperationException("port stub"); }
    public void text(Font font, Component str, int x, int y, int color, boolean dropShadow) { throw new UnsupportedOperationException("port stub"); }
    public void centeredText(Font font, String str, int x, int y, int color) { throw new UnsupportedOperationException("port stub"); }
    public void centeredText(Font font, Component text, int x, int y, int color) { throw new UnsupportedOperationException("port stub"); }
    public void centeredText(Font font, FormattedCharSequence text, int x, int y, int color) { throw new UnsupportedOperationException("port stub"); }
    public void textWithWordWrap(Font font, FormattedText string, int x, int y, int width, int col) { throw new UnsupportedOperationException("port stub"); }
    public void textWithWordWrap(Font font, FormattedText string, int x, int y, int width, int col, boolean dropShadow) { throw new UnsupportedOperationException("port stub"); }
    public void textWithBackdrop(Font font, Component str, int textX, int textY, int textWidth, int textColor) { throw new UnsupportedOperationException("port stub"); }
    public void blit(
        Object renderPipeline,
        ResourceLocation texture,
        int x,
        int y,
        float u,
        float v,
        int width,
        int height,
        int textureWidth,
        int textureHeight,
        int color
    ) { throw new UnsupportedOperationException("port stub"); }
    public void blit(
        Object renderPipeline, ResourceLocation texture, int x, int y, float u, float v, int width, int height, int textureWidth, int textureHeight
    ) { throw new UnsupportedOperationException("port stub"); }
    public void blit(
        Object renderPipeline,
        ResourceLocation texture,
        int x,
        int y,
        float u,
        float v,
        int width,
        int height,
        int srcWidth,
        int srcHeight,
        int textureWidth,
        int textureHeight
    ) { throw new UnsupportedOperationException("port stub"); }
    public void blit(
        Object renderPipeline,
        ResourceLocation texture,
        int x,
        int y,
        float u,
        float v,
        int width,
        int height,
        int srcWidth,
        int srcHeight,
        int textureWidth,
        int textureHeight,
        int color
    ) { throw new UnsupportedOperationException("port stub"); }
    public void blit(ResourceLocation location, int x0, int y0, int x1, int y1, float u0, float u1, float v0, float v1) { throw new UnsupportedOperationException("port stub"); }
    public void blit(Object textureView, Object sampler, int x0, int y0, int x1, int y1, float u0, float u1, float v0, float v1) { throw new UnsupportedOperationException("port stub"); }
    public void blitSprite(Object renderPipeline, ResourceLocation location, int x, int y, int width, int height) { throw new UnsupportedOperationException("port stub"); }
    public void blitSprite(Object renderPipeline, ResourceLocation location, int x, int y, int width, int height, float alpha) { throw new UnsupportedOperationException("port stub"); }
    public void blitSprite(Object renderPipeline, ResourceLocation location, int x, int y, int width, int height, int color) { throw new UnsupportedOperationException("port stub"); }
    public void blitSprite(
        Object renderPipeline, ResourceLocation location, int spriteWidth, int spriteHeight, int textureX, int textureY, int x, int y, int width, int height
    ) { throw new UnsupportedOperationException("port stub"); }
    public void blitSprite(
        Object renderPipeline,
        ResourceLocation location,
        int spriteWidth,
        int spriteHeight,
        int textureX,
        int textureY,
        int x,
        int y,
        int width,
        int height,
        int color
    ) { throw new UnsupportedOperationException("port stub"); }
    public void blitSprite(Object renderPipeline, TextureAtlasSprite sprite, int x, int y, int width, int height) { throw new UnsupportedOperationException("port stub"); }
    public void blitSprite(Object renderPipeline, TextureAtlasSprite sprite, int x, int y, int width, int height, int color) { throw new UnsupportedOperationException("port stub"); }
    public void item(ItemStack itemStack, int x, int y) { throw new UnsupportedOperationException("port stub"); }
    public void item(ItemStack itemStack, int x, int y, int seed) { throw new UnsupportedOperationException("port stub"); }
    public void item(LivingEntity owner, ItemStack itemStack, int x, int y, int seed) { throw new UnsupportedOperationException("port stub"); }
    public void fakeItem(ItemStack itemStack, int x, int y) { throw new UnsupportedOperationException("port stub"); }
    public void fakeItem(ItemStack itemStack, int x, int y, int seed) { throw new UnsupportedOperationException("port stub"); }
    public void itemDecorations(Font font, ItemStack itemStack, int x, int y) { throw new UnsupportedOperationException("port stub"); }
    public void itemDecorations(Font font, ItemStack itemStack, int x, int y, @Nullable String countText) { throw new UnsupportedOperationException("port stub"); }
    public void map(Object mapRenderState) { throw new UnsupportedOperationException("port stub"); }
    public void entity(
        Object renderState,
        float scale,
        Vector3f translation,
        Quaternionf rotation,
        @Nullable Quaternionf overrideCameraAngle,
        int x0,
        int y0,
        int x1,
        int y1
    ) { throw new UnsupportedOperationException("port stub"); }
    public void skin(Object playerModel, ResourceLocation texture, float scale, float rotationX, float rotationY, float pivotY, int x0, int y0, int x1, int y1) { throw new UnsupportedOperationException("port stub"); }
    public void book(Object bookModel, ResourceLocation texture, float scale, float open, float flip, int x0, int y0, int x1, int y1) { throw new UnsupportedOperationException("port stub"); }
    public void bannerPattern(Object flag, DyeColor baseColor, BannerPatternLayers resultBannerPatterns, int x0, int y0, int x1, int y1) { throw new UnsupportedOperationException("port stub"); }
    public void sign(Object signModel, float scale, WoodType woodType, int x0, int y0, int x1, int y1) { throw new UnsupportedOperationException("port stub"); }
    public void profilerChart(List<ResultField> chartData, int x0, int y0, int x1, int y1) { throw new UnsupportedOperationException("port stub"); }
    public void setTooltipForNextFrame(Component component, int x, int y) { throw new UnsupportedOperationException("port stub"); }
    public void setTooltipForNextFrame(List<FormattedCharSequence> formattedCharSequences, int x, int y) { throw new UnsupportedOperationException("port stub"); }
    public void setTooltipForNextFrame(Font font, ItemStack itemStack, int xo, int yo) { throw new UnsupportedOperationException("port stub"); }
    public void setTooltipForNextFrame(Font font, List<Component> textComponents, Optional<TooltipComponent> tooltipComponent, ItemStack stack, int mouseX, int mouseY) { throw new UnsupportedOperationException("port stub"); }
    public void setTooltipForNextFrame(Font font, List<Component> textComponents, Optional<TooltipComponent> tooltipComponent, ItemStack stack, int mouseX, int mouseY, @Nullable ResourceLocation backgroundTexture) { throw new UnsupportedOperationException("port stub"); }
    public void setTooltipForNextFrame(Font font, List<Component> texts, Optional<TooltipComponent> optionalImage, int xo, int yo) { throw new UnsupportedOperationException("port stub"); }
    public void setTooltipForNextFrame(Font font, List<Component> texts, Optional<TooltipComponent> optionalImage, int xo, int yo, @Nullable ResourceLocation style) { throw new UnsupportedOperationException("port stub"); }
    public void setTooltipForNextFrame(
        Font font,
        List<FormattedCharSequence> tooltip,
        Optional<TooltipComponent> component,
        ClientTooltipPositioner positioner,
        int xo,
        int yo,
        boolean replaceExisting,
        @Nullable ResourceLocation style
    ) { throw new UnsupportedOperationException("port stub"); }
    public void setTooltipForNextFrame(Font font, Component text, int xo, int yo) { throw new UnsupportedOperationException("port stub"); }
    public void setTooltipForNextFrame(Font font, Component text, int xo, int yo, @Nullable ResourceLocation style) { throw new UnsupportedOperationException("port stub"); }
    public void setComponentTooltipForNextFrame(Font font, List<Component> lines, int xo, int yo) { throw new UnsupportedOperationException("port stub"); }
    public void setComponentTooltipForNextFrame(Font font, List<Component> lines, int xo, int yo, @Nullable ResourceLocation style) { throw new UnsupportedOperationException("port stub"); }
    public void setComponentTooltipForNextFrame(Font font, List<? extends FormattedText> tooltips, int mouseX, int mouseY, ItemStack stack) { throw new UnsupportedOperationException("port stub"); }
    public void setComponentTooltipForNextFrame(Font font, List<? extends FormattedText> tooltips, int mouseX, int mouseY, ItemStack stack, @Nullable ResourceLocation backgroundTexture) { throw new UnsupportedOperationException("port stub"); }
    public void setComponentTooltipFromElementsForNextFrame(Font font, List<com.mojang.datafixers.util.Either<FormattedText, TooltipComponent>> elements, int mouseX, int mouseY, ItemStack stack) { throw new UnsupportedOperationException("port stub"); }
    public void setComponentTooltipFromElementsForNextFrame(Font font, List<com.mojang.datafixers.util.Either<FormattedText, TooltipComponent>> elements, int mouseX, int mouseY, ItemStack stack, @Nullable ResourceLocation backgroundTexture) { throw new UnsupportedOperationException("port stub"); }
    public void setTooltipForNextFrame(Font font, List<? extends FormattedCharSequence> lines, int xo, int yo) { throw new UnsupportedOperationException("port stub"); }
    public void setTooltipForNextFrame(Font font, List<? extends FormattedCharSequence> lines, int xo, int yo, @Nullable ResourceLocation style) { throw new UnsupportedOperationException("port stub"); }
    public void setTooltipForNextFrame(
        Font font, List<FormattedCharSequence> tooltip, ClientTooltipPositioner positioner, int xo, int yo, boolean replaceExisting
    ) { throw new UnsupportedOperationException("port stub"); }
    public void tooltip(Font font, List<ClientTooltipComponent> lines, int xo, int yo, ClientTooltipPositioner positioner, @Nullable ResourceLocation style) { throw new UnsupportedOperationException("port stub"); }
    public void tooltip(Font font, List<ClientTooltipComponent> lines, int xo, int yo, ClientTooltipPositioner positioner, @Nullable ResourceLocation style, ItemStack tooltipStack) { throw new UnsupportedOperationException("port stub"); }
    public void setPreeditOverlay(Renderable preeditOverlay) { throw new UnsupportedOperationException("port stub"); }
    public void extractDeferredElements(int mouseX, int mouseY, float a) { throw new UnsupportedOperationException("port stub"); }
    public void componentHoverEffect(Font font, Style hoveredStyle, int xMouse, int yMouse) { throw new UnsupportedOperationException("port stub"); }
    public void submitGuiElementRenderState(Object renderState) { throw new UnsupportedOperationException("port stub"); }
    public void submitPictureInPictureRenderState(Object renderState) { throw new UnsupportedOperationException("port stub"); }
    @Nullable
    public ScreenRectangle peekScissorStack() { throw new UnsupportedOperationException("port stub"); }
    public TextureAtlasSprite getSprite(Object sprite) { throw new UnsupportedOperationException("port stub"); }
    public Object textRendererForWidget(AbstractWidget owner, GuiGraphicsExtractor.HoveredTextEffects hoveredTextEffects) { throw new UnsupportedOperationException("port stub"); }
    public Object textRenderer() { throw new UnsupportedOperationException("port stub"); }
    public Object textRenderer(GuiGraphicsExtractor.HoveredTextEffects hoveredTextEffects) { throw new UnsupportedOperationException("port stub"); }
    public Object textRenderer(GuiGraphicsExtractor.HoveredTextEffects hoveredTextEffects, @Nullable Consumer<Style> additionalHoverStyleConsumer) { throw new UnsupportedOperationException("port stub"); }
    @OnlyIn(Dist.CLIENT)
    public static enum HoveredTextEffects {
        NONE(false, false), TOOLTIP_ONLY(true, false), TOOLTIP_AND_CURSOR(true, true);
        private HoveredTextEffects(boolean allowTooltip, boolean allowCursorChanges) { this.allowTooltip = allowTooltip; this.allowCursorChanges = allowCursorChanges; }
        public final boolean allowTooltip;
        public final boolean allowCursorChanges;
        public static GuiGraphicsExtractor.HoveredTextEffects notClickable(boolean canTooltip) { throw new UnsupportedOperationException("port stub"); }
    }
}
