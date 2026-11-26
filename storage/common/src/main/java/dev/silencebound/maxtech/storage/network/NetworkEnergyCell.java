package dev.silencebound.maxtech.storage.network;

/**
 * A device that stores FE for its network (an energy storage block). While it is a member, its store joins the
 * network's pool ({@link EnergyCells}): surplus FE that the controller's or the ad-hoc buffer cannot take fills it, and
 * operations drain it once that buffer runs short.
 */
public interface NetworkEnergyCell extends NetworkDevice, ChannelFree {
    /** The cell's store; the same instance for the device's whole life. */
    EnergyCells.Store cellStore();
}
