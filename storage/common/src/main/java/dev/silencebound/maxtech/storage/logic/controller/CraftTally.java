package dev.silencebound.maxtech.storage.logic.controller;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJobs;
import dev.silencebound.maxtech.storage.crafting.job.JobDriver;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.job.JobRun;
import dev.silencebound.maxtech.storage.crafting.job.JobState;
import dev.silencebound.maxtech.storage.network.NetworkState;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * The unfinished crafting jobs of one network, kept by a Logic Controller whose graphs read the crafting status: each
 * job's item and whether it waits for a CPU or runs on one. Filled once when the controller binds to the network (one
 * walk over the level's jobs), then kept from the job events ({@link CraftWatch}), so a read walks only this network's
 * jobs and allocates nothing. Server thread only.
 */
final class CraftTally {
    private final ObjectArrayList<UUID> ids = new ObjectArrayList<>();
    private final ObjectArrayList<StackKey> keys = new ObjectArrayList<>();
    private final ObjectArrayList<JobState> states = new ObjectArrayList<>();
    private final Object2IntOpenHashMap<UUID> index = new Object2IntOpenHashMap<>();

    CraftTally() {
        index.defaultReturnValue(-1);
    }

    /** Forgets every job; for a controller that no longer reads the status or left its network. */
    void clear() {
        ids.clear();
        keys.clear();
        states.clear();
        index.clear();
    }

    /** Starts over from the level's jobs: those of {@code network} that wait or run. Walks every job; on binds only. */
    void reset(NetworkState network) {
        clear();
        ServerLevel level = network.level();
        for (CraftingJob job : CraftingJobs.get(level).all()) {
            changed(network, level, job);
        }
    }

    /**
     * Job {@code job} of {@code level} changed: kept while it is {@code network}'s and waits or runs, dropped otherwise
     * (ended, partial, removed, or another network's).
     */
    void changed(NetworkState network, ServerLevel level, CraftingJob job) {
        boolean keep = false;
        if (job.state() == JobState.PLANNED || job.state() == JobState.RUNNING) {
            JobRun run = JobQueue.progress(level, job.id());
            keep = run != null && JobDriver.network(level, run.origin()) == network;
        }
        int at = index.getInt(job.id());
        if (keep && at >= 0) {
            keys.set(at, job.key());
            states.set(at, job.state());
        } else if (keep) {
            index.put(job.id(), ids.size());
            ids.add(job.id());
            keys.add(job.key());
            states.add(job.state());
        } else if (at >= 0) {
            remove(at);
        }
    }

    private void remove(int at) {
        int last = ids.size() - 1;
        index.removeInt(ids.get(at));
        if (at != last) {
            ids.set(at, ids.get(last));
            keys.set(at, keys.get(last));
            states.set(at, states.get(last));
            index.put(ids.get(at), at);
        }
        ids.remove(last);
        keys.remove(last);
        states.remove(last);
    }

    /** Jobs for {@code key} (any item when null) in {@code state}. O(this network's jobs); allocates nothing. */
    int count(@Nullable StackKey key, JobState state) {
        int count = 0;
        for (int i = 0; i < ids.size(); i++) {
            if (states.get(i) == state && (key == null || key.equals(keys.get(i)))) {
                count++;
            }
        }
        return count;
    }

    /** Jobs kept; for tests. */
    int size() {
        return ids.size();
    }
}
