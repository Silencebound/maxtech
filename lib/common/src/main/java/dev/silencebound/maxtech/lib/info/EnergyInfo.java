package dev.silencebound.maxtech.lib.info;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetwork;
import dev.silencebound.maxtech.lib.api.energy.EnergyReadout;
import dev.silencebound.maxtech.lib.api.energy.EnergyRole;
import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.energy.EnergyLookups;
import dev.silencebound.maxtech.lib.MaxtechLib;
import java.util.Optional;
import java.util.OptionalLong;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

/**
 * The energy lines of MaxTech energy blocks (MLE item 3) for the scanner, Jade and Storage's analyzer: what the block's
 * {@link EnergyEndpoint} does, then its network's {@link EnergyReadout}. Every number comes from fields the last pass
 * left; nothing here wakes a network, walks its endpoints or rebuilds a graph, so BRIEF stays O(1) and adds one entry.
 * Rates read "64 MU/t"; the brief entry uses compact numbers ("1.2k"). Server thread only.
 */
public final class EnergyInfo {
    public static final String PRODUCING_KEY = "info.maxtech.energy.producing";
    public static final String USING_KEY = "info.maxtech.energy.using";
    public static final String STORED_KEY = "info.maxtech.energy.stored";
    public static final String CHARGING_KEY = "info.maxtech.energy.charging";
    public static final String DISCHARGING_KEY = "info.maxtech.energy.discharging";
    public static final String NETWORK_KEY = "info.maxtech.energy.network";
    public static final String BROWNOUT_KEY = "info.maxtech.energy.brownout";
    public static final String SUPPLIED_KEY = "info.maxtech.energy.supplied";
    public static final String FULL_IN_KEY = "info.maxtech.energy.full_in";
    public static final String EMPTY_IN_KEY = "info.maxtech.energy.empty_in";
    public static final String NETWORK_STORED_KEY = "info.maxtech.energy.network_stored";
    public static final String BUFFERS_KEY = "info.maxtech.energy.buffers";
    public static final String COST_KEY = "info.maxtech.energy.cost";
    public static final String ASLEEP_KEY = "info.maxtech.energy.asleep";
    public static final String ACTIVE_KEY = "info.maxtech.energy.active";
    public static final String PAUSED_KEY = "info.maxtech.energy.paused";
    public static final String NO_NETWORK_KEY = "info.maxtech.energy.no_network";
    public static final String SECONDS_KEY = "info.maxtech.duration.seconds";
    public static final String MINUTES_KEY = "info.maxtech.duration.minutes";
    public static final String HOURS_KEY = "info.maxtech.duration.hours";
    public static final String DAYS_KEY = "info.maxtech.duration.days";

    /** A stored-energy bar's label: "83%". */
    public static final String BAR_KEY = "info.maxtech.energy.bar";
    /** The bolt drawn before a stored-energy bar (8 x 8, drawn by Storage's {@code mtx_info.py}). */
    public static final Identifier SPRITE = MaxtechLib.id("info/energy");

    private static final long TICKS_PER_SECOND = 20;

    private EnergyInfo() {
    }

    /** Adds the energy lines of the block {@code request} names, if it shows an energy endpoint; else nothing. */
    public static void addLines(BlockInfoRequest request, BlockInfoSink sink) {
        EnergyEndpoint endpoint = EnergyLookups.BLOCK.find(request.level(), request.pos(), request.side());
        if (endpoint != null) {
            endpoint(endpoint, request.detail(), sink);
        }
    }

