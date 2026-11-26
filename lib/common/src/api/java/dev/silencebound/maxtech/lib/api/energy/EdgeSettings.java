package dev.silencebound.maxtech.lib.api.energy;

/**
 * What an {@link EnergyEdge} reads from the config each time it runs: the FE rate, whether energy may go out to other
 * mods' blocks and how much, and how often an idle edge looks at its neighbour again. Lib builds it from its server
 * config ({@code energy.*}); energy coming in from other mods is always allowed. Immutable; any thread.
 *
 * @param rate
 *            the FE exchange rate
 * @param exportEnabled
 *            whether a network may send energy out to other mods' blocks at all ({@code energy.exportEnabled})
 * @param exportPerEdge
 *            most MU per tick one edge or one exposed FE handler sends out, 0 for no limit
 *            ({@code energy.exportPerEdge})
 * @param exportPerNetwork
 *            most MU per tick all edges and exposed FE handlers of one network send out together, 0 for no limit
 *            ({@code energy.exportPerNetwork})
 * @param pollTicks
 *            ticks between two looks of an idle edge at its neighbour, at least 1 ({@code energy.edgePollTicks})
 */
public record EdgeSettings(FeRate rate, boolean exportEnabled, long exportPerEdge, long exportPerNetwork,
        int pollTicks) {
    /** 1:1, export on without limits, an idle edge looks again every 20 ticks: the built-in defaults. */
    public static final EdgeSettings DEFAULTS = new EdgeSettings(FeRate.ONE_TO_ONE, true, 0, 0, 20);

    /** Clamps negative limits to 0 (no limit) and the poll interval to at least 1. */
    public EdgeSettings {
        exportPerEdge = Math.max(0, exportPerEdge);
        exportPerNetwork = Math.max(0, exportPerNetwork);
        pollTicks = Math.max(1, pollTicks);
    }

    /** The per-edge export limit in MU per tick, {@link Long#MAX_VALUE} without one; 0 when export is off. */
    public long edgeLimit() {
        return !exportEnabled ? 0 : exportPerEdge == 0 ? Long.MAX_VALUE : exportPerEdge;
    }

    /** The per-network export limit in MU per tick, {@link Long#MAX_VALUE} without one; 0 when export is off. */
    public long networkLimit() {
        return !exportEnabled ? 0 : exportPerNetwork == 0 ? Long.MAX_VALUE : exportPerNetwork;
    }
}
