package dev.silencebound.maxtech.storage.neoforge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.silencebound.maxtech.storage.display.DisplayPanelBlockEntity;
import dev.silencebound.maxtech.storage.display.DisplaySettings;
import dev.silencebound.maxtech.storage.display.DisplaySourceKind;
import dev.silencebound.maxtech.storage.display.DisplayView;
import dev.silencebound.maxtech.storage.display.DisplayWalls;
import dev.silencebound.maxtech.storage.display.client.ScreenPainter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Draws wall screens: only a wall's anchor panel draws, over the whole wall ({@link ScreenPainter}); the other panels'
 * models show the dark glass. The anchor's render box covers the wall and the renderer renders off screen, so the
 * frustum check is on the whole wall, not on the anchor's section. Sampling the client's rolling window happens here,
 * so a screen out of view records nothing.
 */
final class DisplayPanelRenderer implements BlockEntityRenderer<DisplayPanelBlockEntity, DisplayPanelRenderer.State> {
    /**
     * The panel's glass lies 2 px from its back, 6 px behind the block's centre along its facing, recessed 1 px behind
     * the frame (DisplayModels' glass and frame elements).
     */
    private static final double SCREEN_FROM_CENTRE = -6.0 / 16.0;
    /** Lifts the picture off the glass so it never fights it for depth. */
    private static final double LIFT = 0.001;

    private final Font font;

    DisplayPanelRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.font();
    }

    /** What one frame draws of one anchor. */
    static final class State extends BlockEntityRenderState {
        @Nullable
        DisplayView view;
        DisplaySettings settings = DisplaySettings.DEFAULT;
        Direction facing = Direction.NORTH;
        int width = 1;
        int height = 1;
        float time;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(DisplayPanelBlockEntity panel, State state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(panel, state, partialTicks, cameraPosition, breakProgress);
        DisplayView view = panel.view();
        DisplaySettings settings = panel.settings();
        long gameTime = panel.getLevel() == null ? 0 : panel.getLevel().getGameTime();
        if (settings.source() != DisplaySourceKind.HISTORY) {
            // A history chart's points come from the server's record only.
            view.sample(gameTime, Math.max(1, settings.windowSeconds() * 20 / DisplayView.SAMPLES));
        }
        state.view = view;
        state.settings = settings;
        state.facing = panel.facing();
        state.width = panel.wallWidth();
        state.height = panel.wallHeight();
        state.time = gameTime + partialTicks;
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        DisplayView view = state.view;
        if (view == null) {
            return;
        }
        Direction facing = state.facing;
        Direction right = DisplayWalls.right(facing);
        Direction up = DisplayWalls.up(facing);
        double along = SCREEN_FROM_CENTRE + LIFT;
        double x = 0.5 + facing.getStepX() * along - 0.5 * (right.getStepX() + up.getStepX());
        double y = 0.5 + facing.getStepY() * along - 0.5 * (right.getStepY() + up.getStepY());
        double z = 0.5 + facing.getStepZ() * along - 0.5 * (right.getStepZ() + up.getStepZ());
        ScreenPainter.submit(pose, collector, font,
                new ScreenPainter.Frame(x, y, z, facing, state.width, state.height, ScreenPainter.PANEL_BEZEL), view,
                state.settings, state.time);
    }

    /** Only anchors draw; the others leave it to them. */
    @Override
    public boolean shouldRender(DisplayPanelBlockEntity panel, Vec3 cameraPosition) {
        return panel.isAnchor() && BlockEntityRenderer.super.shouldRender(panel, cameraPosition);
    }

    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    // PLATFORM-LEAK: not common code; NeoForge's IBlockEntityRendererExtension sizes the frustum check to the wall.
    @Override
    public AABB getRenderBoundingBox(DisplayPanelBlockEntity panel) {
        BlockPos anchor = panel.getBlockPos();
        if (!panel.isAnchor()) {
            return new AABB(anchor);
        }
        BlockPos far = anchor.relative(DisplayWalls.right(panel.facing()), panel.wallWidth() - 1)
                .relative(DisplayWalls.up(panel.facing()), panel.wallHeight() - 1);
        return AABB.encapsulatingFullBlocks(anchor, far);
    }
}
