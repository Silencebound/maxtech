package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.logic.controller.TeamBudget;
import dev.silencebound.maxtech.storage.logic.controller.TeamSource;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Server caps per team and level (PRD 7.2, Performance: server caps; owner decision 2026-10-04): devices, zones and
 * cell bytes the networks of one team may hold together in one level, each 0 (off) in the standalone preset. A
 * network's team is its owner's FTB team when FTB Teams is installed and the owner is in a party (the logic budget's
 * {@link TeamSource}, owner decision 2026-10-09), else its owner's research team, else its owner alone; unowned
 * networks are not capped. A network picks up a team change when it next rebuilds. Over the device cap a network turns
 * new devices away as at its controller's limit; past the zone cap nodes get no route; at the byte cap the team's
 * networks store nothing more. Server thread only.
 */
public final class TeamCaps {
    /** How long (ticks) a team's summed cell bytes are reused before they are summed again; a performance bound. */
    static final int BYTES_INTERVAL = 20;

    private TeamCaps() {
    }

    /**
     * The team {@code owner}'s networks count against: the {@link TeamSource}'s team (cached by {@link TeamBudget}),
     * else the research team, else the owner; null for an unowned network (never capped).
     */
    public static @Nullable UUID team(ServerLevel level, OwnerRecord owner) {
        if (!owner.isOwned()) {
            return null;
        }
        UUID shared = TeamBudget.sourceTeam(level, owner.owner());
        if (shared != null) {
            return shared;
        }
        ResearchTeam team = Research.data(level.getServer()).team(level.getServer(), owner);
        return team != null ? team.anchor() : owner.owner();
    }

    /** Whether any cap is on. */
    public static boolean anyOn() {
        return StorageServerConfig.CAP_DEVICES_PER_TEAM.getAsInt() > 0
                || StorageServerConfig.CAP_ZONES_PER_TEAM.getAsInt() > 0
                || StorageServerConfig.CAP_BYTES_PER_TEAM.getAsLong() > 0;
    }
}
