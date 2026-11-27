package dev.silencebound.maxtech.farms.lab;

import dev.silencebound.maxtech.farms.hub.LabHubBlockEntity;
import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceMode;
import dev.silencebound.maxtech.lib.api.conduit.ConduitHost;
import dev.silencebound.maxtech.lib.api.conduit.Conduits;
import dev.silencebound.maxtech.lib.api.network.Network;
import dev.silencebound.maxtech.lib.api.network.NetworkGraph;
import dev.silencebound.maxtech.lib.api.network.NetworkLogic;
import dev.silencebound.maxtech.lib.api.network.NetworkManager;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * The lab network type's logic in one level: keeps a {@link LabNetwork} per Lib network and finds the hub and fluid
 * ports next to its tubes when the topology changes ({@link #onRebuilt}) or a tube's neighbour comes or goes (the tube
 * conduit attaches to blocks, so Lib invalidates it; a hub letting go of its lab invalidates the tubes beside it).
 * Nothing moves per tick: nutrient and output move when a port or specimen calls in, MU in the lab's energy network,
 * which sleeps until something changes. The tick only finishes a pending look at the neighbours. Each look also sets
 * the tubes' contents ({@link LabNetwork#refreshContents}); merges and splits tell the lab where its tubes went, so a
 * hub on them keeps running.
 */
final class LabNetworkLogic implements NetworkLogic {
    private static final Direction[] SIDES = Direction.values();

    private final ServerLevel level;
    private final Int2ObjectOpenHashMap<LabNetwork> labs = new Int2ObjectOpenHashMap<>();
    private final ObjectArrayList<LabHubBlockEntity> hubs = new ObjectArrayList<>();
    private final ObjectArrayList<FluidPortBlockEntity> ports = new ObjectArrayList<>();
    private @Nullable NetworkGraph graph;

    LabNetworkLogic(ServerLevel level) {
        this.level = level;
    }

    /** The lab of {@code network}, if it was seen. */
    @Nullable
    LabNetwork lab(Network network) {
        return labs.get(network.id());
    }

    private LabNetwork labOf(Network network) {
        LabNetwork lab = labs.get(network.id());
        if (lab == null) {
            lab = new LabNetwork(level, network);
            labs.put(network.id(), lab);
        }
        return lab;
    }

    private void drop(Network network) {
        LabNetwork lab = labs.remove(network.id());
        if (lab != null) {
            lab.dispose();
        }
    }

    @Override
    public void onNetworkCreated(Network network) {
        labOf(network);
    }

    @Override
    public void onNetworkRemoved(Network network) {
        drop(network);
    }

    @Override
    public void onMerged(Network survivor, Network absorbed) {
        LabNetwork gone = labs.get(absorbed.id());
        if (gone != null) {
            gone.addSuccessor(survivor);
        }
        drop(absorbed);
        labOf(survivor).markDirty();
    }

    @Override
    public void onSplit(Network original, Network part) {
        labOf(original).addSuccessor(part);
        labOf(original).markDirty();
        labOf(part).markDirty();
    }

    @Override
    public void onNodeInvalidated(Network network, long pos) {
        labOf(network).markDirty();
    }

    @Override
    public void onRebuilt(Network network) {
        rescan(network, labOf(network));
    }

    @Override
    public void onPausedChanged(Network network, boolean paused) {
        labOf(network).energy().setPaused(paused);
    }

    @Override
    public void tick(Network network) {
        LabNetwork lab = labs.get(network.id());
        if (lab != null && lab.isDirty()) {
            rescan(network, lab);
        }
    }

    /**
     * Looks at every tube's neighbours for hubs and ports, marks the tube faces towards them and hands what it found to
     * the lab. Runs on topology and neighbour events only.
     */
    private void rescan(Network network, LabNetwork lab) {
        hubs.clear();
        ports.clear();
        for (LongIterator it = network.nodes().iterator(); it.hasNext();) {
            long pos = it.nextLong();
            if (!Conduits.isChunkPresent(level, BlockPos.getX(pos), BlockPos.getZ(pos))) {
                continue;
            }
            ConduitHost tube = Conduits.host(level, BlockPos.of(pos)).orElse(null);
            if (tube != null) {
                scanTube(tube, pos);
            }
        }
        lab.update(hubs, ports);
        hubs.clear();
        ports.clear();
        lab.refreshContents(graph());
    }

    private NetworkGraph graph() {
        NetworkGraph current = graph;
        if (current == null) {
            current = NetworkManager.get(level).graph(LabTubes.NETWORK);
            graph = current;
        }
        return current;
    }

    private void scanTube(ConduitHost tube, long pos) {
        int faces = 0;
        for (Direction side : SIDES) {
            int bit = ConduitConnections.bit(side);
            long next = BlockPos.offset(pos, side);
            if (!Conduits.isChunkPresent(level, BlockPos.getX(next), BlockPos.getZ(next))) {
                // Kept while the neighbour's chunk is away, so the tube's look does not flicker.
                faces |= tube.blockFaces() & bit;
                continue;
            }
            if (tube.faceMode(side) == ConduitFaceMode.DISABLED) {
                continue;
            }
            BlockEntity neighbour = level.getBlockEntity(BlockPos.of(next));
            if (neighbour instanceof LabHubBlockEntity hub && !hub.isRemoved()) {
                faces |= bit;
                if (!hubs.contains(hub)) {
                    hubs.add(hub);
                }
            } else if (neighbour instanceof FluidPortBlockEntity port && !port.isRemoved()) {
                faces |= bit;
                if (!ports.contains(port)) {
                    ports.add(port);
                }
            }
        }
        tube.setBlockFaces(LabTubes.NETWORK_ID, faces);
    }
}
