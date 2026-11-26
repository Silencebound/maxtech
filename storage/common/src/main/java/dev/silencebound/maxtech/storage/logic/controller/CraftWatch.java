package dev.silencebound.maxtech.storage.logic.controller;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.server.level.ServerLevel;

/**
 * Tells Logic Controllers whose graphs read the crafting status (M7L2 batch 2) that a crafting job in their level was
 * submitted, started or ended, which is also when a CPU becomes busy or free. One {@link JobQueue#listen} for all
 * controllers; a job change in a level without listeners costs one map lookup. Listeners only mark work and wake their
 * task; they read the status themselves. Server thread only.
 */
public final class CraftWatch {
    private static final Reference2ObjectOpenHashMap<ServerLevel, ObjectArrayList<Listener>> LEVELS = new Reference2ObjectOpenHashMap<>();

    /** Told of each job change in its level; it updates its own count of the job and wakes its task. */
    public interface Listener {
        void jobChanged(ServerLevel level, CraftingJob job);

        /** What automation may take of the free CPUs changed without a job changing (craft limits edited). */
        void cpusChanged(ServerLevel level);
    }

    private CraftWatch() {
    }

    /** Hooks into the job queue; called once from {@link LogicContent#init()}. */
    static void init() {
        JobQueue.listen(CraftWatch::jobChanged);
        JobQueue.listenCpus(CraftWatch::cpusChanged);
        Platform.get().lifecycle().onServerStopped(server -> LEVELS.clear());
    }

    /** Starts telling {@code listener} about jobs in {@code level}; a listener already added is not added twice. */
    // Listeners are compared by identity.
    @SuppressWarnings("ReferenceEquality")
    public static void listen(ServerLevel level, Listener listener) {
        ObjectArrayList<Listener> listeners = LEVELS.get(level);
        if (listeners == null) {
            listeners = new ObjectArrayList<>(1);
            LEVELS.put(level, listeners);
        }
        for (int i = 0; i < listeners.size(); i++) {
            if (listeners.get(i) == listener) {
                return;
            }
        }
        listeners.add(listener);
    }

    /** Stops telling {@code listener}; the level is forgotten with its last listener. */
    // Listeners are compared by identity.
    @SuppressWarnings("ReferenceEquality")
    public static void unlisten(ServerLevel level, Listener listener) {
        ObjectArrayList<Listener> listeners = LEVELS.get(level);
        if (listeners == null) {
            return;
        }
        for (int i = 0; i < listeners.size(); i++) {
            if (listeners.get(i) == listener) {
                listeners.remove(i);
                break;
            }
        }
        if (listeners.isEmpty()) {
            LEVELS.remove(level);
        }
    }

    private static void cpusChanged(ServerLevel level) {
        ObjectArrayList<Listener> listeners = LEVELS.get(level);
        if (listeners == null) {
            return;
        }
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).cpusChanged(level);
        }
    }

    private static void jobChanged(ServerLevel level, CraftingJob job) {
        ObjectArrayList<Listener> listeners = LEVELS.get(level);
        if (listeners == null) {
            return;
        }
        // A listener only updates its own count and wakes its task, so none unlistens while this walks the list.
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).jobChanged(level, job);
        }
    }
}
