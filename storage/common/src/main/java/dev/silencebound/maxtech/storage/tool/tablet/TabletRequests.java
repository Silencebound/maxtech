package dev.silencebound.maxtech.storage.tool.tablet;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJobs;
import dev.silencebound.maxtech.storage.crafting.job.JobState;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.plan.PlanTree;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.terminal.AmountFormat;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Where the tablet's "request missing" button sends a job: one {@link Requester} for the whole mod. The default plans
 * the request ({@link CraftingPlanner#submitCrafted}) and saves a complete plan as a planned job in the level's
 * {@link CraftingJobs}, which the crafting CPUs take from there. It tells the requester how it went, since the plan
 * arrives some ticks later. A test, or the job queue once it owns submission, swaps it with {@link #use}.
 */
public final class TabletRequests {
    /** Orders one crafting job. */
    @FunctionalInterface
    public interface Requester {
        /**
         * Asks for {@code amount} of {@code key} on {@code network} for {@code player}, who is told the outcome.
         * Returns false when the request could not even be queued (the planner pool is full). Server thread.
         */
        boolean request(ServerPlayer player, NetworkState network, ServerLevel level, StackKey key, long amount);
    }

    private static volatile Requester requester = TabletRequests::planned;

    private TabletRequests() {
    }

    /** The requester in use. */
    public static Requester requester() {
        return requester;
    }

    /** Replaces the requester; {@link #planned} restores the default. */
    public static void use(Requester value) {
        requester = value;
    }

    /** The default: plan, then save a complete plan as a planned job. */
    public static boolean planned(ServerPlayer player, NetworkState network, ServerLevel level, StackKey key,
            long amount) {
        UUID requester = player.getUUID();
        return CraftingPlanner.submitCrafted(network, key, amount, result -> finished(level, requester, result));
    }

    private static void finished(ServerLevel level, UUID requester, CraftingPlanner.Result result) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(requester);
        PlanTree<StackKey> tree = result.tree();
        if (tree.failure().isPresent()) {
            tell(player, Component.translatable(CraftingTabletContent.PLAN_FAILED_KEY,
                    Component.translatable(CraftingTabletContent.failureKey(tree.failure().get()))));
        } else if (!tree.complete()) {
            Object2LongMap.Entry<StackKey> first = tree.missing().object2LongEntrySet().iterator().next();
            tell(player, Component.translatable(CraftingTabletContent.PLAN_MISSING_KEY, PatternItem.name(result.key()),
                    AmountFormat.compact(first.getLongValue()), PatternItem.name(first.getKey())));
        } else {
            CraftingJobs jobs = CraftingJobs.get(level);
            // Asking again for the same item replaces the waiting job; other planned jobs of the player are capped.
            List<UUID> replaced = new ArrayList<>();
            int others = 0;
            for (CraftingJob job : jobs.all()) {
                if (job.state() == JobState.PLANNED && job.requester().equals(Optional.of(requester))) {
                    if (job.key().equals(result.key())) {
                        replaced.add(job.id());
                    } else {
                        others++;
                    }
                }
            }
            if (others >= StorageServerConfig.CRAFTING_TABLET_MAX_PLANNED_JOBS.getAsInt()) {
                tell(player, Component.translatable(CraftingTabletContent.TOO_MANY_JOBS_KEY, others));
                return;
            }
            for (UUID id : replaced) {
                jobs.remove(id);
            }
            jobs.put(CraftingJob.planned(result, Optional.of(requester), level.getGameTime()));
            tell(player, Component.translatable(CraftingTabletContent.QUEUED_KEY, AmountFormat.compact(result.amount()),
                    PatternItem.name(result.key())));
        }
    }

    private static void tell(@Nullable ServerPlayer player, Component message) {
        if (player != null) {
            CraftingTabletItem.tell(player, message);
        }
    }
}
