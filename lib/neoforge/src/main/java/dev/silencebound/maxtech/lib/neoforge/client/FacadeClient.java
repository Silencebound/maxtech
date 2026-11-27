package dev.silencebound.maxtech.lib.neoforge.client;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.conduit.ConduitItem;
import dev.silencebound.maxtech.lib.conduit.ConduitRenderData;
import dev.silencebound.maxtech.lib.config.ClientConfig;
import dev.silencebound.maxtech.lib.facade.FacadeView;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.tool.ToolTags;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
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
import net.neoforged.neoforge.client.event.RegisterItemModelsEvent;
import net.neoforged.neoforge.client.extensions.common.IClientBlockExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/**
 * Client side of facades: the facade item model, the facade's tint on the conduit, and the
 * {@code showConduitsThroughFacade} toggle.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID, value = Dist.CLIENT)
final class FacadeClient {
    private FacadeClient() {
    }

    @SubscribeEvent
    static void registerItemModels(RegisterItemModelsEvent event) {
        event.register(FacadeItemModel.Unbaked.ID, FacadeItemModel.Unbaked.CODEC);
    }

    /**
     * Conduit blocks register no tint sources, so the block renderer asks these extensions for per-position tints
     * instead (NeoForge's hook for mimicking blocks); they are the facade's, so a grass or leaf-coloured facade keeps
     * its colour. Conduit quads carry no tint index and never ask.
     */
    @SubscribeEvent
    static void registerExtensions(RegisterClientExtensionsEvent event) {
        Block[] conduits = ConduitBlock.all().toArray(new Block[0]);
        // Lib alone (or with only addons without conduits, such as Farms) has no conduit blocks, and NeoForge refuses
        // an extension with no targets.
        if (conduits.length == 0) {
            return;
        }
        event.registerBlock(new IClientBlockExtensions() {
            @Override
            public void collectDynamicTintValues(BlockState state, BlockAndTintGetter level, BlockPos pos,
                    IntList tintValues) {
                ConduitRenderData data = level.getModelData(pos).get(ConduitBlockEntity.RENDER_DATA);
                BlockState facade = data == null ? null : data.facade();
                if (facade == null) {
                    return;
                }
                List<BlockTintSource> sources = Minecraft.getInstance().getBlockColors().getTintSources(facade);
                for (int i = 0; i < sources.size(); i++) {
                    tintValues.add(sources.get(i).colorInWorld(facade, level, pos));
                }
            }
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
        for (int x = centre.x() - radius; x <= centre.x() + radius; x++) {
            for (int z = centre.z() - radius; z <= centre.z() + radius; z++) {
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
