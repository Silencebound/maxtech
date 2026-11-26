package dev.silencebound.maxtech.lib.neoforge.compat.ftbteams;

import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.silencebound.maxtech.lib.api.security.OwnerSecurity;
import dev.silencebound.maxtech.lib.api.security.TeamResolver;
import java.util.UUID;
import net.minecraft.world.entity.player.Player;

/**
 * FTB Teams as a {@link TeamResolver}: a player shares the owner's FTB team (party or server team). Only the server has
 * FTB's team manager, so on a client, or before the manager loads, it answers false and access falls back to the other
 * rules. Loaded only when FTB Teams is installed.
 */
public final class FtbTeamsCompat implements TeamResolver {
    private FtbTeamsCompat() {
    }

    /** Registers the resolver; call only when {@code ftbteams} is loaded. */
    public static void init() {
        OwnerSecurity.registerTeamResolver(new FtbTeamsCompat());
    }

    @Override
    public boolean sameTeam(Player player, UUID owner, String ownerName) {
        FTBTeamsAPI.API api = FTBTeamsAPI.api();
        return !player.level().isClientSide() && api.isManagerLoaded()
                && api.getManager().arePlayersInSameTeam(player.getUUID(), owner);
    }
}
