package dev.silencebound.maxtech.storage.crafting.cpu;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.cluster.ClusterNeeds;
import dev.silencebound.maxtech.storage.cluster.ClusterPreview;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJobs;
import dev.silencebound.maxtech.storage.crafting.job.CraftingRuns;
import dev.silencebound.maxtech.storage.crafting.job.JobDriver;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.job.JobRun;
import dev.silencebound.maxtech.storage.crafting.job.JobState;
import dev.silencebound.maxtech.storage.crafting.job.JobToastPayload;
import dev.silencebound.maxtech.storage.network.ChannelFree;
import dev.silencebound.maxtech.storage.network.KeyListener;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * A crafting CPU core (M7 C3 to C5, C16, C19): a storage device that runs one crafting job at a time with the caps of
 * its tier and cluster ({@link CpuStats}). Its job's progress lives in {@link CraftingRuns}, so the job survives the
 * core's chunk unloading; the core keeps only the job's id. It runs on its network's scheduler: while a job runs it
 * works every {@link JobDriver#RUN_TICKS} and pays FE per active lane, without a job it sleeps until the queue or an
 * event wakes it, and costs nothing. Breaking it cancels its job, whose items go back to storage (or drop when storage
 * cannot take them). For partial jobs it ran it listens to the missing keys and resumes them once storage holds them.
 * The core holds no channel ({@link ChannelFree}) and its part blocks are no devices, so a CPU uses none. Never ticks.
 */
public final class CpuCoreBlockEntity extends StorageDeviceBlockEntity implements JobQueue.Cpu, ChannelFree {
    private static final String JOB = "job";

    private final CpuTier tier;
    private CpuCluster.Scan cluster = new CpuCluster.Scan(0, 0, 0, false);
    private boolean conflict;
    private @Nullable UUID job;
    private final JobDriver driver = new JobDriver();
    private final Task task = new Task();
    private final Runnable wake = this::wakeTask;
    private final KeyListener missingListener = this::missingChanged;
    private final ObjectArrayList<StackKey> listened = new ObjectArrayList<>();
    private boolean resumeCheck;
    private boolean registered;

    public CpuCoreBlockEntity(BlockPos pos, BlockState state) {
        super(CpuContent.CORE_BLOCK_ENTITY.get(), pos, state);
        tier = state.getBlock() instanceof CpuCoreBlock core ? core.tier() : CpuTier.TIER_1;
    }

    @Override
    public Role role() {
        return Role.OTHER;
    }

    @Override
    public int tier() {
        return tier.level();
    }

    public CpuTier cpuTier() {
        return tier;
    }

    @Override
    public long position() {
        return worldPosition.asLong();
    }

    @Override
    public boolean busy() {
        return job != null;
    }

    /** The job running here, or null. */
    public @Nullable UUID job() {
        return job;
    }

    /** Whether another core touches this cluster, so this one runs nothing. */
    public boolean conflict() {
        return conflict;
    }

    /** Parts of {@code part} in the cluster as of the last fill. */
    public int count(CpuPart part) {
        return cluster.count(part);
    }

    /** What the cluster can do now; {@link CpuStats#NONE} in a conflict. Server thread. */
    public CpuStats stats() {
        if (conflict) {
            return CpuStats.NONE;
        }
        return CpuStats.of(CpuStats.Caps.of(tier), CpuStats.Parts.fromConfig(), count(CpuPart.STORAGE),
                count(CpuPart.LANE), count(CpuPart.CACHE));
    }

    @Override
    public int capacity() {
        return stats().steps();
    }

    @Override
    public boolean canRun(CraftingJob candidate) {
        return job == null && !conflict && network() != null && candidate.steps().size() <= capacity();
    }

    @Override
    public boolean start(CraftingJob candidate) {
        if (!canRun(candidate)) {
            return false;
        }
        job = candidate.id();
        setChanged();
        wake(task);
        return true;
    }

    @Override
    public void cancel(UUID id) {
        if (!id.equals(job) || !(level instanceof ServerLevel server)) {
            return;
        }
        CraftingJob current = CraftingJobs.get(server).get(id);
        JobRun run = CraftingRuns.get(server).get(id);
        job = null;
        setChanged();
        if (current != null && run != null) {
            returnItems(server, run);
            JobQueue.ended(server, current, run, JobState.CANCELLED, network());
        }
    }

    /** Counts the cluster again. Server side. */
    public void recount() {
        recount(null);
    }

    /**
     * Counts the cluster again, adding each counted part's position to {@code members} when given;
     * {@link CpuConnections#refresh} calls it when a CPU block is placed or broken. Returns the scan (the last one off
     * the server).
     */
    public CpuCluster.Scan recount(@Nullable LongList members) {
        if (!(level instanceof ServerLevel server)) {
            return cluster;
        }
        CpuCluster.Scan scan = CpuCluster.scan(server, worldPosition, StorageServerConfig.CPU_MAX_PARTS.getAsInt(),
                members);
        cluster = scan;
        conflict = scan.conflict();
        // A bigger cluster may fit a waiting job.
        wake(task);
        return scan;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            JobQueue.register(server, this);
            registered = true;
            recount();
            // Once per load: the cluster's connected textures, for clusters saved before they existed (B09 item 28).
            server.scheduleTick(worldPosition, getBlockState().getBlock(), 1);
            schedule(task);
            listenMissing(server);
        }
    }

    @Override
    public void setRemoved() {
        if (registered && level instanceof ServerLevel server) {
            JobQueue.unregister(server, this);
            registered = false;
        }
        super.setRemoved();
    }

    /** Broken (not unloaded): the job is cancelled and the core's items go back. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        UUID current = job;
        if (current != null) {
            cancel(current);
        }
    }

    @Override
    public void networkChanged(@Nullable StorageNetwork network) {
        super.networkChanged(network);
        wake(task);
    }

    /** Puts the run's items into the job's network, or this core's, and drops what neither takes. */
    private void returnItems(ServerLevel server, JobRun run) {
        NetworkState target = JobDriver.network(server, run.origin());
        if (target == null) {
            target = network();
        }
        if (target != null && JobDriver.flushTo(target, run)) {
            return;
        }
        Object2LongMap<StackKey> left = JobDriver.drain(run);
        for (Object2LongMap.Entry<StackKey> entry : left.object2LongEntrySet()) {
            if (entry.getKey() instanceof ItemKey item) {
                long amount = entry.getLongValue();
                int max = item.item().getDefaultMaxStackSize();
                while (amount > 0) {
                    int count = (int) Math.min(amount, max);
                    Containers.dropItemStack(server, worldPosition.getX() + 0.5, worldPosition.getY() + 1,
                            worldPosition.getZ() + 0.5, item.toStack(count));
                    amount -= count;
                }
            }
        }
    }

    /** Listens to the missing keys of the partial jobs this core ran, so they resume when storage holds them. */
    private void listenMissing(ServerLevel server) {
        for (int i = 0; i < listened.size(); i++) {
            deviceLink().unlisten(listened.get(i), missingListener);
        }
        listened.clear();
        long self = position();
        for (JobRun run : CraftingRuns.get(server).all()) {
            CraftingJob partial = CraftingJobs.get(server).get(run.job());
            if (run.cpu() == self && partial != null && partial.state() == JobState.PARTIAL) {
                for (StackKey key : run.missing().keySet()) {
                    if (!listened.contains(key)) {
                        listened.add(key);
                        deviceLink().listen(key, missingListener);
                    }
                }
            }
        }
    }

    private void wakeTask() {
        wake(task);
    }

    private void missingChanged(StackKey key, long total) {
        if (total > 0) {
            resumeCheck = true;
            wake(task);
        }
    }

    /** Resumes the partial jobs this core ran whose every missing key storage now holds enough of. */
    private void checkResumes(ServerLevel server) {
        resumeCheck = false;
        long self = position();
        ObjectArrayList<UUID> ready = new ObjectArrayList<>();
        for (JobRun run : CraftingRuns.get(server).all()) {
            CraftingJob partial = CraftingJobs.get(server).get(run.job());
            NetworkState network = JobDriver.network(server, run.origin());
            if (run.cpu() != self || partial == null || partial.state() != JobState.PARTIAL || network == null) {
                continue;
            }
            boolean all = true;
            for (Object2LongMap.Entry<StackKey> entry : run.missing().object2LongEntrySet()) {
                if (network.contents().amount(entry.getKey()) < entry.getLongValue()) {
                    all = false;
                    break;
                }
            }
            if (all) {
                ready.add(run.job());
            }
        }
        for (UUID id : ready) {
            JobQueue.resume(server, id);
        }
    }

    @Override
    public void addGlance(BlockInfoRequest request, BlockInfoSink sink) {
        if (conflict) {
            sink.line(Component.translatable(CpuContent.INFO_CONFLICT));
            return;
        }
        if (level instanceof ServerLevel server && job != null) {
            CraftingJob current = CraftingJobs.get(server).get(job);
            JobRun run = CraftingRuns.get(server).get(job);
            if (current != null) {
                sink.line(Component.translatable(
                        run != null && run.stalled() ? CpuContent.INFO_STALLED : CpuContent.INFO_RUNNING,
                        InfoFormat.number(current.amount()), JobToastPayload.name(current.key())));
            }
        } else {
            sink.line(Component.translatable(CpuContent.INFO_IDLE));
        }
    }

    @Override
    public void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
        if (!conflict) {
            CpuStats stats = stats();
            sink.line(Component.translatable(CpuContent.INFO_STATS, InfoFormat.number(stats.steps()),
                    InfoFormat.number(stats.lanes()), InfoFormat.number(stats.batch())));
            // What the cluster lacks or wastes (M7C item 22); the heading and the conflict are shown already.
            List<ClusterNeeds.Need> needs = new ArrayList<>();
            ClusterPreview.cpuNeeds(this, needs);
            for (ClusterNeeds.Need need : needs) {
                if (need.kind() != ClusterNeeds.Kind.CPU) {
                    sink.line(ClusterPreview.text(need));
                }
            }
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        UUID current = job;
        if (current != null) {
            output.store(JOB, UUIDUtil.CODEC, current);
        }
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        job = input.read(JOB, UUIDUtil.CODEC).orElse(null);
    }

    /** The core's work on its network's scheduler. */
    private final class Task implements StorageTask {
        @Override
        public int run(StorageTransfer transfer, long gameTime) {
            return work(gameTime);
        }
    }

    /**
     * One run of the core's task: resume checks, the queue when idle, the job's next step when running. Returns when to
     * run next, as a {@link StorageTask} does. Public for the perf GameTest, which times it; server thread.
     */
    public int work(long gameTime) {
        if (!(level instanceof ServerLevel server)) {
            return StorageTask.WAIT;
        }
        if (resumeCheck) {
            checkResumes(server);
        }
        UUID id = job;
        if (id == null) {
            JobQueue.assign(server);
            return job == null ? StorageTask.WAIT : StorageTask.NEXT_TICK;
        }
        CraftingJob current = CraftingJobs.get(server).get(id);
        JobRun run = CraftingRuns.get(server).get(id);
        if (current == null || run == null || current.state() != JobState.RUNNING) {
            job = null;
            setChanged();
            JobQueue.assign(server);
            return StorageTask.WAIT;
        }
        if (run.cancelling()) {
            cancel(id);
            return StorageTask.WAIT;
        }
        run.wake(wake);
        JobQueue.resumed(server, run);
        CpuStats stats = stats();
        int next = driver.run(server, current, run,
                new JobDriver.Limits(stats.lanes(), stats.batch(), stats.muPerLane()), network(), gameTime);
        CraftingRuns.get(server).touched();
        if (next == JobDriver.DONE || next == JobDriver.PARTIAL || next == JobDriver.CANCELLED) {
            job = null;
            setChanged();
            if (next != JobDriver.DONE) {
                returnItems(server, run);
            }
            JobState end = JobState.CANCELLED;
            if (next == JobDriver.DONE) {
                end = JobState.DONE;
            } else if (next == JobDriver.PARTIAL) {
                end = JobState.PARTIAL;
            }
            // Partial, or cancelled because classic mode could not start it for want of materials: a failure.
            JobQueue.ended(server, current, run, end, network(), next != JobDriver.DONE);
            listenMissing(server);
            return job == null ? StorageTask.WAIT : StorageTask.NEXT_TICK;
        }
        return next;
    }
}
