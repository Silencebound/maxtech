package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.conduit.ConduitData;
import dev.silencebound.maxtech.lib.api.network.Network;
import dev.silencebound.maxtech.lib.api.network.NetworkGraph;
import dev.silencebound.maxtech.lib.api.network.NetworkLogic;
import dev.silencebound.maxtech.lib.api.network.NetworkManager;
import dev.silencebound.maxtech.lib.api.network.NetworkType;
import dev.silencebound.maxtech.storage.cable.StorageCableConduitType;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * The storage network type's logic in one level: one {@link NetworkState} per graph network, kept in step with merges
 * and splits, the level's loaded storage devices by position, and the loaded cables that carry parts
 * ({@link DeviceHost}). Devices join and leave their network's state when their nodes come and go; routes are rebuilt
 * on topology changes ({@link NetworkState#rebuildIfDue}). The network's tick does nothing else, so an idle network
 * costs a few field reads per tick. Server thread only.
 */
public final class StorageNetworkLogic implements NetworkLogic {
    private static final Direction[] SIDES = Direction.values();

    private final ServerLevel level;
    private final Identifier networkId;
    private final Reference2ObjectOpenHashMap<Network, NetworkState> states = new Reference2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<StorageDeviceBlockEntity> devices = new Long2ObjectOpenHashMap<>();
    /** Loaded cables that carry devices (parts), by position; registered when their node is added. */
    private final Long2ObjectOpenHashMap<DeviceHost> hosts = new Long2ObjectOpenHashMap<>();
    /**
     * The type of each storage cable node, by position; kept like {@link #hosts}, so a rebuild reads tiers without a
     * block entity lookup per node. A block's conduit type never changes; another type means another block entity,
     * whose node is removed and added again.
     */
    private final Long2ObjectOpenHashMap<StorageCableConduitType> cables = new Long2ObjectOpenHashMap<>();
    /**
     * Devices that sit on another block's node without being part of it (a subnet controller's uplink on the parent
     * node in front of it), by node position.
     */
    private final Long2ObjectOpenHashMap<ObjectArrayList<NetworkDevice>> attached = new Long2ObjectOpenHashMap<>();
    private @Nullable NetworkGraph graph;
    private @Nullable Quarantines quarantines;
    private @Nullable ZoneBorders zoneBorders;
    /**
     * Drives whose chunk unloaded while they held cells, by position: their cell count and the nodes their route ran
     * through (the parent, then the first node in each further chunk), which say which network misses them.
     */
    private final Long2IntOpenHashMap offlineCells = new Long2IntOpenHashMap();
    private final Long2ObjectOpenHashMap<long[]> offlineRoutes = new Long2ObjectOpenHashMap<>();
    /** Each capped team's summed cell bytes and when they were summed; see {@link #teamBytes}. */
    private final Object2LongOpenHashMap<UUID> bytes = new Object2LongOpenHashMap<>();
    private final Object2LongOpenHashMap<UUID> bytesTime = new Object2LongOpenHashMap<>();
    /** The last join sequence handed out ({@link #nextJoinSeq}). */
    private long lastJoinSeq;

    StorageNetworkLogic(ServerLevel level, Identifier networkId) {
        this.level = level;
        this.networkId = networkId;
    }

    ServerLevel level() {
        return level;
    }

    /**
     * A new join sequence for a device joining a network for the first time (M7N): higher than every one handed out
     * before in this level, across restarts too, since it starts from the game time. Server thread.
     */
    long nextJoinSeq() {
        long floor = (level.getGameTime() + 1) << NetworkState.SEQ_SHIFT;
        lastJoinSeq = Math.max(lastJoinSeq + 1, floor);
        return lastJoinSeq;
    }

    /** {@code device} is gone for good or unloads: no network keeps it offline any more. */
    private static void forgetOffline(NetworkDevice device) {
        NetworkState offline = device.deviceLink().offlineBy;
        if (offline != null) {
            offline.forgetOffline(device);
        }
    }

    /** The level's quarantined node positions, looked up once. */
    Quarantines quarantines() {
        Quarantines current = quarantines;
        if (current == null) {
            current = Quarantines.get(level);
            quarantines = current;
        }
        return current;
    }

    NetworkGraph graph() {
        NetworkGraph current = graph;
        if (current == null) {
            NetworkType type = Objects.requireNonNull(NetworkType.byId(networkId).orElse(null), "storage network type");
            current = NetworkManager.get(level).graph(type);
            graph = current;
        }
        return current;
    }

    /** The level's painted zone borders, looked up once. */
    ZoneBorders zoneBorders() {
        ZoneBorders current = zoneBorders;
        if (current == null) {
            current = ZoneBorders.of(level);
            zoneBorders = current;
        }
        return current;
    }

    /** The state of {@code network}, made on first use (split parts get none from the graph's callbacks). */
    public NetworkState state(Network network) {
        NetworkState state = states.get(network);
        if (state == null) {
            state = new NetworkState(this, network);
            states.put(network, state);
            state.readQuarantine();
        }
        return state;
    }

    /** The loaded device at {@code pos}, or null. */
    @Nullable
    StorageDeviceBlockEntity device(long pos) {
        return devices.get(pos);
    }

    /** The loaded cable at {@code pos} that carries devices, or null. */
    @Nullable
    DeviceHost host(long pos) {
        return hosts.get(pos);
    }

    /**
     * A part was put on the cable at {@code pos}, which carries it as {@code host}: it joins the cable's network now
     * (an ad-hoc network may get a new root, so that rebuilds). Server thread.
     */
    public void deviceAdded(long pos, DeviceHost host, NetworkDevice device) {
        hosts.put(pos, host);
        Network network = graph().network(pos);
        if (network != null) {
            NetworkState state = state(network);
            state.join(device);
            if (state.isAdHoc()) {
                state.markChanged();
            }
        }
    }

    /** A part came off its cable: it leaves its network at once. Server thread. */
    public void deviceGone(NetworkDevice device) {
        forgetOffline(device);
        NetworkState state = device.deviceLink().network;
        if (state != null) {
            state.leave(device);
            if (state.isAdHoc()) {
                state.markChanged();
            }
        }
    }

    /**
     * Puts {@code device} on the node at {@code pos} from outside that node's block (a subnet controller's uplink): it
     * joins the node's network now, or when the node appears, and moves with it like a part. Server thread.
     */
    public void attach(long pos, NetworkDevice device) {
        ObjectArrayList<NetworkDevice> list = attached.get(pos);
        if (list == null) {
            list = new ObjectArrayList<>(1);
            attached.put(pos, list);
        }
        if (!list.contains(device)) {
            list.add(device);
            refreshCableFaces(pos);
        }
        Network network = graph().network(pos);
        if (network != null) {
            NetworkState state = state(network);
            state.join(device);
            if (state.isAdHoc()) {
                // An ad-hoc network's root is its lowest device, which this one may be.
                state.markChanged();
            }
        }
    }

    /** Takes {@code device} off the node at {@code pos}: it leaves its network at once. Server thread. */
    public void detach(long pos, NetworkDevice device) {
        ObjectArrayList<NetworkDevice> list = attached.get(pos);
        if (list != null) {
            list.remove(device);
            if (list.isEmpty()) {
                attached.remove(pos);
            }
            refreshCableFaces(pos);
        }
        forgetOffline(device);
        NetworkState state = device.deviceLink().network;
        if (state != null) {
            state.leave(device);
        }
    }

    /** Whether any node in the level has devices attached to it. */
    boolean hasAttached() {
        return !attached.isEmpty();
    }

    /** The devices attached to the node at {@code pos}, or null for none. */
    @Nullable
    ObjectArrayList<NetworkDevice> attached(long pos) {
        return attached.get(pos);
    }

    /** The type of the storage cable at {@code pos}, or null if there is none (cannot happen for a cable node). */
    @Nullable
    StorageCableConduitType cableType(long pos) {
        return cables.get(pos);
    }

    /**
     * The drive at {@code pos} unloaded with {@code cells} cells while its route ran through {@code route}: its cells
     * count as offline for the network that holds the first of those nodes still in the graph, until the drive loads.
     */
    void driveOffline(long pos, int cells, long[] route) {
        offlineCells.put(pos, cells);
        offlineRoutes.put(pos, route);
    }

    /** The drive at {@code pos} loaded (or was replaced): its cells are no longer offline. Server thread. */
    public void driveOnline(long pos) {
        offlineCells.remove(pos);
        offlineRoutes.remove(pos);
    }

    /** Cells of unloaded drives that {@code network} misses. Walks the offline drives; for info lines, not per tick. */
    int offlineCells(Network network) {
        if (offlineCells.isEmpty()) {
            return 0;
        }
        NetworkGraph current = graph();
        int count = 0;
        for (Long2ObjectMap.Entry<long[]> entry : offlineRoutes.long2ObjectEntrySet()) {
            for (long node : entry.getValue()) {
                Network holder = current.network(node);
                if (holder != null) {
                    if (holder == network) {
                        count += offlineCells.get(entry.getLongKey());
                    }
                    break;
                }
            }
        }
        return count;
    }

    /** Devices the team's networks in this level other than {@code except} count against their device limits. */
    int teamDevices(UUID team, NetworkState except) {
        int count = 0;
        for (NetworkState state : states.values()) {
            if (state != except && team.equals(state.capTeam())) {
                count += state.limitedDevices();
            }
        }
        return count;
    }

    /** Zones of the team's networks in this level other than {@code except}. */
    int teamZones(UUID team, NetworkState except) {
        int count = 0;
        for (NetworkState state : states.values()) {
            if (state != except && team.equals(state.capTeam())) {
                count += state.zoneCount();
            }
        }
        return count;
    }

    /**
     * Cell bytes the team's networks in this level use, summed again at most every {@link TeamCaps#BYTES_INTERVAL}
     * ticks and only when asked (a network storing something), so idle networks cost nothing.
     */
    long teamBytes(UUID team, long gameTime) {
        long stamp = bytesTime.getOrDefault(team, Long.MIN_VALUE);
        if (stamp != Long.MIN_VALUE && gameTime - stamp < TeamCaps.BYTES_INTERVAL && gameTime >= stamp) {
            return bytes.getLong(team);
        }
        long used = 0;
        for (NetworkState state : states.values()) {
            if (team.equals(state.capTeam())) {
                used += state.usedBytes();
            }
        }
        bytes.put(team, used);
        bytesTime.put(team, gameTime);
        return used;
    }

    /** The level's live networks of this type; for the admin command. */
    public Collection<NetworkState> networks() {
        return Collections.unmodifiableCollection(states.values());
    }

    void deviceLoaded(StorageDeviceBlockEntity device) {
        devices.put(device.getBlockPos().asLong(), device);
    }

    /** Broken or unloaded: out of its network now, so nothing reaches its providers any more. */
    // A replacement block entity at the same position may already be registered; only this instance is dropped.
    @SuppressWarnings("ReferenceEquality")
    void deviceRemoved(StorageDeviceBlockEntity device) {
        long pos = device.getBlockPos().asLong();
        if (devices.get(pos) == device) {
            devices.remove(pos);
        }
        forgetOffline(device);
        NetworkState state = device.network();
        if (state != null) {
            state.leave(device);
            state.markChanged();
        }
    }

    @Override
    public void onNetworkCreated(Network network) {
        state(network);
    }

    @Override
    public void onNetworkRemoved(Network network) {
        NetworkState state = states.remove(network);
        if (state != null) {
            state.close(null);
        }
    }

    @Override
    public void onNodeAdded(Network network, long pos) {
        if (level.getBlockEntity(BlockPos.of(pos)) instanceof ConduitBlockEntity conduit) {
            if (conduit.type() instanceof StorageCableConduitType cable) {
                cables.put(pos, cable);
            }
            if (conduit.conduit().state() instanceof DeviceHost host) {
                hosts.put(pos, host);
                host.nodeAdded(level);
            }
        }
        state(network).nodeAdded(pos);
        updateCableFaces(pos);
    }

    @Override
    public void onNodeRemoved(Network network, long pos) {
        NetworkState state = state(network);
        StorageDeviceBlockEntity device = devices.get(pos);
        if (device != null && device.network() == state) {
            state.leave(device);
        }
        DeviceHost host = hosts.remove(pos);
        cables.remove(pos);
        ObjectArrayList<NetworkDevice> others = attached.get(pos);
        boolean plainCable = device == null && (host == null || host.deviceCount() == 0) && others == null;
        if (others != null) {
            for (int i = 0; i < others.size(); i++) {
                NetworkState current = others.get(i).deviceLink().network;
                if (current != null) {
                    current.leave(others.get(i));
                }
            }
        }
        if (host != null) {
            // Unloaded or broken with the cable: its parts go with it.
            for (int i = 0; i < host.deviceCount(); i++) {
                NetworkDevice part = host.device(i);
                NetworkState current = part.deviceLink().network;
                if (current != null) {
                    current.leave(part);
                }
            }
        }
        state.nodeRemoved(pos, plainCable);
        // The graph applies removals a tick later: a broken cable has no block entity by now, an unloading one has no
        // chunk. Only a broken one loses its zone border mark and its quarantine spot.
        if (ConduitBlockEntity.isChunkPresent(level, BlockPos.getX(pos), BlockPos.getZ(pos))
                && !(level.getBlockEntity(BlockPos.of(pos)) instanceof ConduitBlockEntity)) {
            zoneBorders().forget(pos);
            quarantines().remove(pos);
        }
        updateCableFaces(pos);
    }

    @Override
    public void onNodeInvalidated(Network network, long pos) {
        state(network).nodeInvalidated(pos);
        updateCableFaces(pos);
    }

    @Override
    public void onMerged(Network survivor, Network absorbed) {
        NetworkState target = state(survivor);
        NetworkState gone = states.remove(absorbed);
        if (gone != null) {
            gone.close(target);
        }
        target.markChanged();
    }

    @Override
    public void onSplit(Network original, Network part) {
        state(original).markChanged();
        state(part).markChanged();
    }

    @Override
    public void onRebuilt(Network network) {
        NetworkState state = state(network);
        if (!NetworkProfiler.on) {
            state.rebuildIfDue(level.getGameTime());
            return;
        }
        long started = System.nanoTime();
        state.rebuildIfDue(level.getGameTime());
        state.profile.add(System.nanoTime() - started);
    }

    @Override
    public void onPausedChanged(Network network, boolean paused) {
        state(network).pausedChanged(paused);
    }

    @Override
    public void tick(Network network) {
        NetworkState state = state(network);
        if (!NetworkProfiler.on) {
            state.tick(level.getGameTime());
            return;
        }
        long started = System.nanoTime();
        state.tick(level.getGameTime());
        state.profile.add(System.nanoTime() - started);
    }

    /**
     * Shows a stub on each storage cable face towards a linked device: the cable at {@code pos} and the cables around a
     * device at {@code pos}. Only on node changes.
     */
    private void updateCableFaces(long pos) {
        refreshCableFaces(pos);
        for (Direction side : SIDES) {
            refreshCableFaces(BlockPos.offset(pos, side));
        }
    }

    private void refreshCableFaces(long pos) {
        if (!ConduitBlockEntity.isChunkPresent(level, BlockPos.getX(pos), BlockPos.getZ(pos))
                || !(level.getBlockEntity(BlockPos.of(pos)) instanceof ConduitBlockEntity conduit)) {
            return;
        }
        ConduitData cable = conduit.findNetwork(networkId);
        if (cable == null) {
            return;
        }
        NetworkGraph current = graph();
        ObjectArrayList<NetworkDevice> outside = attached.get(pos);
        int faces = 0;
        for (Direction side : SIDES) {
            long neighbour = BlockPos.offset(pos, side);
            int bit = 1 << side.get3DDataValue();
            if (!ConduitBlockEntity.isChunkPresent(level, BlockPos.getX(neighbour), BlockPos.getZ(neighbour))) {
                // Kept while the device's chunk is unloaded: the connectivity reads it as a cut edge there.
                faces |= cable.blockFaces() & bit;
            } else if ((devices.containsKey(neighbour) && current.isLinked(pos, neighbour))
                    || attachedFrom(outside, neighbour)) {
                faces |= bit;
            }
        }
        conduit.setBlockFaces(networkId, faces);
    }

    /** Whether one of {@code outside} (the devices attached to a cable's node) belongs to the block at {@code pos}. */
    private static boolean attachedFrom(@Nullable ObjectArrayList<NetworkDevice> outside, long pos) {
        if (outside != null) {
            for (int i = 0; i < outside.size(); i++) {
                if (outside.get(i).blockPos() == pos) {
                    return true;
                }
            }
        }
        return false;
    }
}
