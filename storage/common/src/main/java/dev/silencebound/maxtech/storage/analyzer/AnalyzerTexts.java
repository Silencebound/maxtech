package dev.silencebound.maxtech.storage.analyzer;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.SmartLoad;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.Locale;

/** Lang keys of the full analyzer's report and the controller's Network tab, with their English lines. */
public final class AnalyzerTexts {
    public static final String NETWORK_ID = info("network_id");
    public static final String ROUTE = info("route");
    public static final String BUSIEST = info("route_busiest");
    public static final String ROUTE_ROOT = info("route_root");
    public static final String NO_ROUTE = info("no_route");
    public static final String ANTENNA_HOPS = info("antenna_hops");
    public static final String ROOT_LINK = info("root_link");
    public static final String SEGMENT_LOAD = info("segment_load");
    public static final String ZONE_SIZE = info("zone_size");
    public static final String SUBNET_OF = info("subnet_of");
    public static final String SUBNET_DETACHED = info("subnet_detached");
    public static final String PARENT_POWER = info("parent_power");
    public static final String SUBNETS_BELOW = info("subnets_below");
    /** "Redstone channels: %s", the channels above 0 as coloured squares with their values (M7L2). */
    public static final String CHANNELS = info("channels");
    public static final String OVERLAY_SHOWN = info("overlay_shown");
    public static final String DENIED = gui("denied");
    private static final String BUCKET_PREFIX = info("load.");

    public static final String TAB_NETWORK = gui("tab.network");
    public static final String TAB_HISTORY = gui("tab.history");
    /** The controller's Crafting tab: limits for automated crafting jobs (M7L2 chunk 20). */
    public static final String TAB_CRAFTING = gui("tab.crafting");
    public static final String HEADER_AUTOMATION = gui("header.automation");
    public static final String LIMIT_MAX_PER_JOB = gui("limit.max_per_job");
    public static final String LIMIT_KEPT_FREE = gui("limit.kept_free");
    public static final String NO_LIMIT = gui("limit.none");
    public static final String FROM_CONFIG = gui("limit.from_config");
    public static final String LIMITS_DEFAULT = gui("limit.reset");
    public static final String CPUS_FREE = gui("limit.cpus_free");
    public static final String LIMITS_NOTE = gui("limit.note");
    public static final String LIMITS_PLAYERS = gui("limit.players");
    public static final String LIMITS_LOCKED = gui("limit.locked");
    public static final String LIMITS_NO_CPU = gui("limit.no_cpu");
    public static final String WAITING = gui("waiting");
    public static final String HEADER_NETWORK = gui("header.network");
    public static final String HEADER_POWER = gui("header.power");
    public static final String HEADER_ZONES = gui("header.zones");
    public static final String HEADER_HOTSPOTS = gui("header.hotspots");
    public static final String HEADER_SUBNETS = gui("header.subnets");
    public static final String HEADER_LABELS = gui("header.labels");
    public static final String LABEL_ROW = gui("label_row");
    /** The network's logic log (M7L): its newest lines. */
    public static final String HEADER_LOG = gui("header.log");
    public static final String HEADER_ANTENNAS = gui("header.antennas");
    public static final String HEADER_WARNINGS = gui("header.warnings");
    public static final String SUMMARY = gui("summary");
    public static final String ROLES = gui("roles");
    public static final String CLUSTER = gui("cluster");
    public static final String LIMITS = gui("limits");
    public static final String AD_HOC = gui("ad_hoc");
    public static final String ACTIVITY = gui("activity");
    public static final String POWER = gui("power");
    public static final String ENERGY_FLOW = gui("energy_flow");
    public static final String PARENT = gui("parent");
    public static final String CAPS = gui("caps");
    public static final String ZONE = gui("zone");
    public static final String HOTSPOT = gui("hotspot");
    public static final String SUBNET = gui("subnet");
    public static final String SUBNET_UNLINKED = gui("subnet_unlinked");
    public static final String ANTENNA = gui("antenna");
    public static final String ANTENNA_SPARE = gui("antenna_spare");
    /** The Network tab's hub list (M7N item 2). */
    public static final String HEADER_HUBS = gui("header.hubs");
    /** "Hub at 1, 2, 3: 12 of 32 channels". */
    public static final String HUB = gui("hub");
    /** The analyzer's line for a node behind a hub: the hub it counts against and its use. */
    public static final String THROUGH_HUB = info("through_hub");
    public static final String NONE = gui("none");
    public static final String NO_WARNINGS = gui("no_warnings");
    public static final String LOCATE = gui("locate");
    public static final String NO_RECORDER = gui("no_recorder");
    public static final String HISTORY_OPERATIONS = gui("history.operations");
    public static final String HISTORY_ENERGY = gui("history.energy");
    public static final String HISTORY_SPAN = gui("history.span");
    private static final String WARNING_PREFIX = gui("warning.");
    private static final String KIND_PREFIX = gui("kind.");

