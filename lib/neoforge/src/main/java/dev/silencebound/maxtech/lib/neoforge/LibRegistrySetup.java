package dev.silencebound.maxtech.lib.neoforge;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.conduit.ConduitTypes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.NewRegistryEvent;
import net.neoforged.neoforge.registries.RegistryBuilder;

/**
 * Creates Lib's custom registries (conduit types). Common code only knows their keys and reads them back from the root
 * registry, so {@code Platform} needs no custom-registry method.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
final class LibRegistrySetup {
    private LibRegistrySetup() {
    }

    @SubscribeEvent
    static void createRegistries(NewRegistryEvent event) {
        // Synced so a client with a different set of entries is refused on login instead of misbehaving.
        event.create(new RegistryBuilder<>(ConduitTypes.REGISTRY_KEY).sync(true));
    }
}
