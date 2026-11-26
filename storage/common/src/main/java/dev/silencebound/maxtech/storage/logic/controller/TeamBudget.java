package dev.silencebound.maxtech.storage.logic.controller;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.TeamCaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * The logic budgets of each team, counted per second of game time across the server: checks
 * ({@code logic.teamChecksPerSecond}, 500 by default; M7L) for every node a graph runs and every sensor read, and
 * action points ({@code logic.teamActionPointsPerSecond}, 600 by default; M7L2 chunk 5) for the actions graphs send,
 * about 1 point per microsecond of server time an action takes. The two are separate, since an action costs two to
 * three orders of magnitude more than a node. A team's networks share it: the team is the owner's FTB team when FTB
 * Teams is installed and the owner is in a party ({@link TeamSource}), else the owner's research team, else the owner
 * alone ({@link TeamCaps#team}). A device without an owner (placed by a machine, a command or a structure) counts
 * against its network's owner's team; on a network without one, all such devices share the {@link #UNOWNED} budget at
 * the same limit, so nothing runs unlimited.
 *
 * <p>
 * A graph's cost is known only after it ran, so a run may start while the team has any checks left ({@link #wait}) and
 * pays afterwards ({@link #spend}); what a second overspends is owed by the next one, so over any stretch of seconds a
 * team uses at most its budget per second plus one run. Action points work the same way: a graph that can act starts
 * only while its team also has action points left ({@link #waitActions}) and pays for the actions it sent afterwards
 * ({@link #spendActions}). Fixed costs known in advance, such as a sensor read, use {@link #charge}. Server thread
 * only; cleared when the server stops.
 */
public final class TeamBudget {
    /** Ticks per budget window. */
    public static final int SECOND = 20;
    /** The shared budget of devices with no owner on networks with no owner. */
    public static final UUID UNOWNED = new UUID(0, 0);
    private static final Object2ObjectOpenHashMap<UUID, Window> WINDOWS = new Object2ObjectOpenHashMap<>();
    /**
     * How long (ticks) a {@link TeamSource} answer is kept. The FTB compat also forgets all answers when a team changes
     * ({@link #forgetTeams}); this bound catches changes a team mod reports no event for. A performance bound, not a
     * balance number.
     */
    public static final int TEAM_CACHE_TICKS = 200;
    private static final Object2ObjectOpenHashMap<UUID, CachedTeam> TEAMS = new Object2ObjectOpenHashMap<>();
    private static @Nullable TeamSource teamSource;

    /** A team source's answer for one owner, reused in place so a graph run allocates nothing. */
    private static final class CachedTeam {
        @Nullable
        UUID team;
        long expires;
    }

    /**
     * One team's current second and the checks and action points used in it (more than the budget when a run
     * overspent).
     */
    private static final class Window {
        long second;
        long used;
        long points;
        /** Every check charged since the server started, for tests and the perf budget. */
        long total;
        /** Every action point charged since the server started. */
        long pointsTotal;
    }

    private TeamBudget() {
    }

    /** Clears every team's count when the server stops. */
    static void init() {
        Platform.get().lifecycle().onServerStopped(server -> {
            WINDOWS.clear();
            TEAMS.clear();
        });
    }

    /**
     * The team {@code device}'s logic counts against: its owner's, else its network's owner's, else {@link #UNOWNED}.
     */
    public static UUID team(ServerLevel level, NetworkDevice device) {
        OwnerRecord owner = device.ownerRecord();
        if (!owner.isOwned()) {
            NetworkState network = device.deviceLink().network();
            if (network != null) {
                owner = network.owner();
            }
        }
        return team(level, owner);
    }

    /**
     * The team {@code owner}'s logic counts against: the team mod's team when a {@link TeamSource} is installed and the
     * owner is in one, else the owner's research team, else the owner alone; {@link #UNOWNED} for no owner. The
     * source's answer is cached per owner for {@link #TEAM_CACHE_TICKS} or until {@link #forgetTeams}.
     */
    public static UUID team(ServerLevel level, OwnerRecord owner) {
        UUID team = TeamCaps.team(level, owner);
        return team == null ? UNOWNED : team;
    }

    /**
     * The installed {@link TeamSource}'s team for {@code player}, cached for {@link #TEAM_CACHE_TICKS} or until
     * {@link #forgetTeams}; null without a source or when the player is in no team. The server caps use it too
     * ({@link TeamCaps#team}).
     */
    public static @Nullable UUID sourceTeam(ServerLevel level, UUID player) {
        TeamSource source = teamSource;
        return source == null ? null : cachedTeam(source, level, player);
    }

    /**
     * Installs the team mod's {@link TeamSource} (FTB Teams' compat, or a test's fake); null removes it, so teams come
     * from research teams again. Call during mod construction or, in tests, on the server thread.
     */
    public static void setTeamSource(@Nullable TeamSource source) {
        TEAMS.clear();
        teamSource = source;
    }

    /** Forgets every cached team answer; the team mod calls it when a player joins or leaves a team. Server thread. */
    public static void forgetTeams() {
        TEAMS.clear();
    }

    /** {@code source}'s team for {@code player}, asked again only when the cached answer expired or was forgotten. */
    private static @Nullable UUID cachedTeam(TeamSource source, ServerLevel level, UUID player) {
        long now = level.getGameTime();
        CachedTeam cached = TEAMS.get(player);
        if (cached == null) {
            cached = new CachedTeam();
            TEAMS.put(player, cached);
        } else if (now < cached.expires && cached.expires - now <= TEAM_CACHE_TICKS) {
            return cached.team;
        }
        cached.team = source.team(level.getServer(), player);
        cached.expires = now + TEAM_CACHE_TICKS;
        return cached.team;
    }

    /** The checks a team may use per second. */
    public static int limit() {
        return StorageServerConfig.LOGIC_TEAM_CHECKS.getAsInt();
    }

    /** The action points a team may use per second ({@code logic.teamActionPointsPerSecond}). */
    public static int actionLimit() {
        return StorageServerConfig.LOGIC_TEAM_ACTION_POINTS.getAsInt();
    }

    /**
     * 0 when {@code team} has checks left in the second of {@code gameTime}, so a run may start; otherwise the ticks
     * until the next second, when it may try again.
     */
    public static int wait(UUID team, long gameTime) {
        Window window = window(team, gameTime);
        return window.used < limit() ? 0 : untilNextSecond(gameTime);
    }

    /**
     * 0 when {@code team} has action points left in the second of {@code gameTime}, so a run that may act can start;
     * otherwise the ticks until the next second.
     */
    public static int waitActions(UUID team, long gameTime) {
        Window window = window(team, gameTime);
        return window.points < actionLimit() ? 0 : untilNextSecond(gameTime);
    }

    /** Counts {@code checks} a run used against {@code team}; an overspend is owed by the next second. */
    public static void spend(UUID team, int checks, long gameTime) {
        Window window = window(team, gameTime);
        window.used += checks;
        window.total += checks;
    }

    /** Counts {@code points} a run's actions cost against {@code team}; an overspend is owed by the next second. */
    public static void spendActions(UUID team, int points, long gameTime) {
        if (points <= 0) {
            return;
        }
        Window window = window(team, gameTime);
        window.points += points;
        window.pointsTotal += points;
    }

    /**
     * Charges {@code checks} known in advance: 0 when they fit in the second of {@code gameTime} (and are counted),
     * else the ticks until the next second, when the caller may try again.
     */
    public static int charge(UUID team, int checks, long gameTime) {
        Window window = window(team, gameTime);
        if (window.used + checks > limit()) {
            return untilNextSecond(gameTime);
        }
        window.used += checks;
        window.total += checks;
        return 0;
    }

    /** Checks {@code team} used in the second of {@code gameTime}, overspend from the last second included. */
    public static long used(UUID team, long gameTime) {
        Window window = WINDOWS.get(team);
        if (window == null) {
            return 0;
        }
        return roll(window, gameTime).used;
    }

    /** Action points {@code team} used in the second of {@code gameTime}, overspend from the last second included. */
    public static long actionsUsed(UUID team, long gameTime) {
        Window window = WINDOWS.get(team);
        if (window == null) {
            return 0;
        }
        return roll(window, gameTime).points;
    }

    /** Every check {@code team} was charged since the server started. */
    public static long total(UUID team) {
        Window window = WINDOWS.get(team);
        return window == null ? 0 : window.total;
    }

    /** Every action point {@code team} was charged since the server started. */
    public static long actionsTotal(UUID team) {
        Window window = WINDOWS.get(team);
        return window == null ? 0 : window.pointsTotal;
    }

    /** Forgets {@code team}'s count; for tests that need a fresh second. */
    public static void reset(@Nullable UUID team) {
        if (team != null) {
            WINDOWS.remove(team);
        }
    }

    /**
     * Sets what {@code team} used in the second of {@code gameTime}: {@code checks} and {@code points}; for tests that
     * need one budget used up and the other not.
     */
    public static void setUsed(UUID team, long checks, long points, long gameTime) {
        Window window = window(team, gameTime);
        window.used = checks;
        window.points = points;
    }

    private static int untilNextSecond(long gameTime) {
        return (int) (SECOND - Math.floorMod(gameTime, SECOND));
    }

    private static Window window(UUID team, long gameTime) {
        Window window = WINDOWS.get(team);
        if (window == null) {
            window = new Window();
            window.second = gameTime / SECOND;
            WINDOWS.put(team, window);
            return window;
        }
        return roll(window, gameTime);
    }

    /** Moves the window to the second of {@code gameTime}; the last second's overspend carries over. */
    private static Window roll(Window window, long gameTime) {
        long second = gameTime / SECOND;
        if (second != window.second) {
            boolean next = second == window.second + 1;
            long owed = next ? Math.max(0, window.used - limit()) : 0;
            long owedPoints = next ? Math.max(0, window.points - actionLimit()) : 0;
            window.second = second;
            window.used = owed;
            window.points = owedPoints;
        }
        return window;
    }
}
