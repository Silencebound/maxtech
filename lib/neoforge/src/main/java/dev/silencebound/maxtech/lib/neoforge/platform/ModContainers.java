package dev.silencebound.maxtech.lib.neoforge.platform;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;

final class ModContainers {
    private ModContainers() {
    }

    static ModContainer get(String modId) {
        return ModList.get().getModContainerById(modId)
                .orElseThrow(() -> new IllegalStateException("Mod " + modId + " is not loaded"));
    }

    static IEventBus eventBus(String modId) {
        IEventBus bus = get(modId).getEventBus();
        if (bus == null) {
            throw new IllegalStateException("Mod " + modId + " has no event bus");
        }
        return bus;
    }
}
