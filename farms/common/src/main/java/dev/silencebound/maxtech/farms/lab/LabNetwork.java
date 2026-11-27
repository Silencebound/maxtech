package dev.silencebound.maxtech.farms.lab;

import dev.silencebound.maxtech.farms.hub.LabHubBlockEntity;
import dev.silencebound.maxtech.lib.api.conduit.ConduitContents;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceMode;
import dev.silencebound.maxtech.lib.api.conduit.ConduitHost;
import dev.silencebound.maxtech.lib.api.conduit.Conduits;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetwork;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetworks;
import dev.silencebound.maxtech.lib.api.network.NetworkManager;
import dev.silencebound.maxtech.lib.api.network.NetworkGraph;
import dev.silencebound.maxtech.lib.api.network.Network;
import dev.silencebound.maxtech.lib.api.network.RouteTree;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.Fluid;
// PLATFORM-LEAK: NeoForge's transfer api moves nutrient and output into the hub (root CLAUDE.md: transfers).
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.fluid.FluidStacksResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.fluid.FluidResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * Farms' side of one lab network: the tubes of one Lib network, the one lab hub that runs it, the fluid ports that feed
 * it and the energy network its MU moves in. A lab network has at most one hub. A running hub keeps its lab: a hub
 * placed later beside the tubes stays inactive ({@link LabHubBlockEntity#labLink()}), even at a lower position. The
 * position order (lowest y, then lowest x, then lowest z; {@link #isBefore}) decides only between hubs alike: among
 * running hubs when two labs that each have one merge, and among all hubs when none is running, as after a restart
 * (running is not saved; a hub whose chunk loads after its lab took another hub is refused like a placed one). A split
 * keeps the running hub on its side. A hub beside two lab networks runs the one that took it first; that is a
 * misconfiguration the other lab shows as having no hub. A hub that loses its lab stops running unless the lab merged
 * or split into tubes still beside it. A tube face set to disabled does not attach the block beside it. Nutrient and
 * output reach the hub only through this class, inside the caller's transfer transaction. Kept by
 * {@link LabNetworkLogic}, which rebuilds the hub and ports when the tubes or their neighbours change, never per tick.
 * <p>
 * Every tube shows the hub tank's fluid and level ({@link ConduitContents}), flowing from the hub: the upstream side of
 * a tube is its next step towards the hub along the network, worked out when the topology or the hub changes. The fill
 * step follows the tank with a margin ({@link TubeFill}), and the tubes are set again only when the fluid or the step
 * changes. The second lane (output flowing back) stays off until output moves through the lab. Server thread only.
 */
public final class LabNetwork {
    private static final Direction[] SIDES = Direction.values();
    /** Upstream index of a tube without a route to the hub. */
    private static final byte NO_UPSTREAM = -1;
    private final ServerLevel level;
    private final Network network;
    private final EnergyNetwork energy;
    private @Nullable LabHubBlockEntity hub;
    private final ObjectArrayList<LabHubBlockEntity> refused = new ObjectArrayList<>();
    private final ObjectArrayList<FluidPortBlockEntity> ports = new ObjectArrayList<>();
    private boolean dirty = true;
    /** Networks this lab merged into or split into since its last update; a hub on them keeps running. */
    private final ObjectArrayList<Network> successors = new ObjectArrayList<>();
    /** Tube position -> {@link Direction#get3DDataValue()} of its side towards the hub, from the last update. */
    private final Long2ByteOpenHashMap upstream = new Long2ByteOpenHashMap();
    /** The hub whose tank the tubes show, the fluid and step they show and the contents per upstream side. */
    private @Nullable LabHubBlockEntity shownHub;
    private @Nullable Fluid shownFluid;
    private int shownFill;
    private final ConduitContents[] contents = new ConduitContents[SIDES.length + 1];

    LabNetwork(ServerLevel level, Network network) {
        this.level = level;
        this.network = network;
        upstream.defaultReturnValue(NO_UPSTREAM);
        this.energy = EnergyNetworks.of(level).createNetwork();
    }

    /**
     * The lab network whose tube is at {@code pos}, or empty if there is no lab tube there or it joined this tick.
     * Allocates; blocks that join a lab later (specimens) should keep what they find rather than ask per tick.
     */
    public static Optional<LabNetwork> at(ServerLevel level, BlockPos pos) {
        NetworkGraph graph = NetworkManager.get(level).graph(LabTubes.NETWORK);
        Network found = graph.network(pos.asLong());
        if (found == null || !(graph.logic() instanceof LabNetworkLogic logic)) {
            return Optional.empty();
        }
        return Optional.ofNullable(logic.lab(found));
    }

    /** The Lib network of the tubes. */
    public Network network() {
        return network;
    }

    /** Number of lab tubes. */
    public int tubes() {
        return network.size();
    }

    /** Whether the tubes still form this network (not merged away, split to nothing or broken). */
    public boolean isAlive() {
        return network.isAlive() && !energy.isDisposed();
    }

    /** The hub that runs this lab, or empty while it has none (or it was just removed). */
    public Optional<LabHubBlockEntity> hub() {
        LabHubBlockEntity current = hub;
        return current == null || current.isRemoved() ? Optional.empty() : Optional.of(current);
    }

    /**
     * The lab's energy network: the hub's MU store is its buffer; MU consumers of the lab (specimen blocks) add their
     * endpoints here and remove them when they go.
     */
    public EnergyNetwork energy() {
        return energy;
    }

    /** Fluid ports feeding this lab. */
    public int portCount() {
        return ports.size();
    }

    /**
     * Puts up to {@code amount} mB of {@code fluid} into the hub's tank in {@code transaction} and returns what fit; 0
     * without a hub. Fluid never leaves the lab this way.
     */
    public int insertNutrient(FluidResource fluid, int amount, TransactionContext transaction) {
        LabHubBlockEntity current = hub().orElse(null);
        if (current == null || fluid.isEmpty() || amount <= 0) {
            return 0;
        }
        return current.storage().tank().insert(fluid, amount, transaction);
    }

    /**
     * Puts up to {@code amount} of {@code item} into the hub's output buffer in {@code transaction}, stacking onto what
     * is there first, and returns what fit; 0 without a hub. Where the lab's output (specimen drops) goes.
     */
    public int insertOutput(ItemResource item, int amount, TransactionContext transaction) {
        LabHubBlockEntity current = hub().orElse(null);
        if (current == null || item.isEmpty() || amount <= 0) {
            return 0;
        }
        return ResourceHandlerUtil.insertStacking(current.storage().output(), item, amount, transaction);
    }

    /** Asks for a fresh look at the tubes' neighbours on the next network tick. */
    void markDirty() {
        dirty = true;
    }

    /** Whether a look at the tubes' neighbours is pending. */
    boolean isDirty() {
        return dirty;
    }

    /**
     * The hub order: whether {@code a} comes before {@code b}, that is has a lower y, or the same y and a lower x, or
     * the same y and x and a lower z.
     */
    static boolean isBefore(BlockPos a, BlockPos b) {
        if (a.getY() != b.getY()) {
            return a.getY() < b.getY();
        }
        if (a.getX() != b.getX()) {
            return a.getX() < b.getX();
        }
        return a.getZ() < b.getZ();
    }

    /**
     * Whether a tube of this lab is beside {@code hubPos} with its face towards it not disabled; a tube in an unloaded
     * chunk counts. Allocates; for topology changes only.
     */
    public boolean touches(BlockPos hubPos) {
        return attaches(level, network, hubPos, null);
    }

    /**
     * Whether a tube of {@code network} is beside {@code pos} with its face towards it not disabled (a tube in an
     * unloaded chunk counts), appending the attached tubes' positions to {@code out} if given.
     */
    static boolean attaches(ServerLevel level, Network network, BlockPos pos, @Nullable LongArrayList out) {
        boolean found = false;
        long packed = pos.asLong();
        for (Direction side : SIDES) {
            long next = BlockPos.offset(packed, side);
            if (!network.contains(next)) {
                continue;
            }
            if (Conduits.isChunkPresent(level, BlockPos.getX(next), BlockPos.getZ(next))) {
                ConduitHost tube = Conduits.host(level, BlockPos.of(next)).orElse(null);
                if (tube == null || tube.faceMode(side.getOpposite()) == ConduitFaceMode.DISABLED) {
                    continue;
                }
            }
            found = true;
            if (out == null) {
                return true;
            }
            out.add(next);
        }
        return found;
    }

    /** This lab's tubes merged into or split off {@code next}; a hub this lab lets go of keeps running on them. */
    void addSuccessor(Network next) {
        if (!successors.contains(next)) {
            successors.add(next);
        }
    }

    /** Whether {@code hub}, which this lab lets go of, is beside tubes this lab merged or split into. */
    private boolean handsOver(LabHubBlockEntity hub) {
        for (int i = 0; i < successors.size(); i++) {
            Network next = successors.get(i);
            if (next.isAlive() && attaches(level, next, hub.getBlockPos(), null)) {
                return true;
            }
        }
        return false;
    }

    /** {@code taken} now runs another lab; this one looks for a hub again. Called by the hub only. */
    public void forget(LabHubBlockEntity taken) {
        if (hub == taken) {
            hub = null;
            dirty = true;
        }
    }

    /**
     * Takes the hubs and ports found next to the tubes: among the free hubs, a running one wins over the others and the
     * first in {@link #isBefore} order wins among alike; every other free hub is refused, which ends its running. A
     * replaced hub's MU store leaves the energy network before the new one's joins. Hubs and ports that are gone are
     * let go.
     */
    void update(ObjectArrayList<LabHubBlockEntity> hubs, ObjectArrayList<FluidPortBlockEntity> found) {
        dirty = false;
        LabHubBlockEntity chosen = null;
        boolean chosenRunning = false;
        for (int i = 0; i < hubs.size(); i++) {
            LabHubBlockEntity candidate = hubs.get(i);
            if (!candidate.isFreeFor(this)) {
                continue;
            }
            boolean running = candidate == hub || candidate.isRunning();
            if (chosen == null || (running && !chosenRunning)
                    || (running == chosenRunning && isBefore(candidate.getBlockPos(), chosen.getBlockPos()))) {
                chosen = candidate;
                chosenRunning = running;
            }
        }
        LabHubBlockEntity previous = hub;
        if (previous != null && previous != chosen) {
            previous.leaveLab(this, handsOver(previous));
        }
        hub = chosen;
        if (chosen != null) {
            chosen.joinLab(this);
        }
        for (int i = 0; i < refused.size(); i++) {
            LabHubBlockEntity old = refused.get(i);
            if (!hubs.contains(old) || old == chosen) {
                old.setRefusedBy(this, false);
            }
        }
        refused.clear();
        for (int i = 0; i < hubs.size(); i++) {
            LabHubBlockEntity other = hubs.get(i);
            if (other != chosen && other.isFreeFor(this)) {
                other.setRefusedBy(this, true);
                refused.add(other);
            }
        }
        for (int i = 0; i < ports.size(); i++) {
            FluidPortBlockEntity old = ports.get(i);
            if (!found.contains(old)) {
                old.setLab(this, false);
            }
        }
        ports.clear();
        for (int i = 0; i < found.size(); i++) {
            FluidPortBlockEntity port = found.get(i);
            port.setLab(this, true);
            ports.add(port);
        }
        successors.clear();
    }

    /**
     * Works out each tube's side towards the hub along the network, then sets every tube's contents from the hub tank.
     * Called after {@link #update} with the lab's graph, on topology and neighbour changes only.
     */
    void refreshContents(RouteTree.Links links) {
        upstream.clear();
        LabHubBlockEntity current = hub().orElse(null);
        if (current != null) {
            long root = current.getBlockPos().asLong();
            LongArrayList attached = new LongArrayList();
            attaches(level, network, current.getBlockPos(), attached);
            // The hub is the root; its links are the tubes attached to it, every other node keeps the graph's links.
            RouteTree tree = RouteTree.build(root, (pos, out) -> {
                if (pos == root) {
                    out.addAll(attached);
                } else {
                    links.links(pos, out);
                }
            }, (from, to) -> 1, network.size() + 1);
            for (LongIterator it = network.nodes().iterator(); it.hasNext();) {
                long pos = it.nextLong();
                if (tree.contains(pos)) {
                    upstream.put(pos, sideTowards(pos, tree.parent(pos)));
                }
            }
        }
        if (current != shownHub) {
            shownHub = current;
            // A new hub's level is read afresh, as if from empty.
            shownFill = 0;
        }
        recomputeContents();
        syncTubes();
    }

    /**
     * {@code changed}'s tank changed (on commit): the tubes are set again when its fluid or fill step changes, so
     * filling or draining by small amounts costs one comparison. Called by the hub only.
     */
    public void hubTankChanged(LabHubBlockEntity changed) {
        if (changed != hub || changed != shownHub) {
            return;
        }
        if (recomputeContents()) {
            syncTubes();
        }
    }

    /** Reads the shown hub's tank; returns whether the fluid or step changed, building the contents anew then. */
    private boolean recomputeContents() {
        LabHubBlockEntity current = shownHub;
        Fluid fluid = null;
        int fill = 0;
        if (current != null && !current.isRemoved()) {
            FluidStacksResourceHandler tank = current.storage().tank();
            long amount = tank.getAmountAsLong(0);
            if (amount > 0) {
                fluid = tank.getResource(0).getFluid();
                long capacity = tank.getCapacityAsLong(0, tank.getResource(0));
                fill = TubeFill.step(amount, capacity, Objects.equals(fluid, shownFluid) ? shownFill : 0);
            }
        }
        if (Objects.equals(fluid, shownFluid) && fill == shownFill && contents[0] != null) {
            return false;
        }
        shownFluid = fluid;
        shownFill = fill;
        for (int i = 0; i < contents.length; i++) {
            contents[i] = fluid == null
                    ? ConduitContents.EMPTY
                    : new ConduitContents(Optional.of(fluid), fill,
                            i == 0 ? Optional.empty() : Optional.of(Direction.from3DDataValue(i - 1)), false);
        }
        return true;
    }

    private ConduitContents contentsFor(byte side) {
        ConduitContents shown = contents[side + 1];
        return shown == null ? ConduitContents.EMPTY : shown;
    }

    /** Sets every loaded tube's contents; a tube that already shows them does nothing. */
    private void syncTubes() {
        for (LongIterator it = network.nodes().iterator(); it.hasNext();) {
            long pos = it.nextLong();
            if (!Conduits.isChunkPresent(level, BlockPos.getX(pos), BlockPos.getZ(pos))) {
                continue;
            }
            ConduitHost tube = Conduits.host(level, BlockPos.of(pos)).orElse(null);
            if (tube != null) {
                tube.setContents(contentsFor(upstream.get(pos)));
            }
        }
    }

    /** The side of {@code pos} that faces the next node {@code towards}, as a 3D data value. */
    private static byte sideTowards(long pos, long towards) {
        for (Direction side : SIDES) {
            if (BlockPos.offset(pos, side) == towards) {
                return (byte) side.get3DDataValue();
            }
        }
        return NO_UPSTREAM;
    }

    /** Lets go of the hub and ports and ends the energy network; the tubes are gone or joined another network. */
    void dispose() {
        LabHubBlockEntity previous = hub;
        hub = null;
        if (previous != null) {
            previous.leaveLab(this, handsOver(previous));
        }
        successors.clear();
        for (int i = 0; i < refused.size(); i++) {
            refused.get(i).setRefusedBy(this, false);
        }
        refused.clear();
        for (int i = 0; i < ports.size(); i++) {
            ports.get(i).setLab(this, false);
        }
        ports.clear();
        energy.dispose();
    }
}
