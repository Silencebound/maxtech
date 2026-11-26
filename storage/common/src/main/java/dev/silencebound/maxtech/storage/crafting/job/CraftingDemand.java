package dev.silencebound.maxtech.storage.crafting.job;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.crafting.plan.PlanTree;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/**
 * What running crafting jobs make and wait for, for the condition switch's crafting modes (B09 item 14). Read on the
 * switch's evaluation, which runs on job events and arrivals, never per tick; each call walks the level's unfinished
 * jobs and their steps without allocating. Server thread only.
 */
public final class CraftingDemand {
    private CraftingDemand() {
    }

    /**
     * Whether a running job of {@code network} is making {@code key} now: one of its steps crafts or generates
     * {@code key} and has not got all of it back. A job counts for the network its storage belongs to and the networks
     * its CPU last saw, as for counting outputs.
     */
    public static boolean making(NetworkState network, StackKey key) {
        ServerLevel level = network.level();
        CraftingRuns runs = CraftingRuns.get(level);
        if (runs.all().isEmpty()) {
            return false;
        }
        CraftingJobs jobs = CraftingJobs.get(level);
        for (JobRun run : runs.all()) {
            CraftingJob job = jobs.get(run.job());
            if (job == null || job.state() != JobState.RUNNING || run.cancelling || !on(level, network, run)) {
                continue;
            }
            List<CraftingJob.Step> steps = job.steps();
            for (int i = 0; i < steps.size(); i++) {
                CraftingJob.Step step = steps.get(i);
                if (PlanTree.crafts(step.pattern()) && step.key().equals(key) && run.received[i] < step.toCraft()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Units of {@code key} job {@code job} still waits for from generator sources: the sum over its generator steps for
     * the key of what was ordered and has not arrived. 0 for a job that is gone, not running or being cancelled.
     */
    public static long generatorWaiting(ServerLevel level, UUID job, StackKey key) {
        CraftingJob found = CraftingJobs.get(level).get(job);
        JobRun run = CraftingRuns.get(level).get(job);
        if (found == null || run == null || found.state() != JobState.RUNNING || run.cancelling) {
            return 0;
        }
        List<CraftingJob.Step> steps = found.steps();
        long waiting = 0;
        for (int i = 0; i < steps.size() && i < run.inflight.length; i++) {
            CraftingJob.Step step = steps.get(i);
            if (step.pattern() == PlanTree.GENERATOR && step.key().equals(key)) {
                waiting += run.inflight[i];
            }
        }
        return waiting;
    }

    /**
     * Units of {@code key} that jobs of {@code network} counted as arrived but have not taken out of its storage yet
     * (their pull failed or waits for a transaction to close). They are in storage, but already counted. Jobs of other
     * networks pull from their own storage and are left out.
     */
    public static long counted(NetworkState network, StackKey key) {
        ServerLevel level = network.level();
        long total = 0;
        for (JobRun run : CraftingRuns.get(level).all()) {
            long pending = run.pull.getLong(key);
            if (pending > 0 && on(level, network, run)) {
                total += pending;
            }
        }
        return total;
    }

    private static boolean on(ServerLevel level, NetworkState network, JobRun run) {
        return run.claimNetworks.contains(network) || JobDriver.network(level, run.origin()) == network;
    }
}
