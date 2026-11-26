package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicWorker;
import dev.silencebound.maxtech.storage.part.CablePart;
import it.unimi.dsi.fastutil.longs.LongIterator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * The network profiler behind {@code /maxtech network profile} (M7C item 26): for a few seconds it times the work of
 * every storage network on the server and reports the networks, devices and logic rules that cost the most server time.
 *
 * <p>
 * While a session runs, each scheduled device task ({@link TaskAdapter}), each network's own upkeep
 * ({@link StorageNetworkLogic}'s tick and route rebuilds) and each logic rule run is timed with two
 * {@link System#nanoTime()} reads and added to a {@link ProfileTally} on the device, network or rule; nothing is
 * allocated. A device task's time also counts for its network, and a rule's time is part of its controller's. Outside a
 * session the hooks cost one static field read. The report is gathered once at the end by walking the loaded networks,
 * so networks, devices and rules that unloaded or were rebuilt during the session are missing from it. Server side
 * only; server thread only.
 */
public final class NetworkProfiler {
    /** Seconds a session runs when the command names none. */
    public static final int DEFAULT_SECONDS = 10;
    /** Longest session in seconds. */
    public static final int MAX_SECONDS = 300;

    /** Whether a session runs; read by the timing hooks. */
    static boolean on;
    /** The running or latest session; tallies from other sessions count as empty. 0 before the first. */
    static int session;
    private static long startTick;
    private static long endTick;
    private static @Nullable Consumer<Report> whenDone;
    private static boolean registered;

    /** A network's time: where it is (its route root, else any node), its owner and how many devices it has. */
    public record NetworkRow(String dimension, BlockPos pos, String owner, int devices, long nanos) {
    }

    /** A device's time: its block or part name, where it is, and how many task runs were timed. */
    public record DeviceRow(String dimension, BlockPos pos, Component name, int runs, long nanos) {
    }

    /** The summed time of every device of one kind. */
    public record TypeRow(Component name, int devices, long nanos) {
    }

    /** A logic rule's time: its controller, its number there (from 0), name, owner and timed runs. */
    public record RuleRow(String dimension, BlockPos pos, int rule, String name, String owner, int runs, long nanos) {
    }

    /**
     * A finished session: how many server ticks it lasted, the time of every network together, and the rows, the
     * costliest first.
     */
    public record Report(int ticks, long totalNanos, List<NetworkRow> networks, List<DeviceRow> devices,
            List<TypeRow> types, List<RuleRow> rules) {
        public Report {
            networks = List.copyOf(networks);
            devices = List.copyOf(devices);
            types = List.copyOf(types);
            rules = List.copyOf(rules);
        }

        /** {@code nanos} spread over the session's ticks, in microseconds per tick. */
        public double microsPerTick(long nanos) {
            return nanos / 1000.0 / Math.max(1, ticks);
        }

        /** {@code nanos} as a share of every network's time together, in percent; 0 when nothing was timed. */
        public double percent(long nanos) {
            return totalNanos <= 0 ? 0 : nanos * 100.0 / totalNanos;
        }
    }

    private NetworkProfiler() {
    }

    /** Registers the end-of-tick check and the reset on server stop; once, during mod construction. */
    public static void init() {
        if (registered) {
            return;
        }
        registered = true;
        Platform.get().lifecycle().onServerTickEnd(NetworkProfiler::tick);
        Platform.get().lifecycle().onServerStopped(server -> {
            on = false;
            whenDone = null;
        });
    }

    /** Whether a session runs; the timing hooks check this before reading the clock. */
    public static boolean on() {
        return on;
    }

    /**
     * Starts a session of {@code ticks} server ticks that hands its report to {@code done} at the end. Returns false,
     * changing nothing, while another session runs.
     */
    public static boolean start(MinecraftServer server, int ticks, Consumer<Report> done) {
        if (on) {
            return false;
        }
        session++;
        on = true;
        startTick = server.getTickCount();
        endTick = startTick + Math.max(1, ticks);
        whenDone = done;
        return true;
    }

    /**
     * Ends the running session now and hands its report to the callback given at its start; empty when none runs.
     */
    public static Optional<Report> stop(MinecraftServer server) {
        if (!on) {
            return Optional.empty();
        }
        on = false;
        Report report = gather(server, (int) Math.max(1, server.getTickCount() - startTick));
        Consumer<Report> done = whenDone;
        whenDone = null;
        if (done != null) {
            done.accept(report);
        }
        return Optional.of(report);
    }

    private static void tick(MinecraftServer server) {
        if (on && server.getTickCount() >= endTick) {
            stop(server);
        }
    }

    /** The report of the running or latest session over {@code ticks} ticks, from the loaded networks. */
    static Report gather(MinecraftServer server, int ticks) {
        List<NetworkRow> networks = new ArrayList<>();
        List<DeviceRow> devices = new ArrayList<>();
        List<RuleRow> rules = new ArrayList<>();
        Map<String, TypeRow> types = new LinkedHashMap<>();
        long total = 0;
        for (ServerLevel level : server.getAllLevels()) {
            StorageNetworkLogic logic = StorageNetworks.logic(level, StorageNetworks.ID);
            if (logic == null) {
                continue;
            }
            String dimension = level.dimension().identifier().toString();
            for (NetworkState network : logic.networks()) {
                long nanos = network.profile.nanos();
                if (nanos <= 0) {
                    continue;
                }
                total += nanos;
                String owner = network.owner().isOwned() ? network.owner().ownerName() : "";
                networks.add(new NetworkRow(dimension, where(network), owner, network.deviceCount(), nanos));
                for (NetworkDevice device : network.devices()) {
                    addDevice(device, dimension, devices, types);
                    if (device instanceof LogicControllerBlockEntity controller && !controller.isRemoved()) {
                        addRules(controller, dimension, rules);
                    }
                }
            }
        }
        networks.sort(Comparator.comparingLong(NetworkRow::nanos).reversed());
        devices.sort(Comparator.comparingLong(DeviceRow::nanos).reversed());
        rules.sort(Comparator.comparingLong(RuleRow::nanos).reversed());
        List<TypeRow> sortedTypes = new ArrayList<>(types.values());
        sortedTypes.sort(Comparator.comparingLong(TypeRow::nanos).reversed());
        return new Report(ticks, total, networks, devices, sortedTypes, rules);
    }

    private static void addDevice(NetworkDevice device, String dimension, List<DeviceRow> devices,
            Map<String, TypeRow> types) {
        ProfileTally tally = device.deviceLink().profile;
        long nanos = tally.nanos();
        if (nanos <= 0) {
            return;
        }
        Component name = name(device);
        devices.add(new DeviceRow(dimension, BlockPos.of(device.blockPos()), name, tally.runs(), nanos));
        String key = name.getString();
        TypeRow type = types.get(key);
        types.put(key,
                type == null
                        ? new TypeRow(name, 1, nanos)
                        : new TypeRow(type.name(), type.devices() + 1, type.nanos() + nanos));
    }

    private static void addRules(LogicControllerBlockEntity controller, String dimension, List<RuleRow> rules) {
        LogicWorker worker = controller.worker();
        String owner = controller.ownerRecord().isOwned() ? controller.ownerRecord().ownerName() : "";
        BlockPos pos = controller.getBlockPos().immutable();
        for (int i = 0; i < worker.size(); i++) {
            ProfileTally tally = worker.profile(i);
            if (tally.nanos() > 0) {
                rules.add(new RuleRow(dimension, pos, i, worker.name(i), owner, tally.runs(), tally.nanos()));
            }
        }
    }

    /** The block's or part's name. */
    private static Component name(NetworkDevice device) {
        if (device instanceof CablePart part) {
            return new ItemStack(part.type().item()).getHoverName();
        }
        if (device instanceof BlockEntity entity) {
            return entity.getBlockState().getBlock().getName();
        }
        return Component.literal(device.getClass().getSimpleName());
    }

    /** The network's route root, else any of its nodes. */
    public static BlockPos where(NetworkState network) {
        long root = network.routeRoot();
        if (root != Long.MIN_VALUE) {
            return BlockPos.of(root);
        }
        LongIterator nodes = network.nodes().iterator();
        return nodes.hasNext() ? BlockPos.of(nodes.nextLong()) : BlockPos.ZERO;
    }
}