    /**
     * The entries for {@code endpoint}. {@link BlockInfoRequest.Detail#BRIEF} gives one: what it produces, what it uses
     * (amber in a brownout), or for a buffer a bar of its stored energy. {@link BlockInfoRequest.Detail#FULL} adds a
     * buffer's flow and the network's lines, or a line saying it is in none.
     */
    public static void endpoint(EnergyEndpoint endpoint, BlockInfoRequest.Detail detail, BlockInfoSink sink) {
        Optional<EnergyNetwork> network = endpoint.network();
        switch (endpoint.role()) {
            case PRODUCER -> sink.line(Component.translatable(PRODUCING_KEY, InfoFormat.compact(endpoint.drawn()),
                    InfoFormat.compact(endpoint.supply())));
            case CONSUMER -> {
                MutableComponent using = Component.translatable(USING_KEY, InfoFormat.compact(endpoint.received()),
                        InfoFormat.compact(endpoint.demand()), percent(endpoint.suppliedFraction()));
                boolean brownout = network.isPresent() && network.get().readout().brownout();
                sink.line(brownout ? using.withStyle(ChatFormatting.GOLD) : using);
            }
            case BUFFER -> sink.progress(SPRITE,
                    Component.translatable(BAR_KEY, InfoFormat.percent(endpoint.stored(), endpoint.capacity())),
                    endpoint.stored(), endpoint.capacity(), InfoBars.energy(endpoint.stored(), endpoint.capacity()));
        }
        if (detail != BlockInfoRequest.Detail.FULL) {
            return;
        }
        if (endpoint.role() == EnergyRole.BUFFER) {
            sink.line(Component.translatable(STORED_KEY, InfoFormat.number(endpoint.stored()),
                    InfoFormat.number(endpoint.capacity())));
            if (endpoint.flow() > 0) {
                sink.line(Component.translatable(CHARGING_KEY, InfoFormat.number(endpoint.flow())));
            } else if (endpoint.flow() < 0) {
                sink.line(Component.translatable(DISCHARGING_KEY, InfoFormat.number(endpoint.drawn())));
            }
        }
        if (network.isEmpty()) {
            sink.line(Component.translatable(NO_NETWORK_KEY).withStyle(ChatFormatting.GRAY));
            return;
        }
        network(network.get().readout(), detail, sink);
    }

    /**
     * The network lines of {@code readout}: supply and demand, the brownout when there is one, time to full or empty;
     * with {@link BlockInfoRequest.Detail#FULL} also stored energy, buffer flows and the pass cost.
     */
    public static void network(EnergyReadout readout, BlockInfoRequest.Detail detail, BlockInfoSink sink) {
        sink.line(Component.translatable(NETWORK_KEY, InfoFormat.number(readout.supply()),
                InfoFormat.number(readout.demand())));
        if (readout.paused()) {
            sink.line(Component.translatable(PAUSED_KEY).withStyle(ChatFormatting.GOLD));
        }
        if (readout.brownout()) {
            sink.line(Component.translatable(BROWNOUT_KEY, percent(readout.suppliedFraction()))
                    .withStyle(ChatFormatting.GOLD));
        } else if (detail == BlockInfoRequest.Detail.FULL) {
            sink.line(Component.translatable(SUPPLIED_KEY, percent(readout.suppliedFraction())));
        }
        OptionalLong full = readout.ticksToFull();
        OptionalLong empty = readout.ticksToEmpty();
        if (full.isPresent() && full.getAsLong() > 0) {
            sink.line(Component.translatable(FULL_IN_KEY, duration(full.getAsLong())));
        } else if (empty.isPresent() && empty.getAsLong() > 0) {
            sink.line(Component.translatable(EMPTY_IN_KEY, duration(empty.getAsLong())));
        }
        if (detail != BlockInfoRequest.Detail.FULL) {
            return;
        }
        if (readout.capacity() > 0) {
            sink.line(Component.translatable(NETWORK_STORED_KEY, InfoFormat.number(readout.stored()),
                    InfoFormat.number(readout.capacity())));
            sink.line(Component.translatable(BUFFERS_KEY, InfoFormat.number(readout.bufferCharge()),
                    InfoFormat.number(readout.bufferDischarge())));
        }
        sink.line(Component
                .translatable(COST_KEY, micros(readout.lastPassNanos()), InfoFormat.number(readout.passCount()),
                        Component.translatable(readout.asleep() ? ASLEEP_KEY : ACTIVE_KEY))
                .withStyle(ChatFormatting.GRAY));
    }

    /** {@code fraction} (0 to 1) as a percentage with up to two decimals, without the sign: "75", "33.33". */
    public static String percent(double fraction) {
        return InfoFormat.decimal(Math.clamp(fraction, 0.0, 1.0) * 100);
    }

    /** {@code nanos} as microseconds with up to two decimals. */
    public static String micros(long nanos) {
        return InfoFormat.decimal(Math.max(0, nanos) / 1000.0);
    }

    /**
     * {@code ticks} as a short duration in its two largest units, rounded up to whole seconds: "45s", "3m 20s", "2h
     * 5m", "3d 4h".
     */
    public static Component duration(long ticks) {
        long seconds = ticks / TICKS_PER_SECOND + (ticks % TICKS_PER_SECOND == 0 ? 0 : 1);
        if (seconds < 60) {
            return Component.translatable(SECONDS_KEY, InfoFormat.number(seconds));
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return Component.translatable(MINUTES_KEY, InfoFormat.number(minutes), Long.toString(seconds % 60));
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return Component.translatable(HOURS_KEY, InfoFormat.number(hours), Long.toString(minutes % 60));
        }
        return Component.translatable(DAYS_KEY, InfoFormat.number(hours / 24), Long.toString(hours % 24));
    }
}
