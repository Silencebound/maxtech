package dev.silencebound.maxtech.storage.crafting.job;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.crafting.provider.CraftingDeliveries;
import dev.silencebound.maxtech.storage.display.production.ProductionStats;
import dev.silencebound.maxtech.storage.network.NetworkState;
import net.minecraft.server.level.ServerLevel;
// PLATFORM-LEAK: counted outputs leave storage in NeoForge's transfer transaction (PRD 7.2, cache is a view).
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Counts crafting outputs for running jobs (M7 C13): the {@link CraftingDeliveries} listener. Providers report what
 * they pull out of machines and interfaces what machines push into them, after the items entered storage; running jobs
 * on that network (or one sharing patterns and CPUs with it, C30) count what they expect, main outputs first, then
 * returned inputs and crafting remainders, and take that much back out of storage at once, so an export bus on the same
 * item cannot take it first (inside an open transaction the CPU takes it on its next run instead). What no job expects,
 * a cancelled job's outputs among them, simply stays in storage (C19). Each delivery is counted once, by the first job
 * that expects it. Server thread only.
 */
public final class CraftingOutputs implements CraftingDeliveries.Listener {
    public static final CraftingOutputs INSTANCE = new CraftingOutputs();

    private CraftingOutputs() {
    }

    @Override
    public boolean expects(NetworkState network, StackKey key) {
        ServerLevel level = network.level();
        CraftingRuns runs = CraftingRuns.get(level);
        if (runs.all().isEmpty()) {
            return false;
        }
        CraftingJobs jobs = CraftingJobs.get(level);
        for (JobRun run : runs.all()) {
            CraftingJob job = jobs.get(run.job());
            if (job != null && counts(level, network, job, run) && run.expects(key, job.steps())) {
                return true;
            }
        }
        return false;
    }

    /** O(1) while the level has no unfinished job. */
    @Override
    public void delivered(NetworkState network, StackKey key, long amount) {
        ServerLevel level = network.level();
        CraftingRuns runs = CraftingRuns.get(level);
        if (runs.all().isEmpty() || amount <= 0) {
            return;
        }
        CraftingJobs jobs = CraftingJobs.get(level);
        long left = amount;
        for (JobRun run : runs.all()) {
            if (left <= 0) {
                break;
            }
            CraftingJob job = jobs.get(run.job());
            if (job != null && counts(level, network, job, run)) {
                long counted = run.count(key, left, job.steps());
                left -= counted;
                if (counted > 0 && run.cpu != JobRun.NO_CPU) {
                    ProductionStats.of(network).crafted(run.cpu, counted);
                }
                if (counted > 0 && canPullNow()) {
                    JobDriver.pullCounted(network, run);
                }
            }
        }
        if (left < amount) {
            runs.touched();
        }
    }

    /** No transaction is open: a provider's committed pull, or an interface's insert reported on its root commit. */
    private static boolean canPullNow() {
        Transaction.Lifecycle lifecycle = Transaction.getLifecycle();
        return lifecycle == Transaction.Lifecycle.NONE || lifecycle == Transaction.Lifecycle.ROOT_CLOSING;
    }

    /**
     * Whether {@code run} counts outputs arriving on {@code network}: a running job, not being cancelled, whose CPU
     * last saw that network, or, before its CPU ran since a restart or rebuild, whose origin node is in it.
     */
    private static boolean counts(ServerLevel level, NetworkState network, CraftingJob job, JobRun run) {
        if (job.state() != JobState.RUNNING || run.cancelling) {
            return false;
        }
        return run.claimNetworks.contains(network) || JobDriver.network(level, run.origin()) == network;
    }
}