    static {
        ModContent.CONTENT.translation(NETWORK_ID, "Network #%s");
        ModContent.CONTENT.translation(ROUTE, "Route to the root: %s segments, fewest %s channels at %s");
        ModContent.CONTENT.translation(BUSIEST, "Fullest segment on the route: %s%% of %s channels at %s");
        ModContent.CONTENT.translation(ROUTE_ROOT, "This is the route root");
        ModContent.CONTENT.translation(NO_ROUTE, "No route to the root yet");
        ModContent.CONTENT.translation(ANTENNA_HOPS, "The route crosses %s antenna links");
        ModContent.CONTENT.translation(ROOT_LINK, "Enters the controller through the root link at %s, %s%% busy");
        ModContent.CONTENT.translation(SEGMENT_LOAD, "This segment: %s, %s%%");
        ModContent.CONTENT.translation(ZONE_SIZE, "Zone %s: %s nodes, %s zones in the network");
        ModContent.CONTENT.translation(SUBNET_OF, "Subnet (%s) of network #%s");
        ModContent.CONTENT.translation(SUBNET_DETACHED, "Subnet (%s) without a parent network");
        ModContent.CONTENT.translation(PARENT_POWER, "Parent network power, drawn when this one runs out: %s / %s MU");
        ModContent.CONTENT.translation(SUBNETS_BELOW, "%s subnets hang off this network");
        ModContent.CONTENT.translation(CHANNELS, "Redstone channels: %s");
        ModContent.CONTENT.translation(OVERLAY_SHOWN, "Showing the network within %s blocks for 15 seconds");
        ModContent.CONTENT.translation(bucket(SmartLoad.IDLE), "idle");
        ModContent.CONTENT.translation(bucket(SmartLoad.LOW), "low");
        ModContent.CONTENT.translation(bucket(SmartLoad.BUSY), "busy");
        ModContent.CONTENT.translation(bucket(SmartLoad.SATURATED), "full");

        ModContent.CONTENT.translation(DENIED, "You have no access to this network");
        ModContent.CONTENT.translation(TAB_NETWORK, "Network");
        ModContent.CONTENT.translation(TAB_HISTORY, "History");
        ModContent.CONTENT.translation(TAB_CRAFTING, "Crafting");
        ModContent.CONTENT.translation(HEADER_AUTOMATION, "Limits for automated crafting");
        ModContent.CONTENT.translation(LIMIT_MAX_PER_JOB, "Most items per job: %s");
        ModContent.CONTENT.translation(LIMIT_KEPT_FREE, "CPUs kept free for players: %s");
        ModContent.CONTENT.translation(NO_LIMIT, "no limit");
        ModContent.CONTENT.translation(FROM_CONFIG, "%s (server default)");
        ModContent.CONTENT.translation(LIMITS_DEFAULT, "Use defaults");
        ModContent.CONTENT.translation(CPUS_FREE, "Free CPUs now: %s, of them for automation: %s");
        ModContent.CONTENT.translation(LIMITS_NOTE, "Stock keepers, logic rules and crafting cards follow these.");
        ModContent.CONTENT.translation(LIMITS_PLAYERS, "Player requests ignore them and always go first.");
        ModContent.CONTENT.translation(LIMITS_LOCKED, "Changing them needs full access to the network");
        ModContent.CONTENT.translation(LIMITS_NO_CPU, "Automation can't use any CPU: all %s are kept free");
        ModContent.CONTENT.translation(WAITING, "Reading the network...");
        ModContent.CONTENT.translation(HEADER_NETWORK, "Network");
        ModContent.CONTENT.translation(HEADER_POWER, "Power");
        ModContent.CONTENT.translation(HEADER_ZONES, "Zones (nodes, busiest segment)");
        ModContent.CONTENT.translation(HEADER_HOTSPOTS, "Busiest segments");
        ModContent.CONTENT.translation(HEADER_SUBNETS, "Subnets");
        ModContent.CONTENT.translation(HEADER_LABELS, "Labels (devices)");
        ModContent.CONTENT.translation(LABEL_ROW, "%s: %s");
        ModContent.CONTENT.translation(HEADER_LOG, "Logic log (newest last)");
        ModContent.CONTENT.translation(HEADER_ANTENNAS, "Antenna links");
        ModContent.CONTENT.translation(HEADER_WARNINGS, "Warnings");
        ModContent.CONTENT.translation(SUMMARY, "#%s: %s nodes, %s devices, %s zones");
        ModContent.CONTENT.translation(ROLES, "%s storage, %s access, %s transfer, %s other");
        ModContent.CONTENT.translation(CLUSTER, "Controller: %s of %s blocks, batch %s%%");
        ModContent.CONTENT.translation(LIMITS, "%s of %s devices, %s of %s root links in use");
        ModContent.CONTENT.translation(AD_HOC, "No controller: ad-hoc network");
        ModContent.CONTENT.translation(ACTIVITY, "%s operations, %s rebuilds, %s patches");
        ModContent.CONTENT.translation(POWER, "%s / %s MU");
        ModContent.CONTENT.translation(ENERGY_FLOW, "%s MU in, %s MU spent since formed");
        ModContent.CONTENT.translation(PARENT, "Subnet (%s) of #%s: %s / %s MU behind it");
        ModContent.CONTENT.translation(CAPS, "Team: %s of %s devices, %s of %s zones, %s of %s bytes");
        ModContent.CONTENT.translation(ZONE, "Zone %s: %s nodes, %s%%");
        ModContent.CONTENT.translation(HOTSPOT, "%s%% of %s channels, %s at %s");
        ModContent.CONTENT.translation(SUBNET, "%s at %s: #%s, %s devices");
        ModContent.CONTENT.translation(SUBNET_UNLINKED, "%s at %s: no subnet behind it");
        ModContent.CONTENT.translation(ANTENNA, "%s to %s: %s%% of %s channels");
        ModContent.CONTENT.translation(ANTENNA_SPARE, "%s to %s: spare, %s channels");
        ModContent.CONTENT.translation(HEADER_HUBS, "Hubs");
        ModContent.CONTENT.translation(HUB, "Hub at %s: %s of %s channels");
        ModContent.CONTENT.translation(THROUGH_HUB, "Counts against the hub at %s: %s of %s channels in use");
        ModContent.CONTENT.translation(NONE, "None");
        ModContent.CONTENT.translation(NO_WARNINGS, "Nothing to report");
        ModContent.CONTENT.translation(LOCATE, "Locate");
        ModContent.CONTENT.translation(NO_RECORDER, "Telemetry is turned off in this server's config");
        ModContent.CONTENT.translation(HISTORY_OPERATIONS, "Operations per sample, peak %s");
        ModContent.CONTENT.translation(HISTORY_ENERGY, "MU in per sample, peak %s");
        ModContent.CONTENT.translation(HISTORY_SPAN, "Last %s samples, one every %s seconds");
        ModContent.CONTENT.translation(warning(NetworkOverview.WarningKind.CONTROLLER_STANDBY),
                "%s controllers on standby: this network already has a controller."
                        + " Use a subnet controller to join two networks.");
        ModContent.CONTENT.translation(warning(NetworkOverview.WarningKind.PAUSED),
                "Paused: part of the network is unloaded");
        ModContent.CONTENT.translation(warning(NetworkOverview.WarningKind.QUARANTINED),
                "Quarantined: nothing moves until the owner or an operator lifts the tag");
        ModContent.CONTENT.translation(warning(NetworkOverview.WarningKind.NO_POWER),
                "Not enough power for an operation");
        ModContent.CONTENT.translation(warning(NetworkOverview.WarningKind.DEVICES_REFUSED),
                "%s devices turned away at the device limit");
        ModContent.CONTENT.translation(warning(NetworkOverview.WarningKind.OFFLINE_CELLS),
                "%s cells offline in unloaded chunks");
        ModContent.CONTENT.translation(warning(NetworkOverview.WarningKind.BYTES_CAPPED),
                "Storing stopped: the team uses all of its cell bytes");
        ModContent.CONTENT.translation(warning(NetworkOverview.WarningKind.SATURATED_SEGMENT),
                "A segment uses %s%% of its channels");
        ModContent.CONTENT.translation(warning(NetworkOverview.WarningKind.OVER_CAPACITY),
                "More controller blocks than the size limit allows");
        ModContent.CONTENT.translation(warning(NetworkOverview.WarningKind.CHANNELS_FULL),
                "%s devices offline: a link on their route has no channel left");
        ModContent.CONTENT.translation(warning(NetworkOverview.WarningKind.LOAD_SHEDDING),
                "Low power: devices of power priority %s to 5 are paused");
        ModContent.CONTENT.translation(kind(RouteTrace.Kind.CABLE), "cable");
        ModContent.CONTENT.translation(kind(RouteTrace.Kind.DEVICE), "device");
        ModContent.CONTENT.translation(kind(RouteTrace.Kind.ANTENNA), "antenna link");
        ModContent.CONTENT.translation(kind(RouteTrace.Kind.ROOT_LINK), "root link");
    }

    private AnalyzerTexts() {
    }

    private static String info(String path) {
        return "info." + MaxtechStorage.MOD_ID + ".analyzer." + path;
    }

    private static String gui(String path) {
        return "gui." + MaxtechStorage.MOD_ID + ".network_tab." + path;
    }

    /** The name of a smart-cable load bucket. */
    public static String bucket(int bucket) {
        return BUCKET_PREFIX + bucket;
    }

    /** The line of a warning kind; its one argument is the warning's count. */
    public static String warning(NetworkOverview.WarningKind kind) {
        return WARNING_PREFIX + kind.name().toLowerCase(Locale.ROOT);
    }

    /** The name of a segment kind. */
    public static String kind(RouteTrace.Kind kind) {
        return KIND_PREFIX + kind.name().toLowerCase(Locale.ROOT);
    }

    /** Loads the class during mod construction so its lang lines are declared. */
    public static void init() {
    }
}
