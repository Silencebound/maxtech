package dev.silencebound.maxtech.storage.crafting.job;

import dev.silencebound.maxtech.storage.save.KeptList;
import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.storage.MaxtechStorage;
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
 * The crafting jobs of one level, saved with it (M7a): planned, running and partial jobs survive restarts; finished
 * ones are dropped by {@link #remove}. Keyed by job id in submission order, which is the order CPUs take them in (area
 * A adds player-first ordering on top). Server thread only.
 */
public final class CraftingJobs extends SavedData {
    /** A job naming an item of a removed mod is kept as saved ({@link KeptList}) instead of losing every job. */
    public static final Codec<CraftingJobs> CODEC = KeptList.codec(CraftingJob.CODEC).xmap(CraftingJobs::new,
            jobs -> KeptList.of(new ArrayList<>(jobs.jobs.values()), jobs.kept));
    public static final SavedDataType<CraftingJobs> TYPE = StorageSaveFormats
            .savedData(MaxtechStorage.id("crafting_jobs"), CraftingJobs::new, CODEC);

    private final LinkedHashMap<UUID, CraftingJob> jobs = new LinkedHashMap<>();
    /** Saved jobs that no longer load, written back unchanged. */
    private final List<Tag> kept;

    public CraftingJobs() {
        kept = List.of();
    }

    private CraftingJobs(KeptList<CraftingJob> saved) {
        for (CraftingJob job : saved.known()) {
            jobs.put(job.id(), job);
        }
        kept = saved.kept();
    }

    /** The jobs of {@code level}. */
    public static CraftingJobs get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    /** Adds or replaces {@code job}. */
    public void put(CraftingJob job) {
        jobs.put(job.id(), job);
        setDirty();
    }

    public @Nullable CraftingJob get(UUID id) {
        return jobs.get(id);
    }

    /** Moves job {@code id} to {@code state}; returns the new job, or null for an unknown id. */
    public @Nullable CraftingJob setState(UUID id, JobState state) {
        CraftingJob job = jobs.get(id);
        if (job == null) {
            return null;
        }
        CraftingJob next = job.withState(state);
        jobs.put(id, next);
        setDirty();
        return next;
    }

    public boolean remove(UUID id) {
        boolean removed = jobs.remove(id) != null;
        if (removed) {
            setDirty();
        }
        return removed;
    }

    /** Every job, in submission order; read-only. */
    public Collection<CraftingJob> all() {
        return Collections.unmodifiableCollection(jobs.values());
    }

}
