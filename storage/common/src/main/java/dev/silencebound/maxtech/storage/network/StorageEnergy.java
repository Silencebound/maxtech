package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetwork;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetworks;
import dev.silencebound.maxtech.lib.api.energy.EnergyScheduler;
import dev.silencebound.maxtech.lib.energy.EndpointBuffer;
import dev.silencebound.maxtech.lib.energy.FeBuffer;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * The MaxTech energy network one storage network hosts (MLE item 4): the storage network is the energy network for its
 * devices. Its members are the buffer of the controller in charge (or the ad-hoc buffer, or a subnet controller's) at
 * default priority, the energy cells at leftovers priority, the fuel generators touching the network as producers, and
 * the edges of the cells to other mods' FE blocks. Operations still pay from those buffers inside their transactions
 * ({@link NetworkState#energyBuffer()}); the network's pass only moves generators' supply into them, once per tick and
 * only after a change. Made on first use and disposed with its storage network; a merge moves the members to the
 * survivor's. In {@code pause} mode it is paused with its storage network.
 * <p>
 * It also counts the MU its flows put into Storage's buffers, which {@link NetworkState#energyReceived()} adds to what
 * came in through FE inputs, without walking anything: the primary buffer's flow here, the cells' in their pool. Server
 * thread only.
 */
final class StorageEnergy {
    private final ServerLevel level;
    private final EnergyCells cells;
    private @Nullable EnergyNetwork network;
    private @Nullable EnergyEndpoint primary;
    /** The buffer whose endpoint {@link #primary} is. */
    private @Nullable FeBuffer primaryBuffer;
    /** The primary's flow as last counted. */
    private long primaryFlow;
    /** MU the flows put into primaries up to {@link #chargedTick}. */
    private long charged;
    private long chargedTick;
    private boolean paused;

    StorageEnergy(ServerLevel level, EnergyCells cells) {
        this.level = level;
        this.cells = cells;
    }

    /** The energy network, made on first use. */
    EnergyNetwork network() {
        EnergyNetwork current = network;
        if (current == null || current.isDisposed()) {
            EnergyScheduler scheduler = EnergyNetworks.of(level);
            current = scheduler.createNetwork();
            current.setPaused(paused);
            network = current;
            cells.bind(scheduler::now);
            chargedTick = scheduler.now();
        }
        return current;
    }

    /** The energy network if one was made. */
    Optional<EnergyNetwork> existing() {
        EnergyNetwork current = network;
        return current == null || current.isDisposed() ? Optional.empty() : Optional.of(current);
    }

    /**
     * Makes {@code buffer} the primary member: the buffer operations pay from first. Only a buffer whose energy lives
     * in an endpoint takes part; nothing happens while it is the primary already. O(1).
     */
    @SuppressWarnings("ReferenceEquality") // endpoints by identity
    void syncPrimary(@Nullable FeBuffer buffer) {
        EnergyEndpoint next = buffer instanceof EndpointBuffer endpointBuffer ? endpointBuffer.endpoint() : null;
        if (next == primary) {
            return;
        }
        EnergyEndpoint previous = primary;
        if (previous != null) {
            EnergyNetwork owner = previous.network().orElse(null);
            // After a merge the survivor took the buffer already (its network and listener): leave it there.
            if (owner == null || owner == network) {
                if (owner != null) {
                    owner.remove(previous);
                }
                previous.setListener(null);
            }
        }
        primary = next;
        primaryBuffer = next == null ? null : buffer;
        primaryFlow = 0;
        if (next != null) {
            next.network().ifPresent(owner -> owner.remove(next));
            next.setListener(this::primaryFlowChanged);
            network().add(next);
        }
    }

    /** {@code store} of a cell that joined: its endpoint joins the energy network. */
    void cellJoined(EnergyCells.Store store) {
        EnergyEndpoint endpoint = store.endpoint();
        endpoint.network().ifPresent(owner -> owner.remove(endpoint));
        network().add(endpoint);
    }

    /** {@code store} of a cell that left: its endpoint leaves the energy network. */
    void cellLeft(EnergyCells.Store store) {
        EnergyNetwork current = network;
        if (current != null) {
            current.remove(store.endpoint());
        }
    }

    /**
     * Once per tick from the storage network's tick: buffers whose energy the flow changes tell their blocks, so the
     * chunk is saved with what they hold. A size check and a compare while nothing flows.
     */
    void tick() {
        FeBuffer buffer = primaryBuffer;
        if (primaryFlow != 0 && buffer != null) {
            buffer.changed();
        }
        cells.tick();
    }

    /** The storage network was paused or resumed in pause mode. */
    void setPaused(boolean pause) {
        paused = pause;
        EnergyNetwork current = network;
        if (current != null) {
            current.setPaused(pause);
        }
    }

    /** The storage network is gone: the primary leaves and the energy network is disposed (its members left first). */
    void close() {
        syncPrimary(null);
        EnergyNetwork current = network;
        if (current != null) {
            current.dispose();
        }
    }

    /** MU the energy network's flows put into the primaries and the cells of this network so far. O(1). */
    long charged() {
        return primaryCharged() + cells.charged();
    }

    private long primaryCharged() {
        long elapsed = now() - chargedTick;
        return primaryFlow > 0 && elapsed > 0 ? charged + primaryFlow * elapsed : charged;
    }

    private void primaryFlowChanged(EnergyEndpoint endpoint) {
        long now = now();
        if (primaryFlow > 0 && now > chargedTick) {
            charged += primaryFlow * (now - chargedTick);
        }
        chargedTick = now;
        primaryFlow = endpoint.flow();
    }

    private long now() {
        EnergyNetwork current = network;
        return current == null ? chargedTick : current.scheduler().now();
    }
}
