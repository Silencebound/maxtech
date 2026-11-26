package dev.silencebound.maxtech.storage.energy;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetwork;
import dev.silencebound.maxtech.lib.energy.EnergyLookups;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Finds the MaxTech energy network (MLE) a block's diagnostics show, for the probe and the analyzer: the network of the
 * energy endpoint the block shows (Lib's endpoint capability), else the one its storage network hosts
 * ({@link NetworkState#energyNetwork()}). Both are empty while Storage runs on FE, so the readouts show nothing until
 * MLE item 4. Never wakes a network; one capability lookup per call, so for screens and clicks, not ticks. Server
 * thread only.
 */
public final class EnergyDiagnostics {
    private EnergyDiagnostics() {
    }

    /** The energy network to show for the block at {@code pos} in {@code storage} (null for none); may be empty. */
    public static Optional<EnergyNetwork> networkAt(ServerLevel level, BlockPos pos, @Nullable NetworkState storage) {
        EnergyEndpoint endpoint = EnergyLookups.BLOCK.find(level, pos, null);
        if (endpoint != null && endpoint.network().isPresent()) {
            return endpoint.network();
        }
        return storage == null ? Optional.empty() : storage.energyNetwork();
    }

    /** Whether the block at {@code pos} shows an energy endpoint on {@code side}, whose lines the scanner prints. */
    public static boolean showsEndpoint(ServerLevel level, BlockPos pos, @Nullable Direction side) {
        return EnergyLookups.BLOCK.find(level, pos, side) != null;
    }
}
