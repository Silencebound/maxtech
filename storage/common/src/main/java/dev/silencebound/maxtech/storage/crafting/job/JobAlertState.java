package dev.silencebound.maxtech.storage.crafting.job;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The jobs-tab badge state of one player (B09 item 21d): the latest {@link JobNotice} of each of their jobs that still
 * needs them (waiting or stopped), and the most urgent of those. Notices about a job that runs again or ended drop it.
 * Changes only when a notice arrives, so screens read {@link #worst} without work per frame. Pure; the client keeps
 * one.
 */
public final class JobAlertState {
    private final Object2ObjectLinkedOpenHashMap<UUID, JobNotice> jobs = new Object2ObjectLinkedOpenHashMap<>();
    private @Nullable JobNotice worst;
    private int version;

    /** Takes {@code notice} about the player's job {@code job}; returns whether the worst notice changed. */
    public boolean apply(UUID job, JobNotice notice) {
        if (notice.gone() || notice.alert() == JobNotice.Alert.NONE) {
            jobs.remove(job);
        } else {
            jobs.put(job, notice);
        }
        JobNotice before = worst;
        worst = null;
        for (JobNotice each : jobs.values()) {
            if (worst == null || each.alert().rank() > worst.alert().rank()) {
                worst = each;
            }
        }
        if (before != worst) {
            version++;
            return true;
        }
        return false;
    }

    /** Forgets every job (the player left the server). */
    public void clear() {
        jobs.clear();
        if (worst != null) {
            worst = null;
            version++;
        }
    }

    /** The most urgent notice of a job that needs the player, the first one among equals; null for none. */
    public @Nullable JobNotice worst() {
        return worst;
    }

    /** The job {@link #worst} is about; null for none. */
    public @Nullable UUID worstJob() {
        JobNotice current = worst;
        if (current == null) {
            return null;
        }
        for (var entry : jobs.object2ObjectEntrySet()) {
            if (entry.getValue() == current) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** How many jobs need the player. */
    public int size() {
        return jobs.size();
    }

    /** Changes whenever {@link #worst} does. */
    public int version() {
        return version;
    }
}
