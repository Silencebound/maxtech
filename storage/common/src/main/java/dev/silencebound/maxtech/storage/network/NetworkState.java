package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.storage.crafting.job.CraftLimits;
import dev.silencebound.maxtech.storage.history.HistoryClaims;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.api.security.OwnerSecurity;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetwork;
import dev.silencebound.maxtech.lib.api.energy.EnergyPriority;
import dev.silencebound.maxtech.lib.energy.EndpointBuffer;
import dev.silencebound.maxtech.lib.energy.FeBuffer;
import dev.silencebound.maxtech.lib.network.Network;
import dev.silencebound.maxtech.lib.network.NetworkGraph;
import dev.silencebound.maxtech.lib.network.RouteTree;
import dev.silencebound.maxtech.lib.schedule.LevelTasks;
import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import dev.silencebound.maxtech.storage.api.StorageContents;
import dev.silencebound.maxtech.storage.api.StorageDevice;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.cable.CableLook;
import dev.silencebound.maxtech.storage.cable.SmartCables;
import dev.silencebound.maxtech.storage.cable.StorageCableConduitType;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.crafting.pattern.NetworkPatterns;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternHost;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongArrays;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntArrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
// PLATFORM-LEAK: FE and transfers are NeoForge's transfer types; Storage runs on them (PRD 7.2).
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.Transaction;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * Everything one storage network keeps besides its topology (PRD 7.2, Network): its devices, the storage index over
 * their providers, its energy (the controller's buffer, or a small unsaved one without a controller, plus its energy
 * cells), its Lib {@link TaskGroup}, and its routes: a widest-path {@link RouteTree}, cut into zones of at most
 * {@code network.zoneSize} nodes. A removed cable or a cut link reroutes only the nodes behind it ({@link Routes},
 * M6d); other topology changes rebuild. Nothing here ticks per device; the network's tick only does due rebuilds and,
 * every few seconds, the cluster's load display.
 *
 * <p>
 * Channels (M7N, PRD 7.2 Channels with hubs): every device but the controller, its cluster, hubs and
 * {@link ChannelFree} devices (energy, display, sensing and support blocks, which join whenever they are in the graph)
 * holds one channel on each route segment from its node to the root ({@link Channels}); a segment carries its cables'
 * channels, a root link at most {@code network.channels.rootLink}, and a network without a controller has
 * {@code network.channels.adHoc} in all. Channels are counted only on topology changes: in full at a rebuild, by the
 * devices whose route changed at a patch, and by one device when it joins or leaves; never per transfer or tick. A
 * device that finds no room goes offline: it stays out of the network (no index, no tasks, no transfers) and its
 * {@link DeviceLink} says which link is full. The order is the devices' join sequence ({@link DeviceLink#joinSeq()}),
 * saved with them, so the devices that joined last go offline first. Without channel limits
 * ({@code network.channels.mode = infinite}, B09 item 12) the same counting runs, so loads stay visible, but every link
 * and hub has room and only a missing route (the zone size, the team's zone cap) keeps a device out; the device limits
 * (subnet controllers, the team's device cap) still refuse. A mode change is picked up once on the next tick
 * ({@link Channels#modeStamp()}) as a recount.
 *
 * <p>
 * Hubs (M7N item 2, {@link ChannelHub}): a device whose route passes a hub counts against the hub's budget instead of
 * the segments beyond it, and the hub holds one channel on its own route while any device counts against it. So a
 * device's channel walk stops at the first hub that already counts someone; joins, leaves and patches behind a hub walk
 * only to the hub. A hub is a node like any device, so it never splits the network. Hub budgets are read when the
 * routes are rebuilt and kept until the next rebuild, so holding and releasing always agree.
 *
 * <p>
 * With one controller the tree has several roots, the controller's root links (PRD 7.2, Controller): it is built from a
 * virtual {@link #SUPER_ROOT} whose children are the cluster blocks that carry root links (the active blocks of a
 * multiblock controller, {@link NetworkController#exitNodes()}; M7R item 1). Of the links from those blocks to cables
 * and devices outside the cluster, the widest {@link NetworkController#rootLinks()} are enabled (a storage controller
 * allows all of them, so every face touching a cable or device is a root link; then by position); the others and every
 * link between two cluster blocks have width 0, so no route uses them. Each enabled link carries its own channels, so
 * the channels of several root links add up; a route stops counting at the cluster. Without a controller (ad-hoc) the
 * root is the lowest device that is not a cluster block, as before.
 *
 * <p>
 * One controller per network (M7N item 3, PRD 7.2 Controller): of several controllers in one network, a controller core
 * before a subnet controller, then the one placed first ({@link NetworkController#placedAt()}, then the lowest
 * position) is in charge; it alone gives the root links, the FE buffer and the owner (and a subnet controller its
 * device limit; a storage controller has none since M7R item 1, channels limit its network). The others are on standby:
 * members that hold no channel, carry no route and do nothing until the one in charge leaves, when the next takes over
 * at the following rebuild.
 *
 * <p>
 * Created by {@link StorageNetworkLogic} for each graph network, never persisted (networks are rebuilt when chunks
 * load). Server thread only.
 */
public final class NetworkState implements StorageNetwork, BackedBuffer.Source {
    /**
     * A topology change rebuilds routes at once, then at most this often (ticks). A performance bound, not a balance
     * value: it keeps a player who keeps editing a large network from rebuilding it every tick.
     */
    static final int REBUILD_INTERVAL = 4;
    /**
     * What a transfer waits when the network does not work, before trying again; see
     * {@link StorageTransfer#retryTicks}.
     */
    static final int NOT_WORKING_RETRY = 10;
    private static final int DEVICE_TIER = 0;
    /**
     * The virtual root of a controller network's route tree, whose children are the cluster blocks carrying root links.
     * y = -2048 is below any level's lowest block (-2032), so no node has this position.
     */
    public static final long SUPER_ROOT = BlockPos.asLong(0, -2048, 0);
    /**
     * How often (ticks) the controller may update its cluster's load display. A display cadence, not a balance value:
     * it bounds the block updates load changes cause.
     */
    static final int LOAD_INTERVAL = 40;
    /**
     * Join sequences per game tick: a sequence is {@code gameTime << SEQ_SHIFT} plus a counter, so later joins always
     * number higher, across restarts too.
     */
    static final int SEQ_SHIFT = 20;
    /**
     * What a task held back by a surge regulator waits at most (ticks). The network wakes it as soon as its energy is
     * back above the regulator's resume level; this only bounds a sleep whose wake-up was missed (a regulator broken
     * while no FE comes in).
     */
    static final int SURGE_RETRY = 200;
    /**
     * A patch may detach a quarter of the network, and the routes may carry patches for half of it, but at least this
     * many nodes, before a full rebuild is cheaper; a performance bound, not a balance value.
     */
    static final int PATCH_MIN = 256;
    private static final Direction[] SIDES = Direction.values();
    private static @Nullable Function<NetworkState, @Nullable NetworkObserver> observers;
    /** Told about every line posted to any network's log (logic remotes, M7L2); null for nobody. */
    private static @Nullable BiConsumer<NetworkState, NetworkNotices.Notice> noticeListener;

    private final StorageNetworkLogic logic;
    final Network network;
    private final TaskGroup group;
    /** Server time of the network's upkeep and its devices' tasks in the running or latest profiler session. */
    final ProfileTally profile = new ProfileTally();
    private final StorageIndex index = new StorageIndex();
    /** The members' labels (M7L); a subnet is a network of its own, so it keeps its own. */
    private final NetworkLabels labels = new NetworkLabels();
    /** The 16 bundled redstone channels (M7L2); writers hold their own shares, so nothing here is saved. */
    private final RedstoneChannels channels = new RedstoneChannels();
    /** Load shedding (M7L2): the sheddable members by power priority and the level paused now. */
    private final LoadShedding shedding = new LoadShedding();
    /** Logic controllers told when the shed level changes; usually empty. */
    private final ObjectArrayList<Runnable> sheddingWatchers = new ObjectArrayList<>(0);
    /** Load shedding armed on a network without a controller; see {@link #sheddingArmed()}. */
    private boolean adHocArmed;
    /** Patterns of the members that run them (M7a); empty and untouched on networks without any. */
    private final NetworkPatterns patterns = new NetworkPatterns();
    /** The members' providers that show another network; usually empty (M7 C30, patterns and CPUs follow them). */
    private final ObjectArrayList<NetworkView> views = new ObjectArrayList<>(0);
    private final EnergyTally energy = new EnergyTally();
    /** Devices in this network, with the index entries of their providers; several may share a node (cable parts). */
    private final Reference2ObjectOpenHashMap<NetworkDevice, Member> members = new Reference2ObjectOpenHashMap<>();
    private final ObjectArrayList<NetworkDevice> controllers = new ObjectArrayList<>(1);
    /** The controller in charge among {@link #controllers} ({@link #pickActive}); null without one. */
    private @Nullable NetworkDevice active;
    /** True during {@link #rebuild}, which builds the routes for whichever controller is in charge at its end. */
    private boolean rebuilding;
    /** Cable tier (1, 2, ...) per node, {@link #DEVICE_TIER} for devices; read by the edge widths. */
    private final Long2IntOpenHashMap nodeTiers = new Long2IntOpenHashMap();
    /** The smart cables among the nodes, whose load display the tick refreshes. */
    private final SmartCables smartCables = new SmartCables();
    private final Long2IntOpenHashMap zoneOf = new Long2IntOpenHashMap();
    private final IntArrayList zoneSizes = new IntArrayList();
    /** Zones holding at least one node; kept with every zone change so {@link #zoneCount()} is O(1). */
    private int usedZones;
    /** Channels held per route segment, keyed by the segment's child node; absent means none. */
    private final Long2IntOpenHashMap channelUse = new Long2IntOpenHashMap();
    /** The hubs' budgets by node, as of their join or the last rebuild; absent (0) for nodes that are no hub. */
    private final Long2IntOpenHashMap hubCaps = new Long2IntOpenHashMap();
    /** Devices counting against each hub; absent means none, and then the hub holds no channel upstream. */
    private final Long2IntOpenHashMap hubUse = new Long2IntOpenHashMap();
    /** Devices holding a channel; a network without a controller allows {@link Channels#adHoc()} of them. */
    private int channelsHeld;
    /** The newest join sequence holding a channel; an older device that finds no room asks for a recount. */
    private long newestHeld = Long.MIN_VALUE;
    /** Devices kept offline for lack of a channel, with their reasons in their links; tried again when room frees. */
    private final ReferenceOpenHashSet<NetworkDevice> offline = new ReferenceOpenHashSet<>();
    /** Channels were released while devices are offline: they are tried again at the next due rebuild check. */
    private boolean retryOffline;
    /** An older device found no room that newer devices hold: channels are counted again in full. */
    private boolean recountDue;
    /**
     * Whether the last full count ran without channel limits ({@link ChannelMode#INFINITE}); the joins and patches in
     * between keep it, so a network never mixes the two modes.
     */
    private boolean infinite = Channels.infinite();
    /** {@link Channels#modeStamp()} as of the last full count; once it moves, channels are counted again once. */
    private int countedModeStamp = Channels.modeStamp();
    /** True while a full count admits devices, so {@link #join} does not count them a second time. */
    private boolean counting;
    /** Rises whenever channel use changes; smart cables skip their read while it stays. */
    private long channelVersion;
    /** Route segments walked by channel counting since the network was created; perf tests read it. */
    private long channelSteps;
    /** Full channel counts since the network was created (rebuilds and recounts); perf tests read it. */
    private int channelCounts;
    private final RouteTree.EdgeWidth width = this::edgeWidth;
    private final RouteTree.Links links = this::routeLinks;
    private @Nullable Routes tree;
    /** Cables removed since the routes were last patched or rebuilt; see {@link #patch}. */
    private final LongOpenHashSet removedNodes = new LongOpenHashSet();
    /** Nodes whose own link to their route parent was cut since then. */
    private final LongOpenHashSet cutNodes = new LongOpenHashSet();
    /** Whether removed cables or cut links wait for {@link #patch}. */
    private boolean patchPending;
    private int patches;
    /** The controller in charge as of the last route build, whose cluster roots the tree; null for ad-hoc routes. */
    private @Nullable NetworkController rootController;
    /** The enabled root links; rebuilt with the routes. */
    private final RootLinks rootLinks = new RootLinks();
    /** Members that are modules or member blocks of a multiblock controller: they never count as devices. */
    private int pieces;
    /** Members the controller's device limit counts: everything but controllers and cluster blocks. */
    private int limitedDevices;
    /** Devices turned away for the device limit since the last rebuild, which tries them again. */
    private final ReferenceOpenHashSet<NetworkDevice> refused = new ReferenceOpenHashSet<>();
    /**
     * Membership or channels changed since the devices' front lines were last shown ({@link LinkLook}): the next tick
     * shows them again, once for all the changes of a tick.
     */
    private boolean linksDirty;
    /** Whether the front lines last shown counted the network online: powered, with a controller in charge. */
    private boolean linkOnline;
    /** Devices that left or stopped waiting here since the last showing; shown offline if no other network has them. */
    private final ObjectArrayList<NetworkDevice> linksLeft = new ObjectArrayList<>(0);
    /** Game time the network stopped being online while its fronts still show online; MIN_VALUE otherwise. */
    private long linkOfflineSince = Long.MIN_VALUE;
    /** Paused for unloaded chunks: the network does not tick, {@link LinkDwell} shows offline after the dwell. */
    private boolean linksPaused;
    /** How often the front lines were shown, and how many looks were pushed; for tests and diagnostics. */
    private long linkShowings;
    private long linkPushes;
    private boolean adHoc;
    private @Nullable FeBuffer adHocBuffer;
    /** The MaxTech energy network this storage network hosts (MLE item 4); made with its first member. */
    private @Nullable StorageEnergy muEnergy;
    private OwnerRecord owner = OwnerRecord.UNOWNED;
    private boolean needsRebuild = true;
    private long nextRebuild = Long.MIN_VALUE;
    private Status lastStatus = Status.ONLINE;
    /**
     * Frozen by a quarantine tag: {@link #status()} reports {@link Status#PAUSED}, so no operation runs and tasks wait,
     * while devices, contents and settings stay. Kept across rebuilds and restarts through {@link Quarantines}.
     */
    private boolean quarantined;
    private boolean lastPowered;
    /** Members that power the network without limit. */
    private int powerSources;
    /** Members per {@link NetworkService} (M7R item 4); changed on joins and leaves only. */
    private final EnumMap<NetworkService, ObjectArrayList<NetworkDevice>> services = serviceLists();
    /** The members' energy cells; their FE backs the controller's or ad-hoc buffer. */
    private final EnergyCells cells = new EnergyCells();
    /** The store a subnet uses: its own with its parent's behind it; see {@link BackedBuffer}. */
    private final BackedBuffer backed = new BackedBuffer();
    private long operations;
    private int rebuilds;
    private boolean closed;
    private final Reference2IntOpenHashMap<StorageDevice.Role> roles = new Reference2IntOpenHashMap<>();
    /** Condition switches watching the stored energy; usually empty, so the tick pays one size check. */
    private final ObjectArrayList<EnergyWatcher> energyWatchers = new ObjectArrayList<>(0);
    /** Condition switches watching route load; told whenever channel use changes. */
    private final ObjectArrayList<Runnable> loadWatchers = new ObjectArrayList<>(0);
    /** Moves waiting for their root transaction; empty and unused while no ledger watches. */
    private final Moves moves = new Moves();
    private final ObjectArrayList<MoveWatcher> moveWatchers = new ObjectArrayList<>(0);
    /**
     * Logic's listeners for committed moves of one key (M7L2 item moves); usually empty, so an operation pays two
     * emptiness checks and nothing else.
     */
    private final Object2ObjectOpenHashMap<StackKey, ObjectArrayList<ItemMoveListener>> itemMoveListeners = new Object2ObjectOpenHashMap<>(
            0);
    /** Logic's listeners for committed moves of any key (an item tag, or any item); usually empty. */
    private final ObjectArrayList<ItemMoveListener> anyMoveListeners = new ObjectArrayList<>(0);
    /** Logic controllers told when a player opens a terminal of this network (M7L2); usually empty. */
    private final ObjectArrayList<TerminalWatcher> terminalWatchers = new ObjectArrayList<>(0);
    /** Alarms watching joins and leaves; usually empty. */
    private final ObjectArrayList<Runnable> deviceWatchers = new ObjectArrayList<>(0);
    /** Logic controllers watching neighbour updates of labelled devices (M7L); usually empty. */
    private final ObjectArrayList<NeighbourWatcher> neighbourWatchers = new ObjectArrayList<>(0);
    /** Logic controllers watching drive cells turning full or not (M7L); usually empty. */
    private final ObjectArrayList<Runnable> fillWatchers = new ObjectArrayList<>(0);
    /** Notices logic graphs posted to the network's log (M7L); not saved. */
    private final NetworkNotices notices = new NetworkNotices(this::noticePosted);
    /** Notices logic graphs sent to the network's displays (M7L); not saved. */
    private final NetworkNotices displayNotices = new NetworkNotices();
    /** The series recorded on this network (M7L2 history charts), under {@code history.maxSeriesPerNetwork}. */
    private final HistoryClaims historyClaims = new HistoryClaims();
    /** Display screens showing logic notices; usually empty. */
    private final ObjectArrayList<Runnable> noticeWatchers = new ObjectArrayList<>(0);
    /** Devices whose automation a surge regulator held back; their tasks are woken once the energy is back. */
    private final ReferenceOpenHashSet<DeviceLink> throttled = new ReferenceOpenHashSet<>();
    private long watchedEnergy = -1;
    /** The team the server caps count this network against ({@link TeamCaps}); null while uncapped. */
    private @Nullable UUID capTeam;
    /** Devices the team's other networks in this level hold, as of {@link #teamOthersTime}. */
    private int teamOthers;
    private long teamOthersTime = Long.MIN_VALUE;
    /** Zones this network may open under the team's zone cap; worked out when routes are built. */
    private int zoneRoom = Integer.MAX_VALUE;
    private final @Nullable NetworkObserver observer;

    private static final class Member {
        final NetworkDevice device;
        final ObjectArrayList<StorageIndex.Entry> entries = new ObjectArrayList<>(1);
        /** The member's providers that show another network (subnet mounts); null for the many that have none. */
        @Nullable
        ObjectArrayList<NetworkView> views;

        Member(NetworkDevice device) {
            this.device = device;
        }
    }

    NetworkState(StorageNetworkLogic logic, Network network) {
        this.logic = logic;
        this.network = network;
        this.group = LevelTasks.newGroup(logic.level());
        nodeTiers.defaultReturnValue(-1);
        zoneOf.defaultReturnValue(-1);
        Function<NetworkState, @Nullable NetworkObserver> factory = observers;
        observer = factory == null ? null : factory.apply(this);
        index.observer = observer;
        index.voids = new StorageProvider.Listener() {
            @Override
            public void changed(StackKey key, long delta) {
            }

            @Override
            public void voided(StackKey key, long amount) {
                NetworkController controller = activeController();
                // An ad-hoc network has no recovery bin; the overflow is gone as before.
                if (controller != null) {
                    controller.voided(key, amount);
                }
            }
        };
        patterns.link(this::linkedPatterns);
    }

    /**
     * Makes an observer for every network created from now on (research); the factory may return null for none. Set
     * once during mod construction; a later call replaces the factory for networks created afterwards.
     */
    public static void observeNetworks(Function<NetworkState, @Nullable NetworkObserver> factory) {
        observers = factory;
    }

    /** This network's observer, or null when none was made for it. */
    public @Nullable NetworkObserver observer() {
        return observer;
    }

    /** The level the network is in. */
    public ServerLevel level() {
        return logic.level();
    }

    /** How many of the network's devices have {@code role}. O(1). */
    public int deviceCount(StorageDevice.Role role) {
        return roles.getInt(role);
    }

    /** Tells the observer that a device moved {@code amount} of {@code key} (a port), after its commit. */
    public void transferred(StackKey key, long amount) {
        if (observer != null && amount > 0) {
            observer.transferred(key, amount);
        }
    }

    /** Tells the observer that a cell of a drive in this network is now {@code percent} full. */
    public void cellFilled(int percent) {
        if (observer != null) {
            observer.cellFilled(percent);
        }
    }

    private void devicesChanged() {
        if (observer != null) {
            observer.devicesChanged();
        }
        runDeviceWatchers();
    }

    TaskGroup group() {
        return group;
    }

    /** {@code device}'s own transfer bound to this network, for one task run: a player's or automation. */
    StorageTransfer transfer(NetworkDevice device, boolean player) {
        return device.deviceLink().bind(this, player);
    }

    /** A topology change: routes are rebuilt now if the last rebuild was long enough ago, else on a later tick. */
    void markChanged() {
        needsRebuild = true;
    }

    /** Rebuilds if a change is pending and the last rebuild was at least {@link #REBUILD_INTERVAL} ticks ago. */
    void rebuildIfDue(long gameTime) {
        if (countedModeStamp != Channels.modeStamp()) {
            // The channel mode changed (a config reload): one recount, like a topology change, not one per tick.
            recountDue = true;
        }
        if ((needsRebuild || patchPending || recountDue || retryOffline) && !closed && gameTime >= nextRebuild) {
            if (needsRebuild) {
                rebuild();
            } else if (patchPending) {
                patch();
            }
            if (!needsRebuild && recountDue) {
                recountChannels();
            }
            if (!needsRebuild && retryOffline) {
                retryOfflineDevices();
            }
            nextRebuild = gameTime + REBUILD_INTERVAL;
        }
    }

    /**
     * Re-reads which devices are in the network, the controllers, the cable tiers and the owner, rebuilds the route
     * tree and zones and counts every device's channels again. Linear in the network's size (times the routes' depth
     * for the channels); only on topology changes.
     */
    void rebuild() {
        rebuilding = true;
        try {
            rebuildRoutes();
        } finally {
            rebuilding = false;
        }
    }

    private void rebuildRoutes() {
        needsRebuild = false;
        clearPatch();
        rebuilds++;
        // Devices that moved away (a split) or broke leave first, then devices that arrived join.
        ObjectArrayList<NetworkDevice> gone = new ObjectArrayList<>();
        for (Member member : members.values()) {
            NetworkDevice device = member.device;
            if (!network.contains(device.nodePos()) || device.deviceLink().network != this || device.isRemoved()) {
                gone.add(device);
            }
        }
        for (int i = 0; i < gone.size(); i++) {
            leave(gone.get(i));
        }
        // Devices turned away for the device limit get another try below.
        for (NetworkDevice device : refused) {
            DeviceLink link = device.deviceLink();
            if (link.refusedBy == this) {
                link.refusedBy = null;
                linksLeft.add(device);
            }
        }
        refused.clear();
        linksDirty = true;
        // Devices offline for channels are counted again below with everyone else.
        clearOffline();
        nodeTiers.clear();
        smartCables.clear();
        // The leaves above gave their channels back against the old budgets; the count below uses the new ones.
        hubCaps.clear();
        // Controllers and cluster blocks first, so the device limit and the root links are known; other devices join
        // once their channels are counted.
        LongArrayList later = new LongArrayList();
        LongArrayList withAttached = new LongArrayList(0);
        // Attached devices are rare: most levels have none, and then no node needs the lookup.
        boolean anyAttached = logic.hasAttached();
        LongIterator it = network.nodes().iterator();
        while (it.hasNext()) {
            long pos = it.nextLong();
            if (anyAttached && logic.attached(pos) != null) {
                withAttached.add(pos);
            }
            StorageDeviceBlockEntity device = logic.device(pos);
            if (device != null) {
                if (!limited(device)) {
                    if (device.network() != this) {
                        join(device);
                    }
                } else if (device.network() != this) {
                    later.add(pos);
                }
                nodeTiers.put(pos, DEVICE_TIER);
                if (device instanceof ChannelHub hub) {
                    hubCaps.put(pos, Math.max(1, hub.hubCapacity()));
                }
            } else {
                readCable(pos);
                // Every storage cable is a host; only those carrying parts have devices to collect.
                DeviceHost host = logic.host(pos);
                if (host != null && host.deviceCount() > 0) {
                    later.add(pos);
                }
            }
        }
        // Lowest position first, so devices that never joined before are numbered in an order that does not depend on
        // hash order; the channel count then admits everyone by join sequence.
        LongArrays.quickSort(later.elements(), 0, later.size());
        ObjectArrayList<NetworkDevice> waiting = new ObjectArrayList<>();
        for (int i = 0; i < later.size(); i++) {
            long pos = later.getLong(i);
            StorageDeviceBlockEntity device = logic.device(pos);
            if (device != null) {
                waiting.add(device);
            } else {
                collectHosted(pos, waiting);
            }
        }
        LongArrays.quickSort(withAttached.elements(), 0, withAttached.size());
        for (int i = 0; i < withAttached.size(); i++) {
            collectAttached(withAttached.getLong(i), waiting);
        }
        owner = findOwner(waiting);
        capTeam = TeamCaps.team(logic.level(), owner);
        teamOthersTime = Long.MIN_VALUE;
        adHoc = controllers.isEmpty();
        buildRoutes(waiting);
        countChannels(waiting);
        refreshControllers();
    }

    /**
     * Whether {@code device} counts towards the controller's device limit and holds a channel of its own: everything
     * but controllers, cluster blocks, hubs (a hub's channel follows the devices behind it) and {@link ChannelFree}
     * devices (energy, display, sensing and support blocks; owner 2026-10-17).
     */
    private static boolean limited(NetworkDevice device) {
        return !(device instanceof ClusterNode) && !(device instanceof ChannelHub) && !ChannelFree.of(device)
                && device.role() != StorageDevice.Role.CONTROLLER;
    }

    /** Whether {@code device} is a cluster block that is not a working controller (a module or a member block). */
    private static boolean piece(NetworkDevice device) {
        return device instanceof ClusterNode && device.role() != StorageDevice.Role.CONTROLLER;
    }

    /** The controller in charge, or null without one. */
    private @Nullable NetworkController activeController() {
        return active instanceof NetworkController controller ? controller : null;
    }

    /**
     * Picks the controller in charge after {@link #controllers} changed: a controller core before a subnet controller
     * (an older subnet controller never puts a core on standby), then the one placed first, then the lowest position,
     * so the choice does not depend on join order and stays across reloads. A new one outside a rebuild changes the
     * root links and the device limit, so the routes are rebuilt.
     */
    @SuppressWarnings("ReferenceEquality") // devices are members by identity
    private void pickActive() {
        NetworkDevice best = null;
        int bestRank = Integer.MAX_VALUE;
        long bestPlaced = Long.MAX_VALUE;
        for (int i = 0; i < controllers.size(); i++) {
            NetworkDevice device = controllers.get(i);
            NetworkController controller = device instanceof NetworkController found ? found : null;
            int rank = controller == null ? 2 : controller.isSubnetController() ? 1 : 0;
            long placed = controller == null ? Long.MAX_VALUE : controller.placedAt();
            if (best == null || rank < bestRank || (rank == bestRank
                    && (placed < bestPlaced || (placed == bestPlaced && device.nodePos() < best.nodePos())))) {
                best = device;
                bestRank = rank;
                bestPlaced = placed;
            }
        }
        if (best != active) {
            active = best;
            if (!rebuilding) {
                needsRebuild = true;
            }
            syncEnergy();
            // Service devices that need a working controller (a dimension bridge's drain) look again.
            for (ObjectArrayList<NetworkDevice> list : services.values()) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i) instanceof ServiceDevice service) {
                        service.controllerChanged();
                    }
                }
            }
        }
    }

    private static EnumMap<NetworkService, ObjectArrayList<NetworkDevice>> serviceLists() {
        EnumMap<NetworkService, ObjectArrayList<NetworkDevice>> lists = new EnumMap<>(NetworkService.class);
        for (NetworkService service : NetworkService.values()) {
            lists.put(service, new ObjectArrayList<>(0));
        }
        return lists;
    }

    private ObjectArrayList<NetworkDevice> services(NetworkService service) {
        ObjectArrayList<NetworkDevice> list = services.get(service);
        if (list == null) {
            throw new IllegalStateException("no list for " + service);
        }
        return list;
    }

    /**
     * A member giving {@code service} joined or left. Security: rules on other networks that this network's access
     * decides check again (M7L2 row 13). Anchors look again through {@link #refreshControllers()}, which every join and
     * leave runs.
     */
    private static void serviceChanged(NetworkService service) {
        if (service == NetworkService.SECURITY) {
            NetworkNames.changed();
        }
    }

    /** Members giving {@code service} (M7R item 4); counted on joins and leaves, so this never searches. */
    public int serviceCount(NetworkService service) {
        return services(service).size();
    }

    /**
     * The members giving {@code service}, in join order; the network's own list, so callers must not change it or keep
     * it. Server thread.
     */
    public List<NetworkDevice> serviceDevices(NetworkService service) {
        return services(service);
    }

    /** Whether {@code device} is a controller of this network on standby: another one is in charge. */
    @SuppressWarnings("ReferenceEquality") // devices are members by identity
    public boolean isStandby(NetworkDevice device) {
        return active != null && active != device && controllers.contains(device);
    }

    /**
     * Most devices the network may hold (besides its controller and cluster): a subnet controller's limit and the
     * team's device cap, else {@link Integer#MAX_VALUE}. A storage controller sets no limit (M7R item 1); channels
     * limit its network, and a network without a controller has {@link Channels#adHoc()} channels.
     */
    public int deviceLimit() {
        NetworkController controller = activeController();
        int limit = controller == null ? Integer.MAX_VALUE : controller.maxDevices();
        return Math.min(limit, teamDeviceRoom());
    }

    /**
     * Devices this network may hold under its team's device cap: the cap less what the team's other networks in this
     * level hold, read at most once per tick; {@link Integer#MAX_VALUE} without a cap.
     */
    private int teamDeviceRoom() {
        int cap = StorageServerConfig.CAP_DEVICES_PER_TEAM.getAsInt();
        UUID team = capTeam;
        if (cap <= 0 || team == null) {
            return Integer.MAX_VALUE;
        }
        long now = logic.level().getGameTime();
        if (teamOthersTime != now) {
            teamOthers = logic.teamDevices(team, this);
            teamOthersTime = now;
        }
        return Math.max(0, cap - teamOthers);
    }

    /** Whether the team's device cap, not the controller, sets {@link #deviceLimit()} now. */
    public boolean teamCapped() {
        NetworkController controller = activeController();
        int limit = controller == null ? Integer.MAX_VALUE : controller.maxDevices();
        return teamDeviceRoom() < limit;
    }

    /** The team the server caps count this network against, or null when it is not capped (unowned). */
    public @Nullable UUID capTeam() {
        return capTeam;
    }

    /** Devices the team's networks in this level hold together, this one included; 0 when not capped. */
    public int teamDevicesUsed() {
        UUID team = capTeam;
        return team == null ? 0 : logic.teamDevices(team, this) + limitedDevices;
    }

    /** Zones the team's networks in this level have together, this one included; 0 when not capped. */
    public int teamZonesUsed() {
        UUID team = capTeam;
        return team == null ? 0 : logic.teamZones(team, this) + zoneCount();
    }

    /** Cell bytes the team's networks in this level use together (summed at most once a second); 0 uncapped. */
    public long teamBytesUsed() {
        UUID team = capTeam;
        return team == null ? 0 : logic.teamBytes(team, logic.level().getGameTime());
    }

    /** Whether the team's cell bytes in this level reached {@code caps.bytesPerTeam}: storing stops. */
    public boolean bytesCapped() {
        long cap = StorageServerConfig.CAP_BYTES_PER_TEAM.getAsLong();
        UUID team = capTeam;
        return cap > 0 && team != null && logic.teamBytes(team, logic.level().getGameTime()) >= cap;
    }

    /** Devices the controller's device limit counts. */
    public int limitedDevices() {
        return limitedDevices;
    }

    private void refuse(NetworkDevice device) {
        refused.add(device);
        device.deviceLink().refusedBy = this;
        linksDirty = true;
    }

    /**
     * Counts every device's channels from scratch, oldest join first (rebuilds and recounts only): each device that
     * finds a channel on every segment of its route, and room under the device limit, holds them and is (or joins as) a
     * member; the others leave and wait, offline with the full link named, or refused for the device limit. The
     * candidates are the limited members, the devices offline here, and {@code waiting} (devices on the network's nodes
     * that are no members yet). Cost: a sort plus one route walk per device.
     */
    private void countChannels(ObjectArrayList<NetworkDevice> waiting) {
        channelCounts++;
        recountDue = false;
        // The stamp first: a change between the two reads leaves the stamp behind, which asks for one more recount.
        countedModeStamp = Channels.modeStamp();
        infinite = Channels.infinite();
        retryOffline = false;
        channelUse.clear();
        hubUse.clear();
        channelsHeld = 0;
        newestHeld = Long.MIN_VALUE;
        ReferenceOpenHashSet<NetworkDevice> seen = new ReferenceOpenHashSet<>();
        ObjectArrayList<NetworkDevice> candidates = new ObjectArrayList<>(limitedDevices + waiting.size());
        for (Member member : members.values()) {
            NetworkDevice device = member.device;
            if (limited(device)) {
                device.deviceLink().holdsChannel = false;
                seen.add(device);
                candidates.add(device);
            }
        }
        for (NetworkDevice device : offline) {
            if (!device.isRemoved() && seen.add(device)) {
                candidates.add(device);
            }
        }
        clearOffline();
        for (int i = 0; i < waiting.size(); i++) {
            NetworkDevice device = waiting.get(i);
            if (seen.add(device)) {
                // Numbered in the order given (by position), before the sort, for devices that never joined.
                assignSeq(device.deviceLink());
                candidates.add(device);
            }
        }
        // Rebuilds only, so a comparator is fine here.
        candidates.sort(NetworkState::byJoinOrder);
        Routes current = tree;
        int limit = deviceLimit();
        int admitted = 0;
        ObjectArrayList<NetworkDevice> admit = new ObjectArrayList<>(candidates.size());
        boolean pending = needsRebuild;
        for (int i = 0; i < candidates.size(); i++) {
            NetworkDevice device = candidates.get(i);
            NetworkState elsewhere = device.deviceLink().network;
            if (elsewhere != null && elsewhere != this) {
                // Still counted by the network it was in (a split in progress): that one lets it go first.
                elsewhere.leave(device);
            }
            if (!limited(device)) {
                // Parts and attached devices that hold no channel (display and sensor parts, generators) join as they
                // are.
                admit.add(device);
                continue;
            }
            ChannelShortage shortage = shortage(current, device.nodePos());
            if (shortage == null && admitted >= limit) {
                if (members.containsKey(device)) {
                    leave(device);
                }
                refuse(device);
                continue;
            }
            if (shortage != null) {
                if (members.containsKey(device)) {
                    leave(device);
                }
                markOffline(device, shortage);
                continue;
            }
            admitted++;
            hold(current, device.deviceLink(), device.nodePos());
            admit.add(device);
        }
        counting = true;
        try {
            for (int i = 0; i < admit.size(); i++) {
                NetworkDevice device = admit.get(i);
                if (!members.containsKey(device)) {
                    join(device);
                }
            }
        } finally {
            counting = false;
        }
        // The leaves above found refused devices and asked for a rebuild, which would only count the same again.
        needsRebuild = pending;
        channelsChanged();
    }

    /** Counts channels again in full without rebuilding the routes; when an older device found no room. */
    private void recountChannels() {
        if (tree == null) {
            needsRebuild = true;
            return;
        }
        countChannels(new ObjectArrayList<>(0));
    }

    /**
     * Tries the devices offline here again, oldest first, after channels were released: each that finds room now joins.
     * One route walk per offline device.
     */
    private void retryOfflineDevices() {
        retryOffline = false;
        if (offline.isEmpty()) {
            return;
        }
        ObjectArrayList<NetworkDevice> waiting = new ObjectArrayList<>(offline);
        waiting.sort(NetworkState::byJoinOrder);
        Routes current = tree;
        for (int i = 0; i < waiting.size(); i++) {
            NetworkDevice device = waiting.get(i);
            if (device.isRemoved() || device.deviceLink().offlineBy != this) {
                offline.remove(device);
                continue;
            }
            ChannelShortage shortage = shortage(current, device.nodePos());
            if (shortage == null) {
                // join() counts it against the routes as they are now, which have room.
                join(device);
            } else {
                device.deviceLink().shortage = shortage;
            }
        }
    }

    /** Oldest join first; position breaks ties (devices numbered in one tick of an old save). */
    private static int byJoinOrder(NetworkDevice a, NetworkDevice b) {
        int bySeq = Long.compare(a.deviceLink().joinSeq, b.deviceLink().joinSeq);
        return bySeq != 0 ? bySeq : Long.compare(a.nodePos(), b.nodePos());
    }

    /** Numbers {@code link} for channels when it joins for the first time; later joins keep the number. */
    private void assignSeq(DeviceLink link) {
        if (link.joinSeq <= 0) {
            link.joinSeq = logic.nextJoinSeq();
        }
    }

    /**
     * What keeps a device at {@code pos} from holding a channel now, or null when every segment of its route has room:
     * no route, the ad-hoc allowance, or the full segment or hub nearest the root (the bottleneck the most devices
     * share). One walk up the route, which ends at the first hub that counts devices already; no allocation unless
     * something is full. Without channel limits ({@link #infinite}) only a missing route keeps a device out.
     */
    private @Nullable ChannelShortage shortage(@Nullable Routes current, long pos) {
        if (current == null || !current.contains(pos) || zoneOf.get(pos) < 0) {
            return ChannelShortage.NO_ROUTE;
        }
        if (adHoc) {
            int allowance = Channels.adHoc();
            if (!Channels.hasRoom(channelsHeld, allowance, infinite)) {
                return new ChannelShortage(ChannelShortage.Kind.AD_HOC, Long.MIN_VALUE, channelsHeld, allowance);
            }
        }
        long root = current.root();
        long fullNode = Long.MIN_VALUE;
        long fullParent = Long.MIN_VALUE;
        int fullUsed = 0;
        int fullCapacity = 0;
        for (long node = pos; node != root;) {
            int hubCap = hubCaps.get(node);
            if (hubCap > 0) {
                channelSteps++;
                int used = hubUse.get(node);
                if (!Channels.hasRoom(used, hubCap, infinite)) {
                    return new ChannelShortage(ChannelShortage.Kind.HUB, node, used, hubCap);
                }
                if (used > 0) {
                    // The hub holds its channel upstream already: nothing beyond it is counted again.
                    break;
                }
            }
            long parent = current.parent(node);
            if (parent == SUPER_ROOT) {
                break;
            }
            channelSteps++;
            int capacity = segmentCapacity(node, parent);
            int used = channelUse.get(node);
            if (!Channels.hasRoom(used, capacity, infinite)) {
                fullNode = node;
                fullParent = parent;
                fullUsed = used;
                fullCapacity = capacity;
            }
            node = parent;
        }
        return fullNode == Long.MIN_VALUE ? null : segmentShortage(fullNode, fullParent, fullUsed, fullCapacity);
    }

    /**
     * Takes one channel on every segment of {@code pos}'s route for {@code link}, up to the first hub: there it counts
     * against the hub, and only the hub's first device goes on to take the hub's channel beyond it. After
     * {@link #shortage} found room.
     */
    private void hold(@Nullable Routes current, DeviceLink link, long pos) {
        if (current == null || link.holdsChannel) {
            return;
        }
        long root = current.root();
        for (long node = pos; node != root;) {
            if (hubCaps.get(node) > 0) {
                channelSteps++;
                if (hubUse.addTo(node, 1) > 0) {
                    break;
                }
            }
            long parent = current.parent(node);
            if (parent == SUPER_ROOT) {
                break;
            }
            channelSteps++;
            channelUse.addTo(node, 1);
            node = parent;
        }
        link.holdsChannel = true;
        channelsHeld++;
        newestHeld = Math.max(newestHeld, link.joinSeq);
    }

    /**
     * Gives back {@code link}'s channels on the route of {@code pos}, which must be the route they were taken on; the
     * devices offline here are tried again at the next due check.
     */
    private void release(DeviceLink link, long pos) {
        if (!link.holdsChannel) {
            return;
        }
        link.holdsChannel = false;
        channelsHeld--;
        Routes current = tree;
        if (current != null && current.contains(pos)) {
            long root = current.root();
            for (long node = pos; node != root;) {
                if (hubCaps.get(node) > 0) {
                    channelSteps++;
                    int before = hubUse.addTo(node, -1);
                    if (before <= 1) {
                        // The hub's last device: its channel beyond the hub goes too.
                        hubUse.remove(node);
                    } else {
                        break;
                    }
                }
                long parent = current.parent(node);
                if (parent == SUPER_ROOT) {
                    break;
                }
                channelSteps++;
                if (channelUse.addTo(node, -1) <= 1) {
                    channelUse.remove(node);
                }
                node = parent;
            }
        }
        if (!offline.isEmpty()) {
            retryOffline = true;
        }
    }

    /**
     * Channels the segment from {@code node} to its parent carries: its link's ({@link #baseWidth}, the slower cable, a
     * device link or an antenna pair), and at most {@link Channels#rootLink()} on a controller's root link. A hub's
     * budget is no segment's: see {@link #hubCapacity(long)}.
     */
    private int segmentCapacity(long node, long parent) {
        int capacity = (int) Math.min(Integer.MAX_VALUE, baseWidth(node, parent));
        return isRootLink(parent) ? Math.min(capacity, Channels.rootLink()) : capacity;
    }

    /** Whether a segment whose parent is {@code parent} is a controller's root link. */
    private boolean isRootLink(long parent) {
        return rootController != null && nodeTiers.get(parent) == DEVICE_TIER && isClusterNode(parent);
    }

    /** Names the full segment from {@code node} to {@code parent}: its root link, the slower cable, or the link. */
    private ChannelShortage segmentShortage(long node, long parent, int used, int capacity) {
        if (isRootLink(parent) && Channels.rootLink() <= baseWidth(node, parent)) {
            return new ChannelShortage(ChannelShortage.Kind.ROOT_LINK, node, used, capacity);
        }
        int a = nodeTiers.get(node);
        int b = nodeTiers.get(parent);
        if (a <= DEVICE_TIER && b <= DEVICE_TIER) {
            return new ChannelShortage(ChannelShortage.Kind.LINK, node, used, capacity);
        }
        long cable = a <= DEVICE_TIER
                ? parent
                : b <= DEVICE_TIER ? node : Channels.cable(a) <= Channels.cable(b) ? node : parent;
        return new ChannelShortage(ChannelShortage.Kind.CABLE, cable, used, capacity);
    }

    /** {@code device}, no member, waits offline here for {@code shortage}. */
    private void markOffline(NetworkDevice device, ChannelShortage shortage) {
        DeviceLink link = device.deviceLink();
        NetworkState before = link.offlineBy;
        if (before != null && before != this) {
            before.offline.remove(device);
        }
        offline.add(device);
        link.offlineBy = this;
        link.shortage = shortage;
        linksDirty = true;
    }

    /** Forgets that {@code device} is offline here (it joined, left for another network or is gone). */
    void forgetOffline(NetworkDevice device) {
        DeviceLink link = device.deviceLink();
        if (link.offlineBy == this) {
            link.offlineBy = null;
            link.shortage = null;
            linksLeft.add(device);
            linksDirty = true;
        }
        offline.remove(device);
    }

    /** Forgets every device offline here; they are counted again. */
    private void clearOffline() {
        for (NetworkDevice device : offline) {
            DeviceLink link = device.deviceLink();
            if (link.offlineBy == this) {
                link.offlineBy = null;
                link.shortage = null;
                linksLeft.add(device);
                linksDirty = true;
            }
        }
        offline.clear();
    }

    /** Channel use changed: smart cables read again and route load watchers wake. */
    private void channelsChanged() {
        channelVersion++;
        linksDirty = true;
        for (int i = 0; i < loadWatchers.size(); i++) {
            loadWatchers.get(i).run();
        }
    }

    /**
     * The controller's cluster changed (expansions came or went, the cap changed): routes are rebuilt, since root links
     * and the device limit may differ. Server thread.
     */
    public void clusterChanged() {
        needsRebuild = true;
        // A surge regulator may be gone, or the capacity larger: held-back tasks look again.
        releaseThrottled();
    }

    /**
     * {@code device}'s role is about to change (a controller core going idle or back): it leaves now, under the role
     * the network counted it with, and joins again with its new one at the next rebuild. Call before the change.
     */
    public void roleChanging(NetworkDevice device) {
        leave(device);
        needsRebuild = true;
    }

    /** The devices the cable at {@code pos} hosts (its parts) join this network. */
    private void joinHosted(long pos) {
        DeviceHost host = logic.host(pos);
        if (host == null) {
            return;
        }
        for (int i = 0; i < host.deviceCount(); i++) {
            NetworkDevice device = host.device(i);
            if (device.deviceLink().network != this && !device.isRemoved()) {
                join(device);
            }
        }
    }

    /** The devices attached to the node at {@code pos} from outside it (subnet uplinks) join this network. */
    private void joinAttached(long pos) {
        ObjectArrayList<NetworkDevice> list = logic.attached(pos);
        if (list == null) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            NetworkDevice device = list.get(i);
            if (device.deviceLink().network != this && !device.isRemoved()) {
                join(device);
            }
        }
    }

    /** The devices the cable at {@code pos} hosts (its parts) that are no members yet, added to {@code out}. */
    private void collectHosted(long pos, ObjectArrayList<NetworkDevice> out) {
        DeviceHost host = logic.host(pos);
        if (host == null) {
            return;
        }
        for (int i = 0; i < host.deviceCount(); i++) {
            NetworkDevice device = host.device(i);
            if (device.deviceLink().network != this && !device.isRemoved()) {
                out.add(device);
            }
        }
    }

    /** The devices attached to the node at {@code pos} that are no members yet, added to {@code out}. */
    private void collectAttached(long pos, ObjectArrayList<NetworkDevice> out) {
        ObjectArrayList<NetworkDevice> list = logic.attached(pos);
        if (list == null) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            NetworkDevice device = list.get(i);
            if (device.deviceLink().network != this && !device.isRemoved()) {
                out.add(device);
            }
        }
    }

    /** The owner: the controller in charge's, else the lowest owned device's among the members and {@code waiting}. */
    private OwnerRecord findOwner(ObjectArrayList<NetworkDevice> waiting) {
        NetworkDevice controller = active;
        if (controller != null) {
            return controller.ownerRecord();
        }
        OwnerRecord found = OwnerRecord.UNOWNED;
        long foundPos = Long.MAX_VALUE;
        for (Member member : members.values()) {
            OwnerRecord record = member.device.ownerRecord();
            if (record.isOwned() && member.device.nodePos() < foundPos) {
                found = record;
                foundPos = member.device.nodePos();
            }
        }
        for (int i = 0; i < waiting.size(); i++) {
            NetworkDevice device = waiting.get(i);
            OwnerRecord record = device.ownerRecord();
            if (record.isOwned() && device.nodePos() < foundPos) {
                found = record;
                foundPos = device.nodePos();
            }
        }
        return found;
    }

    /**
     * The ad-hoc route root: the lowest device that is not a cluster block, among the members and {@code waiting};
     * {@code Long.MIN_VALUE} for none (cables only). A controller network is rooted at {@link #SUPER_ROOT} instead.
     */
    private long root(ObjectArrayList<NetworkDevice> waiting) {
        long root = Long.MIN_VALUE;
        for (Member member : members.values()) {
            NetworkDevice device = member.device;
            if (device instanceof ClusterNode) {
                continue;
            }
            long pos = device.nodePos();
            if (root == Long.MIN_VALUE || pos < root) {
                root = pos;
            }
        }
        for (int i = 0; i < waiting.size(); i++) {
            NetworkDevice device = waiting.get(i);
            long pos = device.nodePos();
            if (!(device instanceof ClusterNode) && (root == Long.MIN_VALUE || pos < root)) {
                root = pos;
            }
        }
        return root;
    }

    private void buildRoutes(ObjectArrayList<NetworkDevice> waiting) {
        zoneOf.clear();
        zoneSizes.clear();
        usedZones = 0;
        refreshZoneRoom();
        rootLinks.clear();
        NetworkController controller = activeController();
        rootController = controller;
        RouteTree built;
        if (controller != null) {
            enableRootLinks(controller);
            built = RouteTree.build(SUPER_ROOT, links, width, network.size() + 1);
        } else {
            long root = root(waiting);
            if (root == Long.MIN_VALUE || !network.contains(root)) {
                tree = null;
                return;
            }
            built = logic.graph().routeTree(network, root, width);
        }
        Routes routes = new Routes(built);
        tree = routes;
        assignZones(routes);
    }

    /**
     * Picks the root links: every link from a cluster block that carries them to a node outside the cluster is a
     * candidate; the widest {@code rootLinks()} win, then the lowest cluster block and side, so the choice is stable.
     * Only at rebuilds.
     */
    private void enableRootLinks(NetworkController controller) {
        NetworkGraph graph = logic.graph();
        LongList exits = controller.exitNodes();
        for (int i = 0; i < exits.size(); i++) {
            long node = exits.getLong(i);
            if (!network.contains(node)) {
                continue;
            }
            for (Direction side : SIDES) {
                long neighbour = BlockPos.offset(node, side);
                if (graph.isLinked(node, neighbour) && !isClusterNode(neighbour)) {
                    rootLinks.offer(node, neighbour, baseWidth(node, neighbour));
                }
            }
        }
        rootLinks.enable(controller.rootLinks());
    }

    /** The virtual root's children are the cluster blocks carrying root links; other nodes link as in the graph. */
    private void routeLinks(long pos, LongList out) {
        if (pos != SUPER_ROOT) {
            logic.graph().links(pos, out);
            return;
        }
        NetworkController controller = rootController;
        if (controller == null) {
            return;
        }
        LongList exits = controller.exitNodes();
        for (int i = 0; i < exits.size(); i++) {
            long node = exits.getLong(i);
            if (network.contains(node)) {
                out.add(node);
            }
        }
    }

    private boolean isClusterNode(long pos) {
        return logic.device(pos) instanceof ClusterNode;
    }

    /**
     * Cuts the tree into zones of at most {@code network.zoneSize} nodes, by depth then position (parents before
     * children, and the same zones whatever the node set's hash order): a node joins its parent's zone while that has
     * room and starts a new zone otherwise; a cable painted as a zone border ({@link #isZoneBorder}) always starts a
     * new one. An ad-hoc network is one zone.
     */
    private void assignZones(Routes built) {
        int cap = adHoc ? Integer.MAX_VALUE : StorageServerConfig.ZONE_SIZE.getAsInt();
        if (built.root() == SUPER_ROOT) {
            // The virtual root opens the first zone but is no node, so it takes no room in it.
            zoneOf.put(SUPER_ROOT, newZone());
        }
        long[] nodes = new long[network.size()];
        int count = 0;
        LongIterator it = network.nodes().iterator();
        while (it.hasNext()) {
            long pos = it.nextLong();
            if (built.contains(pos)) {
                nodes[count++] = pos;
            }
        }
        // By position first, so the zones do not depend on the node set's hash order.
        LongArrays.quickSort(nodes, 0, count);
        // Each node's depth, read once: a lookup in the routes costs a few hash probes.
        int[] depths = new int[count];
        int maxDepth = 0;
        for (int i = 0; i < count; i++) {
            depths[i] = built.depth(nodes[i]);
            maxDepth = Math.max(maxDepth, depths[i]);
        }
        // Stable counting sort by depth: parents come before their children, and each depth stays by position.
        int[] starts = new int[maxDepth + 2];
        for (int i = 0; i < count; i++) {
            starts[depths[i] + 1]++;
        }
        for (int d = 1; d < starts.length; d++) {
            starts[d] += starts[d - 1];
        }
        long[] ordered = new long[count];
        for (int i = 0; i < count; i++) {
            ordered[starts[depths[i]]++] = nodes[i];
        }
        for (long pos : ordered) {
            assignZone(built, pos, cap);
        }
    }

    private void assignZone(Routes built, long pos, int cap) {
        int zone;
        if (pos == built.root()) {
            zone = newZone();
        } else {
            int parentZone = zoneOf.get(built.parent(pos));
            boolean room = parentZone >= 0 && zoneSizes.getInt(parentZone) < cap;
            if (room && !isZoneBorder(pos)) {
                zone = parentZone;
            } else {
                zone = newZone();
                if (zone < 0 && room) {
                    // A painted border past the team's zone cap stays in its parent's zone rather than lose its route.
                    zone = parentZone;
                }
            }
        }
        if (zone < 0) {
            // Past the team's zone cap: the node keeps no zone, and devices there get no route.
            return;
        }
        zoneOf.put(pos, zone);
        int held = zoneSizes.getInt(zone);
        if (held == 0) {
            usedZones++;
        }
        zoneSizes.set(zone, held + 1);
    }

    /**
     * Whether the node at {@code pos} starts a zone of its own because its cable is painted as a zone border
     * ({@link ZoneBorders}). An ad-hoc network stays one zone. One hash lookup; at rebuilds, patches and attaches.
     */
    public boolean isZoneBorder(long pos) {
        return !adHoc && logic.zoneBorders().isBorder(pos);
    }

    /** A new zone, or -1 when the team's zone cap leaves no room for one. */
    private int newZone() {
        if (zoneRoom != Integer.MAX_VALUE && zoneCount() >= zoneRoom) {
            return -1;
        }
        zoneSizes.add(0);
        return zoneSizes.size() - 1;
    }

    /** Works out how many zones this network may have under its team's zone cap; at rebuilds and patches. */
    private void refreshZoneRoom() {
        int cap = StorageServerConfig.CAP_ZONES_PER_TEAM.getAsInt();
        UUID team = capTeam;
        zoneRoom = cap <= 0 || team == null || adHoc
                ? Integer.MAX_VALUE
                : Math.max(0, cap - logic.teamZones(team, this));
    }

    /**
     * Width of the link between two nodes for routes: 0 between two cluster blocks and on a cluster block's link that
     * is not an enabled root link (always 0 without a controller), unlimited from the virtual root, else
     * {@link #baseWidth}.
     */
    private long edgeWidth(long from, long to) {
        if (from == SUPER_ROOT || to == SUPER_ROOT) {
            return RouteTree.ROOT_WIDTH;
        }
        int a = nodeTiers.get(from);
        int b = nodeTiers.get(to);
        // Only device nodes can be cluster blocks; cables skip the lookup.
        boolean fromCluster = a == DEVICE_TIER && isClusterNode(from);
        boolean toCluster = b == DEVICE_TIER && isClusterNode(to);
        if ((fromCluster || toCluster) && rootController == null) {
            return 0;
        }
        return rootLinks.width(from, to, SUPER_ROOT, fromCluster, toCluster, baseWidth(from, to, a, b));
    }

    /**
     * Width of the link between two nodes in channels: the slower cable's; two devices touching link at tier 1, two
     * devices linked remotely ({@link RemoteLinkNode}) at that link's own channels. Routes take the widest links, and a
     * segment carries this many channels ({@link #segmentCapacity}).
     */
    private long baseWidth(long from, long to) {
        return baseWidth(from, to, nodeTiers.get(from), nodeTiers.get(to));
    }

    /** {@link #baseWidth(long, long)} with the two nodes' tiers already read. */
    private long baseWidth(long from, long to, int a, int b) {
        if (a < 0 || b < 0) {
            return 0;
        }
        if (a == DEVICE_TIER && b == DEVICE_TIER) {
            if (RootLinks.sideBit(from, to) == 0 && logic.device(from) instanceof RemoteLinkNode remote) {
                return remote.remoteLinkWidth(to);
            }
            return Channels.cable(1);
        }
        if (a == DEVICE_TIER) {
            return Channels.cable(b);
        }
        if (b == DEVICE_TIER) {
            return Channels.cable(a);
        }
        return Math.min(Channels.cable(a), Channels.cable(b));
    }

    /**
     * A node's links were re-read. Cables re-read theirs whenever a neighbour changes, which includes every cable
     * placed next to them, so this changes routes only when a link they use is gone: the node's own link to its parent,
     * or a child's link to it. That node and the nodes routed through it get new routes at the next {@link #patch}; the
     * rest keep theirs. A new link needs no change; like an attached node's other links it counts from the next full
     * rebuild.
     */
    void nodeInvalidated(long pos) {
        Routes current = tree;
        if (needsRebuild || current == null || !current.contains(pos)) {
            needsRebuild = true;
            return;
        }
        NetworkGraph graph = logic.graph();
        long root = current.root();
        long parent = current.parent(pos);
        if (pos != root && parent != SUPER_ROOT && !graph.isLinked(pos, parent)) {
            cut(pos);
        }
        for (Direction side : SIDES) {
            long neighbour = BlockPos.offset(pos, side);
            if (neighbour != root && current.contains(neighbour) && current.parent(neighbour) == pos
                    && !graph.isLinked(neighbour, pos)) {
                cut(neighbour);
            }
        }
        if (!needsRebuild && logic.device(pos) instanceof RemoteLinkNode remote
                && hasNewRemoteLink(current, pos, remote)) {
            needsRebuild = true;
        }
    }

    /** Reused by {@link #hasNewRemoteLink}; server thread only. */
    private final LongArrayList remoteScratch = new LongArrayList(1);

    /**
     * Whether {@code pos} has a remote link (an antenna pair) to a node of this network that its route does not use
     * yet. The route tree only attaches joining nodes, so a link between two nodes already routed would otherwise wait
     * for the next full rebuild; a pairing is rare, so a full rebuild is cheap enough here.
     */
    private boolean hasNewRemoteLink(Routes current, long pos, RemoteLinkNode remote) {
        remoteScratch.clear();
        remote.remoteLinks(remoteScratch);
        for (int i = 0; i < remoteScratch.size(); i++) {
            long far = remoteScratch.getLong(i);
            if (current.contains(far) && current.parent(pos) != far && current.parent(far) != pos) {
                return true;
            }
        }
        return false;
    }

    private void cut(long pos) {
        cutNodes.add(pos);
        patchPending = true;
    }

    /**
     * The node at {@code pos} left the graph. A plain cable only detaches the nodes routed through it, which get new
     * routes at the next {@link #patch} (M6d, per-zone rebuilds); anything else (a device, a cable with parts, a root
     * link) rebuilds the whole network.
     */
    void nodeRemoved(long pos, boolean plainCable) {
        int tier = nodeTiers.remove(pos);
        smartCables.remove(pos);
        Routes current = tree;
        if (!plainCable || tier <= DEVICE_TIER || needsRebuild || current == null || rootLinks.isOutside(pos)) {
            needsRebuild = true;
            return;
        }
        if (current.contains(pos)) {
            removedNodes.add(pos);
            cutNodes.remove(pos);
            patchPending = true;
        }
    }

    private void clearPatch() {
        patchPending = false;
        removedNodes.clear();
        cutNodes.clear();
    }

    /**
     * Gives new routes to the nodes whose route ran through a removed cable or a cut link, and to nobody else: the
     * zones they leave shrink and they join their new parents' zones. Falls back to a full {@link #rebuild()} when the
     * change detached more than a quarter of the network or the routes carry patches for more than half of it (each at
     * least {@link #PATCH_MIN} nodes); cost otherwise one memoised walk over the network plus a search over the
     * detached nodes, no block entity reads. The devices on detached nodes give their channels back on their old routes
     * and take them again on the new ones, oldest first; only their routes are walked, so the rest of the network is
     * not counted again.
     */
    private void patch() {
        Routes current = tree;
        int size = network.size();
        LongArrayList detached = new LongArrayList();
        LongIterator cut = cutNodes.iterator();
        while (cut.hasNext()) {
            if (rootLinks.isOutside(cut.nextLong())) {
                // A root link was cut: another candidate link may take its place, which only a rebuild picks.
                rebuild();
                return;
            }
        }
        if (current == null
                || !current.detached(network.nodes(), removedNodes, cutNodes, Math.max(PATCH_MIN, size / 4), detached)
                || current.patched() + detached.size() > Math.max(PATCH_MIN, size / 2)) {
            rebuild();
            return;
        }
        patches++;
        refreshZoneRoom();
        ObjectArrayList<NetworkDevice> rerouted = releaseDetached(detached);
        LongIterator removed = removedNodes.iterator();
        while (removed.hasNext()) {
            leaveZone(removed.nextLong());
        }
        for (int i = 0; i < detached.size(); i++) {
            leaveZone(detached.getLong(i));
        }
        LongArrayList settled = new LongArrayList(detached.size());
        current.reroute(removedNodes, detached, links, width, settled);
        int cap = adHoc ? Integer.MAX_VALUE : StorageServerConfig.ZONE_SIZE.getAsInt();
        for (int i = 0; i < settled.size(); i++) {
            assignZone(current, settled.getLong(i), cap);
        }
        clearPatch();
        recountRerouted(current, rerouted);
    }

    /**
     * Before a patch reroutes {@code detached}: the members on those nodes give their channels back on the routes they
     * still have. Returns them; a cable removal or cut link detaches cables mostly, so this is usually a few devices.
     */
    private ObjectArrayList<NetworkDevice> releaseDetached(LongArrayList detached) {
        ObjectArrayList<NetworkDevice> rerouted = new ObjectArrayList<>(0);
        if (detached.isEmpty() || members.isEmpty()) {
            return rerouted;
        }
        LongOpenHashSet moved = new LongOpenHashSet(detached);
        for (Member member : members.values()) {
            NetworkDevice device = member.device;
            if (limited(device) && moved.contains(device.nodePos())) {
                release(device.deviceLink(), device.nodePos());
                rerouted.add(device);
            }
        }
        return rerouted;
    }

    /**
     * After a patch: {@code rerouted} take their channels on their new routes, oldest first; a device that finds no
     * room goes offline, and when newer devices hold the room it needs a full recount follows. Devices offline here are
     * tried again for the room the old routes freed.
     */
    private void recountRerouted(Routes current, ObjectArrayList<NetworkDevice> rerouted) {
        if (rerouted.isEmpty()) {
            return;
        }
        rerouted.sort(NetworkState::byJoinOrder);
        for (int i = 0; i < rerouted.size(); i++) {
            NetworkDevice device = rerouted.get(i);
            DeviceLink link = device.deviceLink();
            ChannelShortage shortage = shortage(current, device.nodePos());
            if (shortage == null) {
                hold(current, link, device.nodePos());
                continue;
            }
            if (link.joinSeq < newestHeld) {
                recountDue = true;
            }
            leave(device);
            markOffline(device, shortage);
        }
        if (!offline.isEmpty()) {
            retryOffline = true;
        }
        channelsChanged();
    }

    /** Takes {@code pos} out of its zone; its route segment changed or is gone. */
    private void leaveZone(long pos) {
        int zone = zoneOf.remove(pos);
        if (zone >= 0 && zone < zoneSizes.size()) {
            int left = zoneSizes.getInt(zone) - 1;
            zoneSizes.set(zone, left);
            if (left == 0) {
                usedZones--;
            }
        }
    }

    /** Route patches since the network was created (cable removals and cut links handled without a rebuild). */
    public int patches() {
        return patches;
    }

    /** Notes the tier of the cable at {@code pos} and, if it is a smart one, tracks its load display. */
    private void readCable(long pos) {
        StorageCableConduitType cable = logic.cableType(pos);
        nodeTiers.put(pos, cable == null ? 1 : cable.level());
        if (cable != null && cable.look() == CableLook.SMART) {
            smartCables.add(pos);
        }
    }

    /** How many smart cables the network shows load on. */
    public int smartCableCount() {
        return smartCables.size();
    }

    /** A node joined the network after the last rebuild: it hangs off its best neighbour without a rebuild. */
    void nodeAdded(long pos) {
        Quarantines quarantines = logic.quarantines();
        if (quarantined) {
            quarantines.add(pos);
        } else if (quarantines.contains(pos)) {
            quarantine(quarantines);
        }
        StorageDeviceBlockEntity device = logic.device(pos);
        if (device != null) {
            nodeTiers.put(pos, DEVICE_TIER);
            if (device.role() == StorageDevice.Role.CONTROLLER || device instanceof ClusterNode) {
                // A new root, a second controller or a cluster block: routes and status change everywhere.
                needsRebuild = true;
                joinAttached(pos);
                if (device.network() != this) {
                    join(device);
                }
                return;
            }
            if (device instanceof ChannelHub hub) {
                // Routes through the new hub, and the budgets behind it, come with a rebuild; hubs are placed rarely.
                hubCaps.put(pos, Math.max(1, hub.hubCapacity()));
                needsRebuild = true;
            }
        } else {
            readCable(pos);
        }
        // The node gets its route first, so the devices on it count their channels along it as they join.
        attach(pos);
        joinAttached(pos);
        if (device != null) {
            if (device.network() != this) {
                join(device);
            }
            return;
        }
        int before = members.size();
        joinHosted(pos);
        if (adHoc && members.size() != before) {
            // An ad-hoc network's root is its lowest device, which a cable's parts may have moved.
            needsRebuild = true;
        }
    }

    /** Hangs the node at {@code pos} off its best routed neighbour, or asks for a rebuild when it cannot. */
    private void attach(long pos) {
        Routes current = tree;
        if (needsRebuild || current == null || !current.attach(pos, links, width)) {
            needsRebuild = true;
            return;
        }
        assignZone(current, pos, adHoc ? Integer.MAX_VALUE : StorageServerConfig.ZONE_SIZE.getAsInt());
    }

    /**
     * Moves {@code device} into this network and indexes its providers. A device that counts towards the limits gets
     * its join sequence the first time and, while the routes are up to date, takes one channel on every segment of its
     * route; without room it stays out, offline with the full link named, and when it is older than devices holding
     * channels a full recount follows. While routes wait for a rebuild it joins without a channel and the rebuild
     * counts it.
     */
    void join(NetworkDevice device) {
        DeviceLink link = device.deviceLink();
        NetworkState previous = link.network;
        if (previous == this) {
            return;
        }
        if (previous != null) {
            previous.leave(device);
        }
        NetworkState offlineIn = link.offlineBy;
        if (offlineIn != null) {
            offlineIn.forgetOffline(device);
        }
        Routes counted = null;
        if (limited(device)) {
            assignSeq(link);
            Routes current = tree;
            if (!counting && !needsRebuild && current != null) {
                ChannelShortage shortage = shortage(current, device.nodePos());
                if (shortage != null) {
                    if (link.joinSeq < newestHeld) {
                        // Newer devices hold the room this one had: counting again in full gives it back.
                        recountDue = true;
                    }
                    markOffline(device, shortage);
                    return;
                }
                counted = current;
            }
            if (limitedDevices >= deviceLimit()) {
                // The network stays up and turns newcomers away until a device leaves.
                refuse(device);
                return;
            }
        }
        Member member = new Member(device);
        members.put(device, member);
        roles.addTo(device.role(), 1);
        if (piece(device)) {
            pieces++;
        }
        if (limited(device)) {
            limitedDevices++;
        }
        if (device instanceof NetworkPowerSource) {
            powerSources++;
        }
        if (device instanceof ServiceDevice service) {
            services(service.service()).add(device);
            serviceChanged(service.service());
        }
        if (device instanceof NetworkEnergyCell cell) {
            cells.add(cell.cellStore());
            mu().cellJoined(cell.cellStore());
        }
        if (device instanceof PatternHost host) {
            patterns.register(host.patternProvider());
        }
        link.network = this;
        labels.add(device, link.label());
        shedding.add(link);
        // A new controller or energy source changes the capacity, the arming or the limitless store.
        shedding.invalidate();
        if (device.role() == StorageDevice.Role.CONTROLLER) {
            controllers.add(device);
            pickActive();
        }
        List<StorageProvider> providers = device.providers();
        for (int i = 0; i < providers.size(); i++) {
            member.entries.add(index.add(providers.get(i)));
        }
        addViews(member, providers);
        for (int i = 0; i < link.tasks.size(); i++) {
            group.add(link.tasks.get(i));
        }
        for (int i = 0; i < link.listeners.size(); i++) {
            index.listen(link.listenedKeys.get(i), link.listeners.get(i));
        }
        if (counted != null) {
            hold(counted, link, device.nodePos());
            channelsChanged();
        }
        device.networkChanged(this);
        linksDirty = true;
        // After the device's own providers are indexed, so the first call sees the full total.
        for (int i = 0; i < link.listeners.size(); i++) {
            StackKey key = link.listenedKeys.get(i);
            link.listeners.get(i).keyChanged(key, index.amount(key));
        }
        refreshControllers();
        devicesChanged();
    }

    /**
     * Takes {@code device} out of this network: its providers leave the index and its tasks the scheduler. Members are
     * device instances; a reloaded block at the same position is another device.
     */
    void leave(NetworkDevice device) {
        Member member = members.remove(device);
        if (member == null) {
            return;
        }
        roles.addTo(device.role(), -1);
        if (piece(device)) {
            pieces--;
        }
        if (limited(device)) {
            limitedDevices--;
            if (!refused.isEmpty()) {
                // Room for a device that was turned away.
                needsRebuild = true;
            }
        }
        if (device instanceof NetworkPowerSource) {
            powerSources--;
        }
        if (device instanceof ServiceDevice service) {
            services(service.service()).remove(device);
            serviceChanged(service.service());
        }
        if (device instanceof NetworkEnergyCell cell) {
            cells.remove(cell.cellStore());
            StorageEnergy current = muEnergy;
            if (current != null) {
                current.cellLeft(cell.cellStore());
            }
        }
        if (device instanceof PatternHost host) {
            patterns.unregister(host.patternProvider());
        }
        if (controllers.remove(device)) {
            pickActive();
        }
        labels.remove(device, device.deviceLink().label());
        for (int i = 0; i < member.entries.size(); i++) {
            index.remove(member.entries.get(i));
        }
        removeViews(member);
        DeviceLink link = device.deviceLink();
        if (link.holdsChannel) {
            release(link, device.nodePos());
            channelsChanged();
        }
        throttled.remove(link);
        shedding.remove(link);
        shedding.invalidate();
        for (int i = 0; i < link.tasks.size(); i++) {
            TaskAdapter.leave(link.tasks.get(i));
        }
        for (int i = 0; i < link.listeners.size(); i++) {
            index.unlisten(link.listenedKeys.get(i), link.listeners.get(i));
        }
        if (link.network == this) {
            link.network = null;
        }
        linksLeft.add(device);
        linksDirty = true;
        if (device instanceof NetworkController controller) {
            controller.refreshStatus();
        }
        device.networkChanged(null);
        refreshControllers();
        devicesChanged();
    }

    /** Moves a member from {@code previous} to {@code next} in the label index. */
    void relabel(NetworkDevice device, DeviceLabel previous, DeviceLabel next) {
        if (members.containsKey(device)) {
            labels.remove(device, previous);
            labels.add(device, next);
            // What labels hold changed, as on a join or leave: graphs counting a label's devices look again.
            runDeviceWatchers();
        }
    }

    /** A member was turned off or on by logic (M7L): the device watchers look again. */
    void deviceStateChanged() {
        runDeviceWatchers();
    }

    /** A member's power priority changed from {@code from}: load shedding moves it and pauses or resumes it. */
    void repriority(DeviceLink link, int from) {
        if (members.containsKey(link.device)) {
            boolean paused = link.powerPaused;
            shedding.moved(link, from);
            if (paused != link.powerPaused) {
                runDeviceWatchers();
            }
        }
    }

    /**
     * Whether load shedding is armed (charged once past the highest resume level): kept by the controller in charge, or
     * by this state for a network without one (its buffer is not saved either).
     */
    public boolean sheddingArmed() {
        NetworkController controller = activeController();
        return controller != null ? controller.sheddingArmed() : adHocArmed;
    }

    private void armShedding() {
        NetworkController controller = activeController();
        if (controller != null) {
            controller.armShedding();
        } else {
            adHocArmed = true;
        }
    }

    /** The network's load shedding (M7L2): its shed level and members by power priority. Server thread. */
    public LoadShedding shedding() {
        return shedding;
    }

    /**
     * Runs {@code watcher} whenever the shed level changes ({@link LoadShedding#level()}), from the network's tick; it
     * must only mark work and wake a task. Adding one twice adds it once; it stays with this network like
     * {@link #watchEnergy}.
     */
    @SuppressWarnings("ReferenceEquality") // as in watchEnergy
    public void watchShedding(Runnable watcher) {
        for (int i = 0; i < sheddingWatchers.size(); i++) {
            if (sheddingWatchers.get(i) == watcher) {
                return;
            }
        }
        sheddingWatchers.add(watcher);
    }

    /** Stops running {@code watcher}; nothing happens if it was not watching. */
    public void unwatchShedding(Runnable watcher) {
        sheddingWatchers.remove(watcher);
    }

    /**
     * Brings the shed level up to date with the stored FE and, when it changed, tells the device and shedding watchers
     * once. While the FE stays in the band the level holds in this is two comparisons on the running totals; FE reaches
     * the network through too many stores (the controller's buffer, cells charged by their neighbours, a subnet's
     * parent) for one commit hook, so the network's tick asks, as it does for its energy watchers.
     */
    private void updateShedding() {
        long stored = storedEnergy();
        long capacity = energyCapacity();
        boolean limitless = powerSources > 0;
        if (shedding.holds(stored, capacity, limitless)) {
            return;
        }
        boolean armed = sheddingArmed();
        if (!armed && !limitless && shedding.arms(stored, capacity)) {
            armShedding();
            armed = true;
        }
        if (shedding.update(stored, capacity, limitless, armed)) {
            runDeviceWatchers();
            for (int i = 0; i < sheddingWatchers.size(); i++) {
                sheddingWatchers.get(i).run();
            }
        }
    }

    private void runDeviceWatchers() {
        for (int i = 0; i < deviceWatchers.size(); i++) {
            deviceWatchers.get(i).run();
        }
    }

    /** Hears of neighbour updates of the network's labelled devices (M7L); see {@link #watchNeighbours}. */
    @FunctionalInterface
    public interface NeighbourWatcher {
        /** {@code device}, which carries a label, had a neighbour update; must only mark work and wake a task. */
        void neighbourChanged(NetworkDevice device);
    }

    /**
     * Tells {@code watcher} about neighbour updates of the network's labelled devices (redstone into them, for logic
     * graphs). Adding one twice adds it once; it stays with this network like {@link #watchEnergy}.
     */
    @SuppressWarnings("ReferenceEquality") // as in watchEnergy
    public void watchNeighbours(NeighbourWatcher watcher) {
        for (int i = 0; i < neighbourWatchers.size(); i++) {
            if (neighbourWatchers.get(i) == watcher) {
                return;
            }
        }
        neighbourWatchers.add(watcher);
    }

    /**
     * The log position of the oldest key still in the log of keys that appeared (total from 0 to above 0; B09 item 18).
     * A reader whose position is below it missed keys and walks {@link #contents()} once instead.
     */
    public long appearedStart() {
        return index.appearedStart();
    }

    /** The log position the next key that appears will get; a reader is up to date at this position. */
    public long appearedEnd() {
        return index.appearedEnd();
    }

    /** The key that appeared at log position {@code position}, from {@link #appearedStart()} to before the end. */
    public StackKey appearedKey(long position) {
        return index.appeared(position);
    }

    /** Stops telling {@code watcher}; nothing happens if it was not watching. */
    public void unwatchNeighbours(NeighbourWatcher watcher) {
        neighbourWatchers.remove(watcher);
    }

    /**
     * A neighbour of {@code device} changed. Called by device blocks and cable parts on every neighbour update, so it
     * returns after one check when the device has no label or nothing watches.
     */
    public static void neighbourChanged(NetworkDevice device) {
        NetworkState network = device.deviceLink().network;
        if (network == null || network.neighbourWatchers.isEmpty() || device.label().isEmpty()) {
            return;
        }
        for (int i = 0; i < network.neighbourWatchers.size(); i++) {
            network.neighbourWatchers.get(i).neighbourChanged(device);
        }
    }

    /**
     * Runs {@code watcher} whenever a drive cell of this network turns full or stops being full (M7L); it must only
     * mark work and wake a task. Adding one twice adds it once; it stays with this network like {@link #watchEnergy}.
     */
    @SuppressWarnings("ReferenceEquality") // as in watchEnergy
    public void watchFill(Runnable watcher) {
        for (int i = 0; i < fillWatchers.size(); i++) {
            if (fillWatchers.get(i) == watcher) {
                return;
            }
        }
        fillWatchers.add(watcher);
    }

    /** Stops running {@code watcher}; nothing happens if it was not watching. */
    public void unwatchFill(Runnable watcher) {
        fillWatchers.remove(watcher);
    }

    /** A drive's count of full cells changed; the fill watchers look again. */
    public void fullCellsChanged() {
        for (int i = 0; i < fillWatchers.size(); i++) {
            fillWatchers.get(i).run();
        }
    }

    /** The notices logic graphs posted to this network's log (M7L), newest last; not saved. Server thread. */
    public NetworkNotices notices() {
        return notices;
    }

    /**
     * Hands every line posted to any network's log, with its network, to {@code listener} (logic remotes push notices
     * to their holders, M7L2). Set once during mod construction; null for nobody. The listener runs on the server
     * thread right after the line was kept, so it must be cheap when nobody needs the line.
     */
    public static void listenNotices(@Nullable BiConsumer<NetworkState, NetworkNotices.Notice> listener) {
        noticeListener = listener;
    }

    private void noticePosted(NetworkNotices.Notice notice) {
        BiConsumer<NetworkState, NetworkNotices.Notice> listener = noticeListener;
        if (listener != null) {
            listener.accept(this, notice);
        }
    }

    /**
     * The notices logic graphs sent to this network's displays (M7L), newest last; not saved. Display screens set to
     * show logic notices read the newest few. Server thread.
     */
    public NetworkNotices displayNotices() {
        return displayNotices;
    }

    /** Adds {@code notice} to {@link #displayNotices()} and tells the screens showing them. */
    public void postDisplayNotice(NetworkNotices.Notice notice) {
        displayNotices.post(notice);
        for (int i = 0; i < noticeWatchers.size(); i++) {
            noticeWatchers.get(i).run();
        }
    }

    /** Runs {@code watcher} after each display notice; adding the same watcher twice has no effect. */
    @SuppressWarnings("ReferenceEquality") // a watcher is one object
    public void watchDisplayNotices(Runnable watcher) {
        for (int i = 0; i < noticeWatchers.size(); i++) {
            if (noticeWatchers.get(i) == watcher) {
                return;
            }
        }
        noticeWatchers.add(watcher);
    }

    /** Stops running {@code watcher}; nothing happens if it was not watching. */
    public void unwatchDisplayNotices(Runnable watcher) {
        noticeWatchers.remove(watcher);
    }

    /** The history series recorders claimed on this network (M7L2 history charts). Server thread. */
    public HistoryClaims historyClaims() {
        return historyClaims;
    }

    /** The members' label index (M7L): which devices carry which label. Server thread. */
    public NetworkLabels labels() {
        return labels;
    }

    /**
     * The network's 16 bundled redstone channels (M7L2). A subnet is its own network, so it has its own channels in
     * every subnet mode. Server thread.
     */
    public RedstoneChannels channels() {
        return channels;
    }

    /** Re-reads {@code device}'s providers after it reported a change. */
    void reindex(NetworkDevice device) {
        Member member = members.get(device);
        if (member == null) {
            return;
        }
        for (int i = 0; i < member.entries.size(); i++) {
            index.remove(member.entries.get(i));
        }
        member.entries.clear();
        removeViews(member);
        List<StorageProvider> providers = device.providers();
        for (int i = 0; i < providers.size(); i++) {
            member.entries.add(index.add(providers.get(i)));
        }
        addViews(member, providers);
    }

    private void addViews(Member member, List<StorageProvider> providers) {
        for (int i = 0; i < providers.size(); i++) {
            if (providers.get(i) instanceof NetworkView view) {
                ObjectArrayList<NetworkView> own = member.views;
                if (own == null) {
                    own = new ObjectArrayList<>(1);
                    member.views = own;
                }
                own.add(view);
                views.add(view);
                patterns.linksChanged();
            }
        }
    }

    private void removeViews(Member member) {
        ObjectArrayList<NetworkView> own = member.views;
        if (own != null && !own.isEmpty()) {
            for (int i = 0; i < own.size(); i++) {
                views.remove(own.get(i));
            }
            own.clear();
            patterns.linksChanged();
        }
    }

    /**
     * The other networks this one sees through its members' views (subnet mounts, M7 C30): where items are shared,
     * patterns and crafting CPUs are too. Each network once, this one never; empty without views. Allocates a list when
     * there are views, so call it per request or job run, not per item moved. Server thread.
     */
    public List<NetworkState> linkedNetworks() {
        if (views.isEmpty()) {
            return List.of();
        }
        ObjectArrayList<NetworkState> linked = new ObjectArrayList<>(views.size());
        for (int i = 0; i < views.size(); i++) {
            NetworkState other = views.get(i).viewed();
            if (other != null && other != this && !other.closed && !linked.contains(other)) {
                linked.add(other);
            }
        }
        return linked;
    }

    /**
     * Whether a store or take in this network can reach {@code goal}: through a view of this network
     * ({@link NetworkView#reaches()}: a subnet mount, a storage bus on another network's device), or further through
     * the views of the networks those reach. A storage bus in {@code goal} that mounts a device of this network would
     * close a loop (B09 item 8) and refuses. Walks only networks with views; allocates its visited list only then, so
     * call it when a mount is checked, not per move. Server thread.
     */
    public boolean reaches(NetworkState goal) {
        if (views.isEmpty()) {
            return false;
        }
        ObjectArrayList<NetworkState> visited = new ObjectArrayList<>(4);
        ObjectArrayList<NetworkState> pending = new ObjectArrayList<>(4);
        visited.add(this);
        pending.add(this);
        while (!pending.isEmpty()) {
            NetworkState next = pending.pop();
            for (int i = 0; i < next.views.size(); i++) {
                NetworkState reached = next.views.get(i).reaches();
                if (reached == null || reached.closed) {
                    continue;
                }
                if (reached == goal) {
                    return true;
                }
                if (!visited.contains(reached)) {
                    visited.add(reached);
                    pending.add(reached);
                }
            }
        }
        return false;
    }

    /** Whether {@code other} is one of {@link #linkedNetworks()}, without building the list. */
    public boolean linkedTo(NetworkState other) {
        for (int i = 0; i < views.size(); i++) {
            NetworkState viewed = views.get(i).viewed();
            if (viewed == other && other != this && !other.closed) {
                return true;
            }
        }
        return false;
    }

    /** The pattern registries of {@link #linkedNetworks()}, which this network's own registry includes. */
    private List<NetworkPatterns> linkedPatterns() {
        if (views.isEmpty()) {
            return List.of();
        }
        List<NetworkState> linked = linkedNetworks();
        ObjectArrayList<NetworkPatterns> out = new ObjectArrayList<>(linked.size());
        for (int i = 0; i < linked.size(); i++) {
            out.add(linked.get(i).patterns);
        }
        return out;
    }

    /** Re-reads the patterns of {@code device} after it reported a change. */
    void repattern(NetworkDevice device) {
        if (device instanceof PatternHost host && members.containsKey(device)) {
            patterns.register(host.patternProvider());
        }
    }

    /** The patterns of this network's pattern hosts and the craftable-key index (M7a). */
    public NetworkPatterns patterns() {
        return patterns;
    }

    /** Hands every device to {@code survivor} (a merge) or to none, and drops the scheduler. */
    void close(@Nullable NetworkState survivor) {
        closed = true;
        LinkDwell.resumed(this);
        if (quarantined && survivor != null && !survivor.quarantined) {
            // A merge keeps the freeze: the survivor's own nodes join the quarantine too.
            survivor.quarantine(logic.quarantines());
        }
        // Devices offline here are counted again by the network their nodes end up in.
        clearOffline();
        if (survivor != null) {
            // The survivor's routes do not reach these devices yet: they join without channels until it rebuilds.
            survivor.markChanged();
        }
        NetworkDevice[] devices = members.keySet().toArray(new NetworkDevice[0]);
        for (NetworkDevice device : devices) {
            if (survivor != null) {
                survivor.join(device);
            } else {
                leave(device);
            }
        }
        group.close();
        StorageEnergy current = muEnergy;
        if (current != null) {
            current.close();
        }
    }

    /** Whether this state was closed: its network merged into another or is gone. O(1). */
    public boolean isClosed() {
        return closed;
    }

    /** Whether a quarantine tag froze the network. O(1). */
    public boolean quarantined() {
        return quarantined;
    }

    /**
     * Freezes ({@code on}) or restores the network: frozen, no operation runs, devices' tasks wait and nothing is lost;
     * restored, the tasks run again at once. The state is saved with the level. Server thread.
     */
    public void setQuarantined(boolean on) {
        Quarantines quarantines = logic.quarantines();
        if (on) {
            quarantine(quarantines);
            return;
        }
        quarantines.removeAll(network.nodes());
        if (!quarantined) {
            return;
        }
        quarantined = false;
        for (NetworkDevice device : members.keySet()) {
            device.deviceLink().wakeAll();
        }
        releaseThrottled();
    }

    private void quarantine(Quarantines quarantines) {
        quarantined = true;
        quarantines.addAll(network.nodes());
    }

    /** A new state (a split part, a network built again after a restart) picks its quarantine up from its nodes. */
    void readQuarantine() {
        Quarantines quarantines = logic.quarantines();
        if (!quarantined && quarantines.containsAny(network.nodes())) {
            quarantine(quarantines);
        }
    }

    /** Once per tick while the network is active: due rebuilds, key releases and status changes. O(1) when idle. */
    void tick(long gameTime) {
        rebuildIfDue(gameTime);
        if (muEnergy != null || !members.isEmpty()) {
            // One compare unless the buffer operations pay from first changed (a controller came or went).
            syncEnergy();
        }
        StorageEnergy energyPart = muEnergy;
        if (energyPart != null) {
            energyPart.tick();
        }
        if ((gameTime + network.id()) % StorageIndex.RELEASE_INTERVAL == 0) {
            index.releaseUnused();
        }
        Status status = status();
        boolean powered = isPowered();
        if (status != lastStatus || powered != lastPowered) {
            refreshControllers();
            devicesChanged();
        }
        if (!linksPaused) {
            updateLinks(gameTime, powered && activeController() != null);
        }
        NetworkController controller = rootController;
        if (controller != null && (gameTime + network.id()) % LOAD_INTERVAL == 0) {
            controller.refreshLoad(this);
        }
        if (smartCables.size() > 0 && SmartCables.due(gameTime, network.id())) {
            smartCables.refresh(this, logic.level(), channelVersion);
        }
        if (shedding.active() || shedding.level() > 0) {
            // Two comparisons while the FE stays in the band; devices are visited only when the level changes.
            updateShedding();
        }
        if (!energyWatchers.isEmpty() || !throttled.isEmpty()) {
            long stored = storedEnergy();
            if (stored != watchedEnergy) {
                watchedEnergy = stored;
                long capacity = energyCapacity();
                for (int i = 0; i < energyWatchers.size(); i++) {
                    energyWatchers.get(i).energyChanged(stored, capacity);
                }
                // Held-back automation runs again once the FE is back above the regulator's resume level.
                if (!throttled.isEmpty() && !OperationCost.belowPercent(stored, capacity, surgeResumePercent())) {
                    releaseThrottled();
                }
            }
        }
    }

    /**
     * Whether a surge regulator holds automation back now: the network has one, no limitless power, and less FE than
     * its reserve. O(1).
     */
    private boolean surgeHolds(FeBuffer buffer) {
        int percent = surgeReservePercent();
        return percent > 0 && powerSources == 0
                && OperationCost.belowPercent(buffer.stored(), buffer.capacity(), percent);
    }

    /**
     * Holds {@code link}'s automation back: its tasks wait (at most {@link #SURGE_RETRY}) until the energy watch in
     * {@link #tick} sees the FE back at {@link #surgeResumePercent()}.
     */
    private void throttle(DeviceLink link) {
        throttled.add(link);
    }

    /** Wakes every task a surge regulator held back. */
    private void releaseThrottled() {
        if (throttled.isEmpty()) {
            return;
        }
        // Waking only marks tasks due for the scheduler's next pass; nothing here changes the set.
        for (DeviceLink link : throttled) {
            link.wakeAll();
        }
        throttled.clear();
    }

    /**
     * Twice the regulator's reserve (at most 100 %), so automation that resumes does not drain the network back under
     * the reserve at once and flap; 0 without a regulator.
     */
    private int surgeResumePercent() {
        return Math.min(100, surgeReservePercent() * 2);
    }

    /** Items stored in the network's providers together. O(1). */
    public long storedItems() {
        return index.itemTotal();
    }

    /** mB of fluid stored in the network's providers together. O(1). */
    public long storedFluidMb() {
        return index.fluidTotal();
    }

    /**
     * The controller's telemetry history (a telemetry recorder in its cluster), or null without one or without a
     * controller. Server thread.
     */
    public @Nullable NetworkHistory history() {
        NetworkController controller = activeController();
        return controller == null ? null : controller.history();
    }

    /**
     * Runs {@code watcher} whenever a device joins or leaves this network (an alarm on the device limit); it must only
     * wake a task. Adding one twice adds it once; it stays with this network like {@link #watchEnergy}.
     */
    @SuppressWarnings("ReferenceEquality") // as in watchEnergy
    public void watchDevices(Runnable watcher) {
        for (int i = 0; i < deviceWatchers.size(); i++) {
            if (deviceWatchers.get(i) == watcher) {
                return;
            }
        }
        deviceWatchers.add(watcher);
    }

    /** Stops running {@code watcher}; nothing happens if it was not watching. */
    public void unwatchDevices(Runnable watcher) {
        deviceWatchers.remove(watcher);
    }

    /** Automation devices held back by a surge regulator now; for diagnostics and tests. */
    public int throttledDevices() {
        return throttled.size();
    }

    /**
     * Tells {@code watcher} about the network's stored energy from the network's tick, whenever it differs from the
     * last tick's, committed; adding one twice adds it once. The watcher stays with this network: a device moves it
     * itself in {@link StorageDevice#networkChanged}.
     */
    // Watchers are the device's own objects, compared by identity.
    @SuppressWarnings("ReferenceEquality")
    public void watchEnergy(EnergyWatcher watcher) {
        for (int i = 0; i < energyWatchers.size(); i++) {
            if (energyWatchers.get(i) == watcher) {
                return;
            }
        }
        energyWatchers.add(watcher);
        watchedEnergy = -1;
    }

    /** Stops telling {@code watcher}; nothing happens if it was not watching. */
    public void unwatchEnergy(EnergyWatcher watcher) {
        energyWatchers.remove(watcher);
    }

    /**
     * Runs {@code watcher} whenever channel use on this network's routes changes (topology changes only); it must only
     * wake a task. Adding one twice adds it once; it stays with this network like {@link #watchEnergy}.
     */
    @SuppressWarnings("ReferenceEquality") // as in watchEnergy
    public void watchLoad(Runnable watcher) {
        for (int i = 0; i < loadWatchers.size(); i++) {
            if (loadWatchers.get(i) == watcher) {
                return;
            }
        }
        loadWatchers.add(watcher);
    }

    /** Stops running {@code watcher}; nothing happens if it was not watching. */
    public void unwatchLoad(Runnable watcher) {
        loadWatchers.remove(watcher);
    }

    private void refreshControllers() {
        lastStatus = status();
        lastPowered = isPowered();
        for (int i = 0; i < controllers.size(); i++) {
            if (controllers.get(i) instanceof NetworkController controller) {
                controller.refreshStatus();
            }
        }
    }

    /**
     * Where the network's FE goes: the limitless store while a {@link NetworkPowerSource} is a member, else the only
     * controller in charge's buffer, else the ad-hoc buffer. With energy cells in the network, that buffer comes with
     * the cells behind it ({@link EnergyCells#over}).
     */
    @Override
    public @Nullable FeBuffer energyBuffer() {
        if (powerSources > 0) {
            return CreativeEnergy.INSTANCE;
        }
        FeBuffer primary = primaryBuffer();
        FeBuffer own = primary == null || cells.isEmpty() ? primary : cells.over(primary);
        NetworkController controller = activeController();
        NetworkState parent = controller == null ? null : controller.energyParent();
        // A subnet pays from its parent what its own store cannot (owner decision 2026-10-04).
        return own == null || parent == null || parent == this ? own : backed.bind(own, parent);
    }

    /** The controller's or the ad-hoc buffer, without the cells. */
    private @Nullable FeBuffer primaryBuffer() {
        if (active != null) {
            NetworkController controller = activeController();
            return controller == null ? null : controller.buffer();
        }
        FeBuffer buffer = adHocBuffer;
        if (buffer == null) {
            long capacity = StorageServerConfig.AD_HOC_BUFFER_MU.getAsLong();
            long input = StorageServerConfig.CONTROLLER_INPUT_PER_TICK.getAsLong();
            buffer = new EndpointBuffer(capacity, input, 0, input, 0, EnergyPriority.DEFAULT, NetworkState::unsaved);
            adHocBuffer = buffer;
        }
        return buffer;
    }

    /** This network's energy part, made on first use. */
    private StorageEnergy mu() {
        StorageEnergy current = muEnergy;
        if (current == null) {
            current = new StorageEnergy(logic.level(), cells);
            current.setPaused(network.isPaused());
            muEnergy = current;
        }
        return current;
    }

    /** Puts the buffer operations pay from first into the energy network in place of the last one. */
    private void syncEnergy() {
        if (!closed) {
            mu().syncPrimary(primaryBuffer());
        }
    }

    /** The storage network was paused or resumed in pause mode: its energy network follows. */
    void pausedChanged(boolean paused) {
        StorageEnergy current = muEnergy;
        if (current != null) {
            current.setPaused(paused);
        }
        linksPaused = paused;
        if (paused) {
            // A paused network does not tick: LinkDwell shows its devices offline once the pause outlasts the dwell,
            // so a chunk edge loading and unloading in quick turns changes nothing. The first tick after resuming
            // shows them again.
            LinkDwell.paused(this, logic.level());
        } else {
            LinkDwell.resumed(this);
        }
    }

    /**
     * The pause began {@link LinkDwell#TICKS} ago and still holds: the devices show offline. Called by
     * {@link LinkDwell}.
     */
    void pauseOutlasted() {
        if (!closed && linksPaused) {
            showLinks(false);
        }
    }

    /**
     * Once per tick: shows the devices' front lines again when membership or channels changed or the network went
     * online or offline. Going offline waits {@link LinkDwell#TICKS} first, so energy hovering at the threshold or a
     * short gap while another controller takes charge re-meshes nothing; coming online shows at once. O(1) on a quiet
     * tick.
     */
    private void updateLinks(long gameTime, boolean online) {
        if (online || !linkOnline) {
            linkOfflineSince = Long.MIN_VALUE;
            if (linksDirty || online != linkOnline) {
                showLinks(online);
            }
            return;
        }
        if (linkOfflineSince == Long.MIN_VALUE) {
            linkOfflineSince = gameTime;
        }
        if (gameTime - linkOfflineSince >= LinkDwell.TICKS) {
            linkOfflineSince = Long.MIN_VALUE;
            showLinks(false);
        } else if (linksDirty) {
            // Still within the dwell: new members show as the others do until it ends.
            showLinks(true);
        }
    }

    /** How often this network showed its devices' front lines ({@link LinkLook}); for tests and diagnostics. */
    public long linkShowings() {
        return linkShowings;
    }

    /** How many front line looks this network pushed to devices; for tests and diagnostics. */
    public long linkPushes() {
        return linkPushes;
    }

    /**
     * Shows every device's front line ({@link LinkLook}) after membership, channels, power or the controller in charge
     * changed: members holding a channel online when {@code online}, else offline; devices waiting here for channels or
     * the device limit no channel; devices that left and are in no other network offline. Each device hears only of a
     * change. Linear in the devices; topology and power events only.
     */
    private void showLinks(boolean online) {
        linkShowings++;
        linksDirty = false;
        linkOnline = online;
        for (Member member : members.values()) {
            NetworkDevice device = member.device;
            if (limited(device) || ChannelFree.of(device)) {
                showLink(device);
            }
        }
        for (NetworkDevice device : offline) {
            showLink(device);
        }
        for (NetworkDevice device : refused) {
            showLink(device);
        }
        for (int i = 0; i < linksLeft.size(); i++) {
            showLink(linksLeft.get(i));
        }
        linksLeft.clear();
    }

    /**
     * Pushes {@code device}'s front line look if it changed: online or offline in its network by that network's last
     * showing, no channel while a network keeps it offline or refused it ({@link DeviceLink#noChannel()}), offline in
     * no network and while a network without channel limits turned it away. A member that waits for its channels to be
     * counted keeps its look until they are.
     */
    private void showLink(NetworkDevice device) {
        if (device.isRemoved()) {
            return;
        }
        DeviceLink link = device.deviceLink();
        NetworkState in = link.network;
        LinkLook look;
        if (in != null) {
            if (!link.holdsChannel && !ChannelFree.of(device)) {
                return;
            }
            look = in.linkOnline ? LinkLook.ONLINE : LinkLook.OFFLINE;
        } else if (link.noChannel()) {
            look = LinkLook.NO_CHANNEL;
        } else {
            look = LinkLook.OFFLINE;
        }
        if (look != link.shownLook) {
            link.shownLook = look;
            linkPushes++;
            device.linkLookChanged(look);
        }
    }

    /**
     * The MaxTech energy network (MLE) this storage network hosts, made on first use: its buffers, energy cells,
     * generators and FE edges are its endpoints. For members that join it; diagnostics use {@link #energyNetwork()},
     * which never makes one. Server thread.
     */
    public EnergyNetwork energy() {
        return mu().network();
    }

    /** The network's energy cells; read-only, for diagnostics and tests. */
    public EnergyCells energyCells() {
        return cells;
    }

    /** What devices hand FE to; see {@link #energyBuffer()}. */
    @Nullable
    public EnergyHandler energyStore() {
        FeBuffer buffer = energyBuffer();
        return buffer == null ? null : buffer.inputOnly();
    }

    /** The ad-hoc buffer has no block to save or show it. */
    private static void unsaved() {
    }

    /**
     * The MaxTech energy network (MLE) this storage network hosts, for diagnostics: the analyzer, the probe and sensors
     * show its readout. Empty until a member made it ({@link #energy()}).
     */
    public Optional<EnergyNetwork> energyNetwork() {
        StorageEnergy current = muEnergy;
        return current == null ? Optional.empty() : current.existing();
    }

    @Override
    public long storedEnergy() {
        FeBuffer buffer = energyBuffer();
        return buffer == null ? 0 : buffer.stored();
    }

    @Override
    public long energyCapacity() {
        FeBuffer buffer = energyBuffer();
        return buffer == null ? 0 : buffer.capacity();
    }

    /**
     * FE that went into the network's store since this network was formed, committed only: through any device's FE
     * input and through {@link #countReceived}. A running total that never falls; a merge or split starts a new network
     * at 0, so a reader that samples the rate keeps the network it sampled and starts over when its device's network
     * changes. Stays 0 while a creative source powers the network, which takes no FE in. O(1).
     */
    public long energyReceived() {
        StorageEnergy current = muEnergy;
        return energy.received() + (current == null ? 0 : current.charged());
    }

    /**
     * FE the network paid for operations since it was formed, committed only (a rolled-back operation is refunded and
     * not counted); counted with a creative source too. A running total like {@link #energyReceived}. O(1).
     */
    public long energySpent() {
        return energy.spent();
    }

    /**
     * Counts {@code fe} as received into this network's store as part of {@code transaction}, for a device that takes
     * FE into the store past the network's own input (a controller's buffer, an energy storage block feeding the
     * network). Call it with the amount that actually went in, in the transaction that moved it; a rollback undoes the
     * count.
     */
    public void countReceived(long fe, TransactionContext transaction) {
        energy.receive(fe, transaction);
    }

    /**
     * Bytes used over the network's providers that count bytes ({@link ByteCapacity}: cells). Walks the providers on
     * every call (O(providers)), so a reader samples it on a cadence, never per change.
     */
    public long usedBytes() {
        ObjectArrayList<StorageIndex.Entry> all = index.providers();
        long used = 0;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).provider instanceof ByteCapacity bytes) {
                used += bytes.usedBytes();
            }
        }
        return used;
    }

    /** The capacity in bytes of the network's providers that count bytes; see {@link #usedBytes}. */
    public long totalBytes() {
        ObjectArrayList<StorageIndex.Entry> all = index.providers();
        long total = 0;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).provider instanceof ByteCapacity bytes) {
                total += bytes.totalBytes();
            }
        }
        return total;
    }

    @Override
    public Status status() {
        // The one quarantine check: every operation and task already stops on a status other than ONLINE.
        if (quarantined || network.isPaused()) {
            return Status.PAUSED;
        }
        return Status.ONLINE;
    }

    @Override
    public boolean isPowered() {
        if (status() != Status.ONLINE) {
            return false;
        }
        long smallest = OperationCost.energyCost(false, 1, StorageServerConfig.ENERGY_PER_OPERATION.getAsLong(),
                StorageServerConfig.ENERGY_PER_UNIT.getAsLong(), 1);
        return storedEnergy() >= smallest;
    }

    @Override
    public OwnerRecord owner() {
        return owner;
    }

    /**
     * Whether {@code player} may see the network's contents: at least {@link AccessLevel#VIEW}. Without a security
     * module that is Lib's owner security on the network's owner, as before.
     */
    @Override
    public boolean canAccess(Player player) {
        return accessLevel(player).atLeast(AccessLevel.VIEW);
    }

    /**
     * What {@code player} may do with the network: the controller's security rules when a security module is on the
     * network, otherwise {@link AccessLevel#FULL} for whom Lib's owner security lets in and {@link AccessLevel#NONE}
     * for everyone else. Server thread.
     */
    public AccessLevel accessLevel(Player player) {
        AccessLevel rule = securityLevel(player);
        if (rule != null) {
            return rule;
        }
        return OwnerSecurity.canAccess(player, owner) ? AccessLevel.FULL : AccessLevel.NONE;
    }

    /**
     * The network's name (M7L2 row 13): its controller in charge's, empty without one (ad hoc). Server thread.
     */
    public String name() {
        NetworkController controller = activeController();
        return controller == null ? "" : controller.networkName();
    }

    /** The controller's security rule for {@code player}, or null when the network has no security module. */
    public @Nullable AccessLevel securityLevel(Player player) {
        NetworkController controller = activeController();
        return controller == null ? null : controller.securityLevel(player);
    }

    /**
     * Adds to {@code out}, in order, the chunks a network anchor keeps loaded beyond its controller: for each storage
     * device, energy cell and network anchor (M7R item 4: an anchor anywhere on the network keeps itself connected),
     * lowest position first, the chunks of every node on its route to the root, so the device stays connected. Each
     * node is walked once. Linear in the routes' length; only on network changes.
     */
    public void anchorChunks(LongLinkedOpenHashSet out) {
        LongArrayList devices = new LongArrayList();
        for (Member member : members.values()) {
            NetworkDevice device = member.device;
            if (device.role() == StorageDevice.Role.STORAGE || device instanceof NetworkEnergyCell
                    || (device instanceof ServiceDevice service && service.service() == NetworkService.ANCHOR)) {
                devices.add(device.nodePos());
            }
        }
        LongArrays.quickSort(devices.elements(), 0, devices.size());
        Routes current = tree;
        LongOpenHashSet walked = new LongOpenHashSet();
        for (int i = 0; i < devices.size(); i++) {
            long node = devices.getLong(i);
            if (current == null || !current.contains(node)) {
                out.add(chunkOf(node));
                continue;
            }
            long root = current.root();
            while (node != SUPER_ROOT && walked.add(node)) {
                out.add(chunkOf(node));
                if (node == root) {
                    break;
                }
                node = current.parent(node);
            }
        }
    }

    /**
     * {@code device} (a drive) is about to unload with {@code cells} cells: they leave the network with it and count as
     * offline ({@link #offlineCells()}) until it loads again (owner decision 2026-10-04; a network anchor keeps drives
     * loaded). Records its route parent and the first node of each further chunk on its route. Server thread.
     */
    public void recordOffline(NetworkDevice device, int cells) {
        long pos = device.nodePos();
        Routes current = tree;
        LongArrayList route = new LongArrayList();
        if (current != null && current.contains(pos)) {
            long root = current.root();
            long chunk = chunkOf(pos);
            long node = pos;
            while (node != root) {
                node = current.parent(node);
                if (node == SUPER_ROOT) {
                    break;
                }
                long nodeChunk = chunkOf(node);
                if (route.isEmpty() || nodeChunk != chunk) {
                    route.add(node);
                    chunk = nodeChunk;
                }
            }
        }
        if (route.isEmpty()) {
            // Not routed: the controller or any device still in the network stands for it.
            route.add(routeRoot());
        }
        logic.driveOffline(pos, cells, route.toLongArray());
    }

    /** Cells of drives in unloaded chunks this network misses; for info lines. */
    public int offlineCells() {
        return logic.offlineCells(network);
    }

    private static long chunkOf(long pos) {
        return ChunkPos.pack(BlockPos.getX(pos) >> 4, BlockPos.getZ(pos) >> 4);
    }

    @Override
    public StorageContents contents() {
        return index;
    }

    /** Receives where a key is stored; see {@link #forEachHolder}. */
    @FunctionalInterface
    public interface HolderVisitor {
        /**
         * {@code provider} of {@code device} holds {@code amount} of the key, above 0; {@code network} is the network
         * the device belongs to (a subnet's, for storage seen through a subnet controller).
         */
        void visit(NetworkState network, NetworkDevice device, StorageProvider provider, long amount);
    }

    /**
     * Visits every provider holding {@code key} with what the index was told it holds (committed amounts). A provider
     * showing another network (a subnet mount) is not visited itself; that network's own providers are, one level deep,
     * as a mount shows only them. Walks the members, so it is for a player's click (the terminal's locate), not for
     * anything per tick. Server thread.
     */
    public void forEachHolder(StackKey key, HolderVisitor visitor) {
        forEachHolder(key, visitor, true);
    }

    @SuppressWarnings("ReferenceEquality") // a network is one object; a mount of itself is skipped
    private void forEachHolder(StackKey key, HolderVisitor visitor, boolean throughViews) {
        int id = index.rawId(key);
        if (id < 0 || index.holders(id) == null) {
            return;
        }
        for (Member member : members.values()) {
            for (int i = 0; i < member.entries.size(); i++) {
                StorageIndex.Entry entry = member.entries.get(i);
                long held = entry.held.get(id);
                if (held <= 0) {
                    continue;
                }
                if (!entry.view) {
                    visitor.visit(this, member.device, entry.provider, held);
                } else if (throughViews && entry.provider instanceof NetworkView view) {
                    NetworkState shown = view.viewed();
                    if (shown != null && shown != this) {
                        shown.forEachHolder(key, visitor, false);
                    }
                }
            }
        }
    }

    /**
     * Starts telling {@code watcher} about every change of this network's totals (an open terminal). The watcher stays
     * with this network; a device whose network changes moves its watchers itself
     * ({@link StorageDevice#networkChanged}).
     */
    public void watch(ContentsWatcher watcher) {
        index.watch(watcher);
    }

    /** Stops telling {@code watcher}; nothing happens if it was not watching. */
    public void unwatch(ContentsWatcher watcher) {
        index.unwatch(watcher);
    }

    /**
     * Starts telling {@code watcher} about every committed move into or out of this network (a ledger module). Adding
     * one twice adds it once. Unlike {@link #watch}, it does not follow a merge or split: the watcher moves itself.
     */
    @SuppressWarnings("ReferenceEquality") // as in watchDevices
    public void watchMoves(MoveWatcher watcher) {
        for (int i = 0; i < moveWatchers.size(); i++) {
            if (moveWatchers.get(i) == watcher) {
                return;
            }
        }
        moveWatchers.add(watcher);
    }

    /** Stops telling {@code watcher}; nothing happens if it was not watching. */
    public void unwatchMoves(MoveWatcher watcher) {
        moveWatchers.remove(watcher);
    }

    /**
     * Tells {@code listener} about every committed move of {@code key} into or out of this network, with the device
     * that moved it (M7L2 logic: "items move in or out"), or about moves of every key when {@code key} is null. Only
     * keys someone listens to are recorded at all, so a network with no listener pays two emptiness checks per
     * operation. Adding the same pair twice adds it once. Like {@link #watchMoves}, it does not follow a merge or
     * split: the listener moves itself.
     */
    @SuppressWarnings("ReferenceEquality") // listeners are their owners' objects, compared by identity
    public void listenItemMoves(@Nullable StackKey key, ItemMoveListener listener) {
        ObjectArrayList<ItemMoveListener> list = key == null
                ? anyMoveListeners
                : itemMoveListeners.computeIfAbsent(key, k -> new ObjectArrayList<>(1));
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == listener) {
                return;
            }
        }
        list.add(listener);
    }

    /** Stops telling {@code listener} about any key; nothing happens if it was not listening. */
    public void unlistenItemMoves(ItemMoveListener listener) {
        anyMoveListeners.remove(listener);
        if (itemMoveListeners.isEmpty()) {
            return;
        }
        var entries = itemMoveListeners.object2ObjectEntrySet().fastIterator();
        while (entries.hasNext()) {
            ObjectArrayList<ItemMoveListener> list = entries.next().getValue();
            list.remove(listener);
            if (list.isEmpty()) {
                entries.remove();
            }
        }
    }

    /** Whether some logic listener hears committed moves of any key; for tests and diagnostics. */
    public boolean hearsItemMoves() {
        return !itemMoveListeners.isEmpty() || !anyMoveListeners.isEmpty();
    }

    /** Whether some logic listener wants committed moves of {@code key}; two emptiness checks when none listens. */
    private boolean itemMovesWanted(StackKey key) {
        return !anyMoveListeners.isEmpty() || (!itemMoveListeners.isEmpty() && itemMoveListeners.containsKey(key));
    }

    /** Tells the logic listeners of {@code key} (and of every key) about one committed move. */
    private void tellItemMoves(StackKey key, long amount, @Nullable NetworkDevice device) {
        if (!itemMoveListeners.isEmpty()) {
            ObjectArrayList<ItemMoveListener> list = itemMoveListeners.get(key);
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    list.get(i).itemsMoved(key, amount, device);
                }
            }
        }
        for (int i = 0; i < anyMoveListeners.size(); i++) {
            anyMoveListeners.get(i).itemsMoved(key, amount, device);
        }
    }

    /** Hears that a player opened a terminal of the network (M7L2); see {@link #watchTerminals}. */
    @FunctionalInterface
    public interface TerminalWatcher {
        /**
         * A player opened {@code terminal} (null for one that is no device of the network: a wireless terminal); must
         * only mark work and wake a task.
         */
        void terminalOpened(@Nullable NetworkDevice terminal);
    }

    /** Tells {@code watcher} when a player opens a terminal of this network. Adding one twice adds it once. */
    @SuppressWarnings("ReferenceEquality") // as in watchEnergy
    public void watchTerminals(TerminalWatcher watcher) {
        for (int i = 0; i < terminalWatchers.size(); i++) {
            if (terminalWatchers.get(i) == watcher) {
                return;
            }
        }
        terminalWatchers.add(watcher);
    }

    /** Stops telling {@code watcher}; nothing happens if it was not watching. */
    public void unwatchTerminals(TerminalWatcher watcher) {
        terminalWatchers.remove(watcher);
    }

    /**
     * A player opened a terminal of this network: {@code terminal}, or one that is no device (null). One check when
     * nothing watches.
     */
    public void terminalOpened(@Nullable NetworkDevice terminal) {
        for (int i = 0; i < terminalWatchers.size(); i++) {
            terminalWatchers.get(i).terminalOpened(terminal);
        }
    }

    /**
     * Registers a device's key listener with this network's index; see {@link DeviceLink#listen}. A logic controller
     * that reads another network's counts (M7L2 row 13) listens here directly and unlistens before it lets go.
     */
    public void listenKey(StackKey key, KeyListener listener) {
        index.listen(key, listener);
    }

    /** Removes a device's key listener from this network's index. */
    public void unlistenKey(StackKey key, KeyListener listener) {
        index.unlisten(key, listener);
    }

    /** Keys some device of this network listens to; for diagnostics and tests. */
    public int listenedKeyCount() {
        return index.listenedKeys();
    }

    /**
     * Stores up to {@code amount} of {@code key} for a player, as part of {@code transaction}, and returns how much
     * went in. Player access is instant (PRD 7.2): it is never queued, but pays energy like any operation, inside the
     * transaction: with too little FE for all of {@code amount} it moves what the buffer can pay for. Refused (0) when
     * the network does not work or cannot pay for a single item or fluid unit; a terminal checks {@link #canAccess}
     * first.
     */
    public long insert(StackKey key, long amount, TransactionContext transaction) {
        return operate(true, key, amount, transaction, null);
    }

    /** Takes up to {@code amount} of {@code key} out for a player; see {@link #insert}. */
    public long extract(StackKey key, long amount, TransactionContext transaction) {
        return operate(false, key, amount, transaction, null);
    }

    /** {@link #insert} for {@code player}, who a ledger module records it for; see {@link NetworkActor}. */
    public long insert(StackKey key, long amount, TransactionContext transaction, Player player) {
        UUID previous = NetworkActor.begin(player);
        try {
            return operate(true, key, amount, transaction, null);
        } finally {
            NetworkActor.end(previous);
        }
    }

    /** {@link #extract} for {@code player}, who a ledger module records it for; see {@link NetworkActor}. */
    public long extract(StackKey key, long amount, TransactionContext transaction, Player player) {
        UUID previous = NetworkActor.begin(player);
        try {
            return operate(false, key, amount, transaction, null);
        } finally {
            NetworkActor.end(previous);
        }
    }

    /**
     * One operation: moves up to {@code amount} (a batch at most for a device) in a nested transaction and pays its
     * energy there. The amount shrinks to what the energy buffer can pay for. Refused without any change when the
     * network does not work or the energy does not pay for one unit. No route work: a device in the network holds its
     * channels already (M7N), and only its own rate per cadence limits how often it calls.
     */
    long operate(boolean insert, StackKey key, long amount, TransactionContext transaction,
            @Nullable LinkTransfer transfer) {
        NetworkDevice device = transfer == null ? null : transfer.device();
        if (amount < 0) {
            throw new IllegalArgumentException("Negative amount " + amount);
        }
        if (amount == 0 || status() != Status.ONLINE || (insert && bytesCapped())) {
            return 0;
        }
        FeBuffer buffer = energyBuffer();
        if (buffer == null) {
            return 0;
        }
        if (transfer != null && transfer.open != null) {
            return operatePart(insert, key, amount, transaction, transfer);
        }
        long limit = amount;
        if (transfer != null && device != null) {
            if (!transfer.player && surgeHolds(buffer)) {
                // Low on FE with a surge regulator: automation waits for the energy to come back, players go on.
                transfer.throttled = true;
                throttle(transfer.link());
                return 0;
            }
            limit = Math.min(amount, batchLimit(key.kind()));
        }
        boolean fluid = key.kind() == StackKey.Kind.FLUID;
        int energyPercent = energyPercent();
        // A low buffer shrinks the operation to what it can pay rather than refusing it whole.
        long affordable = OperationCost.affordable(fluid, buffer.stored(),
                StorageServerConfig.ENERGY_PER_OPERATION.getAsLong(), StorageServerConfig.ENERGY_PER_UNIT.getAsLong(),
                StorageServerConfig.ENERGY_FLUID_MB_PER_UNIT.getAsInt(), energyPercent);
        if (affordable <= 0) {
            if (transfer != null) {
                transfer.energyShort = true;
            }
            return 0;
        }
        limit = Math.min(limit, affordable);
        try (Transaction nested = Transaction.open(transaction)) {
            long moved = insert ? insertInto(key, limit, nested, true) : extractFrom(key, limit, nested, true);
            if (moved <= 0) {
                return 0;
            }
            long raw = OperationCost.energyCost(fluid, moved, StorageServerConfig.ENERGY_PER_OPERATION.getAsLong(),
                    StorageServerConfig.ENERGY_PER_UNIT.getAsLong(),
                    StorageServerConfig.ENERGY_FLUID_MB_PER_UNIT.getAsInt());
            if (!pay(buffer, raw, energyPercent, nested)) {
                if (transfer != null) {
                    // A brown-out: the task waits like an unpowered one instead of retrying every tick.
                    transfer.energyShort = true;
                }
                return 0;
            }
            nested.commit();
            if (transfer != null) {
                transfer.energyShort = false;
                // Each operation counts against the network's per-tick operation budget, not only each task run.
                group.spendOps(1);
            }
            operations++;
            if (!moveWatchers.isEmpty() || itemMovesWanted(key)) {
                // A player's own operation carries who it was for; a device's is automation whoever set the actor.
                moves.add(key, insert ? moved : -moved, transfer == null ? NetworkActor.current() : null, device,
                        transaction);
            }
            return moved;
        }
    }

    /**
     * One move inside the multi-key operation {@code transfer} has open: no batch cap, energy or operation count of its
     * own, {@link #finishOperation} pays for the whole operation. The caller keeps the total within what
     * {@link #beginOperation} allowed.
     */
    private long operatePart(boolean insert, StackKey key, long amount, TransactionContext transaction,
            LinkTransfer transfer) {
        if (key.kind() != transfer.open) {
            throw new IllegalStateException("A " + key.kind() + " move inside a " + transfer.open + " operation");
        }
        try (Transaction nested = Transaction.open(transaction)) {
            long moved = insert ? insertInto(key, amount, nested, true) : extractFrom(key, amount, nested, true);
            if (moved <= 0) {
                return 0;
            }
            nested.commit();
            if (!moveWatchers.isEmpty() || itemMovesWanted(key)) {
                moves.add(key, insert ? moved : -moved, null, transfer.device(), transaction);
            }
            return moved;
        }
    }

    /**
     * Opens a multi-key operation of {@code kind} on {@code transfer} (M7E decision 4) and returns the most it may
     * move: one batch, capped at what the energy buffer pays for; 0, opening nothing, when an operation would be
     * refused now (the same checks as {@link #operate}). See {@link StorageTransfer#beginOperation}.
     */
    long beginOperation(StackKey.Kind kind, LinkTransfer transfer) {
        transfer.open = null;
        if (status() != Status.ONLINE) {
            return 0;
        }
        FeBuffer buffer = energyBuffer();
        if (buffer == null) {
            return 0;
        }
        if (!transfer.player && surgeHolds(buffer)) {
            transfer.throttled = true;
            throttle(transfer.link());
            return 0;
        }
        long affordable = OperationCost.affordable(kind == StackKey.Kind.FLUID, buffer.stored(),
                StorageServerConfig.ENERGY_PER_OPERATION.getAsLong(), StorageServerConfig.ENERGY_PER_UNIT.getAsLong(),
                StorageServerConfig.ENERGY_FLUID_MB_PER_UNIT.getAsInt(), energyPercent());
        if (affordable <= 0) {
            transfer.energyShort = true;
            return 0;
        }
        transfer.open = kind;
        return Math.min(batchLimit(kind), affordable);
    }

    /**
     * Closes {@code transfer}'s open operation and pays for {@code moved} as one operation inside {@code transaction}.
     * See {@link StorageTransfer#finishOperation}.
     */
    boolean finishOperation(StackKey.Kind kind, long moved, TransactionContext transaction, LinkTransfer transfer) {
        if (transfer.open != kind) {
            return false;
        }
        transfer.open = null;
        FeBuffer buffer = energyBuffer();
        if (moved <= 0 || buffer == null) {
            return false;
        }
        long raw = OperationCost.energyCost(kind == StackKey.Kind.FLUID, moved,
                StorageServerConfig.ENERGY_PER_OPERATION.getAsLong(), StorageServerConfig.ENERGY_PER_UNIT.getAsLong(),
                StorageServerConfig.ENERGY_FLUID_MB_PER_UNIT.getAsInt());
        try (Transaction nested = Transaction.open(transaction)) {
            if (!pay(buffer, raw, energyPercent(), nested)) {
                transfer.energyShort = true;
                return false;
            }
            transfer.countOnCommit(this, nested);
            nested.commit();
        }
        transfer.energyShort = false;
        return true;
    }

    /** {@code count} multi-key operations committed ({@link LinkTransfer#countOnCommit}). */
    void countOperations(int count) {
        group.spendOps(count);
        operations += count;
    }

    /** Inserts into the providers, those holding the key first; {@code views} false leaves out {@link NetworkView}s. */
    private long insertInto(StackKey key, long amount, TransactionContext transaction, boolean views) {
        long left = amount;
        int id = index.rawId(key);
        ObjectArrayList<StorageIndex.Entry> holding = id < 0 ? null : index.holders(id);
        if (holding != null) {
            for (int i = 0; i < holding.size() && left > 0; i++) {
                StorageIndex.Entry entry = holding.get(i);
                if (views || !entry.view) {
                    left -= entry.provider.insert(key, left, transaction);
                }
            }
        }
        ObjectArrayList<StorageIndex.Entry> all = index.providers();
        for (int i = 0; i < all.size() && left > 0; i++) {
            StorageIndex.Entry entry = all.get(i);
            if ((id < 0 || !entry.held.containsKey(id)) && (views || !entry.view)) {
                left -= entry.provider.insert(key, left, transaction);
            }
        }
        return amount - left;
    }

    private long extractFrom(StackKey key, long amount, TransactionContext transaction, boolean views) {
        int id = index.rawId(key);
        ObjectArrayList<StorageIndex.Entry> holding = id < 0 ? null : index.holders(id);
        if (holding == null) {
            return 0;
        }
        long left = amount;
        for (int i = 0; i < holding.size() && left > 0; i++) {
            StorageIndex.Entry entry = holding.get(i);
            if (views || !entry.view) {
                left -= entry.provider.extract(key, left, transaction);
            }
        }
        return amount - left;
    }

    /**
     * Stores up to {@code amount} of {@code key} in this network's own providers (no {@link NetworkView}s) as part of
     * {@code transaction}, for a view of this network in another one: no energy or status check here, the network that
     * asked pays for its operation. Returns how much went in. Server thread.
     */
    public long nativeInsert(StackKey key, long amount, TransactionContext transaction) {
        return amount <= 0 || closed || status() != Status.ONLINE || bytesCapped()
                ? 0
                : insertInto(key, amount, transaction, false);
    }

    /** Takes up to {@code amount} of {@code key} from this network's own providers; see {@link #nativeInsert}. */
    public long nativeExtract(StackKey key, long amount, TransactionContext transaction) {
        return amount <= 0 || closed || status() != Status.ONLINE ? 0 : extractFrom(key, amount, transaction, false);
    }

    /** How much of {@code key} this network's own providers hold, views left out. */
    public long nativeAmount(StackKey key) {
        return index.nativeAmount(key);
    }

    /** Visits what this network's own providers hold, each key once; views left out. */
    public void forEachNative(StorageProvider.Visitor visitor) {
        index.forEachNative(visitor);
    }

    /**
     * Tells {@code listener} every committed change of this network's own providers (views left out), until
     * {@link #unlistenNative}; a view in another network forwards them to its own listener.
     */
    public void listenNative(StorageProvider.Listener listener) {
        index.listenNative(listener);
    }

    /** Stops telling {@code listener}; nothing happens if it was not listening. */
    public void unlistenNative(StorageProvider.Listener listener) {
        index.unlistenNative(listener);
    }

    /**
     * Most of one kind a device operation moves: {@code network.batch.items} or {@code network.batch.fluidMb}, scaled
     * by {@link #batchPercent()}. Ports and interfaces size their requests by {@link StorageTransfer#batchLimit}, which
     * returns this.
     */
    long batchLimit(StackKey.Kind kind) {
        long base = kind == StackKey.Kind.FLUID
                ? StorageServerConfig.BATCH_FLUID_MB.getAsInt()
                : StorageServerConfig.BATCH_ITEMS.getAsInt();
        return base * batchPercent() / 100;
    }

    /**
     * The network's batch multiplier in percent: the controller cluster's (100 plus its batch amplifiers' share, PRD
     * 7.2, Controller), or 100 without a controller. Device operations carry up to {@code network.batch.*} times this;
     * read it through {@link StorageTransfer#batchLimit} rather than scaling a batch yourself.
     */
    public int batchPercent() {
        NetworkController controller = activeController();
        return controller == null ? 100 : Math.max(100, controller.batchPercent());
    }

    /**
     * The limits for automated crafting jobs of this network (M7L2 chunk 20): the controller's, or
     * {@link CraftLimits#DEFAULT} (the config's) without a controller.
     */
    public CraftLimits craftLimits() {
        NetworkController controller = activeController();
        return controller == null ? CraftLimits.DEFAULT : controller.craftLimits();
    }

    /** What an operation's FE cost is reduced to, in percent (the controller's efficiency cores); 100 without. */
    public int energyPercent() {
        NetworkController controller = activeController();
        return controller == null ? 100 : Math.clamp(controller.energyPercent(), 1, 100);
    }

    /**
     * Below this percent of its FE capacity the network holds automation back for player operations (the controller's
     * surge regulators); 0 without one.
     */
    public int surgeReservePercent() {
        NetworkController controller = activeController();
        return controller == null ? 0 : Math.clamp(controller.surgeReservePercent(), 0, 100);
    }

    /** See {@link StorageTransfer#retryTicks()}. */
    int retryTicks(LinkTransfer transfer) {
        if (transfer.throttled) {
            return SURGE_RETRY;
        }
        if (transfer.energyShort || !isPowered()) {
            return NOT_WORKING_RETRY;
        }
        if (!transfer.player) {
            FeBuffer buffer = energyBuffer();
            if (buffer != null && surgeHolds(buffer)) {
                // Asked before trying an operation: the task sleeps like a held-back one and is woken with them.
                throttle(transfer.link());
                return SURGE_RETRY;
            }
        }
        return 0;
    }

    /**
     * Pays for one point-to-point operation between two devices of this network (a P2P link moving items or fluid from
     * its input to an output without storing them): pays the operation's energy inside {@code transaction}, reduced by
     * efficiency cores like every operation. Returns the amount paid for: {@code amount} capped at one batch of
     * {@code kind} and at what the energy buffer pays for; 0, changing nothing, when the network does not work, either
     * device is not in it, a surge regulator holds automation back, or not one unit is affordable
     * ({@link #pathRetryTicks} says when to try again). Both devices hold their channels already, so no route is
     * walked.
     *
     * <p>
     * Nothing is moved here: the caller simulates its move first, calls this with the amount that will move, then moves
     * exactly the returned amount in the same {@code transaction} and rolls everything back if that fails, which
     * refunds the energy. {@code amount} must not be negative. Server thread only.
     */
    public long operateBetween(NetworkDevice from, NetworkDevice to, StackKey.Kind kind, long amount,
            TransactionContext transaction) {
        if (amount < 0) {
            throw new IllegalArgumentException("Negative amount " + amount);
        }
        if (amount == 0 || status() != Status.ONLINE || from.deviceLink().network != this
                || to.deviceLink().network != this) {
            return 0;
        }
        FeBuffer buffer = energyBuffer();
        if (buffer == null) {
            return 0;
        }
        if (surgeHolds(buffer)) {
            // Point-to-point links are automation: held back like a device's tasks, woken with them.
            throttle(from.deviceLink());
            return 0;
        }
        boolean fluid = kind == StackKey.Kind.FLUID;
        int energyPercent = energyPercent();
        long affordable = OperationCost.affordable(fluid, buffer.stored(),
                StorageServerConfig.ENERGY_PER_OPERATION.getAsLong(), StorageServerConfig.ENERGY_PER_UNIT.getAsLong(),
                StorageServerConfig.ENERGY_FLUID_MB_PER_UNIT.getAsInt(), energyPercent);
        long limit = Math.min(Math.min(amount, batchLimit(kind)), affordable);
        if (limit <= 0) {
            return 0;
        }
        long raw = OperationCost.energyCost(fluid, limit, StorageServerConfig.ENERGY_PER_OPERATION.getAsLong(),
                StorageServerConfig.ENERGY_PER_UNIT.getAsLong(),
                StorageServerConfig.ENERGY_FLUID_MB_PER_UNIT.getAsInt());
        try (Transaction nested = Transaction.open(transaction)) {
            if (!pay(buffer, raw, energyPercent, nested)) {
                return 0;
            }
            nested.commit();
        }
        group.spendOps(1);
        operations++;
        return limit;
    }

    /**
     * Pays {@code fe} for work a device does outside a transfer (an assembler crafting a batch) inside
     * {@code transaction}, reduced by efficiency cores like every operation: a rollback refunds it. Automation like any
     * task: refused, changing nothing, when the network does not work, has no energy buffer, a surge regulator holds
     * automation back (the device's tasks are then woken when the energy is back) or the buffer holds less than the
     * cost. A cost of 0 always passes on an online network. Server thread only.
     */
    public boolean payAutomation(DeviceLink from, long fe, TransactionContext transaction) {
        if (fe < 0) {
            throw new IllegalArgumentException("Negative cost " + fe);
        }
        FeBuffer buffer = energyBuffer();
        if (status() != Status.ONLINE || buffer == null) {
            return false;
        }
        if (fe == 0) {
            return true;
        }
        if (surgeHolds(buffer)) {
            throttle(from);
            return false;
        }
        return pay(buffer, fe, energyPercent(), transaction);
    }

    /**
     * Charges an operation costing {@code raw} FE before efficiency cores to {@code buffer} inside {@code transaction}:
     * the cores' discount applies to the sum of this tick's operations ({@link EnergyTally#charge}), so a 4 FE
     * operation at -10 % costs 3 or 4 FE such that ten of them cost 36. Returns false, changing nothing, when the
     * buffer holds less than the charge. A rollback refunds the charge and takes the operation out of the tick's sum.
     * Callers refuse an operation the buffer cannot pay for at all before moving anything
     * ({@link OperationCost#affordable}), so one charged 0 for the tick's rounding still runs only on a powered
     * network.
     */
    private boolean pay(FeBuffer buffer, long raw, int percent, TransactionContext transaction) {
        long now = level().getGameTime();
        long charge = energy.charge(raw, percent, now);
        if (!buffer.tryConsume(charge, transaction)) {
            return false;
        }
        energy.spend(charge, raw, now, transaction);
        return true;
    }

    /**
     * Ticks to wait before {@link #operateBetween} can pass for these two devices: 0 when it can now, 10 when the
     * network does not work or has too little energy for one unit, or {@link #SURGE_RETRY} while a surge regulator
     * holds automation back (the sending device's tasks are then woken when the energy is back). {@code to} is kept for
     * callers; the answer no longer depends on the path.
     */
    public int pathRetryTicks(NetworkDevice from, NetworkDevice to) {
        FeBuffer buffer = energyBuffer();
        if (!isPowered() || buffer == null) {
            return NOT_WORKING_RETRY;
        }
        if (surgeHolds(buffer)) {
            throttle(from.deviceLink());
            return SURGE_RETRY;
        }
        return 0;
    }

    /** The zone of the node at {@code pos}, or -1 when it has none yet. */
    public int zone(long pos) {
        return zoneOf.get(pos);
    }

    /** Zones in the network that hold a node; a patch can empty a zone until the next rebuild. */
    public int zoneCount() {
        return usedZones;
    }

    /** Nodes in zone {@code zone}; 0 for none. */
    public int zoneSize(int zone) {
        return zone >= 0 && zone < zoneSizes.size() ? zoneSizes.getInt(zone) : 0;
    }

    /**
     * How full the segment from {@code pos} towards the root is, 0 to 100: its channels in use of those it carries
     * ({@link Channels#loadPercent}); 0 for the root or a node off the tree. Two lookups and the segment's capacity.
     */
    public int segmentLoad(long pos) {
        Routes current = tree;
        if (current == null || endsRoutes(current, pos)) {
            return 0;
        }
        return Channels.loadPercent(channelUse.get(pos), segmentCapacity(pos, current.parent(pos)), infinite);
    }

    /**
     * The fullest segment on the route from {@code pos} to the root, 0 to 100; 0 for the root or a node off the tree.
     * Walks the route, so it is for event-driven readers (condition switches), not per operation.
     */
    public int routeLoad(long pos) {
        Routes current = tree;
        if (current == null || !current.contains(pos)) {
            return 0;
        }
        long root = current.root();
        int load = 0;
        for (long node = pos; node != root;) {
            int hubCap = hubCaps.get(node);
            if (hubCap > 0) {
                // A device behind a hub counts against the hub, not the links beyond it.
                return Math.max(load, Channels.loadPercent(hubUse.get(node), hubCap, infinite));
            }
            long parent = current.parent(node);
            if (parent == SUPER_ROOT) {
                // A cluster block's link to the virtual root carries no channels.
                break;
            }
            load = Math.max(load, Channels.loadPercent(channelUse.get(node), segmentCapacity(node, parent), infinite));
            node = parent;
        }
        return load;
    }

    /** Channels the segment from {@code pos} towards the root carries; 0 for the root or a node off the tree. */
    public int segmentCapacity(long pos) {
        Routes current = tree;
        if (current == null || endsRoutes(current, pos)) {
            return 0;
        }
        return segmentCapacity(pos, current.parent(pos));
    }

    /** Channels in use on the segment from {@code pos} towards the root; 0 for the root or a node off the tree. */
    public int segmentChannels(long pos) {
        Routes current = tree;
        if (current == null || endsRoutes(current, pos)) {
            return 0;
        }
        return channelUse.get(pos);
    }

    /** Whether the node at {@code pos} is a hub with a budget in this network. One lookup. */
    public boolean isHub(long pos) {
        return hubCaps.get(pos) > 0;
    }

    /** Channels the hub at {@code pos} gives the devices behind it; 0 when there is no hub there. One lookup. */
    public int hubCapacity(long pos) {
        return hubCaps.get(pos);
    }

    /** Devices counting against the hub at {@code pos}; 0 for none or no hub. One lookup. */
    public int hubChannels(long pos) {
        return hubUse.get(pos);
    }

    /** Hands every hub's node position to {@code visitor}, in no order. For diagnostics, not per tick. */
    public void forEachHub(LongConsumer visitor) {
        LongIterator it = hubCaps.keySet().iterator();
        while (it.hasNext()) {
            visitor.accept(it.nextLong());
        }
    }

    /**
     * The first hub on the route from {@code pos} towards the root (the one a device there counts against), or
     * {@code Long.MIN_VALUE} for none. Walks the route; for the analyzer, not per operation.
     */
    public long routeHub(long pos) {
        Routes current = tree;
        if (current == null || !current.contains(pos)) {
            return Long.MIN_VALUE;
        }
        long root = current.root();
        for (long node = pos; node != root;) {
            if (hubCaps.get(node) > 0) {
                return node;
            }
            long parent = current.parent(node);
            if (parent == SUPER_ROOT) {
                break;
            }
            node = parent;
        }
        return Long.MIN_VALUE;
    }

    /** Devices holding a channel in this network. O(1). */
    public int channelsHeld() {
        return channelsHeld;
    }

    /** Devices offline here for lack of a channel; a read-only view. Diagnostics. */
    public Set<NetworkDevice> offlineDevices() {
        return Collections.unmodifiableSet(offline);
    }

    /** Rises whenever channel use changes; readers that cache loads compare it. O(1). */
    public long channelVersion() {
        return channelVersion;
    }

    /** Route segments walked by channel counting since the network was created; for perf tests. */
    public long channelSteps() {
        return channelSteps;
    }

    /** Full channel counts (rebuilds and recounts) since the network was created; for perf tests. */
    public int channelCounts() {
        return channelCounts;
    }

    /**
     * The next node from {@code pos} towards the root on its route, or {@code Long.MIN_VALUE} for the root, a cluster
     * block carrying root links, or a node without a route. For diagnostics (the analyzer's route display) and tests.
     */
    public long routeParent(long pos) {
        Routes current = tree;
        if (current == null || endsRoutes(current, pos)) {
            return Long.MIN_VALUE;
        }
        return current.parent(pos);
    }

    /**
     * The network's nodes, cables and devices; read-only (Lib's graph owns the set). For diagnostics that walk the
     * whole network on a player's request (the analyzer's overlay), never per tick.
     */
    public LongSet nodes() {
        return network.nodes();
    }

    /** The devices in the network, cluster pieces and attached devices included; a read-only view. Diagnostics. */
    public Set<NetworkDevice> devices() {
        return Collections.unmodifiableSet(members.keySet());
    }

    /** Devices turned away for the device limit since the last rebuild; a read-only view. Diagnostics. */
    public Set<NetworkDevice> refusedDevices() {
        return Collections.unmodifiableSet(refused);
    }

    /** The controller in charge as a device, or null in an ad-hoc network. */
    public @Nullable NetworkDevice controllerDevice() {
        return active;
    }

    /** Controllers in the network: the one in charge and those on standby. */
    public int controllerCount() {
        return controllers.size();
    }

    /**
     * Hands every route segment that carries a channel (the child node's position) to {@code visitor}; the others are
     * unused. The diagnostics' way to find full segments without walking every node. Server thread.
     */
    public void forEachUsedSegment(LongConsumer visitor) {
        LongIterator it = channelUse.keySet().iterator();
        while (it.hasNext()) {
            visitor.accept(it.nextLong());
        }
    }

    /** Adds the graph links of the node at {@code pos} to {@code out}, antenna links included. Diagnostics. */
    public void links(long pos, LongList out) {
        logic.graph().links(pos, out);
    }

    /** The cable tier of the node at {@code pos}: 1, 2, ... for cables, 0 for a device, -1 for no node. */
    public int nodeTier(long pos) {
        return nodeTiers.get(pos);
    }

    /** Zone indices handed out since the last rebuild; {@link #zoneSize} of one may be 0. */
    public int zoneSlots() {
        return zoneSizes.size();
    }

    /** Whether {@code pos} is off the tree, its root, or a cluster block hanging off the virtual root. */
    private static boolean endsRoutes(Routes current, long pos) {
        return !current.contains(pos) || pos == current.root() || current.parent(pos) == SUPER_ROOT;
    }

    /**
     * Whether routes end at the node at {@code pos}: the ad-hoc root, or a cluster block carrying root links in a
     * controller network.
     */
    public boolean isRouteRoot(long pos) {
        Routes current = tree;
        return current != null && current.contains(pos) && (pos == current.root() || current.parent(pos) == SUPER_ROOT);
    }

    /** Root links the routes use now; at most {@link #rootLinks()}. */
    public int rootLinksUsed() {
        return rootLinks.used();
    }

    /**
     * Root links the network may use as of the last rebuild: the links from the controller's active blocks to cables
     * and devices outside it, at most what the controller allows ({@link NetworkController#rootLinks()}; a storage
     * controller allows every face, M7R item 1); 0 without a controller.
     */
    public int rootLinks() {
        NetworkController controller = activeController();
        return controller == null ? 0 : Math.min(controller.rootLinks(), rootLinks.offered());
    }

    /**
     * How busy the busiest root link of the cluster block at {@code clusterPos} is, 0 to 100; 0 when it carries none.
     * At most six lookups.
     */
    public int exitLoad(long clusterPos) {
        Routes current = tree;
        if (current == null) {
            return 0;
        }
        int load = 0;
        for (Direction side : SIDES) {
            long outside = BlockPos.offset(clusterPos, side);
            if (rootLinks.allows(outside, clusterPos) && current.contains(outside)
                    && current.parent(outside) == clusterPos) {
                load = Math.max(load, segmentLoad(outside));
            }
        }
        return load;
    }

    /**
     * Channels the root links in use can carry together: the sum of their segments' capacities; 0 without routes. For
     * the controller's overlay bar; walks the enabled links (at most six per controller block), so cheap but not O(1).
     */
    public int rootCapacity() {
        Routes current = tree;
        if (current == null) {
            return 0;
        }
        int total = 0;
        LongIterator it = rootLinks.outsideNodes();
        while (it.hasNext()) {
            long outside = it.nextLong();
            if (current.contains(outside)) {
                total += segmentCapacity(outside);
            }
        }
        return total;
    }

    /**
     * The busiest root link, the network's likeliest bottleneck: the position of its outside node, or
     * {@code Long.MIN_VALUE} without one. For diagnostics; walks the enabled links.
     */
    public long busiestRootLink() {
        long busiest = Long.MIN_VALUE;
        int busiestLoad = -1;
        Routes current = tree;
        if (current == null) {
            return busiest;
        }
        LongIterator it = rootLinks.outsideNodes();
        while (it.hasNext()) {
            long outside = it.nextLong();
            if (!current.contains(outside)) {
                continue;
            }
            int load = segmentLoad(outside);
            if (load > busiestLoad || (load == busiestLoad && outside < busiest)) {
                busiest = outside;
                busiestLoad = load;
            }
        }
        return busiest;
    }

    /** Devices in the network, the controller included; cluster expansions are not devices. */
    public int deviceCount() {
        return members.size() - pieces;
    }

    /** Nodes in the network, cables and devices. */
    public int nodeCount() {
        return network.size();
    }

    /** Whether the network runs without a controller. */
    public boolean isAdHoc() {
        return adHoc;
    }

    /**
     * Whether this network counted its channels without limits ({@link ChannelMode#INFINITE}) the last time: nothing is
     * offline for channels, loads stay below full and displays show no channel limit. Kept until the next full count.
     */
    public boolean channelsInfinite() {
        return infinite;
    }

    /** Operations done since the network was created. */
    public long operations() {
        return operations;
    }

    /** Route rebuilds since the network was created; perf tests use it to catch rebuilds on a settled network. */
    public int rebuilds() {
        return rebuilds;
    }

    /**
     * The route root: the controller core's position in a controller network (its tree really starts at the virtual
     * {@link #SUPER_ROOT}, see {@link #isRouteRoot}), the lowest device in an ad-hoc one; {@code Long.MIN_VALUE} while
     * there is no tree.
     */
    public long routeRoot() {
        Routes current = tree;
        if (current == null) {
            return Long.MIN_VALUE;
        }
        NetworkController controller = rootController;
        if (current.root() == SUPER_ROOT && controller instanceof NetworkDevice device) {
            return device.nodePos();
        }
        return current.root();
    }

    /** The graph network's id; unique per level while it lives. */
    public int id() {
        return network.id();
    }

    /** The scheduler's group, for diagnostics and tests. */
    public TaskGroup scheduler() {
        return group;
    }

    /**
     * The operations of the open transactions, told to the {@link MoveWatcher}s and the logic listeners of their key
     * ({@link #listenItemMoves}) once the root commits, so a simulation or a rolled-back operation is never recorded.
     * Parallel lists, so recording allocates nothing once they have grown; the snapshot is how many entries there were.
     * Server thread.
     */
    private final class Moves extends SnapshotJournal<Integer> {
        private final ObjectArrayList<StackKey> keys = new ObjectArrayList<>();
        private final LongArrayList amounts = new LongArrayList();
        private final ObjectArrayList<@Nullable UUID> players = new ObjectArrayList<>();
        /** The device whose operation it was; null for a player's own. */
        private final ObjectArrayList<@Nullable NetworkDevice> devices = new ObjectArrayList<>();

        void add(StackKey key, long amount, @Nullable UUID player, @Nullable NetworkDevice device,
                TransactionContext transaction) {
            updateSnapshots(transaction);
            keys.add(key);
            amounts.add(amount);
            players.add(player);
            devices.add(device);
        }

        @Override
        protected Integer createSnapshot() {
            // Small counts come from Integer's cache: a usual root transaction holds one or two moves.
            return keys.size();
        }

        @Override
        protected void revertToSnapshot(Integer snapshot) {
            keys.size(snapshot);
            amounts.size(snapshot);
            players.size(snapshot);
            devices.size(snapshot);
        }

        @Override
        protected void onRootCommit(Integer originalState) {
            long now = level().getGameTime();
            boolean logic = !itemMoveListeners.isEmpty() || !anyMoveListeners.isEmpty();
            for (int i = 0; i < keys.size(); i++) {
                for (int w = 0; w < moveWatchers.size(); w++) {
                    moveWatchers.get(w).moved(keys.get(i), amounts.getLong(i), players.get(i), now);
                }
                if (logic) {
                    tellItemMoves(keys.get(i), amounts.getLong(i), devices.get(i));
                }
            }
            keys.clear();
            amounts.clear();
            players.clear();
            devices.clear();
        }
    }
}
