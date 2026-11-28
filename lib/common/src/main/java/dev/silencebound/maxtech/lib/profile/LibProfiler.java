package dev.silencebound.maxtech.lib.profile;

import dev.silencebound.maxtech.lib.api.network.Network;
import dev.silencebound.maxtech.lib.api.network.NetworkGraph;
import dev.silencebound.maxtech.lib.api.network.NetworkManager;
import dev.silencebound.maxtech.lib.api.network.NetworkType;
import dev.silencebound.maxtech.lib.api.schedule.ScheduledTask;
import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import dev.silencebound.maxtech.lib.api.schedule.TaskScheduler;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.schedule.LevelTasks;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * The profiler behind {@code /maxtech_lib profile}: for a few seconds it times every task run on Lib's level schedulers
 * (by task class), every level's scheduler tick, and every network graph and network tick, then reports where the time
 * went.
 *
 * <p>
 * Outside a session nothing is collected: the scheduler's run hook ({@link TaskScheduler#setRunListener}) is null,
 * which costs each task run one static read, and the end-of-tick check here is one static read per server tick. During
 * a session each task run reads the clock twice and adds to its class's tally, found in an identity map (a tally is
 * allocated once per task class); at the end of each server tick the scheduler and network tick times Lib already keeps
 * while profiling ({@link LevelTasks#acquireProfiling}, {@link NetworkGraph#acquireProfiling}) are summed. The report
 * is built once at the end. Server thread only.
 */
public final class LibProfiler implements TaskScheduler.RunListener {
    /** Seconds a session runs when the command names none. */
    public static final int DEFAULT_SECONDS = 10;
    /** Longest session in seconds. */
    public static final int MAX_SECONDS = 300;
    /** Rows in the list of the most expensive kinds. */
    public static final int TOP_KINDS = 10;
    /** Rows in the list of the largest networks. */
    public static final int TOP_NETWORKS = 5;
    /** Rows in the list of the mods with the most scheduler time. */
    public static final int TOP_OWNERS = 5;
    /** Ticks between drops of dead networks from the per-network tally. */
    private static final int PRUNE_EVERY = 100;

    private static @Nullable LibProfiler running;
    private static boolean registered;

    private final long startTick;
    private final long endTick;
    private final Consumer<Report> done;
    private final List<NetworkType> types = NetworkType.types();
    private final long[] typeNanos = new long[types.size()];
    private final long[] typeTicks = new long[types.size()];
    private final Reference2ObjectOpenHashMap<Class<?>, Tally> tasks = new Reference2ObjectOpenHashMap<>(64);
    private final Reference2LongOpenHashMap<Network> networkNanos = new Reference2LongOpenHashMap<>(256);
    private long schedulerNanos;
    private long runs;
    private int samples;

    /** What one kind of work cost; {@code kind} tells a task class from a network type. */
    public record KindRow(Kind kind, String name, String owner, long nanos, long runs) {
    }

    /** Whether a {@link KindRow} is a scheduler task class or a network type. */
    public enum Kind {
        TASK, NETWORK
    }

    /** The scheduler time of every task class of one mod together. */
    public record OwnerRow(String owner, long nanos, long runs) {
    }

    /** One network: its type, where one of its nodes is, its size and the time its ticks took. */
    public record NetworkRow(String type, String dimension, BlockPos pos, int nodes, long nanos) {
    }

    /**
     * A finished session over {@code ticks} server ticks: the time of every level's scheduler ticks and network ticks,
     * task runs, live networks at the end, scheduler time by mod, the most expensive kinds (costliest first) and the
     * largest networks (largest first).
     */
    public record Report(int ticks, long schedulerNanos, long networkNanos, long runs, int networks,
            List<OwnerRow> owners, List<KindRow> kinds, List<NetworkRow> largest) {
        public Report {
            owners = List.copyOf(owners);
            kinds = List.copyOf(kinds);
            largest = List.copyOf(largest);
        }

        /** {@code nanos} spread over the session's ticks, in microseconds per tick. */
        public double microsPerTick(long nanos) {
            return nanos / 1000.0 / Math.max(1, ticks);
        }

        /** The row of the task or network type named {@code name}, if it made the list. */
        public Optional<KindRow> kind(String name) {
            for (KindRow row : kinds) {
                if (row.name().equals(name)) {
                    return Optional.of(row);
                }
            }
            return Optional.empty();
        }
    }

    /** One task class's time and runs; its names are worked out once, when the class is first seen. */
    private static final class Tally {
        final String name;
        final String owner;
        long nanos;
        long runs;

        Tally(Class<?> type) {
            this.name = taskName(type);
            this.owner = ownerOf(type);
        }
    }

    private LibProfiler(long startTick, long endTick, Consumer<Report> done) {
        this.startTick = startTick;
        this.endTick = endTick;
        this.done = done;
    }

    /** Registers the end-of-tick hook and the reset on server stop; once, from Lib's init. */
    public static void bootstrap() {
        if (registered) {
            return;
        }
        registered = true;
        Platform.get().lifecycle().onServerTickEnd(LibProfiler::tickEnd);
        Platform.get().lifecycle().onServerStopped(server -> {
            LibProfiler session = running;
            if (session != null) {
                session.switchOff();
            }
        });
    }

    /** Whether a session runs. */
    public static boolean isRunning() {
        return running != null;
    }

    /**
     * Starts a session of {@code ticks} server ticks that hands its report to {@code done} at the end. Returns false,
     * changing nothing, while another session runs.
     */
    public static boolean start(MinecraftServer server, int ticks, Consumer<Report> done) {
        if (running != null) {
            return false;
        }
        long now = server.getTickCount();
        LibProfiler session = new LibProfiler(now, now + Math.max(1, ticks), done);
        LevelTasks.acquireProfiling();
        NetworkGraph.acquireProfiling();
        running = session;
        TaskScheduler.setRunListener(session);
        return true;
    }

    /**
     * Ends the running session now and hands its report to the callback given at its start; empty when none runs.
     */
    public static Optional<Report> stop(MinecraftServer server) {
        LibProfiler session = running;
        if (session == null) {
            return Optional.empty();
        }
        session.switchOff();
        Report report = session.report(server, (int) Math.max(1, server.getTickCount() - session.startTick));
        session.done.accept(report);
        return Optional.of(report);
    }

    @Override
    public void ran(TaskGroup group, ScheduledTask task, long nanos) {
        Class<?> type = task.getClass();
        Tally tally = tasks.get(type);
        if (tally == null) {
            tally = new Tally(type);
            tasks.put(type, tally);
        }
        tally.nanos += nanos;
        tally.runs++;
        runs++;
    }

    private void switchOff() {
        TaskScheduler.setRunListener(null);
        running = null;
        LevelTasks.releaseProfiling();
        NetworkGraph.releaseProfiling();
    }

    private static void tickEnd(MinecraftServer server) {
        LibProfiler session = running;
        if (session == null) {
            return;
        }
        session.sample(server);
        if (server.getTickCount() >= session.endTick) {
            stop(server);
        }
    }

    /** Adds this tick's scheduler and network times, which Lib keeps while profiling. */
    private void sample(MinecraftServer server) {
        if (++samples % PRUNE_EVERY == 0) {
            networkNanos.keySet().removeIf(network -> !network.isAlive());
        }
        for (ServerLevel level : server.getAllLevels()) {
            long nanos = LevelTasks.lastTickNanos(level);
            if (nanos > 0) {
                schedulerNanos += nanos;
            }
            NetworkManager manager = NetworkManager.get(level);
            for (int t = 0; t < types.size(); t++) {
                NetworkGraph graph = manager.existingGraph(types.get(t));
                if (graph == null) {
                    continue;
                }
                long graphNanos = graph.lastTickNanos();
                if (graphNanos > 0) {
                    typeNanos[t] += graphNanos;
                    typeTicks[t]++;
                }
                List<Network> networks = graph.networks();
                for (int i = 0; i < networks.size(); i++) {
                    Network network = networks.get(i);
                    long networkTick = network.lastTickNanos();
                    if (networkTick > 0 && !network.isPaused() && !network.isSettling()) {
                        networkNanos.addTo(network, networkTick);
                    }
                }
            }
        }
    }

    private Report report(MinecraftServer server, int ticks) {
        Object2ObjectLinkedOpenHashMap<String, long[]> owners = new Object2ObjectLinkedOpenHashMap<>();
        List<KindRow> kinds = new ArrayList<>();
        for (Tally tally : tasks.values()) {
            kinds.add(new KindRow(Kind.TASK, tally.name, tally.owner, tally.nanos, tally.runs));
            long[] sums = owners.computeIfAbsent(tally.owner, owner -> new long[2]);
            sums[0] += tally.nanos;
            sums[1] += tally.runs;
        }
        long networkTotal = 0;
        for (int t = 0; t < types.size(); t++) {
            networkTotal += typeNanos[t];
            if (typeTicks[t] > 0) {
                NetworkType type = types.get(t);
                kinds.add(new KindRow(Kind.NETWORK, type.id().toString(), type.id().getNamespace(), typeNanos[t],
                        typeTicks[t]));
            }
        }
        kinds.sort(Comparator.comparingLong(KindRow::nanos).reversed());
        List<OwnerRow> ownerRows = new ArrayList<>();
        owners.forEach((owner, sums) -> ownerRows.add(new OwnerRow(owner, sums[0], sums[1])));
        ownerRows.sort(Comparator.comparingLong(OwnerRow::nanos).reversed());

        List<NetworkRow> largest = new ArrayList<>();
        int count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            NetworkManager manager = NetworkManager.get(level);
            String dimension = level.dimension().identifier().toString();
            for (NetworkType type : types) {
                NetworkGraph graph = manager.existingGraph(type);
                if (graph == null) {
                    continue;
                }
                for (Network network : graph.networks()) {
                    if (!network.isAlive() || network.size() == 0) {
                        continue;
                    }
                    count++;
                    LongIterator nodes = network.nodes().iterator();
                    BlockPos pos = nodes.hasNext() ? BlockPos.of(nodes.nextLong()) : BlockPos.ZERO;
                    largest.add(new NetworkRow(type.id().toString(), dimension, pos, network.size(),
                            networkNanos.getLong(network)));
                }
            }
        }
        largest.sort(Comparator.comparingInt(NetworkRow::nodes).reversed());
        return new Report(ticks, schedulerNanos, networkTotal, runs, count,
                ownerRows.subList(0, Math.min(TOP_OWNERS, ownerRows.size())),
                kinds.subList(0, Math.min(TOP_KINDS, kinds.size())),
                largest.subList(0, Math.min(TOP_NETWORKS, largest.size())));
    }

    /**
     * A task class's name without its package, nested names joined with dots: {@code LibTransferPort.Task}. A lambda or
     * hidden class keeps the part before its {@code $$} suffix, so all its instances share one row.
     */
    static String taskName(Class<?> type) {
        String name = type.getName();
        int hidden = name.indexOf("$$");
        if (hidden >= 0) {
            name = name.substring(0, hidden);
        }
        String packageName = type.getPackageName();
        if (!packageName.isEmpty() && name.startsWith(packageName + ".")) {
            name = name.substring(packageName.length() + 1);
        }
        return name.replace('$', '.');
    }

    /**
     * The mod a task class probably belongs to, from its package: {@code dev.silencebound.maxtech.<mod>} is
     * {@code maxtech_<mod>}; anything else shows its first three package segments.
     */
    static String ownerOf(Class<?> type) {
        String packageName = type.getPackageName();
        String ours = "dev.silencebound.maxtech.";
        if (packageName.startsWith(ours)) {
            String rest = packageName.substring(ours.length());
            int dot = rest.indexOf('.');
            return "maxtech_" + (dot < 0 ? rest : rest.substring(0, dot));
        }
        int end = -1;
        for (int i = 0; i < 3; i++) {
            int next = packageName.indexOf('.', end + 1);
            if (next < 0) {
                return packageName.isEmpty() ? "?" : packageName;
            }
            end = next;
        }
        return packageName.substring(0, end);
    }
}
