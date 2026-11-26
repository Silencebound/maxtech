package dev.silencebound.maxtech.lib.api.energy;

/** What an {@link EnergyEndpoint} does in its network; fixed when the endpoint is made. */
public enum EnergyRole {
    /** Declares a supply per tick (a generator); the network draws up to that much each tick. */
    PRODUCER,
    /** Declares a demand per tick (a machine) and receives a share of it each tick, the supplied fraction. */
    CONSUMER,
    /**
     * Holds energy, with a capacity and input and output rates (a battery): charges at its priority from producers and
     * discharges only to cover consumers that producers left short, never into another buffer.
     */
    BUFFER
}
