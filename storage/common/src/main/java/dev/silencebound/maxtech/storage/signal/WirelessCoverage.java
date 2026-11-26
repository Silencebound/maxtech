package dev.silencebound.maxtech.storage.signal;

import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.module.DimensionBridge;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkService;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Where a wireless terminal reaches its network (M6d; M7R item 4): within the network's wireless range of any of its
 * wireless access mounts or of any loaded antenna in the network, in the network's dimension only. The mounts are
 * network devices placed anywhere on the network; the range comes from how many the network has
 * ({@code signal.wireless.*}), and without one there is none. The network keeps its mounts in a list it changes on
 * joins and leaves only, so nothing here searches for them. From another dimension a terminal reaches the network only
 * while it also has a dimension bridge ({@link #reaches}, M6f). Asked when a terminal opens and on each of its menu's
 * validity checks, so the mounts come first and antennas are walked only beyond them. Server thread.
 */
public final class WirelessCoverage {
    private WirelessCoverage() {
    }

    /** The wireless range of {@code core}'s network in blocks; 0 without a wireless access mount. */
    public static int range(ControllerBlockEntity core) {
        NetworkState network = core.network();
        return range(network == null ? 0 : network.serviceCount(NetworkService.WIRELESS_ACCESS));
    }

    /** The wireless range in blocks with {@code mounts} wireless access mounts. */
    public static int range(int mounts) {
        return SignalMath.wirelessRange(mounts, StorageServerConfig.WIRELESS_BASE_RANGE.getAsInt(),
                StorageServerConfig.WIRELESS_RANGE_PER_MOUNT.getAsInt(),
                StorageServerConfig.WIRELESS_MAX_RANGE.getAsInt());
    }

    /**
     * Whether a pocket device at {@code at} in {@code level} reaches {@code core}'s network: in the core's dimension
     * when it {@link #covers} the spot; from any other dimension, anywhere, when the network has a wireless access
     * mount and a {@link DimensionBridge}.
     */
    @SuppressWarnings("ReferenceEquality") // levels are compared by identity
    public static boolean reaches(ControllerBlockEntity core, Level level, Vec3 at) {
        if (core.getLevel() == level) {
            return covers(core, level, at, range(core));
        }
        return !core.isRemoved() && range(core) > 0 && DimensionBridge.bridged(core.network());
    }

    /** Whether {@code at} in {@code level} is covered by {@code core}'s network; see the class comment. */
    public static boolean covers(ControllerBlockEntity core, Level level, Vec3 at) {
        return covers(core, level, at, range(core));
    }

    @SuppressWarnings("ReferenceEquality") // levels and networks are compared by identity
    private static boolean covers(ControllerBlockEntity core, Level level, Vec3 at, int range) {
        NetworkState network = core.network();
        if (core.getLevel() != level || network == null || core.isRemoved()) {
            return false;
        }
        if (range <= 0) {
            return false;
        }
        double rangeSqr = (double) range * range;
        List<NetworkDevice> mounts = network.serviceDevices(NetworkService.WIRELESS_ACCESS);
        for (int i = 0; i < mounts.size(); i++) {
            long pos = mounts.get(i).nodePos();
            if (at.distanceToSqr(BlockPos.getX(pos) + 0.5, BlockPos.getY(pos) + 0.5,
                    BlockPos.getZ(pos) + 0.5) <= rangeSqr) {
                return true;
            }
        }
        for (AntennaBlockEntity antenna : Antennas.loaded(level).values()) {
            if (antenna.network() == network && at.distanceToSqr(antenna.getBlockPos().getCenter()) <= rangeSqr) {
                return true;
            }
        }
        return false;
    }
}
