package dev.silencebound.maxtech.storage.crafting.job;

import java.util.UUID;

/**
 * What runs crafting jobs: the crafting CPU (M7c area A) implements it. The job queue offers each planned job to the
 * smallest free executor that fits (C16); the executor then works through the job's steps from the leaves up, sending
 * crafting steps to {@link PatternDispatch} targets, and moves the job's {@link JobState} in {@link CraftingJobs}.
 * Server thread only, never per tick: executors run on their network's scheduler.
 */
public interface JobExecutor {
    /** Whether the executor can take {@code job} now: free, powered, and large enough for its steps. */
    boolean canRun(CraftingJob job);

    /**
     * Starts {@code job}; true when the executor took it, and then it owns the job until it is done, partial or
     * cancelled. A job is only started on one executor at a time.
     */
    boolean start(CraftingJob job);

    /**
     * Cancels job {@code id}: stop sending, return intermediates to storage; items still inside machines finish and
     * return when they come out (C19). Does nothing for a job the executor does not run.
     */
    void cancel(UUID id);

    /** How many steps of one job the executor holds at most; the queue uses it to pick the smallest that fits. */
    int capacity();
}
