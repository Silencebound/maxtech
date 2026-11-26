package dev.silencebound.maxtech.storage.neoforge.compat.ftbteams;

import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.neoforge.FTBTeamsEvent;
import dev.silencebound.maxtech.storage.logic.controller.TeamBudget;
import dev.silencebound.maxtech.storage.logic.controller.TeamSource;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;
import org.jspecify.annotations.Nullable;

/**
 * FTB Teams as the {@link TeamSource}: a player in an FTB party team shares that team's logic budget, notices and
 * server caps. A player in no party has only FTB's personal team, whose id is their own UUID; that answers null, so the
 * research team still applies. Before FTB's manager loads it answers null too. {@link TeamBudget} caches the answers;
 * FTB's team change and team deleted events (posted on the game bus) clear that cache, so joining or leaving a party
 * applies to the logic budget at once (the caps at the network's next rebuild). Loaded only when FTB Teams is
 * installed.
 */
public final class FtbTeamsSource implements TeamSource {
    /** FTB Teams' mod id; this class must not load without it. */
    public static final String MOD_ID = "ftbteams";

    private FtbTeamsSource() {
    }

    /** Installs the source and its cache resets; call only when {@link #MOD_ID} is loaded. */
    public static void init() {
        TeamBudget.setTeamSource(new FtbTeamsSource());
        NeoForge.EVENT_BUS.addListener(FTBTeamsEvent.PlayerChangedTeam.class, event -> TeamBudget.forgetTeams());
        NeoForge.EVENT_BUS.addListener(FTBTeamsEvent.TeamDeleted.class, event -> TeamBudget.forgetTeams());
    }

    @Override
    public @Nullable UUID team(MinecraftServer server, UUID player) {
        FTBTeamsAPI.API api = FTBTeamsAPI.api();
        if (!api.isManagerLoaded()) {
            return null;
        }
        // FTB's lookup allocates an Optional; TeamBudget caches the answer, so this runs only on a cache miss.
        Optional<Team> team = api.getManager().getTeamForPlayerID(player);
        if (team.isEmpty() || team.get().isPlayerTeam()) {
            return null;
        }
        return team.get().getId();
    }
}
