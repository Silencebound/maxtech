package dev.silencebound.maxtech.storage.analyzer;

import dev.silencebound.maxtech.storage.logic.NetworkRef;
import dev.silencebound.maxtech.storage.api.StorageDevice;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.cluster.ClusterNeeds;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.display.production.ProductionFeed;
import dev.silencebound.maxtech.storage.display.production.ProductionStats;
import dev.silencebound.maxtech.storage.network.NetworkHistory;
import dev.silencebound.maxtech.storage.network.NetworkLabels;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.RemoteLinkNode;
import dev.silencebound.maxtech.storage.network.TeamCaps;
import dev.silencebound.maxtech.storage.subnet.SubnetBlockEntity;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.jspecify.annotations.Nullable;

/**
 * Everything the controller's Network tab shows of one network (PRD 7.2 Diagnostics): size and devices by role, power
 * (with the parent's store for a subnet), the controller's limits, team caps, zones with their busiest segment, the
 * busiest segments overall, subnets below and the parent above, antenna links, warnings, the devices' labels (M7L), and
 * the telemetry recorder's last ten minutes, the network's name (M7L2 row 13; empty without a controller) and what the
 * controller's and the CPUs' clusters lack (M7C item 22; empty without a controller), and the hubs with their channel
 * use (M7N item 2). Built on the server from cached network state; lists are capped so a payload stays small. Compared
 * with {@code equals} to send only changes. Immutable.
 */
