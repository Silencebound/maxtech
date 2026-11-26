package dev.silencebound.maxtech.storage.crafting.job;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.network.NetworkState;
import it.unimi.dsi.fastutil.objects.Object2LongSortedMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Who hears that a crafting job on a network ended (M7 C18): the controller's alarm module, and the Logic Controller,
 * which needs the job's item ({@link KeyWatcher}, M7L) and hears jobs that stopped with something missing
 * ({@link FailWatcher}, M7L2). Watchers subscribe per network and move along when their device changes network; the map
 * holds networks weakly, so a network dropped without its watchers unsubscribing does not stay in memory. Server thread
 * only.
 */
public final class JobEvents {
    private static final Map<NetworkState, ObjectArrayList<Runnable>> WATCHERS = new WeakHashMap<>();
    private static final Map<NetworkState, ObjectArrayList<KeyWatcher>> KEY_WATCHERS = new WeakHashMap<>();
    private static final Map<NetworkState, ObjectArrayList<FailWatcher>> FAIL_WATCHERS = new WeakHashMap<>();
    private static final Map<NetworkState, ObjectArrayList<DoneWatcher>> DONE_WATCHERS = new WeakHashMap<>();

    /**
     * Hears what a job that ended done made (M7C item 15: task boards count crafted items); it must only count and
     * return.
     */
    @FunctionalInterface
    public interface DoneWatcher {
        /** Job {@code job} ended done and made {@code amount} of {@code key} (one call per request of the job). */
        void jobDone(UUID job, StackKey key, long amount);
    }

    /** Hears which item a finished job made; it must only mark work and return. */
    @FunctionalInterface
    public interface KeyWatcher {
        /** A job for {@code key} ended done or partial. */
        void jobFinished(StackKey key);
    }

    /**
     * Hears that a job stopped with something missing (M7L2: the logic node "crafting job failed"); it must only mark
     * work and return.
     */
    @FunctionalInterface
    public interface FailWatcher {
        /**
         * A job for {@code key} stopped short of {@code missing}: each missing item and how many, in the order the plan
         * found them (empty when the job did not say). The map stays the job's; read it during the call only.
         */
        void jobFailed(StackKey key, Object2LongSortedMap<StackKey> missing);
    }

    private JobEvents() {
    }

    /** Runs {@code watcher} whenever a job of {@code network} ends done or partial, or a CPU of it ends one. */
    public static void watchFinished(NetworkState network, Runnable watcher) {
        ObjectArrayList<Runnable> list = WATCHERS.computeIfAbsent(network, n -> new ObjectArrayList<>(1));
        if (!list.contains(watcher)) {
            list.add(watcher);
        }
    }

    /** Stops running {@code watcher} for {@code network}; nothing happens if it was not watching. */
    public static void unwatchFinished(NetworkState network, Runnable watcher) {
        ObjectArrayList<Runnable> list = WATCHERS.get(network);
        if (list != null) {
            list.remove(watcher);
            if (list.isEmpty()) {
                WATCHERS.remove(network);
            }
        }
    }

    /** Calls {@code watcher} with the job's item whenever a job of {@code network} ends, as {@link #watchFinished}. */
    public static void watchFinishedKeys(NetworkState network, KeyWatcher watcher) {
        ObjectArrayList<KeyWatcher> list = KEY_WATCHERS.computeIfAbsent(network, n -> new ObjectArrayList<>(1));
        if (!list.contains(watcher)) {
            list.add(watcher);
        }
    }

    /** Stops calling {@code watcher} for {@code network}; nothing happens if it was not watching. */
    public static void unwatchFinishedKeys(NetworkState network, KeyWatcher watcher) {
        ObjectArrayList<KeyWatcher> list = KEY_WATCHERS.get(network);
        if (list != null) {
            list.remove(watcher);
            if (list.isEmpty()) {
                KEY_WATCHERS.remove(network);
            }
        }
    }

    /** Calls {@code watcher} whenever a job of {@code network} stops with something missing ({@link FailWatcher}). */
    public static void watchFailed(NetworkState network, FailWatcher watcher) {
        ObjectArrayList<FailWatcher> list = FAIL_WATCHERS.computeIfAbsent(network, n -> new ObjectArrayList<>(1));
        if (!list.contains(watcher)) {
            list.add(watcher);
        }
    }

    /** Stops calling {@code watcher} for {@code network}; nothing happens if it was not watching. */
    public static void unwatchFailed(NetworkState network, FailWatcher watcher) {
        ObjectArrayList<FailWatcher> list = FAIL_WATCHERS.get(network);
        if (list != null) {
            list.remove(watcher);
            if (list.isEmpty()) {
                FAIL_WATCHERS.remove(network);
            }
        }
    }

    /** Calls {@code watcher} for every request of a job of {@code network} that ends done ({@link DoneWatcher}). */
    public static void watchDone(NetworkState network, DoneWatcher watcher) {
        ObjectArrayList<DoneWatcher> list = DONE_WATCHERS.computeIfAbsent(network, n -> new ObjectArrayList<>(1));
        if (!list.contains(watcher)) {
            list.add(watcher);
        }
    }

    /** Stops calling {@code watcher} for {@code network}; nothing happens if it was not watching. */
    public static void unwatchDone(NetworkState network, DoneWatcher watcher) {
        ObjectArrayList<DoneWatcher> list = DONE_WATCHERS.get(network);
        if (list != null) {
            list.remove(watcher);
            if (list.isEmpty()) {
                DONE_WATCHERS.remove(network);
            }
        }
    }

    /** Job {@code job} of {@code network} ended done having made {@code amount} of {@code key}. */
    static void done(NetworkState network, UUID job, StackKey key, long amount) {
        ObjectArrayList<DoneWatcher> list = DONE_WATCHERS.get(network);
        if (list != null) {
            DoneWatcher[] copy = list.toArray(new DoneWatcher[0]);
            for (DoneWatcher watcher : copy) {
                watcher.jobDone(job, key, amount);
            }
        }
    }

    /**
     * A job of {@code network} for {@code key} stopped short of {@code missing} (empty when the job did not say what):
     * it ended partial, or could not start in classic mode.
     */
    static void failed(NetworkState network, StackKey key, Object2LongSortedMap<StackKey> missing) {
        ObjectArrayList<FailWatcher> list = FAIL_WATCHERS.get(network);
        if (list != null) {
            FailWatcher[] copy = list.toArray(new FailWatcher[0]);
            for (FailWatcher watcher : copy) {
                watcher.jobFailed(key, missing);
            }
        }
    }

    static void finished(NetworkState network, StackKey key) {
        ObjectArrayList<Runnable> list = WATCHERS.get(network);
        if (list != null) {
            Runnable[] copy = list.toArray(new Runnable[0]);
            for (Runnable watcher : copy) {
                watcher.run();
            }
        }
        ObjectArrayList<KeyWatcher> keyed = KEY_WATCHERS.get(network);
        if (keyed != null) {
            KeyWatcher[] copy = keyed.toArray(new KeyWatcher[0]);
            for (KeyWatcher watcher : copy) {
                watcher.jobFinished(key);
            }
        }
    }
}
