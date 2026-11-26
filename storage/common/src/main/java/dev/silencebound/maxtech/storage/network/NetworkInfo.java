package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.api.info.InfoStack;
import dev.silencebound.maxtech.lib.info.InfoBars;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.cable.StorageCableConduitType;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.crafting.job.JobToastPayload;
import dev.silencebound.maxtech.storage.redstone.RedstoneControlled;
import dev.silencebound.maxtech.storage.redstone.RedstoneTexts;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

/**
 * What storage blocks tell the scanner, the analyzer and Jade through Lib's {@code BlockInfoProvider} (PRD 7.2,
 * Diagnostics). At a glance ({@link BlockInfoRequest.Detail#BRIEF}, at most three entries): the status
 * ({@link Glance}), then the block's most important number (a cable's channel pips, a device's
 * {@link NetworkDevice#addGlance}). With {@link BlockInfoRequest.Detail#FULL}: label, why a device is off or offline
 * for channels, the device's own details, the zone and the channels on the route segment towards the root, power, and
 * the network's size and contents. Brief entries read cached values only, so overlays can ask every few ticks.
 */
public final class NetworkInfo {
    public static final String CABLE_KEY = key("cable");
    /** A cable's pips in words: "Tier 2: 5 / 16 channels". */
    public static final String CABLE_GLANCE_KEY = key("cable_glance");
    /** A fill bar's label: "64%". */
    public static final String FILL_KEY = key("fill");
    /** A channel bar's label: "21/32". */
    public static final String CHANNELS_KEY = key("channels");
    /** A channel limit that is not there ({@link ChannelMode#INFINITE}), in place of a number: "∞". */
    public static final String UNLIMITED_KEY = key("channels_unlimited");
    /** One item or fluid of a stacks row in words: "Cobblestone x1.2k". */
    public static final String STACK_KEY = key("stack");
    /** A drive's details: "Bytes: 1,234 / 4,096 in 2 cells, 0 full". */
    public static final String BYTES_KEY = key("bytes");
    /** A port's glance next to its mode arrow: "64/s". */
    public static final String PORT_GLANCE_KEY = key("port_glance");
    /** A port's details: "Import: 64 items/s, 1,000 mB/s". */
    public static final String PORT_DETAIL_KEY = key("port_detail");
    /** "Hub: 12 of 16 channels in use by the devices behind it" (M7N item 2), on a hub. */
    public static final String HUB_KEY = key("hub");
    public static final String ZONE_KEY = key("zone");
    public static final String ROOT_KEY = key("root");
    public static final String POWER_KEY = key("power");
    public static final String NO_POWER_KEY = key("no_power");
    public static final String NETWORK_KEY = key("network");
    public static final String AD_HOC_KEY = key("ad_hoc");
    public static final String CONTENTS_KEY = key("contents");
    public static final String OWNER_KEY = key("owner");
    public static final String NO_NETWORK_KEY = key("no_network");
    public static final String PART_KEY = key("part");
    public static final String NETWORK_FULL_KEY = key("network_full");
    public static final String CONTROLLER_KEY = key("controller");
    public static final String BOTTLENECK_KEY = key("bottleneck");
    /** A controller network's root links and batch size (no device limit since M7R). */
    public static final String ROOT_LINKS_KEY = key("root_links");
    public static final String OFFLINE_KEY = key("cells_offline");
    public static final String TEAM_FULL_KEY = key("team_full");
    public static final String BYTES_CAPPED_KEY = key("bytes_capped");
    public static final String TEAM_CAPS_KEY = key("team_caps");
    public static final String QUARANTINED_KEY = key("status.quarantined");
    public static final String LABEL_KEY = key("label");
    /** "Network name: North Base" (M7L2 row 13), for every device of a network with a controller. */
    public static final String NAME_KEY = key("network_name");
    /** "Turned off by logic": a device a logic graph switched off (M7L); also on device screens and item tooltips. */
    public static final String SWITCHED_OFF_KEY = key("switched_off");
    /** Which named graph turned a device off ({@link SwitchSource#describe}): name, then the controller's x y z. */
    public static final String SWITCHED_OFF_BY_KEY = key("switched_off_by");
    /** The same for an unnamed graph: its number, then the controller's x y z. */
    public static final String SWITCHED_OFF_BY_NUMBER_KEY = key("switched_off_by_number");
    /** "Running": the status tab of a device logic could turn off but has not. */
    public static final String RUNNING_KEY = key("running");
    /** A device whose faced block a land claim keeps it from using ({@link FacingAccess}); screens show it too. */
    public static final String CLAIM_BLOCKED_KEY = key("claim_blocked");
    /** "Low power, shed level %s: power priority %s to 5 paused" (M7L2); only while something is shed. */
    public static final String SHEDDING_KEY = key("shedding");
    /** Why a device is offline for channels (M7N): one key per {@link ChannelShortage.Kind}. */
    private static final String OFFLINE_PREFIX = key("channels_offline.");
    private static final String STATUS_PREFIX = key("status.");