public record NetworkOverview(Summary summary, List<Zone> zones, List<Hotspot> hotspots, List<Subnet> subnets,
        List<Antenna> antennas, List<Warning> warnings, List<NetworkLabels.Entry> labels,
        List<NetworkNotices.Notice> log, History history, String name, List<ClusterNeeds.Need> clusters,
        List<Hub> hubs) {
    /** Most hubs listed (the fullest first). */
    public static final int MAX_HUBS = 16;
    /** Most zones listed (the largest first). */
    public static final int MAX_ZONES = 16;
    /** Most labels listed (by text). */
    public static final int MAX_LABELS = 32;
    /** Newest lines of the network's logic log listed (M7L). */
    public static final int MAX_LOG = 12;
    /** Busiest segments listed. */
    public static final int MAX_HOTSPOTS = 6;
    /** Most subnets and antenna links listed. */
    public static final int MAX_LINKS = 16;
    /** Load from which a segment counts as saturated, as for smart cables. */
    public static final int SATURATED = 90;
    /** The span of the load graph: ten minutes. */
    public static final int HISTORY_TICKS = 12_000;
    /** Most samples sent for the graph. */
    public static final int MAX_SAMPLES = 120;
    /** Longest name of a production line on the wire (a label is 32 characters, an item id fewer than 100). */
    private static final int PRODUCTION_NAME = 128;
    private static final int NONE = -1;

    public static final StreamCodec<FriendlyByteBuf, NetworkOverview> STREAM_CODEC = StreamCodec
            .of(NetworkOverview::write, NetworkOverview::read);

    /**
     * The network's numbers.
     *
     * @param roles
     *            device count per {@link StorageDevice.Role} ordinal
     * @param parentStoredFe
     *            the parent network's stored FE for a subnet, -1 otherwise
     * @param subnetMode
     *            this network's mode as a subnet ({@code SubnetMode.id()}), -1 when it is no subnet
     * @param parentId
     *            the parent network's id, -1 when none
     * @param teamDevices
     *            -1 when no team cap counts this network
     */
    public record Summary(int networkId, StorageNetwork.Status status, boolean powered, boolean adHoc, int nodes,
            int devices, int zoneCount, List<Integer> roles, long storedFe, long capacityFe, long feIn, long feOut,
            long parentStoredFe, long parentCapacityFe, int subnetMode, int parentId, int limitedDevices,
            int deviceLimit, int rootLinksUsed, int rootLinks, int batchPercent, int controllerBlocks, int sizeCap,
            long operations, int rebuilds, int patches, int offlineCells, int teamDevices, int teamZones,
            long teamBytes, int capDevices, int capZones, long capBytes) {
    }

    /** A zone: its index, nodes and its busiest used segment's load. */
    public record Zone(int zone, int nodes, int peakLoad) {
    }

    /** A busy segment: its child node, kind, rate and load. */
    public record Hotspot(long pos, RouteTrace.Kind kind, long rate, int load) {
    }

    /**
     * A subnet controller hanging off this network: its block, mode, and the subnet's id (-1), devices and status
     * (null) when the subnet side is in a network.
     */
    public record Subnet(long pos, int mode, int networkId, int devices, StorageNetwork.@Nullable Status status) {
    }

    /** An antenna link from {@code pos} to {@code partner}; {@code load} is -1 when no route uses it (a spare link). */
    public record Antenna(long pos, long partner, long rate, int load) {
    }

    /** A hub: its block, the devices counting against it and its budget; 0 without channel limits (no budget). */
    public record Hub(long pos, int used, int capacity) {
    }

    /** Something a player should look at; {@code pos} is where, or {@code Long.MIN_VALUE} for the whole network. */
    public record Warning(WarningKind kind, long count, long pos) {
    }

    /** Warning kinds, each with its own line. */
    public enum WarningKind {
        CONTROLLER_STANDBY, PAUSED, NO_POWER, DEVICES_REFUSED, OFFLINE_CELLS, BYTES_CAPPED, SATURATED_SEGMENT, OVER_CAPACITY, QUARANTINED, LOAD_SHEDDING, CHANNELS_FULL
    }

    /**
     * The telemetry recorder's samples, oldest first, at most ten minutes; empty without a recorder.
     *
     * @param operations
     *            operations per sample
     * @param energyIn
     *            FE in per sample
     */
    public record History(boolean recorder, int sampleTicks, LongList operations, LongList energyIn,
            Production production) {
        public static final History NONE = new History(false, 0, new LongArrayList(), new LongArrayList(),
                Production.NONE);
    }

    /**
     * One line of the production section (M7C item 18): a group or an item and its items per minute. Kinds are
     * {@link #CPU}, {@link #STOCK_KEEPER} and {@link #POOL} (name: the label, or the position without one), then
     * {@link #TOP_IN} and {@link #TOP_OUT} (name: the item's registry id).
     */
    public record ProductionLine(int kind, String name, long perMinute) {
        public static final int CPU = 0;
        public static final int STOCK_KEEPER = 1;
        public static final int POOL = 2;
        public static final int TOP_IN = 3;
        public static final int TOP_OUT = 4;
    }

    /** The network's production stats now: items per minute in and out in all, and the lines, at most 32. */
    public record Production(long totalIn, long totalOut, List<ProductionLine> lines) {
        public static final int MAX_LINES = 32;
        public static final Production NONE = new Production(0, 0, List.of());
    }

    /**
     * The cluster facts only a controller core knows, with the lines of its and its network's CPU clusters
     * ({@code needs}); {@link #NO_CLUSTER} for none.
     */
    public record Cluster(int blocks, int cap, boolean overCapacity, List<ClusterNeeds.Need> needs) {
        public static final Cluster NO_CLUSTER = new Cluster(0, 0, false);

        public Cluster(int blocks, int cap, boolean overCapacity) {
            this(blocks, cap, overCapacity, List.of());
        }
    }

    /** The overview of {@code network}. Walks its devices and used segments once; for an open screen's cadence. */
    public static NetworkOverview of(NetworkState network, Cluster cluster) {
        List<Integer> roles = new ArrayList<>();
        for (StorageDevice.Role role : StorageDevice.Role.values()) {
            roles.add(network.deviceCount(role));
        }
        int subnetMode = NONE;
        int parentId = NONE;
        long parentStored = NONE;
        long parentCapacity = NONE;
        List<Subnet> subnets = new ArrayList<>();
        List<Antenna> antennas = new ArrayList<>();
        LongArrayList partners = new LongArrayList();
        if (network.controllerDevice() instanceof SubnetBlockEntity self) {
            subnetMode = self.mode().id();
            NetworkState parent = self.parent();
            if (parent != null && parent != network) {
                parentId = parent.id();
                parentStored = parent.storedEnergy();
                parentCapacity = parent.energyCapacity();
            }
        }
        for (NetworkDevice device : network.devices()) {
            if (device instanceof SubnetBlockEntity.Uplink uplink && subnets.size() < MAX_LINKS) {
                SubnetBlockEntity block = uplink.subnet();
                NetworkState child = block.network();
                subnets.add(
                        new Subnet(block.getBlockPos().asLong(), block.mode().id(), child == null ? NONE : child.id(),
                                child == null ? 0 : child.deviceCount(), child == null ? null : child.status()));
            }
            if (device instanceof RemoteLinkNode remote && antennas.size() < MAX_LINKS) {
                partners.clear();
                remote.remoteLinks(partners);
                long pos = device.nodePos();
                for (int i = 0; i < partners.size() && antennas.size() < MAX_LINKS; i++) {
                    long partner = partners.getLong(i);
                    // Each pair once: from its lower end, unless the far end is no member here.
                    if (partner < pos && network.nodes().contains(partner)) {
                        continue;
                    }
                    antennas.add(new Antenna(pos, partner, remote.remoteLinkWidth(partner),
                            linkLoad(network, pos, partner)));
                }
            }
        }
        subnets.sort((a, b) -> Long.compare(a.pos(), b.pos()));
        antennas.sort((a, b) -> Long.compare(a.pos(), b.pos()));

        Int2IntOpenHashMap peaks = new Int2IntOpenHashMap();
        List<Hotspot> hotspots = new ArrayList<>();
        network.forEachUsedSegment(pos -> {
            int load = network.segmentLoad(pos);
            if (load <= 0) {
                return;
            }
            int zone = network.zone(pos);
            if (zone >= 0 && load > peaks.get(zone)) {
                peaks.put(zone, load);
            }
            long parent = network.routeParent(pos);
            if (parent != Long.MIN_VALUE) {
                offer(hotspots,
                        new Hotspot(pos, RouteTrace.kind(network, pos, parent), network.segmentCapacity(pos), load));
            }
        });
        List<Zone> zones = new ArrayList<>();
        for (int zone = 0; zone < network.zoneSlots(); zone++) {
            int size = network.zoneSize(zone);
            if (size > 0) {
                zones.add(new Zone(zone, size, peaks.get(zone)));
            }
        }
        zones.sort((a, b) -> a.nodes() != b.nodes()
                ? Integer.compare(b.nodes(), a.nodes())
                : Integer.compare(a.zone(), b.zone()));
        if (zones.size() > MAX_ZONES) {
            zones = new ArrayList<>(zones.subList(0, MAX_ZONES));
        }
        List<Hub> hubs = new ArrayList<>();
        boolean infinite = network.channelsInfinite();
        network.forEachHub(
                pos -> hubs.add(new Hub(pos, network.hubChannels(pos), infinite ? 0 : network.hubCapacity(pos))));
        // Fullest first (busiest without channel limits), then by position, so a full hub is always listed.
        hubs.sort((a, b) -> {
            int byLoad = infinite
                    ? Integer.compare(b.used(), a.used())
                    : Long.compare((long) b.used() * a.capacity(), (long) a.used() * b.capacity());
            return byLoad != 0 ? byLoad : Long.compare(a.pos(), b.pos());
        });
        if (hubs.size() > MAX_HUBS) {
            hubs.subList(MAX_HUBS, hubs.size()).clear();
        }

        boolean capped = network.capTeam() != null && TeamCaps.anyOn();
        Summary summary = new Summary(network.id(), network.status(), network.isPowered(), network.isAdHoc(),
                network.nodeCount(), network.deviceCount(), network.zoneCount(), roles, network.storedEnergy(),
                network.energyCapacity(), network.energyReceived(), network.energySpent(), parentStored, parentCapacity,
                subnetMode, parentId, network.limitedDevices(),
                network.deviceLimit() == Integer.MAX_VALUE ? NONE : network.deviceLimit(), network.rootLinksUsed(),
                network.rootLinks(), network.batchPercent(), cluster.blocks(), cluster.cap(), network.operations(),
                network.rebuilds(), network.patches(), network.offlineCells(),
                capped ? network.teamDevicesUsed() : NONE, capped ? network.teamZonesUsed() : NONE,
                capped ? network.teamBytesUsed() : NONE, StorageServerConfig.CAP_DEVICES_PER_TEAM.getAsInt(),
                StorageServerConfig.CAP_ZONES_PER_TEAM.getAsInt(), StorageServerConfig.CAP_BYTES_PER_TEAM.getAsLong());
        return new NetworkOverview(summary, zones, hotspots, subnets, antennas, warnings(network, hotspots, cluster),
                network.labels().entries(MAX_LABELS), network.notices().newest(MAX_LOG), history(network),
                network.name(), cluster.needs(), hubs);
    }

    /** The load of the link between two antennas, or -1 when no route runs over it. */
    private static int linkLoad(NetworkState network, long pos, long partner) {
        if (network.routeParent(pos) == partner) {
            return network.segmentLoad(pos);
        }
        if (network.routeParent(partner) == pos) {
            return network.segmentLoad(partner);
        }
        return NONE;
    }

    /** Keeps {@code hotspots} the {@link #MAX_HOTSPOTS} busiest, busiest first, ties by position. */
    private static void offer(List<Hotspot> hotspots, Hotspot candidate) {
        int at = hotspots.size();
        while (at > 0 && before(candidate, hotspots.get(at - 1))) {
            at--;
        }
        if (at < MAX_HOTSPOTS) {
            hotspots.add(at, candidate);
            if (hotspots.size() > MAX_HOTSPOTS) {
                hotspots.remove(hotspots.size() - 1);
            }
        }
    }

    private static boolean before(Hotspot a, Hotspot b) {
        return a.load() != b.load() ? a.load() > b.load() : a.pos() < b.pos();
    }

    private static List<Warning> warnings(NetworkState network, List<Hotspot> hotspots, Cluster cluster) {
        List<Warning> warnings = new ArrayList<>();
        long whole = Long.MIN_VALUE;
        switch (network.status()) {
            case PAUSED -> warnings
                    .add(new Warning(network.quarantined() ? WarningKind.QUARANTINED : WarningKind.PAUSED, 0, whole));
            case ONLINE -> {
                if (!network.isPowered()) {
                    warnings.add(new Warning(WarningKind.NO_POWER, 0, whole));
                }
            }
        }
        if (network.controllerCount() > 1) {
            long first = Long.MAX_VALUE;
            for (NetworkDevice device : network.devices()) {
                if (device.role() == StorageDevice.Role.CONTROLLER && network.isStandby(device)) {
                    first = Math.min(first, device.nodePos());
                }
            }
            warnings.add(new Warning(WarningKind.CONTROLLER_STANDBY, network.controllerCount() - 1,
                    first == Long.MAX_VALUE ? whole : first));
        }
        if (!network.refusedDevices().isEmpty()) {
            long first = Long.MAX_VALUE;
            for (NetworkDevice device : network.refusedDevices()) {
                first = Math.min(first, device.nodePos());
            }
            warnings.add(new Warning(WarningKind.DEVICES_REFUSED, network.refusedDevices().size(), first));
        }
        if (!network.offlineDevices().isEmpty()) {
            long first = Long.MAX_VALUE;
            for (NetworkDevice device : network.offlineDevices()) {
                first = Math.min(first, device.nodePos());
            }
            warnings.add(new Warning(WarningKind.CHANNELS_FULL, network.offlineDevices().size(), first));
        }
        if (network.offlineCells() > 0) {
            warnings.add(new Warning(WarningKind.OFFLINE_CELLS, network.offlineCells(), whole));
        }
        if (network.bytesCapped()) {
            warnings.add(new Warning(WarningKind.BYTES_CAPPED, network.teamBytesUsed(), whole));
        }
        for (Hotspot hotspot : hotspots) {
            if (hotspot.load() >= SATURATED) {
                warnings.add(new Warning(WarningKind.SATURATED_SEGMENT, hotspot.load(), hotspot.pos()));
            }
        }
        if (cluster.overCapacity()) {
            warnings.add(new Warning(WarningKind.OVER_CAPACITY, cluster.blocks(), whole));
        }
        if (network.shedding().level() > 0) {
            // The count is the lowest power priority paused; the line names it.
            warnings.add(new Warning(WarningKind.LOAD_SHEDDING, network.shedding().pausedFrom(), whole));
        }
        return warnings;
    }

    /** The production stats lines: groups (busiest first), then the top items in and out. */
    private static Production production(NetworkState network) {
        ProductionStats stats = ProductionStats.of(network);
        long now = network.level().getGameTime();
        List<ProductionLine> lines = new ArrayList<>();
        for (ProductionStats.Line line : stats.groups(now)) {
            if (lines.size() >= Production.MAX_LINES - 2 * ProductionStats.TOP) {
                break;
            }
            int kind = switch (line.kind()) {
                case CPU -> ProductionLine.CPU;
                case STOCK_KEEPER -> ProductionLine.STOCK_KEEPER;
                case POOL -> ProductionLine.POOL;
            };
            lines.add(new ProductionLine(kind, ProductionFeed.subject(line), line.perMinute()));
        }
        for (ProductionStats.Top top : stats.topIn(now)) {
            lines.add(new ProductionLine(ProductionLine.TOP_IN, itemId(top), top.perMinute()));
        }
        for (ProductionStats.Top top : stats.topOut(now)) {
            lines.add(new ProductionLine(ProductionLine.TOP_OUT, itemId(top), top.perMinute()));
        }
        return new Production(stats.totalIn(now), stats.totalOut(now), lines);
    }

    private static String itemId(ProductionStats.Top top) {
        return BuiltInRegistries.ITEM.getKey(top.item().item()).toString();
    }

    /**
     * Whether the network keeps a history, and the production section. The samples themselves stay out since M7R item
     * 3: the controller's History tab asks for them on its own ({@code RecordsPayloads}), so a new sample does not make
     * the Network tab send its overview again.
     */
    private static History history(NetworkState network) {
        Production production = production(network);
        NetworkHistory history = network.history();
        if (history == null) {
            return new History(false, 0, new LongArrayList(), new LongArrayList(), production);
        }
        return new History(true, history.sampleTicks(), new LongArrayList(), new LongArrayList(), production);
    }

    /** The warning with index {@code index}, or null; the menu's locate button resolves indices with this. */
    public @Nullable Warning warning(int index) {
        return index >= 0 && index < warnings.size() ? warnings.get(index) : null;
    }

    private static void write(FriendlyByteBuf buf, NetworkOverview o) {
        Summary s = o.summary();
        buf.writeVarInt(s.networkId());
        buf.writeEnum(s.status());
        buf.writeBoolean(s.powered());
        buf.writeBoolean(s.adHoc());
        buf.writeVarInt(s.nodes());
        buf.writeVarInt(s.devices());
        buf.writeVarInt(s.zoneCount());
        buf.writeVarInt(s.roles().size());
        for (int count : s.roles()) {
            buf.writeVarInt(count);
        }
        buf.writeVarLong(s.storedFe());
        buf.writeVarLong(s.capacityFe());
        buf.writeVarLong(s.feIn());
        buf.writeVarLong(s.feOut());
        buf.writeLong(s.parentStoredFe());
        buf.writeLong(s.parentCapacityFe());
        buf.writeInt(s.subnetMode());
        buf.writeInt(s.parentId());
        buf.writeVarInt(s.limitedDevices());
        buf.writeInt(s.deviceLimit());
        buf.writeVarInt(s.rootLinksUsed());
        buf.writeVarInt(s.rootLinks());
        buf.writeVarInt(s.batchPercent());
        buf.writeVarInt(s.controllerBlocks());
        buf.writeVarInt(s.sizeCap());
        buf.writeVarLong(s.operations());
        buf.writeVarInt(s.rebuilds());
        buf.writeVarInt(s.patches());
        buf.writeVarInt(s.offlineCells());
        buf.writeInt(s.teamDevices());
        buf.writeInt(s.teamZones());
        buf.writeLong(s.teamBytes());
        buf.writeVarInt(s.capDevices());
        buf.writeVarInt(s.capZones());
        buf.writeVarLong(s.capBytes());
        buf.writeVarInt(o.zones().size());
        for (Zone zone : o.zones()) {
            buf.writeVarInt(zone.zone());
            buf.writeVarInt(zone.nodes());
            buf.writeVarInt(zone.peakLoad());
        }
        buf.writeVarInt(o.hotspots().size());
        for (Hotspot hotspot : o.hotspots()) {
            buf.writeLong(hotspot.pos());
            buf.writeEnum(hotspot.kind());
            buf.writeVarLong(hotspot.rate());
            buf.writeVarInt(hotspot.load());
        }
        buf.writeVarInt(o.subnets().size());
        for (Subnet subnet : o.subnets()) {
            buf.writeLong(subnet.pos());
            buf.writeVarInt(subnet.mode());
            buf.writeInt(subnet.networkId());
            buf.writeVarInt(subnet.devices());
            StorageNetwork.Status status = subnet.status();
            buf.writeBoolean(status != null);
            if (status != null) {
                buf.writeEnum(status);
            }
        }
        buf.writeVarInt(o.antennas().size());
        for (Antenna antenna : o.antennas()) {
            buf.writeLong(antenna.pos());
            buf.writeLong(antenna.partner());
            buf.writeVarLong(antenna.rate());
            buf.writeInt(antenna.load());
        }
        buf.writeVarInt(o.warnings().size());
        for (Warning warning : o.warnings()) {
            buf.writeEnum(warning.kind());
            buf.writeVarLong(warning.count());
            buf.writeLong(warning.pos());
        }
        buf.writeVarInt(o.labels().size());
        for (NetworkLabels.Entry label : o.labels()) {
            DeviceLabel.STREAM_CODEC.encode(buf, label.label());
            buf.writeVarInt(label.devices());
        }
        buf.writeVarInt(o.log().size());
        for (NetworkNotices.Notice notice : o.log()) {
            buf.writeVarLong(notice.gameTime());
            buf.writeUtf(notice.key(), NetworkNotices.MAX_LENGTH);
            buf.writeUtf(notice.text(), NetworkNotices.MAX_LENGTH);
            buf.writeUtf(notice.argKey(), NetworkNotices.MAX_LENGTH);
        }
        History h = o.history();
        buf.writeBoolean(h.recorder());
        buf.writeVarInt(h.sampleTicks());
        writeLongs(buf, h.operations());
        writeLongs(buf, h.energyIn());
        buf.writeVarLong(h.production().totalIn());
        buf.writeVarLong(h.production().totalOut());
        buf.writeVarInt(h.production().lines().size());
        for (ProductionLine line : h.production().lines()) {
            buf.writeVarInt(line.kind());
            buf.writeUtf(line.name(), PRODUCTION_NAME);
            buf.writeVarLong(line.perMinute());
        }
        buf.writeUtf(o.name(), NetworkRef.MAX_NAME * 4);
        buf.writeVarInt(o.clusters().size());
        for (ClusterNeeds.Need need : o.clusters()) {
            need.write(buf);
        }
        buf.writeVarInt(o.hubs().size());
        for (Hub hub : o.hubs()) {
            buf.writeLong(hub.pos());
            buf.writeVarInt(hub.used());
            buf.writeVarInt(hub.capacity());
        }
    }

    private static NetworkOverview read(FriendlyByteBuf buf) {
        int networkId = buf.readVarInt();
        StorageNetwork.Status status = buf.readEnum(StorageNetwork.Status.class);
        boolean powered = buf.readBoolean();
        boolean adHoc = buf.readBoolean();
        int nodes = buf.readVarInt();
        int devices = buf.readVarInt();
        int zoneCount = buf.readVarInt();
        int roleCount = Math.min(buf.readVarInt(), 16);
        List<Integer> roles = new ArrayList<>();
        for (int i = 0; i < roleCount; i++) {
            roles.add(buf.readVarInt());
        }
        Summary summary = new Summary(networkId, status, powered, adHoc, nodes, devices, zoneCount, roles,
                buf.readVarLong(), buf.readVarLong(), buf.readVarLong(), buf.readVarLong(), buf.readLong(),
                buf.readLong(), buf.readInt(), buf.readInt(), buf.readVarInt(), buf.readInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarLong(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readInt(), buf.readInt(), buf.readLong(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarLong());
        List<Zone> zones = new ArrayList<>();
        for (int i = count(buf, MAX_ZONES); i > 0; i--) {
            zones.add(new Zone(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
        }
        List<Hotspot> hotspots = new ArrayList<>();
        for (int i = count(buf, MAX_HOTSPOTS); i > 0; i--) {
            hotspots.add(new Hotspot(buf.readLong(), buf.readEnum(RouteTrace.Kind.class), buf.readVarLong(),
                    buf.readVarInt()));
        }
        List<Subnet> subnets = new ArrayList<>();
        for (int i = count(buf, MAX_LINKS); i > 0; i--) {
            subnets.add(new Subnet(buf.readLong(), buf.readVarInt(), buf.readInt(), buf.readVarInt(),
                    buf.readBoolean() ? buf.readEnum(StorageNetwork.Status.class) : null));
        }
        List<Antenna> antennas = new ArrayList<>();
        for (int i = count(buf, MAX_LINKS); i > 0; i--) {
            antennas.add(new Antenna(buf.readLong(), buf.readLong(), buf.readVarLong(), buf.readInt()));
        }
        List<Warning> warnings = new ArrayList<>();
        for (int i = count(buf, WarningKind.values().length + MAX_HOTSPOTS); i > 0; i--) {
            warnings.add(new Warning(buf.readEnum(WarningKind.class), buf.readVarLong(), buf.readLong()));
        }
        List<NetworkLabels.Entry> labels = new ArrayList<>();
        for (int i = count(buf, MAX_LABELS); i > 0; i--) {
            labels.add(new NetworkLabels.Entry(DeviceLabel.STREAM_CODEC.decode(buf), buf.readVarInt()));
        }
        List<NetworkNotices.Notice> log = new ArrayList<>();
        for (int i = count(buf, MAX_LOG); i > 0; i--) {
            log.add(new NetworkNotices.Notice(buf.readVarLong(), buf.readUtf(NetworkNotices.MAX_LENGTH),
                    buf.readUtf(NetworkNotices.MAX_LENGTH), buf.readUtf(NetworkNotices.MAX_LENGTH)));
        }
        boolean recorder = buf.readBoolean();
        int sampleTicks = buf.readVarInt();
        LongList operations = readLongs(buf);
        LongList energyIn = readLongs(buf);
        long totalIn = buf.readVarLong();
        long totalOut = buf.readVarLong();
        List<ProductionLine> production = new ArrayList<>();
        for (int i = count(buf, Production.MAX_LINES); i > 0; i--) {
            production.add(new ProductionLine(buf.readVarInt(), buf.readUtf(PRODUCTION_NAME), buf.readVarLong()));
        }
        History history = new History(recorder, sampleTicks, operations, energyIn,
                new Production(totalIn, totalOut, production));
        String name = buf.readUtf(NetworkRef.MAX_NAME * 4);
        List<ClusterNeeds.Need> clusters = new ArrayList<>();
        for (int i = count(buf, ClusterNeeds.MAX); i > 0; i--) {
            clusters.add(ClusterNeeds.Need.read(buf));
        }
        List<Hub> hubs = new ArrayList<>();
        for (int i = count(buf, MAX_HUBS); i > 0; i--) {
            hubs.add(new Hub(buf.readLong(), buf.readVarInt(), buf.readVarInt()));
        }
        return new NetworkOverview(summary, zones, hotspots, subnets, antennas, warnings, labels, log, history, name,
                clusters, hubs);
    }

    /** A list length, refused when larger than any server sends. */
    private static int count(FriendlyByteBuf buf, int max) {
        int count = buf.readVarInt();
        if (count < 0 || count > max) {
            throw new IllegalArgumentException("Network overview list of " + count + " entries, at most " + max);
        }
        return count;
    }

    private static void writeLongs(FriendlyByteBuf buf, LongList values) {
        buf.writeVarInt(values.size());
        for (int i = 0; i < values.size(); i++) {
            buf.writeVarLong(values.getLong(i));
        }
    }

    private static LongList readLongs(FriendlyByteBuf buf) {
        int count = count(buf, MAX_SAMPLES);
        LongArrayList values = new LongArrayList(count);
        for (int i = 0; i < count; i++) {
            values.add(buf.readVarLong());
        }
        return values;
    }
}
