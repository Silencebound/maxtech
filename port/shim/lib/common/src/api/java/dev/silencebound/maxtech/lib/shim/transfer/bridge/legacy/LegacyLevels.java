package dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Drops the ledger keys of a level when it unloads, so the wrappers' ledger registries never hold a stale level. */
@EventBusSubscriber(modid = "maxtech_lib")
public final class LegacyLevels {
    private LegacyLevels() {
    }

    @SubscribeEvent
    static void unload(LevelEvent.Unload event) {
        Object level = event.getLevel();
        LegacyItemHandler.LEDGERS.forget(level);
        LegacyFluidHandler.LEDGERS.forget(level);
        LegacyEnergyHandler.LEDGERS.forget(level);
    }
}
