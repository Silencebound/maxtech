package dev.silencebound.maxtech.lib.energy;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetworks;
import dev.silencebound.maxtech.lib.platform.BlockLookup;
import dev.silencebound.maxtech.lib.platform.Platform;

/** Suite-internal handle for the endpoint capability declared by {@link EnergyNetworks#ENDPOINT_CAPABILITY}. */
public final class EnergyLookups {
    /** The sided energy endpoint of a block, for diagnostics. */
    public static final BlockLookup<EnergyEndpoint> BLOCK = Platform.get().capabilities()
            .sided(EnergyNetworks.ENDPOINT_CAPABILITY, EnergyEndpoint.class);

    private EnergyLookups() {
    }
}
