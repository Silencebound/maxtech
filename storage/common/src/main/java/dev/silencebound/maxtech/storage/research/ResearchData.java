package dev.silencebound.maxtech.storage.research;

import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.api.security.OwnerSecurity;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.machine.ResearchWaits;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * Every team's research progress, in the Overworld's save data ({@code data/maxtech_storage/research.dat}), and the one
 * place that decides which team a player belongs to.
 *
 * <p>
 * Lib's owner security only answers "are these two on one team" (vanilla scoreboard teams, FTB Teams when present), so
 * a team is anchored on a player: a player joins the oldest team whose anchor shares a team with them, and otherwise
 * gets a team of their own (a solo player is their own team). Membership is re-checked when a player logs in and when
 * they open the research screen; between those checks the remembered team is used, which keeps lookups by owner (an
 * offline machine owner) to one map read. A player who leaves a team returns to their own team's progress. Server
 * thread only.
 */
public final class ResearchData extends SavedData {
    /** How the data is saved: the list of teams. */
    public static final Codec<ResearchData> CODEC = ResearchTeam.CODEC.listOf().xmap(ResearchData::new, d -> d.teams);
    public static final SavedDataType<ResearchData> TYPE = StorageSaveFormats.savedData(MaxtechStorage.id("research"),
            ResearchData::new, CODEC);

    private final List<ResearchTeam> teams;
    private final Object2ObjectOpenHashMap<UUID, ResearchTeam> byMember = new Object2ObjectOpenHashMap<>();
    private final Object2ObjectOpenHashMap<UUID, ResearchTeam> byAnchor = new Object2ObjectOpenHashMap<>();
    private long nextCreated;
    /** Rises whenever a player's team changes, so cached team lookups know to look again. */
    private int version;

    private ResearchData() {
        this(List.of());
    }

    private ResearchData(List<ResearchTeam> loaded) {
        this.teams = new ArrayList<>(loaded);
        for (ResearchTeam team : teams) {
            team.attach(this);
            byAnchor.put(team.anchor, team);
            nextCreated = Math.max(nextCreated, team.created + 1);
        }
        // Members first, anchors last: a player is always in their own team's index if they anchor one and are
        // listed nowhere else.
        for (ResearchTeam team : teams) {
            for (UUID member : team.members) {
                if (!member.equals(team.anchor)) {
                    byMember.put(member, team);
                }
            }
        }
        for (ResearchTeam team : teams) {
            byMember.putIfAbsent(team.anchor, team);
        }
    }

    /** The server's research data. */
    public static ResearchData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** Every team, oldest first. */
    public List<ResearchTeam> teams() {
        return teams;
    }

    /**
     * {@code player}'s team. With {@code recheck}, membership is checked again against Lib's team rules (on login and
     * when the research screen opens); otherwise the remembered team is returned when there is one.
     */
    public ResearchTeam team(ServerPlayer player, boolean recheck) {
        UUID id = player.getUUID();
        ResearchTeam current = byMember.get(id);
        if (current != null && !recheck) {
            return current;
        }
        ResearchTeam best = null;
        for (int i = 0; i < teams.size(); i++) {
            ResearchTeam team = teams.get(i);
            if (!team.anchor.equals(id) && OwnerSecurity.sameTeam(player, team.anchorRecord())
                    && (best == null || team.created < best.created)) {
                best = team;
            }
        }
        if (best == null) {
            best = own(id, player.getGameProfile().name());
        }
        // A player who anchors an older team keeps it rather than joining a newer one.
        ResearchTeam own = byAnchor.get(id);
        if (own != null && own.created < best.created) {
            best = own;
        }
        if (current != best) {
            if (current != null && !current.anchor.equals(id)) {
                current.members.remove(id);
            }
            best.members.add(id);
            byMember.put(id, best);
            version++;
            setDirty();
            if (current != null) {
                // The player's machines now answer to another team's research.
                ResearchWaits.wake(owner -> owner.isOwner(id));
                Research.teamChanged(id);
            }
        }
        return best;
    }

    /**
     * The team of {@code owner} (a network's or machine's owner), or null for an unowned thing. An owner whose team is
     * not known yet is resolved like a player when online, and gets a team of their own otherwise.
     */
    public @Nullable ResearchTeam team(MinecraftServer server, OwnerRecord owner) {
        if (!owner.isOwned()) {
            return null;
        }
        ResearchTeam known = byMember.get(owner.owner());
        if (known != null) {
            return known;
        }
        ServerPlayer online = server.getPlayerList().getPlayer(owner.owner());
        if (online != null) {
            return team(online, true);
        }
        ResearchTeam own = own(owner.owner(), owner.ownerName());
        byMember.put(owner.owner(), own);
        version++;
        return own;
    }

    /** The team anchored on {@code anchor}, or null. */
    public @Nullable ResearchTeam anchoredOn(UUID anchor) {
        return byAnchor.get(anchor);
    }

    /** Rises whenever a player's team changes. */
    public int version() {
        return version;
    }

    private ResearchTeam own(UUID id, String name) {
        ResearchTeam own = byAnchor.get(id);
        if (own == null) {
            own = new ResearchTeam(id, name, nextCreated++);
            own.attach(this);
            teams.add(own);
            byAnchor.put(id, own);
            setDirty();
        } else if (!name.isEmpty() && !name.equals(own.anchorName)) {
            own.anchorName = name;
            setDirty();
        }
        return own;
    }
}
