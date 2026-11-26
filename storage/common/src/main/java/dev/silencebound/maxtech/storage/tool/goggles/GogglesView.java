package dev.silencebound.maxtech.storage.tool.goggles;

import dev.silencebound.maxtech.lib.highlight.HighlightRequest;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.analyzer.AnalyzerReport;
import dev.silencebound.maxtech.storage.analyzer.RouteTrace;
import dev.silencebound.maxtech.storage.network.NetworkState;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * What the network goggles show (M6e, PRD 7.2 Diagnostics): an action bar line for the node the wearer looks at (its
 * zone, segment load and channels, route length) and a through-wall overlay of the network around the wearer: route
 * segments coloured by load as the analyzer draws them, a white box where a zone starts and an amber box on each
 * painted zone border. Built on the goggles' cadence from cached network state. Server thread.
 */
public final class GogglesView {
    /** The overlay's channel; each refresh replaces the last. */
    public static final Identifier CHANNEL = MaxtechStorage.id("goggles");
    /** How far from the wearer the overlay draws nodes, per axis; smaller than the analyzer's, as it is resent. */
    public static final int RADIUS = 24;
    /** Most shapes one overlay sends, a quarter of Lib's limit, since the goggles resend it. */
    public static final int MAX_SHAPES = HighlightRequest.MAX_SHAPES / 4;
    static final int ZONE_START_COLOUR = 0xFFFFFFFF;
    static final int BORDER_COLOUR = 0xFFFFA000;
    static final String READOUT_KEY = "info." + MaxtechStorage.MOD_ID + ".goggles.readout";
    static final String READOUT_ROOT_KEY = "info." + MaxtechStorage.MOD_ID + ".goggles.readout_root";
    static final String BORDER_KEY = "info." + MaxtechStorage.MOD_ID + ".goggles.border";

    private GogglesView() {
    }

    /** The action bar line for {@code node} in {@code network}. */
    public static Component readout(NetworkState network, long node) {
        int zone = network.zone(node);
        MutableComponent line;
        if (network.routeParent(node) == Long.MIN_VALUE) {
            line = Component.translatable(READOUT_ROOT_KEY, InfoFormat.number(network.id()), InfoFormat.number(zone));
        } else {
            RouteTrace trace = RouteTrace.of(network, node);
            line = Component.translatable(READOUT_KEY, InfoFormat.number(network.id()), InfoFormat.number(zone),
                    InfoFormat.number(network.segmentLoad(node)), InfoFormat.number(network.segmentCapacity(node)),
                    InfoFormat.number(trace.segments().size()) + (trace.truncated() ? "+" : ""));
        }
        if (network.isZoneBorder(node)) {
            line.append(Component.translatable(BORDER_KEY));
        }
        return line;
    }

    /** The overlay of {@code network} around {@code centre}, lasting {@code ticks}. */
    public static HighlightRequest overlay(NetworkState network, BlockPos centre, int ticks) {
        HighlightRequest.Builder builder = HighlightRequest.builder(CHANNEL).duration(ticks).throughWalls(true);
        LongIterator it = network.nodes().iterator();
        while (it.hasNext() && builder.size() < MAX_SHAPES - 2) {
            long node = it.nextLong();
            if (!near(node, centre)) {
                continue;
            }
            if (network.isZoneBorder(node)) {
                builder.block(BlockPos.of(node), BORDER_COLOUR);
            }
            long parent = network.routeParent(node);
            if (parent != Long.MIN_VALUE) {
                builder.line(centre(node), centre(parent), AnalyzerReport.loadColour(network.segmentLoad(node)));
                if (network.zone(node) != network.zone(parent) && !network.isZoneBorder(node)) {
                    builder.block(BlockPos.of(node), ZONE_START_COLOUR);
                }
            }
        }
        return builder.build();
    }

    private static boolean near(long pos, BlockPos centre) {
        return Math.abs(BlockPos.getX(pos) - centre.getX()) <= RADIUS
                && Math.abs(BlockPos.getY(pos) - centre.getY()) <= RADIUS
                && Math.abs(BlockPos.getZ(pos) - centre.getZ()) <= RADIUS;
    }

    static Vec3 centre(long pos) {
        return new Vec3(BlockPos.getX(pos) + 0.5, BlockPos.getY(pos) + 0.5, BlockPos.getZ(pos) + 0.5);
    }
}
