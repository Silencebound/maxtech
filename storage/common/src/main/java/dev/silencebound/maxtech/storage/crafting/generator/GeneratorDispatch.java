package dev.silencebound.maxtech.storage.crafting.generator;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.crafting.pattern.GeneratorSource;
import dev.silencebound.maxtech.storage.network.NetworkState;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * How the executor reaches generator providers (M7C item 11): an order for a key goes to one generator for it on the
 * network or, if none takes it, on a linked network (C30). An idle generator is preferred, so several generators for
 * the same key share the work across orders. The level's loaded generators are also kept here, so a job that ends
 * withdraws its orders from every generator, even one a network split or a lost CPU took out of reach. Condition
 * switches in request mode (B09 item 14) are generator sources too and go through the same dispatch. Called by the
 * executor and the job queue, never per tick; server thread.
 */
public final class GeneratorDispatch {
    private static final Map<ServerLevel, ReferenceOpenHashSet<GeneratorSource>> LOADED = new WeakHashMap<>();

    private GeneratorDispatch() {
    }

    /**
     * Lets job ends in {@code level} reach {@code generator} ({@link #cancel}): the generator provider while it is
     * loaded, a condition switch in request mode while it holds orders (B09 item 14).
     */
    public static void register(ServerLevel level, GeneratorSource generator) {
        ReferenceOpenHashSet<GeneratorSource> set = LOADED.computeIfAbsent(level, l -> new ReferenceOpenHashSet<>());
        dropGone(set);
        set.add(generator);
    }

    /** Whether job ends in {@code level} reach {@code generator} now; for tests and diagnostics. */
    public static boolean registered(ServerLevel level, GeneratorSource generator) {
        ReferenceOpenHashSet<GeneratorSource> set = LOADED.get(level);
        return set != null && set.contains(generator);
    }

    /** Drops sources whose device is gone without unregistering (a part's cable unloaded with it). */
    private static void dropGone(ReferenceOpenHashSet<GeneratorSource> set) {
        if (!set.isEmpty()) {
            set.removeIf(GeneratorSource::gone);
        }
    }

    /** Job ends no longer reach {@code generator}; nothing happens for one never registered. */
    public static void unregister(ServerLevel level, GeneratorSource generator) {
        ReferenceOpenHashSet<GeneratorSource> set = LOADED.get(level);
        if (set != null) {
            set.remove(generator);
        }
    }

    /**
     * Withdraws job {@code job}'s orders from every loaded generator in {@code level}: the job ended (done, partial,
     * cancelled), so their signals go off at once where nothing else is owed. Linear in the level's generators.
     */
    public static void cancel(ServerLevel level, UUID job) {
        ReferenceOpenHashSet<GeneratorSource> set = LOADED.get(level);
        if (set == null || set.isEmpty()) {
            return;
        }
        dropGone(set);
        // Copied: a cancel may change block states, which must not touch the set, but stay safe if it ever does.
        ObjectArrayList<GeneratorSource> generators = new ObjectArrayList<>(set);
        for (int i = 0; i < generators.size(); i++) {
            generators.get(i).cancel(job);
        }
    }

    /** Orders {@code amount} of {@code key} for job {@code job}; returns what a generator took on, 0 when none. */
    public static long order(NetworkState network, StackKey key, UUID job, long amount) {
        if (amount <= 0) {
            return 0;
        }
        long taken = orderOn(network, key, job, amount);
        List<NetworkState> linked = taken > 0 ? List.of() : network.linkedNetworks();
        for (int i = 0; i < linked.size() && taken == 0; i++) {
            taken = orderOn(linked.get(i), key, job, amount);
        }
        return taken;
    }

    private static long orderOn(NetworkState network, StackKey key, UUID job, long amount) {
        GeneratorSource pick = pick(network.patterns().generatorsFor(key));
        return pick == null ? 0 : pick.order(job, amount);
    }

    /** The first idle generator that can work, else the first busy one, else null. */
    private static @Nullable GeneratorSource pick(List<GeneratorSource> generators) {
        GeneratorSource busy = null;
        for (int i = 0; i < generators.size(); i++) {
            GeneratorSource generator = generators.get(i);
            if (!generator.canGenerate()) {
                continue;
            }
            if (!generator.busy()) {
                return generator;
            }
            if (busy == null) {
                busy = generator;
            }
        }
        return busy;
    }

    /** What the generators for {@code key} on the network and linked ones still owe job {@code job}. */
    public static long owed(NetworkState network, StackKey key, UUID job) {
        long total = owed(network.patterns().generatorsFor(key), job);
        List<NetworkState> linked = network.linkedNetworks();
        for (int i = 0; i < linked.size(); i++) {
            total += owed(linked.get(i).patterns().generatorsFor(key), job);
        }
        return total;
    }

    private static long owed(List<GeneratorSource> generators, UUID job) {
        long total = 0;
        for (int i = 0; i < generators.size(); i++) {
            total += generators.get(i).owed(job);
        }
        return total;
    }

    /** Whether some generator for {@code key} is on the network or a linked one, whether or not it can work now. */
    public static boolean present(NetworkState network, StackKey key) {
        if (!network.patterns().generatorsFor(key).isEmpty()) {
            return true;
        }
        List<NetworkState> linked = network.linkedNetworks();
        for (int i = 0; i < linked.size(); i++) {
            if (!linked.get(i).patterns().generatorsFor(key).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The fastest measured rate of the network's own generators for {@code key}, as ticks per 1,000 units, or 0 when
     * none has measured one yet.
     */
    public static long ticksPerThousand(NetworkState network, StackKey key) {
        List<GeneratorSource> generators = network.patterns().generatorsFor(key);
        long best = 0;
        for (int i = 0; i < generators.size(); i++) {
            long ticks = generators.get(i).ticksPerThousand();
            if (ticks > 0 && (best == 0 || ticks < best)) {
                best = ticks;
            }
        }
        return best;
    }
}
