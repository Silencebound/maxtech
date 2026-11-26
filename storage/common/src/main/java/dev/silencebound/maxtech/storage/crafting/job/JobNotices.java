package dev.silencebound.maxtech.storage.crafting.job;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.crafting.pattern.NetworkPatterns;
import dev.silencebound.maxtech.storage.crafting.plan.PlanTree;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderLogic;
import dev.silencebound.maxtech.storage.crafting.provider.StuckReason;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.TeamCaps;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import org.jspecify.annotations.Nullable;

/**
 * Tells players about crafting jobs they started (B09 item 21d): one {@link JobToastPayload} per {@link JobNotice}
 * change, never polled. The job queue calls {@link #changed} on each state change (submitted, started, resumed) and the
 * driver when a CPU stalls for power or runs again and when a provider's lock mode holds the job or lets it go (that
 * notice toasts once per job); {@link #ended} when a job ends. Only live notices of existing jobs are kept, and only
 * for online requesters: an ended job's entry goes at once, a player's on logout ({@link #loggedOut}), and a login
 * sends the player the current notice of each of their jobs ({@link #loggedIn}). Only the requester hears live notices;
 * how a job ended also goes, with {@code crafting.teamToasts} on, to the requester's online team members (the team that
 * also shares network caps: the team mod's, else the research team). A requester who cancelled their own job gets the
 * notice without a toast, so their badge clears; the player who cancelled hears nothing from the team copy. Background
 * jobs (no requester) send nothing. Server thread only.
 */
public final class JobNotices {
    /** Where a notice goes: the player's connection, or a recorder in tests. */
    @FunctionalInterface
    public interface Sink {
        void send(ServerPlayer player, JobToastPayload payload);
    }

    /** The last live notice sent per job, so an unchanged state sends nothing; not saved. */
    private static final Map<ServerLevel, Object2ObjectOpenHashMap<UUID, JobNotice>> LAST = new WeakHashMap<>();
    /** The jobs whose lock-held notice already toasted; not saved. */
    private static final Map<ServerLevel, ObjectOpenHashSet<UUID>> HELD = new WeakHashMap<>();
    private static final int LOCKED = 1;
    private static final int RECIPE = 2;
    private static @Nullable Sink testSink;
    private static @Nullable Supplier<List<ServerPlayer>> testOnline;

    private JobNotices() {
    }

    /**
     * Sends notices to {@code sink} and finds online players in {@code online} instead of the server's player list;
     * null for both restores both. GameTest players have no connection. Server thread.
     */
    public static void install(@Nullable Sink sink, @Nullable Supplier<List<ServerPlayer>> online) {
        testSink = sink;
        testOnline = sink == null ? null : online;
    }

    /** A job's state changed; sends the requester its live notice when it differs from the last one. */
    static void changed(ServerLevel level, CraftingJob job) {
        changed(level, job, CraftingRuns.get(level).get(job.id()));
    }

    /** {@link #changed(ServerLevel, CraftingJob)} with the job's progress at hand (its CPU stalled or ran again). */
    static void changed(ServerLevel level, CraftingJob job, @Nullable JobRun run) {
        UUID requester = job.requester().orElse(null);
        if (requester == null) {
            return;
        }
        JobNotice notice = JobNotice.live(job.state(), run != null && run.stalled, run != null && run.lockHeld);
        if (notice == null) {
            return;
        }
        Object2ObjectOpenHashMap<UUID, JobNotice> last = LAST.computeIfAbsent(level,
                l -> new Object2ObjectOpenHashMap<>());
        if (last.put(job.id(), notice) == notice) {
            return;
        }
        // A lock can hold a job again and again (one batch per pulse or per result): it toasts the first time only.
        boolean toast = notice == JobNotice.PROVIDER_LOCKED
                && HELD.computeIfAbsent(level, l -> new ObjectOpenHashSet<>()).add(job.id());
        ServerPlayer player = online(level, requester);
        if (player != null) {
            send(player, JobToastPayload.of(job, notice).withToast(toast));
        }
    }

