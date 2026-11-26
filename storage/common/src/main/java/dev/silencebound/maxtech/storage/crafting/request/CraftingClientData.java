package dev.silencebound.maxtech.storage.crafting.request;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.sync.ClientSyncedList;
import dev.silencebound.maxtech.storage.crafting.job.JobState;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The client's copy of a terminal menu's crafting state, filled from the {@link RequestPayloads} and the two synced
 * lists; the crafting screens read it every frame. Plain data, so the payload handlers load no client classes. Client
 * thread.
 */
public final class CraftingClientData {
    private final ClientSyncedList<StackKey> craftables = new ClientSyncedList<>();
    private final ClientSyncedList<StackKey> progress = new ClientSyncedList<>();
    private final ObjectOpenHashSet<StackKey> craftableKeys = new ObjectOpenHashSet<>();
    private int craftablesVersion = -1;
    private int nextRequest = 1;
    private int pendingRequest;
    private @Nullable PlanView plan;
    private List<RequestPayloads.JobSummary> jobs = List.of();
    private @Nullable UUID job;
    private JobState jobState = JobState.PLANNED;
    private @Nullable PlanView jobView;
    private List<PlanView.Missing> jobMissing = List.of();
    private List<PlanView.Tile> jobTiles = List.of();
    private List<RequestPayloads.Stuck> jobStuck = List.of();
    private @Nullable String notice;
    private int version;

    /** The craftable keys the server lists: id per key, count 1. */
    public ClientSyncedList<StackKey> craftables() {
        return craftables;
    }

    /** The watched job's progress: step index to crafts still to run, with the step's key. */
    public ClientSyncedList<StackKey> progress() {
        return progress;
    }

    /** Whether some pattern on the network makes {@code key}. */
    public boolean isCraftable(StackKey key) {
        return craftableSet().contains(key);
    }

    /** Every craftable key; rebuilt when the list changed. */
    public Set<StackKey> craftableSet() {
        if (craftablesVersion != craftables.version()) {
            craftablesVersion = craftables.version();
            craftableKeys.clear();
            for (IntIterator it = craftables.ids().iterator(); it.hasNext();) {
                StackKey key = craftables.entry(it.nextInt());
                if (key != null) {
                    craftableKeys.add(key);
                }
            }
        }
        return craftableKeys;
    }

    /** A number for a new plan request; the next {@link #plan()} answers it. */
    public int newRequest() {
        pendingRequest = nextRequest++;
        plan = null;
        notice = null;
        version++;
        return pendingRequest;
    }

    /** Whether a plan was asked for and has not come back. */
    public boolean planning() {
        return pendingRequest != 0;
    }

    /** The answer to the latest plan request, null before it arrives. */
    public @Nullable PlanView plan() {
        return plan;
    }

    public List<RequestPayloads.JobSummary> jobs() {
        return jobs;
    }

    /** The watched job, null when none. */
    public @Nullable UUID job() {
        return job;
    }

    public JobState jobState() {
        return jobState;
    }

    public @Nullable PlanView jobView() {
        return jobView;
    }

    /** What the watched job lacks; empty unless the server reports it (extended mode). */
    public List<PlanView.Missing> jobMissing() {
        return jobMissing;
    }

    /** The watched job's live tiles (in CPU / in flight / to send / waiting per key); empty before they arrive. */
    public List<PlanView.Tile> jobTiles() {
        return jobTiles;
    }

    /** Providers holding up the watched job (M7C item 9); empty when none or before a status arrived. */
    public List<RequestPayloads.Stuck> jobStuck() {
        return jobStuck;
    }

    /** The last notice as a translation key, null when none. */
    public @Nullable String notice() {
        return notice;
    }

    /** Bumped by every payload, so a screen can rebuild what it derives from this. */
    public int version() {
        return version;
    }

    /** Forgets the last notice, before an action whose answer may be one. */
    public void clearNotice() {
        notice = null;
        version++;
    }

    /** Forgets the watched job, when the screen stops watching. */
    public void clearJob() {
        job = null;
        jobView = null;
        jobMissing = List.of();
        jobTiles = List.of();
        jobStuck = List.of();
        version++;
    }

    void accept(RequestPayloads.Plan payload) {
        if (payload.request() == pendingRequest) {
            pendingRequest = 0;
            plan = payload.view();
            version++;
        }
    }

    void accept(RequestPayloads.Jobs payload) {
        jobs = payload.jobs();
        version++;
    }

    void accept(RequestPayloads.Job payload) {
        if (!payload.id().equals(job)) {
            jobTiles = List.of();
            jobStuck = List.of();
        }
        job = payload.id();
        jobState = payload.state();
        jobView = payload.view();
        jobMissing = payload.missing();
        version++;
    }

    void accept(RequestPayloads.JobStatus payload) {
        if (payload.id().equals(job)) {
            jobTiles = payload.tiles();
            jobStuck = payload.stuck();
            version++;
        }
    }

    void accept(RequestPayloads.Notice payload) {
        notice = payload.key();
        pendingRequest = 0;
        version++;
    }
}
