package dev.silencebound.maxtech.storage.neoforge.client;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.tool.carried.client.CarriedKeys;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

/** Registers the "open carried gadget" keys, which act in the world only, and polls them each client tick. */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID, value = Dist.CLIENT)
final class CarriedKeyRegistration {
    private CarriedKeyRegistration() {
    }

    @SubscribeEvent
    static void registerKeys(RegisterKeyMappingsEvent event) {
        event.registerCategory(CarriedKeys.CATEGORY);
        CarriedKeys.OPEN.setKeyConflictContext(KeyConflictContext.IN_GAME);
        event.register(CarriedKeys.OPEN);
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        CarriedKeys.tick(Minecraft.getInstance());
    }
}