    static {
        ModContent.CONTENT.translation(CABLE_KEY, "Storage cable, tier %s: %s channels");
        ModContent.CONTENT.translation(CABLE_GLANCE_KEY, "Tier %s: %s / %s channels");
        ModContent.CONTENT.translation(FILL_KEY, "%s%%");
        ModContent.CONTENT.translation(CHANNELS_KEY, "%s/%s");
        ModContent.CONTENT.translation(UNLIMITED_KEY, "∞");
        ModContent.CONTENT.translation(STACK_KEY, "%s x%s");
        ModContent.CONTENT.translation(BYTES_KEY, "Bytes: %s / %s in %s cells, %s full");
        ModContent.CONTENT.translation(PORT_GLANCE_KEY, "%s/s");
        ModContent.CONTENT.translation(PORT_DETAIL_KEY, "%s: %s items/s, %s mB/s");
        ModContent.CONTENT.translation(ZONE_KEY, "Zone %s, %s of %s channels in use towards the root");
        ModContent.CONTENT.translation(ROOT_KEY, "Zone %s, route root");
        ModContent.CONTENT.translation(POWER_KEY, "Network power: %s / %s MU");
        ModContent.CONTENT.translation(NO_POWER_KEY, "Not enough power for an operation");
        ModContent.CONTENT.translation(NETWORK_KEY, "Network: %s nodes, %s devices, %s zones");
        ModContent.CONTENT.translation(AD_HOC_KEY, "No controller: %s of %s channels in use");
        ModContent.CONTENT.translation(CONTENTS_KEY, "Stored: %s kinds, %s operations so far");
        ModContent.CONTENT.translation(OWNER_KEY, "Owner: %s");
        ModContent.CONTENT.translation(NO_NETWORK_KEY, "Not in a storage network");
        ModContent.CONTENT.translation(PART_KEY, "Part: %s");
        ModContent.CONTENT.translation(LABEL_KEY, "Label: %s");
        ModContent.CONTENT.translation(NAME_KEY, "Network name: %s");
        ModContent.CONTENT.translation(SWITCHED_OFF_KEY, "Turned off by logic");
        ModContent.CONTENT.translation(SWITCHED_OFF_BY_KEY, "Graph \"%s\" on the Logic Controller at %s %s %s");
        ModContent.CONTENT.translation(SWITCHED_OFF_BY_NUMBER_KEY, "Graph #%s on the Logic Controller at %s %s %s");
        ModContent.CONTENT.translation(RUNNING_KEY, "Running");
        ModContent.CONTENT.translation(CLAIM_BLOCKED_KEY, "Blocked by a land claim");
        ModContent.CONTENT.translation(SHEDDING_KEY,
                "Low power, shed level %s: devices of power priority %s to 5 paused");
        ModContent.CONTENT.translation(NETWORK_FULL_KEY,
                "Not joined: the network already has %s of its controller's %s devices");
        ModContent.CONTENT.translation(CONTROLLER_KEY,
                "Controller: %s of %s devices, %s of %s root links in use, batch size %s%%");
        ModContent.CONTENT.translation(BOTTLENECK_KEY, "Fullest root link: %s of %s channels in use");
        ModContent.CONTENT.translation(ROOT_LINKS_KEY, "Root links: %s of %s in use, batch size %s%%");
        ModContent.CONTENT.translation(offlineKey(ChannelShortage.Kind.CABLE),
                "Offline: the cable at %s %s %s is full, %s of %s channels in use");
        ModContent.CONTENT.translation(offlineKey(ChannelShortage.Kind.LINK),
                "Offline: the link at %s %s %s is full, %s of %s channels in use");
        ModContent.CONTENT.translation(offlineKey(ChannelShortage.Kind.ROOT_LINK),
                "Offline: the controller's root link at %s %s %s is full, %s of %s channels in use");
        ModContent.CONTENT.translation(offlineKey(ChannelShortage.Kind.AD_HOC),
                "Offline: a network without a controller has %s of %s channels in use");
        ModContent.CONTENT.translation(offlineKey(ChannelShortage.Kind.NO_ROUTE),
                "Offline: no route to the controller");
        ModContent.CONTENT.translation(offlineKey(ChannelShortage.Kind.HUB),
                "Offline: the hub at %s %s %s is full (%s/%s)");
        ModContent.CONTENT.translation(HUB_KEY, "Hub: %s of %s channels in use by the devices behind it");
        ModContent.CONTENT.translation(OFFLINE_KEY, "%s cells offline: their drives are in unloaded chunks");
        ModContent.CONTENT.translation(TEAM_FULL_KEY,
                "Not joined: the owner's team has %s of its %s devices in this dimension");
        ModContent.CONTENT.translation(BYTES_CAPPED_KEY,
                "Storing stopped: the owner's team uses all of its cell bytes in this dimension");
        ModContent.CONTENT.translation(TEAM_CAPS_KEY, "Team caps: %s of %s devices, %s of %s zones, %s of %s bytes");
        ModContent.CONTENT.translation(statusKey(StorageNetwork.Status.ONLINE), "Status: online");
        ModContent.CONTENT.translation(statusKey(StorageNetwork.Status.PAUSED),
                "Status: paused, part of the network is unloaded");
        ModContent.CONTENT.translation(QUARANTINED_KEY, "Status: quarantined, nothing moves until the tag is lifted");
    }

