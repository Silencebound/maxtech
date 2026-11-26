package dev.silencebound.maxtech.storage.crafting.job;

import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.stock.BackgroundCrafting;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The job queue behind stock keepers and crafting cards ({@link BackgroundCrafting}, M7c area D): plans the request off
 * the server thread and queues it as a background job, after every player request. Refuses everything in classic mode,
 * which has no stock keeping (PRD 7.2). A background job that ends partial is dropped rather than kept for resume, so a
 * stock keeper never finds an old job of its own waiting; its outcome is then
 * {@link BackgroundCrafting.Outcome#PARTIAL}. The outcome callback is not saved: after a restart a job still runs, but
 * its requester is not told, and a stock keeper simply asks again when the stock is still low. The amount is capped to
 * the network's per-job maximum for automation ({@link CraftLimits}, M7L2 chunk 20) rather than split into several
 * jobs: the requester asks again for the rest once its job ends, so one request never fills the queue. Server thread
 * only.
 */
public final class QueueBackgroundCrafting implements BackgroundCrafting {
    public static final QueueBackgroundCrafting INSTANCE = new QueueBackgroundCrafting();

    private QueueBackgroundCrafting() {
    }

    @Override
    public boolean submit(NetworkState network, Request request, Consumer<Outcome> done) {
        return submit(network, request, done, id -> {
        });
    }

    @Override
    public boolean submit(NetworkState network, Request request, Consumer<Outcome> done, Consumer<UUID> queued) {
        if (!CraftingMode.current().extended() || request.amount() <= 0
                || !network.patterns().isCraftable(request.key())) {
            return false;
        }
        long origin = origin(network);
        if (origin == Long.MIN_VALUE) {
            return false;
        }
        boolean[] answered = {false};
        Consumer<Outcome> once = outcome -> {
            if (!answered[0]) {
                answered[0] = true;
                done.accept(outcome);
            }
        };
        long amount = network.craftLimits().cap(request.amount());
        // The requester asks for what its stock lacks, so the plan crafts all of it rather than counting stored ones.
        return CraftingPlanner.submitCrafted(network, request.key(), amount, plan -> {
            JobQueue.Submission submission = JobQueue.submit(network, origin, plan, new JobQueue.Request(
                    Optional.empty(), request.priority(), request.cpuTier(), state -> once.accept(outcome(state))));
            if (submission.job().isEmpty()) {
                once.accept(Outcome.FAILED);
            } else if (!answered[0]) {
                queued.accept(submission.job().get().id());
            }
        });
    }

    private static Outcome outcome(JobState state) {
        return switch (state) {
            case DONE -> Outcome.DONE;
            case PARTIAL -> Outcome.PARTIAL;
            default -> Outcome.CANCELLED;
        };
    }

    /** A node of {@code network} to anchor the job: the controller's, else any; {@code Long.MIN_VALUE} for none. */
    public static long origin(NetworkState network) {
        NetworkDevice controller = network.controllerDevice();
        if (controller != null) {
            return controller.nodePos();
        }
        var nodes = network.nodes().iterator();
        return nodes.hasNext() ? nodes.nextLong() : Long.MIN_VALUE;
    }
}
