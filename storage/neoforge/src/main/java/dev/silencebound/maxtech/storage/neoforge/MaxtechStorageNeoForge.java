package dev.silencebound.maxtech.storage.neoforge;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.neoforge.compat.ae2.Ae2Compat;
import dev.silencebound.maxtech.storage.neoforge.compat.ae2.Ae2Lang;
import dev.silencebound.maxtech.storage.neoforge.compat.curios.CuriosCarriedSource;
import dev.silencebound.maxtech.storage.neoforge.compat.curios.CuriosSlots;
import dev.silencebound.maxtech.storage.neoforge.compat.ftbteams.FtbTeamsSource;
import dev.silencebound.maxtech.storage.neoforge.compat.refinedstorage.RefinedStorageCompat;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;

@Mod(MaxtechStorage.MOD_ID)
public final class MaxtechStorageNeoForge {
    public MaxtechStorageNeoForge(IEventBus modBus) {
        MaxtechStorage.init();
        WorldDeviceEvents.install();
        if (ModList.get().isLoaded(FtbTeamsSource.MOD_ID)) {
            FtbTeamsSource.init();
        }
        if (ModList.get().isLoaded(CuriosSlots.MOD_ID)) {
            CuriosCarriedSource.install();
        }
        if (ModList.get().isLoaded(Ae2Lang.MOD_ID)) {
            Ae2Compat.install();
        }
        RefinedStorageCompat.init();
    }
}