    private NetworkInfo() {
    }

    private static String key(String path) {
        return "info." + MaxtechStorage.MOD_ID + "." + path;
    }

    /** Lang key of a status line, e.g. {@code info.maxtech_storage.status.online}. */
    public static String statusKey(StorageNetwork.Status status) {
        return STATUS_PREFIX + status.name().toLowerCase(Locale.ROOT);
    }

    /** Lang key of a channel shortage line, e.g. {@code info.maxtech_storage.channels_offline.cable}. */
    public static String offlineKey(ChannelShortage.Kind kind) {
        return OFFLINE_PREFIX + kind.name().toLowerCase(Locale.ROOT);
    }

    /**
     * "Offline: the cable at 1 2 3 is full, 8 of 8 channels in use" for a device offline for channels (M7N): the full
     * cable, link or root link with its numbers, the ad-hoc allowance, or no route.
     */
    public static MutableComponent offlineLine(ChannelShortage shortage) {
        String key = offlineKey(shortage.kind());
        String used = InfoFormat.number(shortage.used());
        String capacity = InfoFormat.number(shortage.capacity());
        return switch (shortage.kind()) {
            case CABLE, LINK, ROOT_LINK, HUB -> {
                long pos = shortage.pos();
                yield Component.translatable(key, BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos), used,
                        capacity);
            }
            case AD_HOC -> Component.translatable(key, used, capacity);
            case NO_ROUTE -> Component.translatable(key);
        };
    }

    /**
     * A storage cable: at a glance its network's status and the channels in use on its segment towards the root as a
     * bar; with details the same as pips, its tier and its network.
     */
    public static void cable(StorageCableConduitType cable, BlockInfoRequest request, BlockInfoSink sink) {
        NetworkState network = StorageNetworks.network(request.level(), cable.networkType(), request.pos());
        Glance.status(network == null ? Glance.OFFLINE : Glance.of(network), sink);
        long pos = request.pos().asLong();
        int capacity = Channels.cable(cable.level());
        int used = 0;
        boolean infinite = network != null ? network.channelsInfinite() : Channels.infinite();
        if (network != null && network.segmentCapacity(pos) > 0) {
            capacity = network.segmentCapacity(pos);
            used = network.segmentChannels(pos);
        }
        channels(used, capacity, infinite, sink);
        if (request.detail() == BlockInfoRequest.Detail.FULL) {
            channelPips(Component.translatable(CABLE_GLANCE_KEY, InfoFormat.number(cable.level()),
                    InfoFormat.number(used), capacityText(capacity, infinite)), used, capacity, infinite, sink);
            sink.line(Component.translatable(CABLE_KEY, InfoFormat.number(cable.level()),
                    capacityText(Channels.cable(cable.level()), infinite)));
            network(network, request, sink);
        }
    }

    /**
     * A channel bar behind the channel icon, labelled "21/32" and coloured in the fill bands. Without channel limits
     * ({@code infinite}) it reads "21/∞" and fills by {@link Channels#loadPercent(int, int, boolean)}, never full.
     */
    public static void channels(int used, int capacity, boolean infinite, BlockInfoSink sink) {
        Component label = Component.translatable(CHANNELS_KEY, InfoFormat.number(used),
                capacityText(capacity, infinite));
        if (infinite) {
            int load = Channels.loadPercent(used, capacity, true);
            sink.progress(Glance.CHANNELS, label, load, 100, InfoBars.fill(load, 100));
            return;
        }
        sink.progress(Glance.CHANNELS, label, used, capacity, InfoBars.fill(used, capacity));
    }

    /** {@code text} with channel pips, or as a plain line without channel limits, where pips would read as full. */
    public static void channelPips(Component text, int used, int capacity, boolean infinite, BlockInfoSink sink) {
        if (infinite) {
            sink.line(text);
        } else {
            sink.pips(text, used, capacity);
        }
    }

    /** {@code capacity} as a number, or "∞" without channel limits ({@code infinite}); a lang argument. */
    public static Object capacityText(int capacity, boolean infinite) {
        return infinite ? Component.translatable(UNLIMITED_KEY) : InfoFormat.number(capacity);
    }

    /**
     * A storage device: at a glance its status ({@link Glance}) and what the device itself shows
     * ({@link NetworkDevice#addGlance}); with details its label, why it is off, its own details
     * ({@link NetworkDevice#addDetails}) and its network.
     */
    public static void device(NetworkDevice device, BlockInfoRequest request, BlockInfoSink sink) {
        Glance.status(Glance.of(device), sink);
        device.addGlance(request, sink);
        details(device, request, sink);
    }

    /**
     * A part on a cable: at a glance its status and name, then what the part itself shows; with details as
     * {@link #device}.
     */
    public static void part(NetworkDevice part, Component name, BlockInfoRequest request, BlockInfoSink sink) {
        Glance.status(Glance.of(part), sink);
        sink.line(name);
        part.addGlance(request, sink);
        details(part, request, sink);
    }

    /**
     * A bar of how full something is, labelled "64%" and coloured in the drive cells' bands (accent, amber from 75 %,
     * red when full).
     */
    public static void fill(long used, long max, BlockInfoSink sink) {
        sink.progress(Component.translatable(FILL_KEY, InfoFormat.percent(used, max)), used, max,
                InfoBars.fill(used, max));
    }

    /** Item and fluid icons, with "Cobblestone x1.2k, Water x4k" as their words; nothing for an empty list. */
    public static void stacks(List<InfoStack> stacks, BlockInfoSink sink) {
        if (stacks.isEmpty()) {
            return;
        }
        MutableComponent text = Component.empty();
        for (int i = 0; i < stacks.size(); i++) {
            InfoStack stack = stacks.get(i);
            if (i > 0) {
                text.append(", ");
            }
            text.append(Component.translatable(STACK_KEY, JobToastPayload.name(stack.key()),
                    InfoFormat.compact(stack.amount())));
        }
        sink.stacks(text, stacks);
    }

    private static void details(NetworkDevice device, BlockInfoRequest request, BlockInfoSink sink) {
        if (request.detail() != BlockInfoRequest.Detail.FULL) {
            return;
        }
        label(device, sink);
        claim(device, sink);
        refused(device.deviceLink(), sink);
        device.addDetails(request, sink);
        network(device.deviceLink().network(), request, sink);
    }

    /**
     * "Label: smelters", in the label's colour, and "Turned off by logic" with the graph that did it for a device a
     * graph switched off; nothing for a device without either.
     */
    public static void label(NetworkDevice device, BlockInfoSink sink) {
        DeviceLabel label = device.label();
        if (!label.isEmpty()) {
            sink.line(Component.translatable(LABEL_KEY, label.component()));
        }
        if (device.switchedOff()) {
            sink.line(Component.translatable(SWITCHED_OFF_KEY).withStyle(ChatFormatting.RED));
            device.switchedOffBy().ifPresent(by -> sink.line(by.describe().withStyle(ChatFormatting.GRAY)));
        }
        if (device.powerPaused()) {
            sink.line(Component.translatable(PowerPriority.PAUSED_KEY, device.powerPriority())
                    .withStyle(ChatFormatting.GOLD));
        }
        if (device instanceof RedstoneControlled controlled) {
            Component reason = RedstoneTexts.inactiveReason(controlled.redstone());
            if (reason != null) {
                sink.line(reason.copy().withStyle(ChatFormatting.GRAY));
            }
        }
    }

    /** "Blocked by a land claim" for a device a claim keeps from the block it faces. */
    private static void claim(NetworkDevice device, BlockInfoSink sink) {
        if (device.claimBlocked()) {
            sink.line(Component.translatable(CLAIM_BLOCKED_KEY));
        }
    }

    /** A device the network turned away for its device limit or for channels says so. */
    private static void refused(DeviceLink link, BlockInfoSink sink) {
        if (link.network() == null) {
            link.channelShortage()
                    .ifPresent(shortage -> sink.line(offlineLine(shortage).withStyle(ChatFormatting.RED)));
        }
        NetworkState full = link.refusedBy();
        if (link.network() == null && full != null) {
            sink.line(full.teamCapped()
                    ? Component.translatable(TEAM_FULL_KEY, InfoFormat.number(full.teamDevicesUsed()),
                            InfoFormat.number(StorageServerConfig.CAP_DEVICES_PER_TEAM.getAsInt()))
                    : Component.translatable(NETWORK_FULL_KEY, InfoFormat.number(full.limitedDevices()),
                            InfoFormat.number(full.deviceLimit())));
        }
    }

    /** "Network name: North Base" (when named) and "Network power: x / y MU", from cached values. */
    public static void namePower(NetworkState network, BlockInfoSink sink) {
        String name = network.name();
        if (!name.isEmpty()) {
            sink.line(Component.translatable(NAME_KEY, name));
        }
        sink.line(Component.translatable(POWER_KEY, InfoFormat.number(network.storedEnergy()),
                InfoFormat.number(network.energyCapacity())));
    }

    private static void network(@Nullable NetworkState network, BlockInfoRequest request, BlockInfoSink sink) {
        if (network == null) {
            sink.line(Component.translatable(NO_NETWORK_KEY));
            return;
        }
        long pos = request.pos().asLong();
        int zone = network.zone(pos);
        if (zone >= 0) {
            sink.line(network.isRouteRoot(pos)
                    ? Component.translatable(ROOT_KEY, InfoFormat.number(zone))
                    : Component.translatable(ZONE_KEY, InfoFormat.number(zone),
                            InfoFormat.number(network.segmentChannels(pos)),
                            capacityText(network.segmentCapacity(pos), network.channelsInfinite())));
        }
        String name = network.name();
        if (!name.isEmpty()) {
            sink.line(Component.translatable(NAME_KEY, name));
        }
        // The status entry already says online, paused, quarantined or no power; these say why and how much.
        if (network.quarantined()) {
            sink.line(Component.translatable(QUARANTINED_KEY));
        } else if (network.status() != StorageNetwork.Status.ONLINE) {
            sink.line(Component.translatable(statusKey(network.status())));
        }
        sink.line(Component.translatable(POWER_KEY, InfoFormat.number(network.storedEnergy()),
                InfoFormat.number(network.energyCapacity())));
        int level = network.shedding().level();
        if (level > 0) {
            sink.line(Component.translatable(SHEDDING_KEY, InfoFormat.number(level),
                    InfoFormat.number(network.shedding().pausedFrom())));
        }
        int offline = network.offlineCells();
        if (offline > 0) {
            sink.line(Component.translatable(OFFLINE_KEY, InfoFormat.number(offline)));
        }
        if (network.bytesCapped()) {
            sink.line(Component.translatable(BYTES_CAPPED_KEY));
        }
        sink.line(Component.translatable(NETWORK_KEY, InfoFormat.number(network.nodeCount()),
                InfoFormat.number(network.deviceCount()), InfoFormat.number(network.zoneCount())));
        if (network.isAdHoc()) {
            sink.line(Component.translatable(AD_HOC_KEY, InfoFormat.number(network.channelsHeld()),
                    capacityText(Channels.adHoc(), network.channelsInfinite())));
        } else {
            if (network.deviceLimit() != Integer.MAX_VALUE) {
                sink.line(Component.translatable(CONTROLLER_KEY, InfoFormat.number(network.limitedDevices()),
                        InfoFormat.number(network.deviceLimit()), InfoFormat.number(network.rootLinksUsed()),
                        InfoFormat.number(network.rootLinks()), InfoFormat.number(network.batchPercent())));
            } else {
                // A storage controller has no device limit (M7R): its root links and channels limit the network.
                sink.line(Component.translatable(ROOT_LINKS_KEY, InfoFormat.number(network.rootLinksUsed()),
                        InfoFormat.number(network.rootLinks()), InfoFormat.number(network.batchPercent())));
            }
            long busiest = network.busiestRootLink();
            if (busiest != Long.MIN_VALUE) {
                sink.line(Component.translatable(BOTTLENECK_KEY, InfoFormat.number(network.segmentChannels(busiest)),
                        capacityText(network.segmentCapacity(busiest), network.channelsInfinite())));
            }
        }
        if (network.capTeam() != null && TeamCaps.anyOn()) {
            sink.line(Component.translatable(TEAM_CAPS_KEY, InfoFormat.number(network.teamDevicesUsed()),
                    capText(StorageServerConfig.CAP_DEVICES_PER_TEAM.getAsInt()),
                    InfoFormat.number(network.teamZonesUsed()),
                    capText(StorageServerConfig.CAP_ZONES_PER_TEAM.getAsInt()),
                    InfoFormat.number(network.teamBytesUsed()),
                    capText(StorageServerConfig.CAP_BYTES_PER_TEAM.getAsLong())));
        }
        sink.line(Component.translatable(CONTENTS_KEY, InfoFormat.number(network.contents().size()),
                InfoFormat.number(network.operations())));
        if (network.owner().isOwned()) {
            sink.line(Component.translatable(OWNER_KEY, network.owner().ownerName()));
        }
    }

    /** A cap for an info line: the number, or "-" when that cap is off. */
    private static String capText(long cap) {
        return cap > 0 ? InfoFormat.number(cap) : "-";
    }

    /** Loads the class during mod construction so its lang lines are declared. */
    public static void init() {
    }
}
