package dev.silencebound.maxtech.storage.neoforge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.silencebound.maxtech.storage.display.DisplayWalls;
import dev.silencebound.maxtech.storage.smartlabel.LabelMode;
import dev.silencebound.maxtech.storage.smartlabel.LabelSettings;
import dev.silencebound.maxtech.storage.smartlabel.LabelUpdate;
import dev.silencebound.maxtech.storage.smartlabel.LabelView;
import dev.silencebound.maxtech.storage.smartlabel.SmartLabelBlock;
import dev.silencebound.maxtech.storage.smartlabel.SmartLabelBlockEntity;
import dev.silencebound.maxtech.storage.smartlabel.SmartLabelContent;
import dev.silencebound.maxtech.storage.terminal.AmountFormat;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * Draws what a smart label shows on its plate: an item's icon and count, a fill bar with its percentage, or the
 * machine's state. A block entity renderer, because the label is a block of its own (not a cable, which never gets one)
 * and its text and icon change with the values; the plate itself is the block model. Text is full-bright and the bar is
 * vanilla's position-colour {@code debugQuads}, as on the display panels. Client render thread; vanilla and Blaze3D
 * APIs only.
 */
final class SmartLabelRenderer implements BlockEntityRenderer<SmartLabelBlockEntity, SmartLabelRenderer.State> {
    private static final Quaternionf[] ROTATIONS = new Quaternionf[6];
    private static final float WIDTH = SmartLabelBlock.WIDTH / 16F;
    private static final float HEIGHT = SmartLabelBlock.HEIGHT / 16F;
    /** The plate's front lies this far behind the block's centre, along its facing; the picture just in front. */
    private static final double FRONT = -0.5 + SmartLabelBlock.DEPTH / 16.0 + 0.002;
    private static final float MARGIN = 0.05F;
    private static final int TEXT = 0xFFDCE6EE;
    private static final int ACCENT = 0xFF7FE9F5;
    private static final int DIM = 0xFFA9B4BE;
    private static final int GOOD = 0xFF5FD068;
    private static final int WARN = 0xFFE0A040;
    private static final int FULL = 0xFFE05A5A;
    private static final int TRACK = 0xFF2A3640;

    static {
        for (Direction facing : Direction.values()) {
            Direction right = DisplayWalls.right(facing);
            Direction up = DisplayWalls.up(facing);
            // Columns: local x to the viewer's right, y up, z out of the plate.
            Matrix3f basis = new Matrix3f(right.getStepX(), right.getStepY(), right.getStepZ(), up.getStepX(),
                    up.getStepY(), up.getStepZ(), facing.getStepX(), facing.getStepY(), facing.getStepZ());
            ROTATIONS[facing.get3DDataValue()] = basis.getNormalizedRotation(new Quaternionf());
        }
    }

    private static @Nullable Language language;
    private static FormattedCharSequence noItem = FormattedCharSequence.EMPTY;
    private static FormattedCharSequence nothing = FormattedCharSequence.EMPTY;
    private static FormattedCharSequence working = FormattedCharSequence.EMPTY;
    private static FormattedCharSequence idle = FormattedCharSequence.EMPTY;
    private static FormattedCharSequence stuck = FormattedCharSequence.EMPTY;

    private final Font font;

    SmartLabelRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.font();
    }

    /** What one frame draws of one label. */
    static final class State extends BlockEntityRenderState {
        Direction facing = Direction.NORTH;
        LabelMode mode = LabelMode.ITEM;
        int status = LabelUpdate.NO_DATA;
        int detail;
        long value;
        long max;
        boolean hasIcon;
        final ItemStackRenderState icon = new ItemStackRenderState();
        final Bar bar = new Bar();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(SmartLabelBlockEntity label, State state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(label, state, partialTicks, cameraPosition, breakProgress);
        LabelView view = label.view();
        LabelSettings settings = label.settings();
        state.facing = label.facing();
        state.mode = settings.mode();
        state.status = view.status();
        state.detail = view.detail();
        state.value = view.value();
        state.max = view.max();
        Optional<Item> item = settings.item();
        state.hasIcon = settings.mode() == LabelMode.ITEM && item.isPresent();
        if (state.hasIcon && item.isPresent()) {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.getItemModelResolver().updateForTopItem(state.icon, icon(view, item.get()),
                    ItemDisplayContext.GUI, label.getLevel(), null, 0);
        }
    }

    /** The label's icon stack, made once per item. */
    private static ItemStack icon(LabelView view, Item item) {
        ItemStack stack = view.icon();
        if (!stack.is(item)) {
            stack = new ItemStack(item);
            view.setIcon(stack);
        }
        return stack;
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.status == LabelUpdate.NO_DATA) {
            return;
        }
        refreshLanguage();
        Direction facing = state.facing;
        Direction right = DisplayWalls.right(facing);
        Direction up = DisplayWalls.up(facing);
        double x = 0.5 + facing.getStepX() * FRONT - (right.getStepX() * WIDTH + up.getStepX() * HEIGHT) / 2;
        double y = 0.5 + facing.getStepY() * FRONT - (right.getStepY() * WIDTH + up.getStepY() * HEIGHT) / 2;
        double z = 0.5 + facing.getStepZ() * FRONT - (right.getStepZ() * WIDTH + up.getStepZ() * HEIGHT) / 2;
        pose.pushPose();
        pose.translate(x, y, z);
        pose.mulPose(ROTATIONS[facing.get3DDataValue()]);
        if (state.status != LabelUpdate.SHOWN) {
            text(pose, collector, state.mode == LabelMode.ITEM && !state.hasIcon ? noItem : nothing, WIDTH / 2,
                    HEIGHT / 2 - 0.04F, 0.08F, DIM, true);
        } else {
            switch (state.mode) {
                case ITEM -> item(state, pose, collector);
                case FILL -> fill(state, pose, collector);
                case MACHINE -> machine(state, pose, collector);
            }
        }
        pose.popPose();
    }

    private void item(State state, PoseStack pose, SubmitNodeCollector collector) {
        float size = HEIGHT - 2 * MARGIN - 0.06F;
        if (state.hasIcon) {
            pose.pushPose();
            pose.translate(MARGIN + size / 2, HEIGHT / 2, 0.01F);
            // Flat on the plate: the GUI model's depth squashed to a twentieth.
            pose.scale(size, size, size * 0.05F);
            state.icon.submit(pose, collector, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
            pose.popPose();
        }
        float left = MARGIN + size + 0.03F;
        FormattedCharSequence count = FormattedCharSequence.forward(AmountFormat.compact(state.value), Style.EMPTY);
        text(pose, collector, count, (left + WIDTH - MARGIN) / 2, HEIGHT / 2 - 0.07F, 0.14F, ACCENT, true);
    }

    private void fill(State state, PoseStack pose, SubmitNodeCollector collector) {
        float fraction = state.max <= 0 ? 0 : (float) Math.clamp((double) state.value / state.max, 0, 1);
        int percent = (int) Math.floor(fraction * 100);
        int colour = fraction >= 0.9F ? FULL : ACCENT;
        FormattedCharSequence shown = FormattedCharSequence.forward(percent + "%", Style.EMPTY);
        text(pose, collector, shown, WIDTH / 2, HEIGHT / 2 + 0.01F, 0.13F, colour, true);
        float barTop = HEIGHT / 2 - 0.04F;
        float barBottom = barTop - 0.05F;
        Bar bar = state.bar;
        bar.set(MARGIN, barBottom, WIDTH - MARGIN, barTop, MARGIN + (WIDTH - 2 * MARGIN) * fraction, colour);
        collector.submitCustomGeometry(pose, RenderTypes.debugQuads(), bar);
    }

    private void machine(State state, PoseStack pose, SubmitNodeCollector collector) {
        if (state.detail == LabelUpdate.STUCK) {
            text(pose, collector, stuck, WIDTH / 2, HEIGHT / 2 + 0.02F, 0.12F, WARN, true);
            FormattedCharSequence reason = Component
                    .translatable(SmartLabelContent.reasonKey(SmartLabelContent.reasonById(state.value)))
                    .getVisualOrderText();
            text(pose, collector, reason, WIDTH / 2, HEIGHT / 2 - 0.12F, 0.08F, DIM, true);
            return;
        }
        boolean on = state.detail == LabelUpdate.WORKING;
        text(pose, collector, on ? working : idle, WIDTH / 2, HEIGHT / 2 - 0.06F, 0.12F, on ? GOOD : TEXT, true);
    }

    /** Translated texts are cached until the language changes (a resource reload makes a new {@link Language}). */
    // Identity says whether the language is the one the texts were made for.
    @SuppressWarnings("ReferenceEquality")
    private static void refreshLanguage() {
        Language current = Language.getInstance();
        if (current == language) {
            return;
        }
        language = current;
        noItem = Component.translatable(SmartLabelContent.NO_ITEM_KEY).getVisualOrderText();
        nothing = Component.translatable(SmartLabelContent.NOTHING_KEY).getVisualOrderText();
        working = Component.translatable(SmartLabelContent.WORKING_KEY).getVisualOrderText();
        idle = Component.translatable(SmartLabelContent.IDLE_KEY).getVisualOrderText();
        stuck = Component.translatable(SmartLabelContent.STUCK_KEY).getVisualOrderText();
    }

    /**
     * Draws {@code text} {@code height} blocks tall with its bottom at {@code y} and its left edge (or centre) at
     * {@code x}, shrunk to fit the plate.
     */
    private void text(PoseStack pose, SubmitNodeCollector collector, FormattedCharSequence sequence, float x, float y,
            float height, int colour, boolean centred) {
        int pixels = Math.max(1, font.width(sequence));
        float scale = height / font.lineHeight;
        float room = WIDTH - 2 * MARGIN;
        if (pixels * scale > room) {
            scale = room / pixels;
        }
        float left = centred ? x - pixels * scale / 2 : x;
        pose.pushPose();
        pose.translate(left, y + font.lineHeight * scale, 0.003F);
        pose.scale(scale, -scale, scale);
        collector.submitText(pose, 0, 0, sequence, false, Font.DisplayMode.POLYGON_OFFSET, LightCoordsUtil.FULL_BRIGHT,
                colour, 0, 0);
        pose.popPose();
    }

    /** The fill bar: a dark track and the filled part, recorded on the render thread and drawn with the batch. */
    static final class Bar implements SubmitNodeCollector.CustomGeometryRenderer {
        private float x0;
        private float y0;
        private float x1;
        private float y1;
        private float filled;
        private int colour;

        void set(float x0, float y0, float x1, float y1, float filled, int colour) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.filled = filled;
            this.colour = colour;
        }

        @Override
        public void render(PoseStack.Pose pose, VertexConsumer buffer) {
            quad(pose, buffer, x0, x1, TRACK, 0.001F);
            if (filled > x0) {
                // A hair in front of the track.
                quad(pose, buffer, x0, filled, colour, 0.0015F);
            }
        }

        /** Counter-clockwise as the viewer sees it. */
        private void quad(PoseStack.Pose pose, VertexConsumer buffer, float left, float right, int argb, float z) {
            buffer.addVertex(pose, left, y0, z).setColor(argb);
            buffer.addVertex(pose, right, y0, z).setColor(argb);
            buffer.addVertex(pose, right, y1, z).setColor(argb);
            buffer.addVertex(pose, left, y1, z).setColor(argb);
        }
    }
}
