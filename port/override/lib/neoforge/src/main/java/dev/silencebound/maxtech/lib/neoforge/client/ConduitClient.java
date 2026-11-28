// port-override: base=c358e8b8562e13c4
package dev.silencebound.maxtech.lib.neoforge.client;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.client.conduit.ConduitLooks;
import dev.silencebound.maxtech.lib.content.CustomConduitModels;
import dev.silencebound.maxtech.lib.neoforge.api.client.conduit.RegisterConduitModelsEvent;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ModelEvent;

/**
 * Client wiring for conduit blocks: their model loader, and the addon hooks of conduits marked {@code customModel}.
 * The hooks are collected once at startup ({@link RegisterConduitModelsEvent}, fired from here); after every bake each
 * hooked block's {@link ConduitModel} is replaced by one drawn by its hook. Storage's cable blocks are never marked, so
 * this never touches the models Storage wraps.
 * <p>
 * Contents paints ({@link ConduitLooks}) are resolved again after every resource reload, once the new models and
 * atlases are in place ({@code ModelEvent.BakingCompleted}, which 1.21.1 fires after the atlas upload) and before the
 * chunks re-mesh; the look indices themselves stay, since loaded conduits' render data still names them, and are
 * dropped on leaving a world.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID, value = Dist.CLIENT)
final class ConduitClient {
    private static volatile Map<ResourceLocation, ConduitModelHooks.Hooked> hooks = Map.of();

    private ConduitClient() {
    }

    @SubscribeEvent
    static void registerModels(ModelEvent.RegisterGeometryLoaders event) {
        event.register(ConduitModel.Unbaked.ID, ConduitModel.Unbaked.LOADER);
        // Fires once while the client starts, after registration: every conduit is known by then.
        ConduitModelHooks collected = new ConduitModelHooks(CustomConduitModels.all());
        ModLoader.postEvent(new RegisterConduitModelsEvent(collected::add));
        hooks = collected.finish();
    }

    @SubscribeEvent
    static void baked(ModelEvent.BakingCompleted event) {
        ConduitLooks.INDEX.repaint();
    }

    @SubscribeEvent
    static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ConduitLooks.INDEX.clear();
    }

    @SubscribeEvent
    static void wrapHooked(ModelEvent.ModifyBakingResult event) {
        Map<ModelResourceLocation, BakedModel> models = event.getModels();
        Function<Material, TextureAtlasSprite> atlas = event.getTextureGetter();
        for (ConduitModelHooks.Hooked hooked : hooks.values()) {
            Block block = BuiltInRegistries.BLOCK.get(hooked.block());
            ConduitModel.Custom custom = new ConduitModel.Custom(hooked.block(), hooked.hook(),
                    ConduitModelHooks.sprites(hooked.hook(),
                            id -> atlas.apply(new Material(InventoryMenu.BLOCK_ATLAS, id))),
                    hooked.translucent());
            // States sharing a baked model share the hooked one, and with it the part caches.
            IdentityHashMap<BakedModel, ConduitModel> wrapped = new IdentityHashMap<>();
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                ModelResourceLocation location = BlockModelShaper.stateToModelLocation(state);
                BakedModel base = models.get(location);
                if (!(base instanceof ConduitModel conduit)) {
                    throw new IllegalStateException("Conduit " + hooked.block()
                            + " is marked customModel, but its block model is not " + ConduitModel.Unbaked.ID
                            + "; set a look on its builder or name that model loader in its block model file");
                }
                models.put(location, wrapped.computeIfAbsent(conduit, lib -> conduit.withHook(custom)));
            }
        }
    }
}
