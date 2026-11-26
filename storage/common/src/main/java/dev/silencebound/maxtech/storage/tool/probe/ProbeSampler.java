package dev.silencebound.maxtech.storage.tool.probe;

import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.AnalyzerItem;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetwork;
import dev.silencebound.maxtech.lib.api.energy.EnergyReadout;
import dev.silencebound.maxtech.storage.energy.EnergyDiagnostics;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

/**
 * Reads the signal a probe graphs: how busy the link from a storage block towards its network's root is, the same
 * number the analyzer reports as segment load. One read costs a map lookup, so a probe screen samples it once a second
 * and nothing runs for a probe that is not open.
 */
public final class ProbeSampler {
    private ProbeSampler() {
    }

    /**
     * What one read found; {@code load} is 0 to 100, or {@link ProbeSeries#NO_SIGNAL}; {@code rate} is the link's
     * channels. {@code energy} says whether the block has a MaxTech energy network to show (MLE; none while Storage
     * runs on FE), whose supply, demand and delivered MU per tick follow from its last pass.
     */
    public record Sample(int load, long rate, boolean energy, long supply, long demand, long received) {
        public static final Sample NONE = new Sample(ProbeSeries.NO_SIGNAL, 0, false, 0, 0, 0);
    }

    /**
     * Whether {@code pos} in {@code level} is a storage cable or device, or a block that shows a MaxTech energy
     * endpoint (a fuel generator), which a probe can be bound to.
     */
    public static boolean probeable(ServerLevel level, BlockPos pos) {
        return AnalyzerItem.networkAt(level, pos) != null || EnergyDiagnostics.showsEndpoint(level, pos, null);
    }

    /**
     * Whether {@code player} may read the block at {@code pos}: any access above none, by the network's security rules
     * or, without them, by the device's owner security (a cable has no owner of its own).
     */
    public static boolean mayRead(ServerLevel level, BlockPos pos, Player player) {
        if (level.getBlockEntity(pos) instanceof StorageDeviceBlockEntity device) {
            return NetworkAccess.level(player, device) != AccessLevel.NONE;
        }
        NetworkState network = AnalyzerItem.networkAt(level, pos);
        AccessLevel rule = network == null ? null : network.securityLevel(player);
        return rule != AccessLevel.NONE;
    }

    /**
     * The load of the block at {@code pos}, and its energy network's readout when it has one (never waking it);
     * {@link Sample#NONE} when its chunk is unloaded or it is in neither a storage nor an energy network. A block
     * outside storage networks whose energy endpoint is in an energy network (a generator that feeds one) has no load
     * but shows the readout.
     */
    public static Sample sample(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return Sample.NONE;
        }
        NetworkState network = AnalyzerItem.networkAt(level, pos);
        if (network == null) {
            Optional<EnergyNetwork> energy = EnergyDiagnostics.networkAt(level, pos, null);
            if (energy.isEmpty()) {
                return Sample.NONE;
            }
            EnergyReadout readout = energy.get().readout();
            return new Sample(ProbeSeries.NO_SIGNAL, 0, true, readout.supply(), readout.demand(), readout.received());
        }
        long node = level.getBlockEntity(pos) instanceof StorageDeviceBlockEntity device
                ? device.nodePos()
                : pos.asLong();
        Optional<EnergyNetwork> energy = EnergyDiagnostics.networkAt(level, pos, network);
        if (energy.isEmpty()) {
            return new Sample(network.segmentLoad(node), network.segmentCapacity(node), false, 0, 0, 0);
        }
        EnergyReadout readout = energy.get().readout();
        return new Sample(network.segmentLoad(node), network.segmentCapacity(node), true, readout.supply(),
                readout.demand(), readout.received());
    }
}
