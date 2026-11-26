package dev.silencebound.maxtech.storage.controller.module;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.api.security.OwnerSecurity;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;
import org.jspecify.annotations.Nullable;

/**
 * The access rules a controller's security module applies to its network (PRD 7.2, Controller): a level for players
 * with a rule of their own, for members of a trusted scoreboard team, for the owner's team, and for everyone else. The
 * owner, players the owner trusts and game masters always have full access. Saved on the controller core and kept in
 * its item. Immutable; any thread.
 *
 * @param everyone
 *            the level of players no rule names
 * @param ownerTeam
 *            the level of players on the owner's team (Lib's team resolvers)
 * @param players
 *            per-player rules, at most {@link #MAX_PLAYERS}, one per player
 * @param teams
 *            per-scoreboard-team rules, at most {@link #MAX_TEAMS}, one per team
 */
public record SecuritySettings(AccessLevel everyone, AccessLevel ownerTeam, List<PlayerRule> players,
        List<TeamRule> teams) {
    /** Most player rules. */
    public static final int MAX_PLAYERS = 32;
    /** Most team rules. */
    public static final int MAX_TEAMS = 16;
    /** Longest scoreboard team name kept. */
    public static final int MAX_TEAM_NAME = 64;
    /** Strangers have no access, the owner's team full access: owner security as without a module. */
    public static final SecuritySettings DEFAULT = new SecuritySettings(AccessLevel.NONE, AccessLevel.FULL, List.of(),
            List.of());

    /** One player's level. */
    public record PlayerRule(UUID id, String name, AccessLevel level) {
        public static final Codec<PlayerRule> CODEC = RecordCodecBuilder
                .create(i -> i
                        .group(UUIDUtil.CODEC.fieldOf("id").forGetter(PlayerRule::id),
                                Codec.string(0, OwnerRecord.MAX_NAME_LENGTH).fieldOf("name")
                                        .forGetter(PlayerRule::name),
                                AccessLevel.CODEC.fieldOf("level").forGetter(PlayerRule::level))
                        .apply(i, PlayerRule::new));
        public static final StreamCodec<ByteBuf, PlayerRule> STREAM_CODEC = StreamCodec.composite(UUIDUtil.STREAM_CODEC,
                PlayerRule::id, ByteBufCodecs.stringUtf8(OwnerRecord.MAX_NAME_LENGTH), PlayerRule::name,
                AccessLevel.STREAM_CODEC, PlayerRule::level, PlayerRule::new);
    }

    /** One scoreboard team's level. */
    public record TeamRule(String team, AccessLevel level) {
        public static final Codec<TeamRule> CODEC = RecordCodecBuilder
                .create(i -> i.group(Codec.string(1, MAX_TEAM_NAME).fieldOf("team").forGetter(TeamRule::team),
                        AccessLevel.CODEC.fieldOf("level").forGetter(TeamRule::level)).apply(i, TeamRule::new));
        public static final StreamCodec<ByteBuf, TeamRule> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(MAX_TEAM_NAME), TeamRule::team, AccessLevel.STREAM_CODEC, TeamRule::level,
                TeamRule::new);
    }

    public static final Codec<SecuritySettings> CODEC = RecordCodecBuilder.create(i -> i.group(
            AccessLevel.CODEC.optionalFieldOf("everyone", AccessLevel.NONE).forGetter(SecuritySettings::everyone),
            AccessLevel.CODEC.optionalFieldOf("owner_team", AccessLevel.FULL).forGetter(SecuritySettings::ownerTeam),
            Codec.list(PlayerRule.CODEC, 0, MAX_PLAYERS).optionalFieldOf("players", List.of())
                    .forGetter(SecuritySettings::players),
            Codec.list(TeamRule.CODEC, 0, MAX_TEAMS).optionalFieldOf("teams", List.of())
                    .forGetter(SecuritySettings::teams))
            .apply(i, SecuritySettings::new));

    public static final StreamCodec<ByteBuf, SecuritySettings> STREAM_CODEC = StreamCodec.composite(
            AccessLevel.STREAM_CODEC, SecuritySettings::everyone, AccessLevel.STREAM_CODEC, SecuritySettings::ownerTeam,
            PlayerRule.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_PLAYERS)), SecuritySettings::players,
            TeamRule.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_TEAMS)), SecuritySettings::teams, SecuritySettings::new);

    public SecuritySettings {
        players = List.copyOf(players.size() > MAX_PLAYERS ? players.subList(0, MAX_PLAYERS) : players);
        teams = List.copyOf(teams.size() > MAX_TEAMS ? teams.subList(0, MAX_TEAMS) : teams);
    }

    /**
     * What {@code player} may do with a network owned by {@code owner}: full access for the owner, trusted players and
     * game masters (Lib's owner security without team access); then the player's own rule, a rule for the player's
     * scoreboard team, the owner's team level, and the level for everyone else. An unowned network lets everyone in.
     */
    public AccessLevel levelFor(Player player, OwnerRecord owner) {
        if (canEdit(player, owner)) {
            return AccessLevel.FULL;
        }
        UUID id = player.getUUID();
        for (PlayerRule rule : players) {
            if (rule.id().equals(id)) {
                return rule.level();
            }
        }
        PlayerTeam team = player.getTeam();
        if (team != null) {
            for (TeamRule rule : teams) {
                if (rule.team().equals(team.getName())) {
                    return rule.level();
                }
            }
        }
        if (OwnerSecurity.sameTeam(player, owner)) {
            return ownerTeam;
        }
        return everyone;
    }

    /**
     * Whether {@code player} may change the rules of a network owned by {@code owner}: its owner, players the owner
     * trusts and game masters; everyone for an unowned network.
     */
    public static boolean canEdit(Player player, OwnerRecord owner) {
        return OwnerSecurity.canAccess(player, owner.withTeamAccess(false));
    }

    /** The rule {@code id} has, or null. */
    public @Nullable AccessLevel playerLevel(UUID id) {
        for (PlayerRule rule : players) {
            if (rule.id().equals(id)) {
                return rule.level();
            }
        }
        return null;
    }

    /** The rule scoreboard team {@code name} has, or null. */
    public @Nullable AccessLevel teamLevel(String name) {
        for (TeamRule rule : teams) {
            if (rule.team().equals(name)) {
                return rule.level();
            }
        }
        return null;
    }

    /** These settings with {@code level} for everyone else. */
    public SecuritySettings withEveryone(AccessLevel level) {
        return new SecuritySettings(level, ownerTeam, players, teams);
    }

    /** These settings with {@code level} for the owner's team. */
    public SecuritySettings withOwnerTeam(AccessLevel level) {
        return new SecuritySettings(everyone, level, players, teams);
    }

    /**
     * These settings with {@code level} as {@code id}'s rule, or without a rule for {@code id} when {@code level} is
     * null. A new rule beyond {@link #MAX_PLAYERS} is not added.
     */
    public SecuritySettings withPlayer(UUID id, String name, @Nullable AccessLevel level) {
        List<PlayerRule> next = new ArrayList<>(players.size() + 1);
        boolean found = false;
        for (PlayerRule rule : players) {
            if (rule.id().equals(id)) {
                found = true;
                if (level != null) {
                    next.add(new PlayerRule(id, name, level));
                }
            } else {
                next.add(rule);
            }
        }
        if (!found && level != null && next.size() < MAX_PLAYERS) {
            next.add(new PlayerRule(id, name, level));
        }
        return new SecuritySettings(everyone, ownerTeam, next, teams);
    }

    /**
     * These settings with {@code level} as scoreboard team {@code name}'s rule, or without one when {@code level} is
     * null. A new rule beyond {@link #MAX_TEAMS} is not added.
     */
    public SecuritySettings withTeam(String name, @Nullable AccessLevel level) {
        List<TeamRule> next = new ArrayList<>(teams.size() + 1);
        boolean found = false;
        for (TeamRule rule : teams) {
            if (rule.team().equals(name)) {
                found = true;
                if (level != null) {
                    next.add(new TeamRule(name, level));
                }
            } else {
                next.add(rule);
            }
        }
        if (!found && level != null && next.size() < MAX_TEAMS) {
            next.add(new TeamRule(name, level));
        }
        return new SecuritySettings(everyone, ownerTeam, players, next);
    }
}
