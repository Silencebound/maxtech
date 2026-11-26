package dev.silencebound.maxtech.lib.api.energy;

/**
 * Named priorities of {@link EnergyEndpoint consumers and buffers}. A priority is a plain {@code int}: an energy
 * network serves higher priorities first, and the endpoints of one priority share what reaches them evenly, each
 * getting the same fraction of its demand (within one MU of rounding). Constants only.
 */
public final class EnergyPriority {
    /** The priority an endpoint has unless its owner sets another. */
    public static final int DEFAULT = 0;
    /**
     * Served after every other priority: a buffer at this priority charges only from what is left once every machine
     * got its share ("leftovers only").
     */
    public static final int LEFTOVERS = Integer.MIN_VALUE;

    private EnergyPriority() {
    }
}
