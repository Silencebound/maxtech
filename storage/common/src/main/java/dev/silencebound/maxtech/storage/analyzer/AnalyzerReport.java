package dev.silencebound.maxtech.storage.analyzer;

import dev.silencebound.maxtech.lib.highlight.HighlightRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.info.EnergyInfo;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.SmartLoad;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.RedstoneChannels;
import dev.silencebound.maxtech.storage.subnet.SubnetBlockEntity;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.phys.Vec3;

/**
 * What the full analyzer adds to a block's report (M6d, PRD 7.2 Diagnostics): the network's id, the route to the root
 * with its slowest and busiest segments, antenna hops and root link, the zone, and subnet membership with the parent's
 * power; plus the highlights it sends: the clicked node's route, and with sneak the whole network near the player
 * coloured by load, with zone boundaries and spare links. Built on a player's use, never per tick. Server thread.
 */
public final class AnalyzerReport {
    /** The route highlight's channel; a new route replaces the last. */
    public static final Identifier ROUTE_CHANNEL = MaxtechStorage.id("analyzer_route");
    /** The network overlay's channel. */
    public static final Identifier OVERLAY_CHANNEL = MaxtechStorage.id("analyzer_overlay");
    /** Ticks a highlight stays: 15 seconds. */
    public static final int HIGHLIGHT_TICKS = 300;
    /** How far from the player the overlay draws nodes, per axis. */
    public static final int OVERLAY_RADIUS = 32;
    /** Idle segments: the Accent Frame cyan, dimmed. */
    static final int IDLE_COLOUR = 0x2A8794;
    static final int ZONE_EDGE_COLOUR = 0xFFFFFFFF;
    static final int SPARE_COLOUR = 0xFF6E7880;
    private static final int OPAQUE = 0xFF000000;

    private AnalyzerReport() {
    }

