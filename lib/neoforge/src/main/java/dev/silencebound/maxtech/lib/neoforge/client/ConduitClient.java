package dev.silencebound.maxtech.lib.neoforge.client;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.content.CustomConduitModels;
import dev.silencebound.maxtech.lib.neoforge.api.client.conduit.RegisterConduitModelsEvent;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterBlockStateModels;

/**
 * Client wiring for conduit blocks: their block state model type, and the addon hooks of conduits marked
 * {@code customModel}. The hooks are collected once at startup ({@link RegisterConduitModelsEvent}, fired from here);
 * after every bake each hooked block's {@link ConduitModel} is replaced by one drawn by its hook. Storage's cable
 * blocks are never marked, so this never touches the models Storage wraps.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID, value = Dist.CLIENT)
final class ConduitClient {
    private static volatile Map<Identifier, ConduitModelHooks.Hooked> hooks = Map.of();

    private ConduitClient() {
    }

    @SubscribeEvent
    static void registerModels(RegisterBlockStateModels event) {
        event.registerModel(ConduitModel.Unbaked.ID, ConduitModel.Unbaked.CODEC);
        // Fires once while the client starts, after registration: every conduit is known by then.
        ConduitModelHooks collected = new ConduitModelHooks(CustomConduitModels.all());
        ModLoader.postEvent(new RegisterConduitModelsEvent(collected::add));
        hooks = collected.finish();
    }

    @SubscribeEvent
    static void wrapHooked(ModelEvent.ModifyBakingResult event) {
        Map<BlockState, BlockStateModel> models = event.getBakingResult().blockStateModels();
        for (ConduitModelHooks.Hooked hooked : hooks.values()) {
            Block block = BuiltInRegistries.BLOCK.getValue(hooked.block());
            ConduitModel.Custom custom = new ConduitModel.Custom(hooked.block(), hooked.hook(), ConduitModelHooks
                    .sprites(hooked.hook(), id -> new Material.Baked(event.getTextureGetter().apply(id), false)),
                    hooked.translucent());
            // States sharing a baked model share the hooked one, and with it the part caches.
            IdentityHashMap<BlockStateModel, ConduitModel> wrapped = new IdentityHashMap<>();
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                BlockStateModel base = models.get(state);
                if (!(base instanceof ConduitModel conduit)) {
                    throw new IllegalStateException("Conduit " + hooked.block()
                            + " is marked customModel, but its block state model is not " + ConduitModel.Unbaked.ID
                            + "; set a look on its builder or name that model in its block state file");
                }
                models.put(state, wrapped.computeIfAbsent(conduit, lib -> conduit.withHook(custom)));
            }
        }
    }
}
