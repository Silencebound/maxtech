package dev.silencebound.maxtech.lib.config;

import java.util.Map;

/**
 * English names of Lib's config values and sections for NeoForge's config screen, by dotted path. Tooltips come from
 * the values' comments ({@code ConfigTranslations}). Datagen and {@code LibConfigNamesTest} fail while a value or
 * section has no name here; the test also fails on a name without a value.
 */
public final class LibConfigNames {
    public static final Map<String, String> NAMES = Map.ofEntries(Map.entry("preset", "Preset"),
            Map.entry("networks", "Networks"), Map.entry("networks.unloadedChunkMode", "Unloaded chunks"),
            Map.entry("networks.allowAnchors", "Allow network anchors"), Map.entry("security", "Security"),
            Map.entry("security.opsBypassOwnership", "Operators bypass owners"), Map.entry("scheduler", "Scheduler"),
            Map.entry("scheduler.ownerBudgetMicros", "Time per owner (µs/t)"),
            Map.entry("scheduler.ownerOpsPerTick", "Operations per owner (/t)"),
            Map.entry("scheduler.maxBackoffTicks", "Longest idle sleep (ticks)"),
            Map.entry("workers", "Background workers"), Map.entry("workers.threads", "Threads"),
            Map.entry("sync", "Screen sync"), Map.entry("sync.listUpdatesPerSecond", "List updates per second"),
            Map.entry("energy", "Energy"), Map.entry("energy.feRateFe", "Exchange rate: FE"),
            Map.entry("energy.feRateMu", "Exchange rate: MU"),
            Map.entry("energy.exportEnabled", "Export to other mods"),
            Map.entry("energy.exportPerEdge", "Export per block (MU/t)"),
            Map.entry("energy.exportPerNetwork", "Export per network (MU/t)"),
            Map.entry("energy.edgePollTicks", "FE recheck (ticks)"), Map.entry("facades", "Facades"),
            Map.entry("facades.showConduitsThroughFacade", "Show cables in facades"),
            Map.entry("highlights", "Highlights"), Map.entry("highlights.renderDistance", "Draw distance (blocks)"),
            Map.entry("text", "Text"), Map.entry("text.scale", "Text size"),
            Map.entry("accessibility", "Accessibility"),
            Map.entry("accessibility.conduitColorLabels", "Cable colour labels"), Map.entry("overlay", "Jade overlay"),
            Map.entry("overlay.detailLevel", "Detail level"),
            Map.entry("overlay.sneakForDetails", "Show details while sneaking"));

    private LibConfigNames() {
    }
}
