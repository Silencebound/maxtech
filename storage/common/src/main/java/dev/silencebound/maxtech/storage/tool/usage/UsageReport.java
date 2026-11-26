package dev.silencebound.maxtech.storage.tool.usage;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.api.StorageDevice;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageNetworkLogic;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import dev.silencebound.maxtech.storage.network.TeamCaps;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * A snapshot of what a team's storage networks use in one level (the usage report book, M6e area F): the team's totals
 * against the server caps, each network's devices by role against its device limit, the devices it turned away, its
 * cell bytes and zones, and the devices per owner. Built from the networks' own counters on the server thread; the
 * record itself is immutable and any thread may read it.
 *
 * @param subject
 *            whose report it is: the player's name, or the network's owner
 * @param devices
 *            devices the team's networks in this level hold against the device cap; 0 when not capped
 * @param zones
 *            zones of the team's networks in this level; 0 when not capped
 * @param bytes
 *            cell bytes the team's networks in this level use; 0 when not capped
 * @param networks
 *            one row per network, by id
 * @param owners
 *            devices per owner over every network of the report, most first
 */
public record UsageReport(String subject, int devices, int zones, long bytes, List<NetworkRow> networks,
        List<OwnerCount> owners) {
    /** Name used for devices without an owner. */
    public static final String UNOWNED = "-";

    public UsageReport {
        networks = List.copyOf(networks);
        owners = List.copyOf(owners);
    }

    /**
     * One network's line.
     *
     * @param limit
     *            most devices the network allows, {@link Integer#MAX_VALUE} without a controller limit or cap
     * @param teamCapped
     *            whether the team's device cap, not the controller, sets {@code limit}
     * @param storage
     *            devices of role {@link StorageDevice.Role#STORAGE}; likewise {@code access}, {@code transfer} and
     *            {@code other} (controllers and their cluster are left out)
     */
    public record NetworkRow(int id, @Nullable BlockPos root, String owner, int limited, int limit, boolean teamCapped,
            int refused, long usedBytes, long totalBytes, int zones, int offlineCells, int storage, int access,
            int transfer, int other) {
    }

    /** Devices one owner placed in the report's networks. */
    public record OwnerCount(String name, int devices) {
    }

    /**
     * The report for {@code viewer}'s team in {@code level}: every network whose team (as the server caps count it) is
     * the viewer's research team, else the viewer alone.
     */
    public static UsageReport forTeam(ServerLevel level, String viewer, OwnerRecord asOwner) {
        UUID team = TeamCaps.team(level, asOwner);
        List<NetworkState> networks = new ArrayList<>();
        StorageNetworkLogic logic = StorageNetworks.logic(level, StorageNetworks.ID);
        if (logic != null && team != null) {
            for (NetworkState network : logic.networks()) {
                if (team.equals(network.capTeam())) {
                    networks.add(network);
                }
            }
        }
        return build(viewer, networks);
    }

    /** The report for {@code network} alone, with its team's totals. */
    public static UsageReport forNetwork(NetworkState network) {
        OwnerRecord owner = network.owner();
        return build(owner.isOwned() ? owner.ownerName() : UNOWNED, List.of(network));
    }

    private static UsageReport build(String subject, List<NetworkState> found) {
        List<NetworkState> networks = new ArrayList<>(found);
        networks.sort(Comparator.comparingInt(NetworkState::id));
        List<NetworkRow> rows = new ArrayList<>(networks.size());
        List<String> owners = new ArrayList<>();
        int devices = 0;
        int zones = 0;
        long bytes = 0;
        boolean totals = false;
        for (NetworkState network : networks) {
            rows.add(row(network));
            for (NetworkDevice device : network.devices()) {
                if (device.role() != StorageDevice.Role.CONTROLLER) {
                    owners.add(ownerName(device.ownerRecord()));
                }
            }
            // Every network of one team reports the same team totals; the first one's are the report's.
            if (!totals && network.capTeam() != null) {
                totals = true;
                devices = network.teamDevicesUsed();
                zones = network.teamZonesUsed();
                bytes = network.teamBytesUsed();
            }
        }
        return new UsageReport(subject, devices, zones, bytes, rows, tally(owners));
    }

    private static NetworkRow row(NetworkState network) {
        int storage = network.deviceCount(StorageDevice.Role.STORAGE);
        int access = network.deviceCount(StorageDevice.Role.ACCESS);
        int transfer = network.deviceCount(StorageDevice.Role.TRANSFER);
        int other = network.deviceCount(StorageDevice.Role.OTHER);
        long root = network.routeRoot();
        OwnerRecord owner = network.owner();
        return new NetworkRow(network.id(), root == Long.MIN_VALUE ? null : BlockPos.of(root), ownerName(owner),
                network.limitedDevices(), network.deviceLimit(), network.teamCapped(), network.refusedDevices().size(),
                network.usedBytes(), network.totalBytes(), network.zoneCount(), network.offlineCells(), storage, access,
                transfer, other);
    }

    private static String ownerName(OwnerRecord owner) {
        return owner.isOwned() ? owner.ownerName() : UNOWNED;
    }

    /** Counts {@code names} (one entry per device), most devices first, ties by name. */
    public static List<OwnerCount> tally(List<String> names) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String name : names) {
            counts.merge(name, 1, Integer::sum);
        }
        List<OwnerCount> result = new ArrayList<>(counts.size());
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            result.add(new OwnerCount(entry.getKey(), entry.getValue()));
        }
        result.sort(Comparator.comparingInt(OwnerCount::devices).reversed().thenComparing(OwnerCount::name));
        return result;
    }
}
