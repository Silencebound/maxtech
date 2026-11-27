package dev.silencebound.maxtech.lib.neoforge;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.menu.MenuValuesPayload;
import dev.silencebound.maxtech.lib.neoforge.compat.ftbteams.FtbTeamsCompat;
import dev.silencebound.maxtech.lib.platform.Platform;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;

@Mod(MaxtechLib.MOD_ID)
public final class MaxtechLibNeoForge {
    /** FTB Teams' mod id; its compat class must not load without it. */
    private static final String FTB_TEAMS = "ftbteams";

    public MaxtechLibNeoForge() {
        MaxtechLib.init();
        ContentEnabledCondition.init();
        Platform.get().network().registerToClient(MaxtechLib.MOD_ID, MenuValuesPayload.TYPE, MenuValuesPayload.CODEC,
                MenuValuesPayload::receive);
        if (ModList.get().isLoaded(FTB_TEAMS)) {
            FtbTeamsCompat.init();
        }
    }
}
