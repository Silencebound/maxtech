package dev.silencebound.maxtech.farms.neoforge.client;

import dev.silencebound.maxtech.farms.MaxtechFarms;
import dev.silencebound.maxtech.farms.hub.HubStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Lets the lab hub screen's fuel slot know the client's furnace fuels, so a click into it is predicted. Client only.
 */
@EventBusSubscriber(modid = MaxtechFarms.MOD_ID, value = Dist.CLIENT)
final class HubClientSetup {
    private HubClientSetup() {
    }

    @SubscribeEvent
    static void setup(FMLClientSetupEvent event) {
        HubStorage.setClientFuels(() -> {
            ClientLevel level = Minecraft.getInstance().level;
            return level == null ? null : level.fuelValues();
        });
    }
}
