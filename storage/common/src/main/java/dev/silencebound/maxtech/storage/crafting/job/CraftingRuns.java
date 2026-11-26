package dev.silencebound.maxtech.storage.crafting.job;

import dev.silencebound.maxtech.storage.save.KeptList;
import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * The executor's progress of every unfinished job of a level ({@link JobRun}), keyed by job id beside
 * {@link CraftingJobs}, saved with the level so a job survives restarts and its CPU's chunk unloading. Server thread.
 */
public final class CraftingRuns extends SavedData {
    /**
     * A run naming an item of a removed mod (in its buffer, say) is kept as saved ({@link KeptList}) instead of losing
     * every run and the items their buffers hold.
     */
    public static final Codec<CraftingRuns> CODEC = KeptList.codec(JobRun.CODEC).xmap(CraftingRuns::new,
            runs -> KeptList.of(new ArrayList<>(runs.runs.values()), runs.kept));
    public static final SavedDataType<CraftingRuns> TYPE = StorageSaveFormats
            .savedData(MaxtechStorage.id("crafting_runs"), CraftingRuns::new, CODEC);

    private final LinkedHashMap<UUID, JobRun> runs = new LinkedHashMap<>();
    /** Saved runs that no longer load, written back unchanged. */
    private final List<Tag> kept;

    public CraftingRuns() {
        kept = List.of();
    }

    private CraftingRuns(KeptList<JobRun> saved) {
        for (JobRun run : saved.known()) {
            runs.put(run.job(), run);
        }
        kept = saved.kept();
    }

    public static CraftingRuns get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    void put(JobRun run) {
        runs.put(run.job(), run);
        setDirty();
    }

    public @Nullable JobRun get(UUID job) {
        return runs.get(job);
    }

    void remove(UUID job) {
        if (runs.remove(job) != null) {
            setDirty();
        }
    }

    /** Every run; read-only. */
    public Collection<JobRun> all() {
        return Collections.unmodifiableCollection(runs.values());
    }

    /**
     * Wakes the CPUs of the jobs held by a provider's lock (B09 item 26) that work on {@code network} (its own, a CPU's
     * or a linked one, as last seen): a provider there let go of its lock or became free, so such a job can send again.
     * Jobs not held are left alone. Called on those events only, never per tick; server thread, allocation-free.
     */
    public static void wakeHeld(NetworkState network) {
        ServerLevel level = network.level();
        for (JobRun run : get(level).runs.values()) {
            Runnable wake = run.wake;
            if (run.lockHeld && wake != null
                    && (run.claimNetworks.contains(network) || JobDriver.network(level, run.origin()) == network)) {
                run.lockHeld = false;
                wake.run();
            }
        }
    }

    /** Progress changed: saved with the level's next save. */
    public void touched() {
        setDirty();
    }
}
