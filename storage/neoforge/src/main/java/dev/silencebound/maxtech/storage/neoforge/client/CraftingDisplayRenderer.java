package dev.silencebound.maxtech.storage.neoforge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageClientConfig;
import dev.silencebound.maxtech.storage.crafting.assembler.AssemblerContent;
import dev.silencebound.maxtech.storage.crafting.assembler.CraftingDisplay;
import dev.silencebound.maxtech.storage.crafting.crafter.CrafterContent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * The item a crafter or crafting assembler is crafting, turning slowly in the middle of the block behind its glass (B09
 * item 29, like AE2's molecular assembler; the owner's exception to "only the controller animates", which is about
 * textures). Draws only while the block reports an item ({@link CraftingDisplay#craftingItem()}, synced and cleared by
 * time), within {@code visuals.craftingDisplayDistance} blocks, with {@code visuals.craftingDisplay} on and the camera
 * in front of one of its windows; {@link #shouldRender} checks all that before a render state exists, so an idle block
 * costs a few comparisons a frame and allocates nothing. Vanilla item rendering with the light at the block. Client
 * render thread.
 */
final class CraftingDisplayRenderer<T extends BlockEntity & CraftingDisplay>
        implements
            BlockEntityRenderer<T, CraftingDisplayRenderer.State> {
    /** Degrees the item turns per tick: a full turn in nine seconds. */
    private static final float DEGREES_PER_TICK = 2F;
    /** The item's size in blocks, inside the window. */
    private static final float SIZE = 0.55F;

    CraftingDisplayRenderer(BlockEntityRendererProvider.Context context) {
    }

    /** What one frame draws of one block. */
    static final class State extends BlockEntityRenderState {
        final ItemStackRenderState item = new ItemStackRenderState();
        final Quaternionf turn = new Quaternionf();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(T entity, State state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(entity, state, partialTicks, cameraPosition, breakProgress);
        Level level = entity.getLevel();
        ItemStack stack = entity.craftingItem();
        long time = level == null ? 0 : level.getGameTime();
        Minecraft.getInstance().getItemModelResolver().updateForTopItem(state.item, stack, ItemDisplayContext.FIXED,
                level, null, 0);
        float degrees = ((time % 180) + partialTicks) * DEGREES_PER_TICK;
        state.turn.rotationY(degrees * Mth.DEG_TO_RAD);
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.item.isEmpty()) {
            return;
        }
        pose.pushPose();
        pose.translate(0.5F, 0.5F, 0.5F);
        pose.mulPose(state.turn);
        pose.scale(SIZE, SIZE, SIZE);
        state.item.submit(pose, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        pose.popPose();
    }

    @Override
    public int getViewDistance() {
        return StorageClientConfig.craftingDisplayDistance();
    }

    /**
     * Whether to draw this frame: switched on, an item to show, within the distance, and the camera in front of a
     * window (more than half a block out of the block's centre along the window's side), so a crafter seen from behind
     * draws nothing.
     */
    @Override
    public boolean shouldRender(T entity, Vec3 cameraPosition) {
        if (!StorageClientConfig.craftingDisplay() || entity.craftingItem().isEmpty()) {
            return false;
        }
        BlockPos pos = entity.getBlockPos();
        double dx = cameraPosition.x - (pos.getX() + 0.5);
        double dy = cameraPosition.y - (pos.getY() + 0.5);
        double dz = cameraPosition.z - (pos.getZ() + 0.5);
        double distance = getViewDistance();
        if (dx * dx + dy * dy + dz * dz >= distance * distance) {
            return false;
        }
        return CraftingDisplay.seenThroughWindow(entity.windowMask(), dx, dy, dz);
    }

    /** Registers the renderer for both blocks with a window. */
    @EventBusSubscriber(modid = MaxtechStorage.MOD_ID, value = Dist.CLIENT)
    static final class Registration {
        private Registration() {
        }

        @SubscribeEvent
        static void renderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerBlockEntityRenderer(AssemblerContent.BLOCK_ENTITY.get(), CraftingDisplayRenderer::new);
            event.registerBlockEntityRenderer(CrafterContent.BLOCK_ENTITY.get(), CraftingDisplayRenderer::new);
        }
    }
}
