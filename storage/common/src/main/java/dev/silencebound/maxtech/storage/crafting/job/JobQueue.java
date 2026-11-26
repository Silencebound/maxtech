package dev.silencebound.maxtech.storage.crafting.job;

import dev.silencebound.maxtech.storage.crafting.generator.GeneratorDispatch;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.plan.PlanTree;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import it.unimi.dsi.fastutil.objects.Object2LongSortedMap;
import it.unimi.dsi.fastutil.objects.Object2LongSortedMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * The crafting job queue of each level (M7 C16, C18, C19, PRD 7.2 partial results): jobs are submitted from plans, wait
 * in {@link CraftingJobs} until a CPU takes them, and end done, partial or cancelled. Player requests go before
 * background (stock-keeping) requests, then by priority, then in submission order; a job takes the smallest free CPU
 * that fits it, on its own network or one sharing CPUs with it through a subnet view (C30). A background job never
 * takes one of the last free CPUs its network keeps for players ({@link CraftLimits}, M7L2 chunk 20): it waits, and a
 * player's job submitted after it still starts first. Nothing polls: the queue looks for a CPU when a job is submitted
 * or resumed, when a CPU frees up, joins a network or changes its cluster. Server thread only.
 *
 * <p>
 * Screens (M7c area C) {@link #submit} a plan, read {@link #progress} and the job from {@link CraftingJobs} (its
 * {@link JobRun#version()} says when to send a delta), hear state changes through {@link #listen}, and {@link #cancel}
 * or {@link #resume} by id.
 */
public final class JobQueue {
    /** A crafting CPU as the queue sees it; {@code crafting.cpu}'s core implements it. */
    public interface Cpu extends JobExecutor {
        /** The network the CPU is in, or null. */
        @Nullable
        NetworkState network();

        /** 1 to 3. */
        int tier();

        /** Whether a job runs on it now. */
        boolean busy();

        /** The core's position, saved in the job's progress. */
        long position();
    }

    /** Hears every state change of a job (submitted, started, ended, resumed). */
    @FunctionalInterface
    public interface Listener {
        void jobChanged(ServerLevel level, CraftingJob job);
    }

    /**
     * Who asks and how.
     *
     * @param requester
     *            the player, empty for background work such as stock keeping
     * @param priority
     *            among background jobs, higher first
     * @param cpuTier
     *            the only CPU tier the job may run on, 0 for any
     * @param onEnd
     *            told once when the job ends (done, partial or cancelled); not saved, so a restart drops it
     */
    public record Request(Optional<UUID> requester, int priority, int cpuTier, @Nullable Consumer<JobState> onEnd) {
        /** A player's request: first in the queue, any CPU. */
        public static Request player(UUID player) {
            return new Request(Optional.of(player), 0, 0, null);
        }
    }

    /** Why {@link #submit} turned a plan away. */
    public enum Refusal {
        /** The plan failed as a whole (too large, too slow, an error). */
        PLAN_FAILED,
        /** Classic mode starts only jobs that can be made in full. */
        INCOMPLETE,
        /** The plan has no steps. */
        EMPTY
    }

    /** What {@link #submit} did: the queued job, or why none was queued. */
    public record Submission(Optional<CraftingJob> job, Optional<Refusal> refusal) {
    }

    private static final Map<ServerLevel, ObjectArrayList<Cpu>> CPUS = new WeakHashMap<>();
    private static final Map<ServerLevel, ObjectArrayList<JobRun>> RUNNING = new WeakHashMap<>();
    private static final ObjectArrayList<Listener> LISTENERS = new ObjectArrayList<>();
    private static final ObjectArrayList<Consumer<ServerLevel>> CPU_LISTENERS = new ObjectArrayList<>();

    private JobQueue() {
    }

    /** Adds a listener for every level; for screens, registered once at mod construction. */
    public static void listen(Listener listener) {
        LISTENERS.add(listener);
    }

    /**
     * Adds a listener told when what automation may take of a level's free CPUs changed without a job changing (a
     * network's craft limits were edited); for every level, registered once at mod construction.
     */
    public static void listenCpus(Consumer<ServerLevel> listener) {
        CPU_LISTENERS.add(listener);
    }

    /**
     * A network in {@code level} changed its craft limits: waiting jobs may start now, and readers of the free CPUs for
     * automation hear it.
     */
    public static void limitsChanged(ServerLevel level) {
        assign(level);
        for (int i = 0; i < CPU_LISTENERS.size(); i++) {
            CPU_LISTENERS.get(i).accept(level);
        }
    }

    /**
     * Queues the plan as a job on {@code network}, whose node {@code origin} anchors it across restarts (a terminal's
     * node, say), and looks for a CPU at once. Classic mode refuses a plan with anything missing.
     */
    public static Submission submit(NetworkState network, long origin, CraftingPlanner.Result plan, Request request) {
        if (plan.tree().failure().isPresent()) {
            return new Submission(Optional.empty(), Optional.of(Refusal.PLAN_FAILED));
        }
        if (plan.tree().steps().isEmpty()) {
            return new Submission(Optional.empty(), Optional.of(Refusal.EMPTY));
        }
        if (!plan.tree().complete() && !CraftingMode.current().extended()) {
            return new Submission(Optional.empty(), Optional.of(Refusal.INCOMPLETE));
        }
        ServerLevel level = network.level();
        CraftingJob job = CraftingJob.planned(plan, request.requester(), level.getGameTime());
        JobRun run = new JobRun(job.id(), origin, job.steps().size(), request.priority(), request.cpuTier());
        run.onEnd = request.onEnd();
        run.need.putAll(plan.tree().used());
        run.missing.putAll(plan.tree().missing());
        CraftingJobs.get(level).put(job);
        CraftingRuns.get(level).put(run);
        changed(level, job);
        assign(level);
        CraftingJob now = CraftingJobs.get(level).get(job.id());
        return new Submission(Optional.of(now == null ? job : now), Optional.empty());
    }

    /** The executor's progress on job {@code id}, or null for an unknown or finished job. */
    public static @Nullable JobRun progress(ServerLevel level, UUID id) {
        return CraftingRuns.get(level).get(id);
    }

    /** The unfinished jobs whose storage is {@code network}'s, in submission order. */
    public static List<CraftingJob> jobsOn(NetworkState network) {
        ServerLevel level = network.level();
        CraftingRuns runs = CraftingRuns.get(level);
        List<CraftingJob> out = new ArrayList<>();
        for (CraftingJob job : CraftingJobs.get(level).all()) {
            JobRun run = runs.get(job.id());
            if (run != null && JobDriver.network(level, run.origin()) == network) {
                out.add(job);
            }
        }
        return out;
    }

    /**
     * The loaded CPUs that could take a job of {@code network} now, as {@link #assign} picks them: idle, on the network
     * or on one linked to it through a subnet view, and with room for at least one step (a core in a conflict has
     * none). A CPU tier a job asks for and the job's own size are not looked at. Allocates nothing.
     */
    public static int freeCpus(NetworkState network) {
        ObjectArrayList<Cpu> cpus = CPUS.get(network.level());
        if (cpus == null) {
            return 0;
        }
        int free = 0;
        for (int i = 0; i < cpus.size(); i++) {
            Cpu cpu = cpus.get(i);
            NetworkState home = cpu.network();
            if (!cpu.busy() && cpu.capacity() > 0 && home != null && (home == network || network.linkedTo(home))) {
                free++;
            }
        }
        return free;
    }

    /**
     * The loaded CPUs that could ever take a job of {@code network}, busy or not: on the network or on one linked to it
     * through a subnet view, with room for at least one step. Allocates nothing.
     */
    public static int cpus(NetworkState network) {
        ObjectArrayList<Cpu> cpus = CPUS.get(network.level());
        if (cpus == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < cpus.size(); i++) {
            Cpu cpu = cpus.get(i);
            NetworkState home = cpu.network();
            if (cpu.capacity() > 0 && home != null && (home == network || network.linkedTo(home))) {
                count++;
            }
        }
        return count;
    }

    /**
     * Whether the network keeps every CPU it has free for players, so automation can use none: at least one kept, and
     * no more CPUs than that. Allocates nothing.
     */
    public static boolean automationBlocked(NetworkState network) {
        int kept = network.craftLimits().effectiveCpusKeptFree();
        return kept > 0 && cpus(network) <= kept;
    }

    /**
     * The free CPUs a background job of {@code network} may take now: {@link #freeCpus} less the CPUs the network keeps
     * free for players ({@link CraftLimits#effectiveCpusKeptFree()}), never below 0. Allocates nothing.
     */
    public static int freeForAutomation(NetworkState network) {
        return Math.max(0, freeCpus(network) - network.craftLimits().effectiveCpusKeptFree());
    }

    /**
     * Cancels job {@code id}: a waiting or partial job just ends; a running one stops sending, and its CPU returns what
     * it holds to storage; items still in machines go to storage when they come out (C19). False for an unknown id.
     */
    public static boolean cancel(ServerLevel level, UUID id) {
        return cancel(level, id, null);
    }

    /**
     * {@link #cancel(ServerLevel, UUID)} by player {@code by} (null for logic or another non-player cause), whom the
     * requester's toast names (B09 item 21d).
     */
    public static boolean cancel(ServerLevel level, UUID id, @Nullable UUID by) {
        CraftingJob job = CraftingJobs.get(level).get(id);
        JobRun run = CraftingRuns.get(level).get(id);
        if (job == null || run == null || job.state().finished()) {
            return false;
        }
        run.cancelledBy = by;
        // Generators switch their machines off now, even while an unloaded CPU still owes the cancel (M7C item 11).
        GeneratorDispatch.cancel(level, id);
        if (job.state() == JobState.RUNNING) {
            Cpu cpu = cpuAt(level, run.cpu());
            if (cpu != null) {
                cpu.cancel(id);
                return true;
            }
            // The CPU is unloaded: what it holds goes back now; what storage refuses stays owed on the run, and the
            // CPU returns or drops it when it loads, so nothing is lost.
            NetworkState network = JobDriver.network(level, run.origin());
            if (network == null || !JobDriver.flush(network, run)) {
                run.cancelling = true;
                run.changed();
                CraftingRuns.get(level).touched();
                changed(level, job);
                return true;
            }
        }
        ended(level, job, run, JobState.CANCELLED, null);
        return true;
    }

    /**
     * Resumes partial job {@code id} (extended mode): plans what it still owes again, from storage as it is now (the
     * intermediates went back there), and queues it. False when the job is not partial, the mode is classic or the
     * planner is busy; the job then stays partial.
     */
    public static boolean resume(ServerLevel level, UUID id) {
        CraftingJob job = CraftingJobs.get(level).get(id);
        JobRun run = CraftingRuns.get(level).get(id);
        if (job == null || run == null || job.state() != JobState.PARTIAL || !CraftingMode.current().extended()) {
            return false;
        }
        NetworkState network = JobDriver.network(level, run.origin());
        if (network != null && !job.more().isEmpty()) {
            // An order list (M7C item 6): what each request still owes, from its root step's received output.
            // What the requests already delivered went to storage; it stays theirs and does not feed the others.
            List<CraftingPlanner.Root> owed = new ArrayList<>();
            List<CraftingPlanner.Root> delivered = new ArrayList<>();
            List<CraftingPlanner.Root> roots = job.roots();
            int root = 0;
            for (int i = 0; i < job.steps().size() && root < roots.size(); i += job.steps().get(i).size()) {
                long got = Math.min(run.received[i], roots.get(root).amount());
                if (got > 0) {
                    delivered.add(new CraftingPlanner.Root(roots.get(root).key(), got));
                }
                if (roots.get(root).amount() - got > 0) {
                    owed.add(new CraftingPlanner.Root(roots.get(root).key(), roots.get(root).amount() - got));
                }
                root++;
            }
            return !owed.isEmpty()
                    && CraftingPlanner.submitOrder(network, owed, delivered, plan -> replan(level, job.id(), plan));
        }
        long owed = job.amount() - run.delivered;
        if (network == null || owed <= 0) {
            return false;
        }
        return CraftingPlanner.submitCrafted(network, job.key(), owed, plan -> replan(level, job.id(), plan));
    }

    private static void replan(ServerLevel level, UUID id, CraftingPlanner.Result plan) {
        CraftingJob job = CraftingJobs.get(level).get(id);
        JobRun old = CraftingRuns.get(level).get(id);
        if (job == null || old == null || job.state() != JobState.PARTIAL || plan.tree().failure().isPresent()
                || plan.tree().steps().isEmpty()) {
            return;
        }
        CraftingJob fresh = CraftingJob.planned(plan, job.requester(), job.created());
        CraftingJob next = new CraftingJob(id, job.requester(), fresh.key(), fresh.amount(), fresh.steps(),
                fresh.patterns(), JobState.PLANNED, job.created(), fresh.more());
        JobRun run = new JobRun(id, old.origin(), next.steps().size(), old.priority(), old.cpuTier());
        run.onEnd = old.onEnd;
        run.need.putAll(plan.tree().used());
        run.missing.putAll(plan.tree().missing());
        CraftingJobs.get(level).put(next);
        CraftingRuns.get(level).put(run);
        changed(level, next);
        assign(level);
    }

    /** Running jobs' progress in {@code level}; read by {@link CraftingOutputs}. */
    static List<JobRun> running(ServerLevel level) {
        ObjectArrayList<JobRun> list = RUNNING.get(level);
        return list == null ? List.of() : list;
    }

    /** The loaded CPU cores of {@code level}; a live list, do not change it. Server thread. */
    public static List<Cpu> cpusIn(ServerLevel level) {
        ObjectArrayList<Cpu> list = CPUS.get(level);
        return list == null ? List.of() : list;
    }

    /** A loaded CPU core; it unregisters when it unloads or breaks. */
    public static void register(ServerLevel level, Cpu cpu) {
        ObjectArrayList<Cpu> list = CPUS.computeIfAbsent(level, l -> new ObjectArrayList<>());
        if (!list.contains(cpu)) {
            list.add(cpu);
        }
    }

    public static void unregister(ServerLevel level, Cpu cpu) {
        ObjectArrayList<Cpu> list = CPUS.get(level);
        if (list != null) {
            list.remove(cpu);
        }
    }

    // CPUs are compared by identity; each is one block entity.
    @SuppressWarnings("ReferenceEquality")
    private static @Nullable Cpu cpuAt(ServerLevel level, long pos) {
        ObjectArrayList<Cpu> list = CPUS.get(level);
        if (list == null || pos == JobRun.NO_CPU) {
            return null;
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).position() == pos) {
                return list.get(i);
            }
        }
        return null;
    }

    /** Hands waiting jobs to free CPUs, players first; see the class comment. */
    public static void assign(ServerLevel level) {
        ObjectArrayList<Cpu> cpus = CPUS.get(level);
        if (cpus == null || cpus.isEmpty()) {
            return;
        }
        CraftingRuns runs = CraftingRuns.get(level);
        List<CraftingJob> waiting = new ArrayList<>();
        for (CraftingJob job : CraftingJobs.get(level).all()) {
            if (job.state() == JobState.PLANNED && runs.get(job.id()) != null) {
                waiting.add(job);
            }
        }
        if (waiting.isEmpty()) {
            return;
        }
        // Stable: players first, then background by priority, each in submission order.
        waiting.sort(Comparator.comparingInt((CraftingJob job) -> job.requester().isPresent() ? 0 : 1)
                .thenComparingInt(job -> {
                    JobRun run = runs.get(job.id());
                    return run == null ? 0 : -run.priority();
                }));
        for (CraftingJob job : waiting) {
            JobRun run = runs.get(job.id());
            NetworkState network = run == null ? null : JobDriver.network(level, run.origin());
            if (run == null || network == null) {
                continue;
            }
            // Players always find the kept CPUs; automation waits until one more than those is free.
            if (job.requester().isEmpty() && freeForAutomation(network) <= 0) {
                continue;
            }
            List<NetworkState> linked = network.linkedNetworks();
            Cpu best = null;
            for (int i = 0; i < cpus.size(); i++) {
                Cpu cpu = cpus.get(i);
                NetworkState home = cpu.network();
                if (cpu.busy() || home == null || (home != network && !linked.contains(home))
                        || (run.cpuTier() > 0 && cpu.tier() != run.cpuTier()) || !cpu.canRun(job)) {
                    continue;
                }
                if (best == null || cpu.capacity() < best.capacity()) {
                    best = cpu;
                }
            }
            if (best != null) {
                run.cpu = best.position();
                CraftingJob running = job.withState(JobState.RUNNING);
                CraftingJobs.get(level).put(running);
                if (best.start(running)) {
                    ObjectArrayList<JobRun> list = RUNNING.computeIfAbsent(level, l -> new ObjectArrayList<>());
                    if (!list.contains(run)) {
                        list.add(run);
                    }
                    runs.touched();
                    changed(level, running);
                } else {
                    run.cpu = JobRun.NO_CPU;
                    CraftingJobs.get(level).put(job);
                }
            }
        }
    }

    /** A CPU picked its running job up again after loading; its outputs count again. */
    public static void resumed(ServerLevel level, JobRun run) {
        if (run.claimNetworks.isEmpty()) {
            NetworkState origin = JobDriver.network(level, run.origin());
            if (origin != null) {
                run.claimNetworks.add(origin);
            }
        }
        ObjectArrayList<JobRun> list = RUNNING.computeIfAbsent(level, l -> new ObjectArrayList<>());
        if (!list.contains(run)) {
            list.add(run);
        }
    }

    /**
     * Job {@code job} ended in {@code state} (done, partial or cancelled); the CPU already returned what it held. Done
     * and cancelled jobs leave the level's data; a partial one stays with its report until resumed or cancelled. The
     * requester gets a toast, the alarm modules of the job's and the CPU's network hear it, and the queue looks for
     * work for the freed CPU. {@code cpuNetwork} may be null.
     */
    public static void ended(ServerLevel level, CraftingJob job, JobRun run, JobState state,
            @Nullable NetworkState cpuNetwork) {
        ended(level, job, run, state, cpuNetwork, state == JobState.PARTIAL);
    }

    /**
     * {@link #ended(ServerLevel, CraftingJob, JobRun, JobState, NetworkState)}, where {@code failed} says the job
     * stopped because something was missing (it ended partial, or classic mode could not start it): the logic watchers
     * of failed jobs ({@link JobEvents#watchFailed}) of the job's and the CPU's network hear it too.
     */
    public static void ended(ServerLevel level, CraftingJob job, JobRun run, JobState state,
            @Nullable NetworkState cpuNetwork, boolean failed) {
        ObjectArrayList<JobRun> list = RUNNING.get(level);
        if (list != null) {
            list.remove(run);
        }
        // Done, partial or cancelled: generators stop working for it at once (M7C item 11).
        GeneratorDispatch.cancel(level, job.id());
        CraftingJob next = job.withState(state);
        if (state == JobState.DONE) {
            run.delivered = job.amount();
        } else if (state == JobState.PARTIAL && !job.steps().isEmpty()
                && PlanTree.crafts(job.steps().getFirst().pattern())) {
            run.delivered += run.received[0];
        }
        run.changed();
        // A background job does not wait partial for a resume: its requester asks again if it still needs it.
        if (state.finished() || (state == JobState.PARTIAL && job.requester().isEmpty())) {
            CraftingJobs.get(level).remove(job.id());
            CraftingRuns.get(level).remove(job.id());
            CraftDeliveries.get(level).remove(job.id());
        } else {
            CraftingJobs.get(level).put(next);
            CraftingRuns.get(level).touched();
        }
        changed(level, next);
        Consumer<JobState> onEnd = run.onEnd;
        if (onEnd != null) {
            if (state.finished() || job.requester().isEmpty()) {
                run.onEnd = null;
            }
            onEnd.accept(state);
        }
        // The requester (and, by config, their team) hears how it ended (B09 item 21d).
        JobNotices.ended(level, job, run, state, failed, JobDriver.network(level, run.origin()));
        if (state != JobState.CANCELLED) {
            NetworkState network = JobDriver.network(level, run.origin());
            // Every request of an order list counts as finished for logic (M7C item 6).
            List<CraftingPlanner.Root> roots = job.roots();
            for (int i = 0; i < roots.size(); i++) {
                if (network != null) {
                    JobEvents.finished(network, roots.get(i).key());
                }
                if (cpuNetwork != null && cpuNetwork != network) {
                    JobEvents.finished(cpuNetwork, roots.get(i).key());
                }
            }
            if (state == JobState.DONE) {
                // What a done job made, by network (M7C item 15: task boards count crafted items).
                for (int i = 0; i < roots.size(); i++) {
                    if (network != null) {
                        JobEvents.done(network, job.id(), roots.get(i).key(), roots.get(i).amount());
                    }
                    if (cpuNetwork != null && cpuNetwork != network) {
                        JobEvents.done(cpuNetwork, job.id(), roots.get(i).key(), roots.get(i).amount());
                    }
                }
            }
        }
        if (failed) {
            Object2LongSortedMap<StackKey> missing = Object2LongSortedMaps.unmodifiable(run.missing);
            NetworkState network = JobDriver.network(level, run.origin());
            List<CraftingPlanner.Root> roots = job.roots();
            for (int i = 0; i < roots.size(); i++) {
                if (network != null) {
                    JobEvents.failed(network, roots.get(i).key(), missing);
                }
                if (cpuNetwork != null && cpuNetwork != network) {
                    JobEvents.failed(cpuNetwork, roots.get(i).key(), missing);
                }
            }
        }
        assign(level);
    }

    private static void changed(ServerLevel level, CraftingJob job) {
        JobNotices.changed(level, job);
        for (int i = 0; i < LISTENERS.size(); i++) {
            LISTENERS.get(i).jobChanged(level, job);
        }
    }
}
