package dev.silencebound.maxtech.storage.machine;

import dev.silencebound.maxtech.lib.api.energy.EdgeSettings;
import dev.silencebound.maxtech.lib.api.energy.EnergyEdge;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetworks;
import dev.silencebound.maxtech.lib.api.energy.FeRate;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.energy.FeBuffer;
import java.util.function.LongSupplier;
import net.minecraft.server.level.ServerLevel;

/**
 * A fuel generator's place in the energy network of the storage network it touches (MLE item 4): a producer whose
 * supply is what the generator burned into its own buffer, drawn by the network's pass. Lib's {@link EnergyEdge} does
 * the accounting, with the generator's buffer as its "neighbour" at 1:1 (both sides are MU): the producer only offers
 * what the link already took from the buffer, so the network never draws more than the generator made, and the link
 * takes at most {@code machines.fuelGenerator.maxEnergyOutput} per call. The generator takes nothing from the network.
 * What the link holds (at most about one pass of the network's demand) is saved as the generator's energy. Server
 * thread only.
 */
final class GeneratorLink extends EnergyEdge {
    private final FeBuffer buffer;
    private final LongSupplier maxOutput;
    /** Lib's settings the cached {@link #settings} were made from. */
    private EdgeSettings source = EdgeSettings.DEFAULTS;
    private EdgeSettings settings = EdgeSettings.DEFAULTS;

    GeneratorLink(ServerLevel level, FeBuffer buffer, LongSupplier maxOutput) {
        super(EnergyNetworks.of(level));
        this.buffer = buffer;
        this.maxOutput = maxOutput;
    }

    @Override
    protected long simulateInsert(long max) {
        // The generator is no consumer.
        return 0;
    }

    @Override
    protected long insert(long max) {
        // Only reached when the link gives back what it holds (closing): it goes back into the buffer.
        return buffer.receive(max);
    }

    @Override
    protected long simulateExtract(long max) {
        return Math.min(max, Math.min(buffer.stored(), Math.max(0, maxOutput.getAsLong())));
    }

    @Override
    protected long extract(long max) {
        long amount = simulateExtract(max);
        return amount > 0 && buffer.tryConsume(amount) ? amount : 0;
    }

    @Override
    @SuppressWarnings("ReferenceEquality") // a cache keyed on Lib's cached instance; equal copies only rebuild it
    protected EdgeSettings settings() {
        // Lib's export limits as they are (a network shares them among its edges), but MU on both sides.
        EdgeSettings current = ServerConfig.edgeSettings();
        if (current != source) {
            source = current;
            settings = new EdgeSettings(FeRate.ONE_TO_ONE, current.exportEnabled(), current.exportPerEdge(),
                    current.exportPerNetwork(), current.pollTicks());
        }
        return settings;
    }
}
