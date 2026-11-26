package dev.silencebound.maxtech.storage.neoforge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.display.DisplayClientParts;
import dev.silencebound.maxtech.storage.display.DisplayContent;
import dev.silencebound.maxtech.storage.display.DisplayPart;
import dev.silencebound.maxtech.storage.display.DisplaySourceKind;
import dev.silencebound.maxtech.storage.display.DisplayView;
import dev.silencebound.maxtech.storage.display.DisplayWalls;
import dev.silencebound.maxtech.storage.display.client.ScreenPainter;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMaps;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/**
 * The display panels' client glue: registers the panel block entity renderer, and draws display parts, which live in
 * the cable's block entity and get no renderer of their own. Each frame the extract event picks the known parts in the
 * frustum and within the panels' view distance and samples their windows; the submit event draws them.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID, value = Dist.CLIENT)
final class DisplayClient {
    /** As vanilla's block entity renderers. */
    private static final double VIEW_DISTANCE = 64;
    private static final double PART_HALF = 6.0 / 16.0;
    private static final ObjectArrayList<DisplayPart> VISIBLE = new ObjectArrayList<>();
    private static float time;

    private DisplayClient() {
    }

    @SubscribeEvent
    static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(DisplayContent.PANEL_BLOCK_ENTITY.get(), DisplayPanelRenderer::new);
    }

    @SubscribeEvent
    static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        DisplayClientParts.prune(true);
        VISIBLE.clear();
    }

    // A part of another level (the client switched dimensions) is skipped: levels are compared by identity.
    @SuppressWarnings("ReferenceEquality")
    @SubscribeEvent
    static void extract(ExtractLevelRenderStateEvent event) {
        // Last frame's quads are drawn by now; the panels' renderer and the parts reuse them this frame.
        ScreenPainter.beginFrame();
        VISIBLE.clear();
        Reference2ObjectMap<DisplayPart, AABB> parts = DisplayClientParts.parts();
        if (parts.isEmpty()) {
            return;
        }
        DisplayClientParts.prune(false);
        Vec3 camera = event.getRenderState().cameraRenderState.pos;
        long gameTime = event.getLevel().getGameTime();
        time = gameTime + event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        for (Reference2ObjectMap.Entry<DisplayPart, AABB> entry : Reference2ObjectMaps.fastIterable(parts)) {
            DisplayPart part = entry.getKey();
            AABB box = entry.getValue();
            double dx = box.minX + 0.5 - camera.x;
            double dy = box.minY + 0.5 - camera.y;
            double dz = box.minZ + 0.5 - camera.z;
            if (part.host().level() != event.getLevel() || dx * dx + dy * dy + dz * dz >= VIEW_DISTANCE * VIEW_DISTANCE
                    || !event.getFrustum().isVisible(box)) {
                continue;
            }
            if (part.settings().source() != DisplaySourceKind.HISTORY) {
                // A history chart's points come from the server's record only.
                part.view().sample(gameTime, Math.max(1, part.settings().windowSeconds() * 20 / DisplayView.SAMPLES));
            }
            VISIBLE.add(part);
        }
    }

    @SubscribeEvent
    static void submit(SubmitCustomGeometryEvent event) {
        if (VISIBLE.isEmpty()) {
            return;
        }
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        PoseStack pose = event.getPoseStack();
        for (int i = 0; i < VISIBLE.size(); i++) {
            DisplayPart part = VISIBLE.get(i);
            Direction face = part.face();
            Direction right = DisplayWalls.right(face);
            Direction up = DisplayWalls.up(face);
            BlockPos pos = part.pos();
            // The part's front lies on the cable block's face; the 12 px screen is centred on it.
            double x = pos.getX() + 0.5 + face.getStepX() * 0.501 - PART_HALF * (right.getStepX() + up.getStepX());
            double y = pos.getY() + 0.5 + face.getStepY() * 0.501 - PART_HALF * (right.getStepY() + up.getStepY());
            double z = pos.getZ() + 0.5 + face.getStepZ() * 0.501 - PART_HALF * (right.getStepZ() + up.getStepZ());
            ScreenPainter.submit(pose, event.getSubmitNodeCollector(), Minecraft.getInstance().font,
                    new ScreenPainter.Frame(x - camera.x, y - camera.y, z - camera.z, face, 0.75F, 0.75F,
                            ScreenPainter.PART_BEZEL),
                    part.view(), part.settings(), time);
        }
    }
}
