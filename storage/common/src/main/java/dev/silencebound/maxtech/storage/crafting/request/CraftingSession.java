package dev.silencebound.maxtech.storage.crafting.request;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.sync.ServerSyncedList;
import dev.silencebound.maxtech.storage.crafting.job.CraftDeliveries;
import dev.silencebound.maxtech.storage.crafting.job.CraftDelivery;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJobs;
import dev.silencebound.maxtech.storage.crafting.job.CraftingMode;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.job.JobRun;
import dev.silencebound.maxtech.storage.crafting.job.JobState;
import dev.silencebound.maxtech.storage.crafting.job.QueueBackgroundCrafting;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.crafting.generator.GeneratorDispatch;
import dev.silencebound.maxtech.storage.crafting.generator.GeneratorLogic;
import dev.silencebound.maxtech.storage.crafting.pattern.GeneratorSource;
import dev.silencebound.maxtech.storage.crafting.pattern.NetworkPatterns;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.plan.PlanTree;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderLogic;
import dev.silencebound.maxtech.storage.crafting.provider.StuckReason;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkState;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * The crafting side of one player's open network terminal (M7c area C), owned by its menu on the server. It keeps the
 * client's list of craftable keys current (a Lib synced list with ids of its own, updated when the network's pattern
 * generation moves), plans requests on the worker pool ({@link CraftingPlanner#submitCrafted}) and starts the last plan
 * as a job through the CPU's {@link JobQueue} (on the CPU tier the client names; with missing leaves only in extended
 * mode, as a partial job), lists the network's jobs while the jobs screen is open and streams one watched job's
 * progress as step deltas (another synced list: step index to crafts still to run) and as live tiles
 * ({@link RequestPayloads.JobStatus}). Every action needs the craft access level (C17); a client gets one action per
 * tick and one plan in flight. Nothing runs once the menu closes. Server thread.
 */
public final class CraftingSession {
    /** Largest amount one request may ask for; a bound on the payload, the node cap bounds the work. */
    public static final long MAX_AMOUNT = Integer.MAX_VALUE;
    /** Ticks between two looks at the job list while the jobs screen is open. */
    static final int JOBS_INTERVAL = 10;
    /** Least ticks between two tile updates of the watched job. */
    public static final int STATUS_INTERVAL = 10;
    /** Open sessions, told of job changes; weak, since a test menu may never close. */
    private static final Set<CraftingSession> OPEN = Collections.newSetFromMap(new WeakHashMap<>());

    private final int containerId;
    private final Player player;
    private final Supplier<@Nullable NetworkState> network;
    private final Consumer<CustomPacketPayload> sink;
    private BooleanSupplier extended = () -> CraftingMode.current().extended();
    private boolean closed;
    private long lastAction = Long.MIN_VALUE;

    private final ServerSyncedList<StackKey> craftables;
    private final Object2IntOpenHashMap<StackKey> craftableIds = new Object2IntOpenHashMap<>();
    private final Int2ObjectOpenHashMap<StackKey> craftableKeys = new Int2ObjectOpenHashMap<>();
    private int nextCraftableId;
    private @Nullable NetworkState craftablesOf;
    private long craftablesGeneration;
    private boolean craftablesDumped;

    private boolean planning;
    private int planGeneration;
    private CraftingPlanner.@Nullable Result lastPlan;
    /** The delivery labels sent with the last plan; a start's choice indexes them. */
    private List<DeviceLabel> destinations = List.of();
    private @Nullable NetworkState lastPlanNetwork;

    private boolean watchingJobs;
    private long nextJobsCheck;
    private @Nullable List<RequestPayloads.JobSummary> lastJobs;

    private final ServerSyncedList<StackKey> progress;
    private @Nullable CraftingJob watched;
    /** The watched job ended (done or cancelled): only its last deltas still go out. */
    private boolean watchedEnded;
    private long watchedVersion;
    private JobState watchedState = JobState.PLANNED;
    private long[] remaining = new long[0];
    private boolean statusDirty;
    private long nextStatus;
    /** The stuck providers the client was last told about (M7C item 9). */
    private List<RequestPayloads.Stuck> lastStuck = List.of();

    /**
     * @param network
     *            the terminal's network now, null when it has none
     * @param sink
     *            where payloads for the client go: the player's connection, or a recorder in tests
     */
    public CraftingSession(int containerId, Player player, Supplier<@Nullable NetworkState> network,
            Consumer<CustomPacketPayload> sink) {
        this.containerId = containerId;
        this.player = player;
        this.network = network;
        this.sink = sink;
        craftableIds.defaultReturnValue(-1);
        craftables = new ServerSyncedList<>(RequestContent.CRAFTABLES, containerId, craftableKeys::get, sink::accept,
                RequestContent.CRAFTABLES.sizer(player.registryAccess()));
        progress = new ServerSyncedList<>(RequestContent.PROGRESS, containerId, this::stepKey, sink::accept,
                RequestContent.PROGRESS.sizer(player.registryAccess()));
        OPEN.add(this);
    }

    /** The terminal's network now; null when it has none. For other areas' terminal tabs (the stock tab). */
    public @Nullable NetworkState network() {
        return network.get();
    }

    /** The terminal menu's container id. */
    public int containerId() {
        return containerId;
    }

    /** The player whose terminal this is. */
    public Player player() {
        return player;
    }

    /** Sends {@code payload} to the session's client (or the test recorder); for other areas' terminal tabs. */
    public void send(CustomPacketPayload payload) {
        sink.accept(payload);
    }

    /** Tells every open session that {@code job} changed; registered with {@link JobQueue#listen}. */
    static void jobChanged(ServerLevel level, CraftingJob job) {
        for (CraftingSession session : List.copyOf(OPEN)) {
            session.changed(level, job);
        }
    }

    @SuppressWarnings("ReferenceEquality") // one level object per dimension
    private void changed(ServerLevel level, CraftingJob job) {
        NetworkState net = network.get();
        if (closed || net == null || net.level() != level) {
            return;
        }
        nextJobsCheck = 0;
        CraftingJob current = watched;
        if (current == null || watchedEnded || !current.id().equals(job.id())) {
            return;
        }
        if (job.state().finished()) {
            // The job left the level's data: count every step down here, since nothing can be read any more.
            watchedEnded = true;
            for (int i = 0; i < remaining.length; i++) {
                if (remaining[i] != 0) {
                    remaining[i] = 0;
                    progress.changed(i, 0);
                }
            }
            watchedState = job.state();
            sink.accept(new RequestPayloads.Job(containerId, job.id(), watchedState,
                    PlanView.of(current, level, extended.getAsBoolean()), List.of()));
            sink.accept(new RequestPayloads.JobStatus(containerId, job.id(), List.of()));
            statusDirty = false;
        } else if (!job.steps().equals(current.steps())) {
            // A resumed job was planned again: its tree is new.
            view(net, job, level.getGameTime());
        }
    }

    /** Reads the mode from {@code source} instead of the config; for tests. */
    public void useMode(BooleanSupplier source) {
        extended = source;
    }

    /** Called from the menu's {@code broadcastChanges()} once its screen asked for the contents. */
    public void tick(long gameTime) {
        if (closed) {
            return;
        }
        NetworkState net = network.get();
        syncCraftables(net, gameTime);
        if (watchingJobs && net != null && gameTime >= nextJobsCheck) {
            nextJobsCheck = gameTime + JOBS_INTERVAL;
            List<RequestPayloads.JobSummary> jobs = summaries(net);
            if (!jobs.equals(lastJobs)) {
                lastJobs = jobs;
                sink.accept(new RequestPayloads.Jobs(containerId, jobs));
            }
        }
        if (watched != null && net != null) {
            tickWatched(net, gameTime);
        }
    }

    /** The menu closed: pending plans are dropped when they come back. */
    public void close() {
        closed = true;
        watched = null;
        watchingJobs = false;
        OPEN.remove(this);
    }

    /** The terminal moved to another network: the craftable list starts over. */
    public void reset() {
        craftablesOf = null;
    }

    /** Runs {@code request} for the player; false when it was refused. */
    public boolean handle(RequestPayloads.Request request) {
        NetworkState net = network.get();
        if (closed || net == null) {
            return false;
        }
        long now = net.level().getGameTime();
        if (now == lastAction) {
            return false;
        }
        lastAction = now;
        if (request.action() == RequestPayloads.Action.UNWATCH) {
            watchingJobs = false;
            watched = null;
            return true;
        }
        if (!net.accessLevel(player).atLeast(AccessLevel.CRAFT)) {
            notice(RequestContent.NO_ACCESS_KEY);
            return false;
        }
        return switch (request.action()) {
            case PLAN -> plan(net, request);
            case START -> start(net, request.amount(), request.request());
            case WATCH_JOBS -> {
                watchingJobs = true;
                nextJobsCheck = now;
                lastJobs = null;
                yield true;
            }
            case VIEW_JOB -> request.job().isPresent() && view(net, request.job().get(), now);
            case CANCEL_JOB -> request.job().isPresent() && cancel(net, request.job().get());
            case RESUME_JOB -> request.job().isPresent() && resume(net, request.job().get());
            case UNWATCH -> false;
        };
    }

    /**
     * Plans an order list (M7C item 6): every request in {@code roots} crafted in full, as one plan the player starts
     * with {@code START} like a single request. Needs craft access; one to {@link CraftingPlanner#MAX_ROOTS} requests
     * of 1 to {@link #MAX_AMOUNT} each; one action per tick and one plan in flight, as {@link #handle}. The answer is
     * the usual {@code Plan} payload with {@code request}. False when refused.
     */
    public boolean planOrder(int request, List<CraftingPlanner.Root> requested) {
        NetworkState net = network.get();
        if (closed || net == null || requested.isEmpty() || requested.size() > CraftingPlanner.MAX_ROOTS) {
            return false;
        }
        for (int i = 0; i < requested.size(); i++) {
            long amount = requested.get(i).amount();
            if (amount < 1 || amount > MAX_AMOUNT) {
                return false;
            }
        }
        List<CraftingPlanner.Root> roots = merged(requested);
        long now = net.level().getGameTime();
        if (now == lastAction) {
            return false;
        }
        lastAction = now;
        if (!net.accessLevel(player).atLeast(AccessLevel.CRAFT)) {
            notice(RequestContent.NO_ACCESS_KEY);
            return false;
        }
        if (planning) {
            notice(RequestContent.BUSY_KEY);
            return false;
        }
        int generation = ++planGeneration;
        planning = true;
        if (!CraftingPlanner.submitOrder(net, roots, result -> planned(net, generation, request, result))) {
            planning = false;
            notice(RequestContent.POOL_FULL_KEY);
            return false;
        }
        return true;
    }

    /**
     * {@code roots} with each key once, in the order first named, amounts added up and capped at {@link #MAX_AMOUNT}:
     * the client merges a typed list, but a hand-built packet may name a key twice.
     */
    static List<CraftingPlanner.Root> merged(List<CraftingPlanner.Root> roots) {
        Object2LongLinkedOpenHashMap<StackKey> sums = new Object2LongLinkedOpenHashMap<>(roots.size());
        for (CraftingPlanner.Root root : roots) {
            sums.put(root.key(), Math.min(MAX_AMOUNT, sums.getLong(root.key()) + root.amount()));
        }
        List<CraftingPlanner.Root> out = new ArrayList<>(sums.size());
        for (Object2LongMap.Entry<StackKey> entry : sums.object2LongEntrySet()) {
            out.add(new CraftingPlanner.Root(entry.getKey(), entry.getLongValue()));
        }
        return out;
    }

    /** Whether a plan is on the worker pool; tests use it. */
    public boolean planning() {
        return planning;
    }

    /** The plan a start would run, null when none; tests use it. */
    public CraftingPlanner.@Nullable Result lastPlan() {
        return lastPlan;
    }

    private boolean plan(NetworkState net, RequestPayloads.Request request) {
        Optional<StackKey> key = request.key();
        if (key.isEmpty() || request.amount() < 1 || request.amount() > MAX_AMOUNT) {
            return false;
        }
        if (planning) {
            notice(RequestContent.BUSY_KEY);
            return false;
        }
        int generation = ++planGeneration;
        int number = request.request();
        planning = true;
        if (!CraftingPlanner.submitCrafted(net, key.get(), request.amount(),
                result -> planned(net, generation, number, result))) {
            planning = false;
            notice(RequestContent.POOL_FULL_KEY);
            return false;
        }
        return true;
    }

    private void planned(NetworkState net, int generation, int number, CraftingPlanner.Result result) {
        if (closed || generation != planGeneration) {
            return;
        }
        planning = false;
        lastPlan = result;
        lastPlanNetwork = net;
        sink.accept(new RequestPayloads.Plan(containerId, number,
                PlanView.of(result, net.level(), extended.getAsBoolean(), generatorTicks(net))));
        destinations = CraftDestinations.labels(net);
        sink.accept(new CraftDestinations.Destinations(containerId, destinations));
    }

    @SuppressWarnings("ReferenceEquality") // a network is one object until it is rebuilt
    private boolean start(NetworkState net, long cpuTier, int deliverTo) {
        CraftingPlanner.Result plan = lastPlan;
        if (cpuTier < 0 || cpuTier > 3 || deliverTo < 0 || deliverTo >= CraftDestinations.LABEL + destinations.size()) {
            return false;
        }
        if (plan == null || lastPlanNetwork != net) {
            notice(RequestContent.NO_PLAN_KEY);
            return false;
        }
        boolean full = extended.getAsBoolean();
        // Extended mode starts a plan with missing leaves as a partial job (M7 C9); classic only a complete one.
        if (!plan.tree().complete() && (!full || plan.tree().failure().isPresent())) {
            notice(RequestContent.INCOMPLETE_KEY);
            return false;
        }
        CraftDelivery delivery = null;
        if (deliverTo != CraftDestinations.STORAGE) {
            // Delivering takes items out of the network, which needs full access (M7C item 5).
            if (!net.accessLevel(player).atLeast(AccessLevel.FULL)) {
                notice(CraftDestinations.DENIED_KEY);
                return false;
            }
            if (deliverTo == CraftDestinations.PLAYER) {
                delivery = CraftDelivery.player();
            } else {
                delivery = CraftDelivery.label(destinations.get(deliverTo - CraftDestinations.LABEL));
                if (CraftDelivery.ports(net, delivery.label()).isEmpty()) {
                    notice(CraftDestinations.NO_PORT_KEY);
                    return false;
                }
            }
        }
        long origin = QueueBackgroundCrafting.origin(net);
        if (origin == Long.MIN_VALUE) {
            notice(RequestContent.REFUSED_KEY);
            return false;
        }
        // Classic mode has untiered CPUs (C29): any CPU.
        JobQueue.Request request = new JobQueue.Request(Optional.of(player.getUUID()), 0, full ? (int) cpuTier : 0,
                null);
        JobQueue.Submission submission = JobQueue.submit(net, origin, plan, request);
        if (submission.job().isEmpty()) {
            notice(RequestContent.REFUSED_KEY);
            return false;
        }
        CraftingJob job = submission.job().get();
        if (delivery != null) {
            CraftDeliveries.get(net.level()).put(job.id(), delivery);
        }
        lastPlan = null;
        lastJobs = null;
        view(net, job, net.level().getGameTime());
        return true;
    }

    private boolean view(NetworkState net, UUID id, long now) {
        CraftingJob job = find(net, id);
        if (job == null) {
            notice(RequestContent.JOB_ENDED_KEY);
            return false;
        }
        view(net, job, now);
        return true;
    }

    private void view(NetworkState net, CraftingJob job, long now) {
        watched = job;
        watchedEnded = false;
        boolean full = extended.getAsBoolean();
        PlanView view = PlanView.of(job, net.level(), full, generatorTicks(net));
        JobRun run = JobQueue.progress(net.level(), job.id());
        watchedState = job.state();
        watchedVersion = run == null ? -1 : run.version();
        remaining = new long[view.nodes().size()];
        Int2LongOpenHashMap open = new Int2LongOpenHashMap(remaining.length);
        for (int i = 0; i < remaining.length; i++) {
            remaining[i] = left(job, run, i);
            open.put(i, remaining[i]);
        }
        sink.accept(new RequestPayloads.Job(containerId, job.id(), watchedState, view, missing(run, full)));
        lastStuck = stuck(net, job, run);
        sink.accept(new RequestPayloads.JobStatus(containerId, job.id(), tiles(job, run), lastStuck));
        statusDirty = false;
        nextStatus = now + STATUS_INTERVAL;
        progress.open(open, now);
    }

    /**
     * The providers holding up {@code job} (M7C item 9): for each step still owed its output, the providers with the
     * step's pattern on {@code net} and linked networks that report a {@link StuckReason}, and for a generator step
     * (M7C item 11) the generator providers for its key that report one; each provider once, at most
     * {@link RequestPayloads#MAX_STUCK}. Empty for a job without progress. Called every status interval while a player
     * watches the job, not per tick.
     */
    public static List<RequestPayloads.Stuck> stuck(NetworkState net, CraftingJob job, @Nullable JobRun run) {
        if (run == null) {
            return List.of();
        }
        List<RequestPayloads.Stuck> out = new ArrayList<>();
        ReferenceOpenHashSet<Object> seen = new ReferenceOpenHashSet<>();
        for (int i = 0; i < job.steps().size(); i++) {
            CraftingJob.Step step = job.steps().get(i);
            if (step.pattern() == PlanTree.GENERATOR && run.received(i) < step.toCraft()) {
                if (stuckGenerators(net, step.key(), seen, out)) {
                    return out;
                }
                continue;
            }
            if (step.pattern() < 0 || run.received(i) >= step.toCraft()) {
                continue;
            }
            CraftingPattern pattern = job.patterns().get(step.pattern());
            List<NetworkPatterns.Entry> entries = net.patterns().allPatternsFor(step.key());
            for (int e = 0; e < entries.size(); e++) {
                NetworkPatterns.Entry entry = entries.get(e);
                if (!(entry.provider() instanceof ProviderLogic logic) || !entry.pattern().equals(pattern)) {
                    continue;
                }
                StuckReason reason = logic.stuck();
                if (reason == null) {
                    continue;
                }
                if (seen.add(logic)) {
                    out.add(new RequestPayloads.Stuck(logic.pos(), step.key(), reason));
                    if (out.size() >= RequestPayloads.MAX_STUCK) {
                        return out;
                    }
                }
            }
        }
        return out;
    }

    /** The measured ticks per 1,000 units of {@code net}'s generators per key, for the plan view's estimate. */
    private static ToLongFunction<StackKey> generatorTicks(NetworkState net) {
        return key -> GeneratorDispatch.ticksPerThousand(net, key);
    }

    /**
     * Adds the generator providers for {@code key} on {@code net} and linked networks that report a reason (M7C item
     * 11), each once; returns true once {@code out} is full.
     */
    private static boolean stuckGenerators(NetworkState net, StackKey key, ReferenceOpenHashSet<Object> seen,
            List<RequestPayloads.Stuck> out) {
        List<NetworkState> linked = net.linkedNetworks();
        for (int n = -1; n < linked.size(); n++) {
            NetworkState network = n < 0 ? net : linked.get(n);
            List<GeneratorSource> generators = network.patterns().generatorsFor(key);
            for (int g = 0; g < generators.size(); g++) {
                if (!(generators.get(g) instanceof GeneratorLogic logic)) {
                    continue;
                }
                StuckReason reason = logic.stuck();
                if (reason != null && seen.add(logic)) {
                    out.add(new RequestPayloads.Stuck(logic.pos(), key, reason));
                    if (out.size() >= RequestPayloads.MAX_STUCK) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The live tile grid of {@code job}: per key what its CPU holds, what is in flight in machines, what is still to
     * send and what it waits for because storage lacks it. Empty for a job without progress or one that ended.
     */
    static List<PlanView.Tile> tiles(CraftingJob job, @Nullable JobRun run) {
        if (run == null || job.state().finished()) {
            return List.of();
        }
        TileSums<StackKey> sums = new TileSums<>();
        List<CraftingJob.Step> steps = job.steps();
        for (int i = 0; i < steps.size(); i++) {
            CraftingJob.Step step = steps.get(i);
            sums.add(step.key(), 0, run.inflight(i), TileSums.scheduled(step.toCraft(), step.crafts(), run.sent(i)), 0);
        }
        for (Object2LongMap.Entry<StackKey> entry : run.buffer().object2LongEntrySet()) {
            sums.add(entry.getKey(), entry.getLongValue(), 0, 0, 0);
        }
        for (Object2LongMap.Entry<StackKey> entry : run.missing().object2LongEntrySet()) {
            sums.add(entry.getKey(), 0, 0, 0, entry.getLongValue());
        }
        return PlanView.Tile.of(sums.result(PlanView.MAX_TILES));
    }

    /** The job's missing report for the view, empty in classic mode (C29). */
    private static List<PlanView.Missing> missing(@Nullable JobRun run, boolean extended) {
        if (run == null || !extended || run.missing().isEmpty()) {
            return List.of();
        }
        List<PlanView.Missing> out = new ArrayList<>(Math.min(run.missing().size(), PlanView.MAX_MISSING));
        for (Object2LongMap.Entry<StackKey> entry : run.missing().object2LongEntrySet()) {
            if (out.size() >= PlanView.MAX_MISSING) {
                break;
            }
            out.add(new PlanView.Missing(entry.getKey(), entry.getLongValue()));
        }
        return out;
    }

    /**
     * Crafts step {@code step} still has to run: its crafts less those whose main output came back. A craft's output is
     * the step's amount to craft over its crafts, rounded up; a done job has none left.
     */
    static long left(CraftingJob job, @Nullable JobRun run, int step) {
        CraftingJob.Step s = job.steps().get(step);
        if (s.crafts() <= 0 || job.state() == JobState.DONE) {
            return 0;
        }
        if (run == null) {
            return s.crafts();
        }
        long perCraft = Math.max(1, Math.ceilDiv(s.toCraft(), s.crafts()));
        return Math.max(0, s.crafts() - run.received(step) / perCraft);
    }

    private void tickWatched(NetworkState net, long now) {
        CraftingJob seen = watched;
        if (seen == null) {
            return;
        }
        if (!watchedEnded) {
            CraftingJob job = CraftingJobs.get(net.level()).get(seen.id());
            JobRun run = JobQueue.progress(net.level(), seen.id());
            if (job == null || run == null) {
                watched = null;
                notice(RequestContent.JOB_ENDED_KEY);
                return;
            }
            long version = run.version();
            if (version != watchedVersion || job.state() != watchedState) {
                watchedVersion = version;
                statusDirty = true;
                for (int i = 0; i < remaining.length; i++) {
                    long left = left(job, run, i);
                    if (left != remaining[i]) {
                        remaining[i] = left;
                        progress.changed(i, left);
                    }
                }
                if (job.state() != watchedState) {
                    watchedState = job.state();
                    boolean full = extended.getAsBoolean();
                    sink.accept(new RequestPayloads.Job(containerId, job.id(), watchedState,
                            PlanView.of(job, net.level(), full, generatorTicks(net)), missing(run, full)));
                }
            }
            if (now >= nextStatus) {
                // A provider can get stuck or free again without the job moving (M7C item 9).
                List<RequestPayloads.Stuck> stuck = stuck(net, job, run);
                if (statusDirty || !stuck.equals(lastStuck)) {
                    statusDirty = false;
                    lastStuck = stuck;
                    nextStatus = now + STATUS_INTERVAL;
                    sink.accept(new RequestPayloads.JobStatus(containerId, job.id(), tiles(job, run), stuck));
                }
            }
        }
        progress.tick(now);
    }

    private boolean cancel(NetworkState net, UUID id) {
        CraftingJob job = find(net, id);
        if (job == null) {
            notice(RequestContent.JOB_ENDED_KEY);
            return false;
        }
        boolean mine = job.requester().isPresent() && job.requester().get().equals(player.getUUID());
        if (!mine && net.accessLevel(player) != AccessLevel.FULL) {
            notice(RequestContent.NOT_YOURS_KEY);
            return false;
        }
        if (!JobQueue.cancel(net.level(), id, player.getUUID())) {
            notice(RequestContent.JOB_ENDED_KEY);
            return false;
        }
        lastJobs = null;
        nextJobsCheck = 0;
        notice(RequestContent.CANCELLED_KEY);
        return true;
    }

    private boolean resume(NetworkState net, UUID id) {
        if (find(net, id) == null) {
            notice(RequestContent.JOB_ENDED_KEY);
            return false;
        }
        if (!JobQueue.resume(net.level(), id)) {
            notice(RequestContent.RESUME_FAILED_KEY);
            return false;
        }
        notice(RequestContent.RESUMING_KEY);
        return true;
    }

    private @Nullable CraftingJob find(NetworkState net, UUID id) {
        for (CraftingJob job : JobQueue.jobsOn(net)) {
            if (job.id().equals(id)) {
                return job;
            }
        }
        return null;
    }

    private List<RequestPayloads.JobSummary> summaries(NetworkState net) {
        List<CraftingJob> jobs = JobQueue.jobsOn(net);
        List<RequestPayloads.JobSummary> lines = new ArrayList<>(Math.min(jobs.size(), RequestPayloads.MAX_JOBS));
        for (CraftingJob job : jobs) {
            if (lines.size() >= RequestPayloads.MAX_JOBS) {
                break;
            }
            JobRun run = JobQueue.progress(net.level(), job.id());
            long crafts = 0;
            long left = 0;
            for (int i = 0; i < job.steps().size(); i++) {
                crafts += Math.max(0, job.steps().get(i).crafts());
                left += left(job, run, i);
            }
            boolean mine = job.requester().isPresent() && job.requester().get().equals(player.getUUID());
            long cpu = run == null || run.cpu() == JobRun.NO_CPU ? RequestPayloads.JobSummary.NO_CPU : run.cpu();
            lines.add(new RequestPayloads.JobSummary(job.id(), job.key(), job.amount(), job.state(), crafts, left, mine,
                    cpu, cpuTier(net, cpu)));
        }
        return lines;
    }

    /** The tier of the CPU core at {@code cpu}, 0 when there is none or its chunk is not loaded. */
    private static int cpuTier(NetworkState net, long cpu) {
        if (cpu == RequestPayloads.JobSummary.NO_CPU) {
            return 0;
        }
        BlockPos pos = BlockPos.of(cpu);
        LevelChunk chunk = net.level().getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ()));
        return chunk != null && chunk.getBlockEntity(pos) instanceof JobQueue.Cpu core ? core.tier() : 0;
    }

    private @Nullable StackKey stepKey(int step) {
        CraftingJob job = watched;
        return job == null || step < 0 || step >= job.steps().size() ? null : job.steps().get(step).key();
    }

    @SuppressWarnings("ReferenceEquality") // a network is one object until it is rebuilt
    private void syncCraftables(@Nullable NetworkState net, long now) {
        boolean dump = !craftablesDumped || net != craftablesOf;
        if (net != craftablesOf) {
            craftablesOf = net;
            craftableIds.clear();
            craftableKeys.clear();
        }
        // Not monotonic since linked networks count (M7c area A): compare for equality only.
        long generation = net == null ? -1 : net.patterns().generation();
        if (!dump && generation == craftablesGeneration) {
            craftables.tick(now);
            return;
        }
        craftablesGeneration = generation;
        Set<StackKey> current = net == null ? Set.of() : net.patterns().craftableKeys();
        for (ObjectIterator<Object2IntMap.Entry<StackKey>> it = craftableIds.object2IntEntrySet().fastIterator(); it
                .hasNext();) {
            Object2IntMap.Entry<StackKey> entry = it.next();
            if (!current.contains(entry.getKey())) {
                craftables.changed(entry.getIntValue(), 0);
                craftableKeys.remove(entry.getIntValue());
                it.remove();
            }
        }
        for (StackKey key : current) {
            if (!craftableIds.containsKey(key)) {
                int id = nextCraftableId++;
                craftableIds.put(key, id);
                craftableKeys.put(id, key);
                craftables.changed(id, 1);
            }
        }
        if (dump) {
            craftablesDumped = true;
            Int2LongOpenHashMap all = new Int2LongOpenHashMap(craftableKeys.size());
            for (IntIterator it = craftableKeys.keySet().iterator(); it.hasNext();) {
                all.put(it.nextInt(), 1);
            }
            craftables.open(all, now);
        } else {
            craftables.tick(now);
        }
    }

    private void notice(String key) {
        sink.accept(new RequestPayloads.Notice(containerId, key));
    }
}