    /**
     * A job sent work again: when a provider's lock held it last, its requester hears that it runs. One map lookup;
     * called by the driver after a dispatch that sent something.
     */
    static void progressed(ServerLevel level, CraftingJob job, JobRun run) {
        Object2ObjectOpenHashMap<UUID, JobNotice> last = LAST.get(level);
        if (last != null && last.get(job.id()) == JobNotice.PROVIDER_LOCKED) {
            changed(level, job, run);
        }
    }

    /**
     * Job {@code job} ended in {@code state} ({@code failed}: something was missing); {@code network} is the job's own
     * network, null while unloaded. Tells the requester and, by config, their team how.
     */
    static void ended(ServerLevel level, CraftingJob job, JobRun run, JobState state, boolean failed,
            @Nullable NetworkState network) {
        UUID requester = job.requester().orElse(null);
        // Only live notices are kept: an ended job's entries go, whether it left or stays partial (a resume sends
        // its live notice again, which differs from none).
        forget(level, job.id());
        if (requester == null) {
            return;
        }
        JobNotice notice = stopped(state, failed, run, network, job);
        JobToastPayload payload = withMissing(JobToastPayload.of(job, notice), run);
        UUID by = run.cancelledBy;
        if (notice == JobNotice.CANCELLED && by != null) {
            payload = payload.withBy(name(level, by));
            if (by.equals(requester)) {
                // Their own cancel: no toast, but the notice clears their badge.
                payload = payload.withToast(false);
            }
        }
        ServerPlayer player = online(level, requester);
        if (player != null) {
            send(player, payload);
        }
        if (!payload.toast() || !StorageServerConfig.CRAFTING_TEAM_TOASTS.getAsBoolean()) {
            return;
        }
        UUID team = team(level, requester);
        if (team == null || team.equals(requester)) {
            return;
        }
        JobToastPayload shared = payload.forTeam(name(level, requester));
        List<ServerPlayer> players = players(level);
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer member = players.get(i);
            UUID id = member.getUUID();
            if (!id.equals(requester) && !id.equals(by) && team.equals(team(level, id))) {
                send(member, shared);
            }
        }
    }

    /** How a job that ended in {@code state} reads, with the providers' causes when nothing was missing. */
    private static JobNotice stopped(JobState state, boolean failed, JobRun run, @Nullable NetworkState network,
            CraftingJob job) {
        boolean locked = false;
        boolean recipeChanged = false;
        if (run.missing.isEmpty() && network != null && (failed || state == JobState.PARTIAL)) {
            int causes = causes(network, job, run);
            locked = (causes & LOCKED) != 0;
            recipeChanged = (causes & RECIPE) != 0;
        }
        return JobNotice.ended(state, failed, !run.missing.isEmpty(), locked, recipeChanged);
    }

    private static JobToastPayload withMissing(JobToastPayload payload, JobRun run) {
        if (payload.notice() != JobNotice.MISSING || run.missing.isEmpty()) {
            return payload;
        }
        Object2LongMap.Entry<StackKey> first = run.missing.object2LongEntrySet().iterator().next();
        return payload.withMissing(first.getKey(), first.getLongValue());
    }

    private static void forget(ServerLevel level, UUID job) {
        Object2ObjectOpenHashMap<UUID, JobNotice> last = LAST.get(level);
        if (last != null) {
            last.remove(job);
        }
        ObjectOpenHashSet<UUID> held = HELD.get(level);
        if (held != null) {
            held.remove(job);
        }
    }

    /**
     * {@code player} joined: sends them, without toasts, the current notice of each of their jobs that still exists
     * (live, or stopped and kept partial), so their jobs tab badge is right after a relog. Server thread.
     */
    public static void loggedIn(ServerPlayer player) {
        UUID id = player.getUUID();
        for (ServerLevel level : player.level().getServer().getAllLevels()) {
            for (CraftingJob job : CraftingJobs.get(level).all()) {
                if (!job.requester().filter(id::equals).isPresent() || job.state().finished()) {
                    continue;
                }
                JobRun run = CraftingRuns.get(level).get(job.id());
                JobNotice notice = JobNotice.live(job.state(), run != null && run.stalled, run != null && run.lockHeld);
                if (notice != null) {
                    LAST.computeIfAbsent(level, l -> new Object2ObjectOpenHashMap<>()).put(job.id(), notice);
                } else if (run != null) {
                    notice = stopped(job.state(), true, run, JobDriver.network(level, run.origin()), job);
                } else {
                    continue;
                }
                JobToastPayload payload = JobToastPayload.of(job, notice).withToast(false);
                send(player, run == null ? payload : withMissing(payload, run));
            }
        }
    }

    /**
     * {@code player} left: forgets the last notices of their jobs, so the map holds only online players' jobs; their
     * next login sends the current ones again ({@link #loggedIn}). Server thread.
     */
    public static void loggedOut(ServerPlayer player) {
        UUID id = player.getUUID();
        for (ServerLevel level : player.level().getServer().getAllLevels()) {
            Object2ObjectOpenHashMap<UUID, JobNotice> last = LAST.get(level);
            if (last == null) {
                continue;
            }
            CraftingJobs jobs = CraftingJobs.get(level);
            last.keySet().removeIf(job -> {
                CraftingJob known = jobs.get(job);
                return known == null || known.requester().filter(id::equals).isPresent();
            });
        }
    }

    /** Whether a last notice of job {@code job} is kept; for tests. */
    public static boolean tracked(ServerLevel level, UUID job) {
        Object2ObjectOpenHashMap<UUID, JobNotice> last = LAST.get(level);
        return last != null && last.containsKey(job);
    }

    /**
     * What the pattern providers of {@code job}'s owed steps on {@code network} report: {@link #LOCKED} when a land
     * claim keeps one from its machine or its lock mode holds it, {@link #RECIPE} when one's recipe changed. Runs once
     * per stopped job.
     */
    private static int causes(NetworkState network, CraftingJob job, JobRun run) {
        int causes = 0;
        for (int i = 0; i < job.steps().size(); i++) {
            CraftingJob.Step step = job.steps().get(i);
            if (step.pattern() < 0 || step.pattern() == PlanTree.GENERATOR || run.received(i) >= step.toCraft()) {
                continue;
            }
            CraftingPattern pattern = job.patterns().get(step.pattern());
            List<NetworkPatterns.Entry> entries = network.patterns().allPatternsFor(step.key());
            for (int e = 0; e < entries.size(); e++) {
                NetworkPatterns.Entry entry = entries.get(e);
                if (entry.provider() instanceof ProviderLogic logic && entry.pattern().equals(pattern)) {
                    if (logic.claimBlocked() || logic.locked() != null) {
                        causes |= LOCKED;
                    }
                    if (logic.stuck() == StuckReason.RECIPE_CHANGED) {
                        causes |= RECIPE;
                    }
                }
            }
        }
        return causes;
    }

    /** {@code player}'s team as network caps count it; null without one. */
    private static @Nullable UUID team(ServerLevel level, UUID player) {
        return TeamCaps.team(level, new OwnerRecord(player, "", List.of(), false));
    }

    private static List<ServerPlayer> players(ServerLevel level) {
        Supplier<List<ServerPlayer>> online = testOnline;
        return online != null ? online.get() : level.getServer().getPlayerList().getPlayers();
    }

    private static @Nullable ServerPlayer online(ServerLevel level, UUID id) {
        Supplier<List<ServerPlayer>> online = testOnline;
        if (online == null) {
            return level.getServer().getPlayerList().getPlayer(id);
        }
        List<ServerPlayer> players = online.get();
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).getUUID().equals(id)) {
                return players.get(i);
            }
        }
        return null;
    }

    /** {@code id}'s name: online, else as the server last saw it, else the start of the id. */
    private static String name(ServerLevel level, UUID id) {
        ServerPlayer player = online(level, id);
        if (player != null) {
            return player.getGameProfile().name();
        }
        return level.getServer().services().nameToIdCache().get(id).map(NameAndId::name)
                .orElse(id.toString().substring(0, 8));
    }

    private static void send(ServerPlayer player, JobToastPayload payload) {
        Sink sink = testSink;
        if (sink != null) {
            sink.send(player, payload);
            return;
        }
        // A GameTest player has no connection.
        if (player.connection != null) {
            Platform.get().network().sendToPlayer(player, payload);
        }
    }
}
