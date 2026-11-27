package dev.silencebound.maxtech.farms.lab;

import dev.silencebound.maxtech.farms.hub.LabHubBlockEntity;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetwork;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetworks;
import dev.silencebound.maxtech.lib.api.network.NetworkManager;
import dev.silencebound.maxtech.lib.api.network.NetworkGraph;
import dev.silencebound.maxtech.lib.api.network.Network;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
// PLATFORM-LEAK: NeoForge's transfer api moves nutrient and output into the hub (root CLAUDE.md: transfers).
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.fluid.FluidResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * Farms' side of one lab network: the tubes of one Lib network, the one lab hub that runs it, the fluid ports that feed
 * it and the energy network its MU moves in. A lab network has at most one hub; a second hub on the same tubes stays
 * inactive ({@link LabHubBlockEntity#labLink()}). Nutrient and output reach the hub only through this class, inside the
 * caller's transfer transaction. Kept by {@link LabNetworkLogic}, which rebuilds the hub and ports when the tubes or
 * their neighbours change, never per tick. Server thread only.
 */
public final class LabNetwork {
    private final Network network;
    private final EnergyNetwork energy;
    private @Nullable LabHubBlockEntity hub;
    private final ObjectArrayList<LabHubBlockEntity> refused = new ObjectArrayList<>();
    private final ObjectArrayList<FluidPortBlockEntity> ports = new ObjectArrayList<>();
    boolean dirty = true;

    LabNetwork(ServerLevel level, Network network) {
        this.network = network;
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

    /**
     * Takes the hubs and ports found next to the tubes: keeps the current hub if it is still there and free, else the
     * one at the lowest position; every other hub is refused. Hubs and ports that are gone are let go.
     */
    void update(ObjectArrayList<LabHubBlockEntity> hubs, ObjectArrayList<FluidPortBlockEntity> found) {
        dirty = false;
        LabHubBlockEntity chosen = null;
        for (int i = 0; i < hubs.size(); i++) {
            LabHubBlockEntity candidate = hubs.get(i);
            if (!candidate.isFreeFor(this)) {
                continue;
            }
            if (candidate == hub) {
                chosen = candidate;
                break;
            }
            if (chosen == null || candidate.getBlockPos().asLong() < chosen.getBlockPos().asLong()) {
                chosen = candidate;
            }
        }
        LabHubBlockEntity previous = hub;
        if (previous != null && previous != chosen) {
            previous.leaveLab(this);
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
    }

    /** Lets go of the hub and ports and ends the energy network; the tubes are gone or joined another network. */
    void dispose() {
        LabHubBlockEntity previous = hub;
        hub = null;
        if (previous != null) {
            previous.leaveLab(this);
        }
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
