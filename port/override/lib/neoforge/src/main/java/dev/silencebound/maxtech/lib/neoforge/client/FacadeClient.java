// port-override: base=e2fa1d6c1c1af1f1
package dev.silencebound.maxtech.lib.neoforge.client;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.conduit.ConduitItem;
import dev.silencebound.maxtech.lib.conduit.ConduitRenderData;
import dev.silencebound.maxtech.lib.config.ClientConfig;
import dev.silencebound.maxtech.lib.facade.FacadeView;
import dev.silencebound.maxtech.lib.tool.ToolTags;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

/**
 * Client side of facades: the facade item model, the facade's tint on the conduit, and the
 * {@code showConduitsThroughFacade} toggle.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID, value = Dist.CLIENT)
final class FacadeClient {
    private FacadeClient() {
    }

    @SubscribeEvent
    static void registerItemModels(ModelEvent.RegisterGeometryLoaders event) {
        event.register(FacadeItemModel.Unbaked.ID, FacadeItemModel.Unbaked.LOADER);
    }

    /**
     * The block renderer asks a conduit block's colour for the tinted quads of the facade model it draws; the answer is
     * the facade's own, so a grass or leaf-coloured facade keeps its colour. Conduit quads carry no tint index and
     * never ask. (26.1 asks NeoForge's dynamic tint hook instead.)
     */
    @SubscribeEvent
    static void registerColors(RegisterColorHandlersEvent.Block event) {
        Block[] conduits = ConduitBlock.all().toArray(new Block[0]);
        // Lib alone (or with only addons without conduits, such as Farms) has no conduit blocks.
        if (conduits.length == 0) {
            return;
        }
        event.register((state, level, pos, tintIndex) -> {
            if (level == null || pos == null) {
                return -1;
            }
            ConduitRenderData data = level.getModelData(pos).get(ConduitBlockEntity.RENDER_DATA);
            BlockState facade = data == null ? null : data.facade();
            return facade == null
                    ? -1
                    : Minecraft.getInstance().getBlockColors().getColor(facade, level, pos, tintIndex);
        }, conduits);
    }

    /**
     * Checks the held items once a tick; only a change of the answer, which needs the option on, re-meshes anything.
     */
    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        boolean shown = player != null && ClientConfig.showConduitsThroughFacade()
                && (reveals(player.getMainHandItem()) || reveals(player.getOffhandItem()));
        ClientLevel level = minecraft.level;
        if (FacadeView.setConduitsShown(shown) && player != null && level != null) {
            remeshFacades(minecraft, level, player.chunkPosition());
        }
    }

    private static boolean reveals(ItemStack stack) {
        return stack.is(ToolTags.ACTS_AS_WRENCH) || stack.getItem() instanceof ConduitItem;
    }

    /**
     * Marks every section within render distance that holds a facaded conduit for re-meshing. Walks the loaded chunks'
     * block entities, which is fine for something that happens when the player switches items with the option on.
     */
    private static void remeshFacades(Minecraft minecraft, ClientLevel level, ChunkPos centre) {
        int radius = minecraft.options.getEffectiveRenderDistance();
        for (int x = centre.x - radius; x <= centre.x + radius; x++) {
            for (int z = centre.z - radius; z <= centre.z + radius; z++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (blockEntity instanceof ConduitBlockEntity conduitEntity && conduitEntity.facade() != null) {
                        BlockPos pos = conduitEntity.getBlockPos();
                        minecraft.levelRenderer.setSectionDirty(SectionPos.blockToSectionCoord(pos.getX()),
                                SectionPos.blockToSectionCoord(pos.getY()), SectionPos.blockToSectionCoord(pos.getZ()));
                    }
                }
            }
        }
    }
}
