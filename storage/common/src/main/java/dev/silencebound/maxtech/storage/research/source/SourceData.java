package dev.silencebound.maxtech.storage.research.source;

import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * What each research team has scanned (block ids) and analysed (item ids), saved next to the research data in the
 * Overworld ({@code data/maxtech_storage/research_sources.dat}). Keyed by the team's anchor, so a player who changes
 * team reads the new team's sets, as with every other research progress. Server thread only.
 */
public final class SourceData extends SavedData {
    private static final Codec<Team> TEAM_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("team").forGetter(Team::anchor),
            Identifier.CODEC.listOf().optionalFieldOf("scanned", List.of()).forGetter(t -> List.copyOf(t.scanned())),
            Identifier.CODEC.listOf().optionalFieldOf("analysed", List.of()).forGetter(t -> List.copyOf(t.analysed())))
            .apply(instance, (anchor, scanned, analysed) -> new Team(anchor, new ObjectOpenHashSet<>(scanned),
                    new ObjectOpenHashSet<>(analysed))));
    /** How the data is saved: one entry per team that has scanned or analysed anything. */
    public static final Codec<SourceData> CODEC = TEAM_CODEC.listOf().xmap(SourceData::new,
            data -> new ArrayList<>(data.teams.values()));
    public static final SavedDataType<SourceData> TYPE = StorageSaveFormats
            .savedData(MaxtechStorage.id("research_sources"), SourceData::new, CODEC);

    private final Map<UUID, Team> teams = new Object2ObjectOpenHashMap<>();

    private record Team(UUID anchor, Set<Identifier> scanned, Set<Identifier> analysed) {
    }

    private SourceData() {
    }

    private SourceData(List<Team> loaded) {
        for (Team team : loaded) {
            teams.put(team.anchor(), team);
        }
    }

    /** The server's source data. */
    public static SourceData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** Records that {@code team} scanned block {@code block}; true the first time. */
    public boolean scan(ResearchTeam team, Identifier block) {
        return added(team(team).scanned().add(block));
    }

    /** Records that {@code team} analysed item {@code item}; true the first time. */
    public boolean analyse(ResearchTeam team, Identifier item) {
        return added(team(team).analysed().add(item));
    }

    /** Whether {@code team} has scanned block {@code block}. */
    public boolean scanned(ResearchTeam team, Identifier block) {
        Team known = teams.get(team.anchor());
        return known != null && known.scanned().contains(block);
    }

    /** Whether {@code team} has analysed item {@code item}. */
    public boolean analysed(ResearchTeam team, Identifier item) {
        Team known = teams.get(team.anchor());
        return known != null && known.analysed().contains(item);
    }

    private boolean added(boolean added) {
        if (added) {
            setDirty();
        }
        return added;
    }

    private Team team(ResearchTeam team) {
        Team known = teams.get(team.anchor());
        if (known == null) {
            known = new Team(team.anchor(), new ObjectOpenHashSet<>(), new ObjectOpenHashSet<>());
            teams.put(team.anchor(), known);
        }
        return known;
    }
}
