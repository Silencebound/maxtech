package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.storage.cable.StorageCableConduitType;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;

/**
 * Channel numbers (M7N, PRD 7.2 Channels with hubs): every device uses one channel on each link of its route to the
 * root ({@link ChannelFree} devices use none); a cable carries {@code network.channels.tier<n>}, a controller's root
 * link {@code network.channels.rootLink}, and a network without a controller has {@code network.channels.adHoc} in all.
 * Channels are counted only when the topology changes ({@link NetworkState}), never per transfer. With
 * {@code network.channels.mode = infinite} ({@link ChannelMode}, B09 item 12) no link runs out: devices still take
 * their channels, so loads show, but never more than {@link #INFINITE_LOAD_MAX} percent. Reads the server config;
 * server thread unless a method says otherwise.
 */
public final class Channels {
    /**
     * The highest load an unlimited link shows ({@link ChannelMode#INFINITE}): below {@code SmartLoad}'s saturated
     * bucket and the analyzer's saturated warning, so nothing reads as full while channels have no limit.
     */
    public static final int INFINITE_LOAD_MAX = 89;

    /** The mode the server config last loaded with; the default until it loads. */
    private static volatile ChannelMode mode = ChannelMode.LIMITED;
    /** Rises whenever {@link #mode} changes, so every network counts its channels again once. */
    private static volatile int modeStamp;

    private Channels() {
    }

    /** Whether channels have no limit ({@code network.channels.mode = infinite}). Any thread. */
    public static boolean infinite() {
        return mode == ChannelMode.INFINITE;
    }

    /** Rises each time the channel mode changes; networks compare it once per tick and recount when it moved. */
    public static int modeStamp() {
        return modeStamp;
    }

    /**
     * Takes {@code configured} as the mode in effect; a change raises {@link #modeStamp()}, so each network recounts
     * its channels once on its next tick. Called when the server config loads or reloads, on whichever thread does
     * that.
     */
    public static synchronized void modeLoaded(ChannelMode configured) {
        if (configured != mode) {
            mode = configured;
            modeStamp++;
        }
    }

    /** Whether a link with {@code capacity} channels, {@code used} of them in use, takes one more device. */
    public static boolean hasRoom(int used, int capacity, boolean infinite) {
        return infinite || used < capacity;
    }

    /** Channels a storage cable of tier {@code level} carries; tier 1 below 1, tier 4 above it. */
    public static int cable(int level) {
        return switch (Math.clamp(level, 1, StorageCableConduitType.MAX_TIER)) {
            case 1 -> StorageServerConfig.CABLE_CHANNELS_TIER_1.getAsInt();
            case 2 -> StorageServerConfig.CABLE_CHANNELS_TIER_2.getAsInt();
            case 3 -> StorageServerConfig.CABLE_CHANNELS_TIER_3.getAsInt();
            default -> StorageServerConfig.CABLE_CHANNELS_TIER_4.getAsInt();
        };
    }

    /** Channels the devices behind a hub of tier {@code level} share; tier 1 below 1, tier 3 above it. */
    public static int hub(int level) {
        return switch (Math.clamp(level, 1, 3)) {
            case 1 -> StorageServerConfig.HUB_CHANNELS_TIER_1.getAsInt();
            case 2 -> StorageServerConfig.HUB_CHANNELS_TIER_2.getAsInt();
            default -> StorageServerConfig.HUB_CHANNELS_TIER_3.getAsInt();
        };
    }

    /** Channels each root link of a controller carries. */
    public static int rootLink() {
        return StorageServerConfig.ROOT_LINK_CHANNELS.getAsInt();
    }

    /** Channels a network without a controller has in all: its device limit. */
    public static int adHoc() {
        return StorageServerConfig.AD_HOC_CHANNELS.getAsInt();
    }

    /** How full a link is, 0 to 100: {@code used} of {@code capacity} channels; 0 for a link without a limit. */
    public static int loadPercent(int used, int capacity) {
        if (used <= 0 || capacity <= 0) {
            return 0;
        }
        return (int) Math.min(100, (long) used * 100 / capacity);
    }

    /**
     * {@link #loadPercent(int, int)}, but with no limit ({@code infinite}) at most {@link #INFINITE_LOAD_MAX}: the link
     * still shows how busy it is against its tier's channels, never full.
     */
    public static int loadPercent(int used, int capacity, boolean infinite) {
        int load = loadPercent(used, capacity);
        return infinite ? Math.min(load, INFINITE_LOAD_MAX) : load;
    }
}
