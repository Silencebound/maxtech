package dev.silencebound.maxtech.storage.network;

/**
 * Marks a device that powers its network without limit. While one is a member, the network's operations are paid from a
 * store that never runs out instead of the controller's or the ad-hoc buffer, so the network counts as powered.
 */
public interface NetworkPowerSource extends NetworkDevice, ChannelFree {
}
