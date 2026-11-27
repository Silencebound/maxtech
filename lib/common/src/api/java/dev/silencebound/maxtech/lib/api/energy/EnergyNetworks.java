package dev.silencebound.maxtech.lib.api.energy;

import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

/**
 * The {@link EnergyScheduler} of each server level, which Lib ticks at the end of the level's tick. A host forms its
 * energy networks with {@code EnergyNetworks.of(level).createNetwork()}. Schedulers die with the server; a host makes
 * its networks again when its level loads. Server thread only.
 */
public final class EnergyNetworks {
    /**
     * Id of the sided block capability ({@code Direction} context, nullable) through which a block shows its
     * {@link EnergyEndpoint}, so diagnostics can find its network: Lib's Jade lines and scanner, and Storage's
     * analyzer, probe and sensors read {@link EnergyNetwork#readout()} through it. A block with several endpoints
     * exposes the one players care about (a machine its consumer, a battery its buffer). The capability is for reading:
     * callers must not add, remove or declare on the endpoint they find. Hosts register it for their block entities; a
     * NeoForge addon reaches it with {@code BlockCapability.createSided(ENDPOINT_CAPABILITY, EnergyEndpoint.class)}.
     * The block's own info provider should not add energy lines: Lib adds them under the provider's.
     */
    public static final Identifier ENDPOINT_CAPABILITY = Identifier.fromNamespaceAndPath("maxtech_lib",
            "energy_endpoint");

    private static volatile Function<ServerLevel, EnergyScheduler> levels = level -> {
        throw new IllegalStateException("MaxTech Lib has not set up energy schedulers yet");
    };

    private static volatile Supplier<FeRate> feRate = () -> FeRate.ONE_TO_ONE;

    private EnergyNetworks() {
    }

    /**
     * The server's configured FE exchange rate ({@code energy.feRateFe} FE are worth {@code energy.feRateMu} MU), or
     * {@link FeRate#ONE_TO_ONE} while no server config is loaded. For an addon's own FE edges, such as an item that
     * shows its MU to other mods' chargers as FE. Server thread.
     */
    public static FeRate feRate() {
        return feRate.get();
    }

    /** {@code level}'s energy scheduler, made on first use. */
    public static EnergyScheduler of(ServerLevel level) {
        return levels.apply(level);
    }

    /** Sets where {@link #feRate} reads the configured rate; Lib calls it once during init. */
    @ApiStatus.Internal
    public static void setFeRateSource(Supplier<FeRate> source) {
        feRate = source;
    }

    /** Sets where {@link #of} finds a level's scheduler; Lib calls it once during init. */
    @ApiStatus.Internal
    public static void setLevelSource(Function<ServerLevel, EnergyScheduler> source) {
        levels = source;
    }
}
