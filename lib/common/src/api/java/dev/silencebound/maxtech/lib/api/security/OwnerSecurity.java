package dev.silencebound.maxtech.lib.api.security;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.jetbrains.annotations.ApiStatus;

/**
 * The one access check for owned things. A player has access when the record is unowned, the player is the owner or
 * trusted, the record allows team access and a {@link TeamResolver} puts the player on the owner's team, or the player
 * is a game master (permission level 2, as for {@code /gamemode}) and the server config
 * {@code security.opsBypassOwnership} is on (the default), so admins can reach a stuck machine. With the option off,
 * game masters get in only by the other rules. The option is read at each check, so it takes effect on config reload;
 * where no server config is loaded (a client outside a world) the default, on, applies. Teams come from vanilla
 * scoreboard teams plus every registered resolver.
 * <p>
 * Threading: the checks run on the thread of the caller, normally the server thread (a client may check its own player
 * to grey out a button); registration is safe from any thread.
 */
public final class OwnerSecurity {
    private static final List<TeamResolver> RESOLVERS = new CopyOnWriteArrayList<>();

    /** Vanilla scoreboard teams: the owner's name and the player's are on the same team (or allied teams). */
    public static final TeamResolver SCOREBOARD_TEAMS = OwnerSecurity::sameScoreboardTeam;

    private static volatile BooleanSupplier opsBypass = () -> true;

    private OwnerSecurity() {
    }

    /**
     * Adds a team resolver, asked after vanilla teams. Call during mod construction or setup; safe from any thread.
     */
    public static void registerTeamResolver(TeamResolver resolver) {
        RESOLVERS.add(resolver);
    }

    /**
     * Connects the game master bypass to the server config option; called once by Lib at startup. Other mods have no
     * reason to call it, and a later call replaces the earlier source. Until it is called the bypass is on.
     *
     * @param source
     *            read at each access check, possibly off the server thread; must not throw
     */
    @ApiStatus.Internal
    public static void setOpsBypassSource(BooleanSupplier source) {
        opsBypass = source;
    }

    /** Whether {@code player} may use {@code owned}; see the class comment. */
    public static boolean canAccess(Player player, Owned owned) {
        return canAccess(player, owned.ownerRecord());
    }

    /** Whether {@code player} may use something with {@code record}; see the class comment. */
    public static boolean canAccess(Player player, OwnerRecord record) {
        UUID id = player.getUUID();
        if (!record.isOwned() || record.isOwner(id) || record.isTrusted(id)) {
            return true;
        }
        if (record.teamAccess() && sameTeam(player, record)) {
            return true;
        }
        return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) && opsBypass.getAsBoolean();
    }

    /** Whether any resolver, vanilla teams first, puts {@code player} on the owner's team. */
    public static boolean sameTeam(Player player, OwnerRecord record) {
        if (!record.isOwned()) {
            return false;
        }
        if (SCOREBOARD_TEAMS.sameTeam(player, record.owner(), record.ownerName())) {
            return true;
        }
        for (TeamResolver resolver : RESOLVERS) {
            if (resolver.sameTeam(player, record.owner(), record.ownerName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameScoreboardTeam(Player player, UUID owner, String ownerName) {
        if (ownerName.isEmpty()) {
            return false;
        }
        Scoreboard scoreboard = player.level().getScoreboard();
        PlayerTeam ownerTeam = scoreboard.getPlayersTeam(ownerName);
        PlayerTeam playerTeam = scoreboard.getPlayersTeam(player.getScoreboardName());
        return ownerTeam != null && playerTeam != null && playerTeam.isAlliedTo(ownerTeam);
    }
}