    /** The analyzer's extra lines for the node at {@code pos} in {@code network}. */
    public static List<Component> lines(NetworkState network, long pos) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(AnalyzerTexts.NETWORK_ID, InfoFormat.number(network.id())));
        RouteTrace trace = RouteTrace.of(network, pos);
        if (trace.segments().isEmpty()) {
            lines.add(Component.translatable(network.isRouteRoot(pos) || network.routeRoot() == pos
                    ? AnalyzerTexts.ROUTE_ROOT
                    : AnalyzerTexts.NO_ROUTE));
        } else {
            RouteTrace.Segment narrowest = trace.narrowest();
            RouteTrace.Segment busiest = trace.busiest();
            if (narrowest != null && busiest != null) {
                lines.add(Component.translatable(AnalyzerTexts.ROUTE,
                        InfoFormat.number(trace.segments().size()) + (trace.truncated() ? "+" : ""),
                        InfoFormat.number(narrowest.rate()), position(narrowest.pos())));
                lines.add(Component.translatable(AnalyzerTexts.BUSIEST, InfoFormat.number(busiest.load()),
                        InfoFormat.number(busiest.rate()), position(busiest.pos())));
            }
            int hops = trace.antennaHops();
            if (hops > 0) {
                lines.add(Component.translatable(AnalyzerTexts.ANTENNA_HOPS, InfoFormat.number(hops)));
            }
            RouteTrace.Segment last = trace.segments().get(trace.segments().size() - 1);
            if (last.kind() == RouteTrace.Kind.ROOT_LINK) {
                lines.add(Component.translatable(AnalyzerTexts.ROOT_LINK, position(last.pos()),
                        InfoFormat.number(last.load())));
            }
            long hub = network.routeHub(pos);
            if (hub != Long.MIN_VALUE && hub != pos) {
                lines.add(Component.translatable(AnalyzerTexts.THROUGH_HUB, position(hub),
                        InfoFormat.number(network.hubChannels(hub)),
                        NetworkInfo.capacityText(network.hubCapacity(hub), network.channelsInfinite())));
            }
            RouteTrace.Segment first = trace.segments().get(0);
            lines.add(Component.translatable(AnalyzerTexts.SEGMENT_LOAD,
                    Component.translatable(AnalyzerTexts.bucket(SmartLoad.bucket(first.load()))),
                    InfoFormat.number(first.load())));
        }
        int zone = network.zone(pos);
        if (zone >= 0) {
            lines.add(Component.translatable(AnalyzerTexts.ZONE_SIZE, InfoFormat.number(zone),
                    InfoFormat.number(network.zoneSize(zone)), InfoFormat.number(network.zoneCount())));
        }
        if (network.controllerDevice() instanceof SubnetBlockEntity self) {
            NetworkState parent = self.parent();
            Component mode = Component.translatable(self.mode().translationKey());
            if (parent != null && parent != network) {
                lines.add(Component.translatable(AnalyzerTexts.SUBNET_OF, mode, InfoFormat.number(parent.id())));
                lines.add(Component.translatable(AnalyzerTexts.PARENT_POWER, InfoFormat.number(parent.storedEnergy()),
                        InfoFormat.number(parent.energyCapacity())));
            } else {
                lines.add(Component.translatable(AnalyzerTexts.SUBNET_DETACHED, mode));
            }
        }
        int subnets = 0;
        for (NetworkDevice device : network.devices()) {
            if (device instanceof SubnetBlockEntity.Uplink) {
                subnets++;
            }
        }
        if (subnets > 0) {
            lines.add(Component.translatable(AnalyzerTexts.SUBNETS_BELOW, InfoFormat.number(subnets)));
        }
        if (network.channels().any()) {
            lines.add(Component.translatable(AnalyzerTexts.CHANNELS, channels(network.channels())));
        }
        return lines;
    }

    /**
     * The readout lines of the MaxTech energy network {@code network} hosts (MLE), in full; empty while it hosts none,
     * as while Storage runs on FE. Reads the network's last pass only, never waking it.
     */
    public static List<Component> energyLines(NetworkState network) {
        List<Component> lines = new ArrayList<>();
        network.energyNetwork()
                .ifPresent(energy -> EnergyInfo.network(energy.readout(), BlockInfoRequest.Detail.FULL, lines::add));
        return lines;
    }

    /** The redstone channels above 0 as one line: a square in the channel's dye colour, then its value. */
    static Component channels(RedstoneChannels channels) {
        MutableComponent out = Component.empty();
        boolean first = true;
        for (int channel = 0; channel < RedstoneChannels.COUNT; channel++) {
            int value = channels.value(channel);
            if (value <= 0) {
                continue;
            }
            if (!first) {
                out.append(" ");
            }
            first = false;
            out.append(Component.literal("■").withColor(DyeColor.byId(channel).getTextColor()));
            out.append(Integer.toString(value));
        }
        return out;
    }

    /** "x, y, z" of a packed position. */
    static String position(long pos) {
        return BlockPos.getX(pos) + ", " + BlockPos.getY(pos) + ", " + BlockPos.getZ(pos);
    }

    /** The colour (ARGB) a segment with {@code load} is drawn in: the smart cables' green, amber and red. */
    public static int loadColour(int load) {
        return OPAQUE | SmartLoad.colour(SmartLoad.bucket(load), IDLE_COLOUR);
    }

    /** The route of {@code trace} as lines coloured by load, with a box on the start and on the root end. */
    public static HighlightRequest route(RouteTrace trace, long start) {
        HighlightRequest.Builder builder = HighlightRequest.builder(ROUTE_CHANNEL).duration(HIGHLIGHT_TICKS)
                .throughWalls(true);
        builder.block(BlockPos.of(start), loadColour(0));
        for (RouteTrace.Segment segment : trace.segments()) {
            if (builder.size() >= HighlightRequest.MAX_SHAPES - 1) {
                break;
            }
            builder.line(centre(segment.pos()), centre(segment.parent()), loadColour(segment.load()));
        }
        if (!trace.segments().isEmpty()) {
            builder.block(BlockPos.of(trace.segments().get(trace.segments().size() - 1).parent()), ZONE_EDGE_COLOUR);
        }
        return builder.build();
    }

    /**
     * The network around {@code centre}: every node within {@link #OVERLAY_RADIUS} gets a line to its route parent
     * coloured by load, a white box where its zone differs from its parent's (a zone boundary), and grey lines for
     * graph links no route uses (spare links). Stops at Lib's shape limit.
     */
    public static HighlightRequest overlay(NetworkState network, BlockPos centre) {
        HighlightRequest.Builder builder = HighlightRequest.builder(OVERLAY_CHANNEL).duration(HIGHLIGHT_TICKS)
                .throughWalls(true);
        LongArrayList links = new LongArrayList();
        LongIterator it = network.nodes().iterator();
        while (it.hasNext() && builder.size() < HighlightRequest.MAX_SHAPES - 8) {
            long node = it.nextLong();
            if (!near(node, centre)) {
                continue;
            }
            long parent = network.routeParent(node);
            if (parent != Long.MIN_VALUE) {
                builder.line(centre(node), centre(parent), loadColour(network.segmentLoad(node)));
                if (network.zone(node) != network.zone(parent)) {
                    builder.block(BlockPos.of(node), ZONE_EDGE_COLOUR);
                }
            }
            links.clear();
            network.links(node, links);
            for (int i = 0; i < links.size(); i++) {
                long other = links.getLong(i);
                // Each pair once, from its lower end.
                if (other > node && parent != other && network.routeParent(other) != node
                        && network.nodes().contains(other)) {
                    builder.line(centre(node), centre(other), SPARE_COLOUR);
                }
            }
        }
        return builder.build();
    }

    private static boolean near(long pos, BlockPos centre) {
        return Math.abs(BlockPos.getX(pos) - centre.getX()) <= OVERLAY_RADIUS
                && Math.abs(BlockPos.getY(pos) - centre.getY()) <= OVERLAY_RADIUS
                && Math.abs(BlockPos.getZ(pos) - centre.getZ()) <= OVERLAY_RADIUS;
    }

    private static Vec3 centre(long pos) {
        return new Vec3(BlockPos.getX(pos) + 0.5, BlockPos.getY(pos) + 0.5, BlockPos.getZ(pos) + 0.5);
    }
}
