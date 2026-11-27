package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.neoforge.api.client.conduit.ConduitModelHook;
import dev.silencebound.maxtech.lib.neoforge.api.client.conduit.RegisterConduitModelsEvent;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * The model hook of {@link ConduitApiGameTests#HOOKED_TUBE}, on the NeoForge api alone: it overrides nothing, so the
 * tube draws Lib's own parts through the hook path. Part of the GameTest source set, so it is in dev runs only.
 */
@EventBusSubscriber(modid = ConduitApiGameTests.MOD_ID, value = Dist.CLIENT)
public final class HookedTubeDemoClient {
    private HookedTubeDemoClient() {
    }

    @SubscribeEvent
    static void register(RegisterConduitModelsEvent event) {
        event.register(ConduitApiGameTests.HOOKED_TUBE, new ConduitModelHook() {
            @Override
            public Set<Identifier> sprites() {
                return Set.of();
            }
        });
    }
}
