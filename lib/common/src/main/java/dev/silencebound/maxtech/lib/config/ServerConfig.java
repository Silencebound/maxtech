package dev.silencebound.maxtech.lib.config;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.energy.EdgeSettings;
import dev.silencebound.maxtech.lib.api.energy.FeRate;
import dev.silencebound.maxtech.lib.api.network.UnloadedChunkMode;
import dev.silencebound.maxtech.lib.api.config.ConfigBuilder;
import dev.silencebound.maxtech.lib.api.guide.GuideConfigValues;
import dev.silencebound.maxtech.lib.api.config.ConfigFile;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Lib's server config (PRD 5.7): the values of the frameworks every MaxTech mod shares. Content values are in each
 * content mod's own config. Defaults are the built-in {@code standalone} preset, so preset values live only in the
 * preset JSON. Every value is read on the server thread and may change on reload.
 */
public final class ServerConfig {
    private static final LibPresetConfig CONFIG = new LibPresetConfig(MaxtechLib.MOD_ID, ServerConfig.class);
    private static final ConfigBuilder BUILDER = CONFIG.builder();

    public static final Supplier<String> PRESET = CONFIG.preset();

    public static final Supplier<UnloadedChunkMode> UNLOADED_CHUNK_MODE;
    public static final BooleanSupplier ALLOW_ANCHORS;
    public static final BooleanSupplier OPS_BYPASS_OWNERSHIP;
    /** Time and operations one owner's task group may use per tick ({@code scheduler.*}, PRD 7.2). */
    public static final IntSupplier SCHEDULER_OWNER_BUDGET_MICROS;
    public static final IntSupplier SCHEDULER_OWNER_OPS_PER_TICK;
    public static final IntSupplier SCHEDULER_MAX_BACKOFF_TICKS;
    /** Threads of the background worker pool ({@code workers.threads}). */
    public static final IntSupplier WORKER_THREADS;
    /** {@code sync.listUpdatesPerSecond}: most change batches a synced list sends an open screen per second. */
    public static final IntSupplier LIST_UPDATES_PER_SECOND;

    /**
     * {@code energy.feRateFe} FE are worth {@code energy.feRateMu} MaxTech Units (MLE); read through {@link #feRate()}.
     */
    public static final IntSupplier FE_RATE_FE;
    public static final IntSupplier FE_RATE_MU;
    /** {@code energy.export*} and {@code energy.edgePollTicks}, read through {@link #edgeSettings()}. */
    public static final BooleanSupplier EXPORT_ENABLED;
    public static final LongSupplier EXPORT_PER_EDGE;
    public static final LongSupplier EXPORT_PER_NETWORK;
    public static final IntSupplier EDGE_POLL_TICKS;
    /** {@code transfer.*}: what one transfer port operation moves at most, how often, and filter size (ADR-0018). */
    public static final IntSupplier TRANSFER_ITEMS_PER_OP;
    public static final IntSupplier TRANSFER_FLUID_PER_OP;
    public static final LongSupplier TRANSFER_ENERGY_PER_OP;
    public static final IntSupplier TRANSFER_MIN_INTERVAL_TICKS;
    public static final IntSupplier TRANSFER_FILTER_SIZE;

    private static volatile @Nullable ConfigFile file;
    private static volatile FeRate feRate = FeRate.ONE_TO_ONE;
    private static volatile EdgeSettings edgeSettings = EdgeSettings.DEFAULTS;

    static {
        BUILDER.push("networks");
        UNLOADED_CHUNK_MODE = BUILDER
                .comment("truncate: networks only span loaded chunks and reconnect when a chunk loads.",
                        "pause: a network that reaches into an unloaded chunk stops until all its chunks are loaded.")
                .defineEnum("unloadedChunkMode",
                        CONFIG.enumDefault("networks.unloadedChunkMode", UnloadedChunkMode.class));
        ALLOW_ANCHORS = BUILDER.comment("Whether network anchors may keep the chunks of their network loaded.")
                .define("allowAnchors", CONFIG.booleanDefault("networks.allowAnchors"));
        BUILDER.pop();

        BUILDER.push("security");
        OPS_BYPASS_OWNERSHIP = BUILDER
                .comment("Whether game masters (permission level 2) can always open and use owned machines and",
                        "containers. Off, only the owner, trusted players and team members get in.")
                .define("opsBypassOwnership", CONFIG.booleanDefault("security.opsBypassOwnership"));
        BUILDER.pop();

        BUILDER.push("scheduler");
        SCHEDULER_OWNER_BUDGET_MICROS = BUILDER
                .comment("Microseconds of tick time one owner (such as a storage network) may spend on its devices",
                        "per tick; work beyond that waits for the next tick, so the owner slows down, not the server.")
                .defineInRange("ownerBudgetMicros", CONFIG.intDefault("scheduler.ownerBudgetMicros"), 1, 50_000);
        SCHEDULER_OWNER_OPS_PER_TICK = BUILDER
                .comment("Operations one owner may run per tick (each device run counts one, plus what it moves in",
                        "batches); the same throttle as the time budget, but independent of the machine's speed.")
                .defineInRange("ownerOpsPerTick", CONFIG.intDefault("scheduler.ownerOpsPerTick"), 1, 1_000_000);
        SCHEDULER_MAX_BACKOFF_TICKS = BUILDER
                .comment("Longest sleep of an idle device between two checks; the sleep doubles from 1 tick with",
                        "each idle check. Events (an inventory change, a click) wake devices at once.")
                .defineInRange("maxBackoffTicks", CONFIG.intDefault("scheduler.maxBackoffTicks"), 1, 1200);
        BUILDER.pop();

        BUILDER.push("workers");
        WORKER_THREADS = BUILDER
                .comment("Background threads for planning and searches on snapshots; read when the server starts.")
                .worldRestart().defineInRange("threads", CONFIG.intDefault("workers.threads"), 1, 16);
        BUILDER.pop();

        BUILDER.push("sync");
        LIST_UPDATES_PER_SECOND = BUILDER
                .comment("Most times per second a large list on an open screen (a storage terminal) receives its",
                        "changes; changes in between are merged. The full list is sent once when the screen opens.")
                .defineInRange("listUpdatesPerSecond", CONFIG.intDefault("sync.listUpdatesPerSecond"), 1, 20);
        BUILDER.pop();

        BUILDER.push("energy");
        FE_RATE_FE = BUILDER
                .comment("Exchange rate between FE and MaxTech Units (MU): feRateFe FE are worth feRateMu MU.",
                        "Both directions round down, so converting back and forth never creates energy.")
                .defineInRange("feRateFe", CONFIG.intDefault("energy.feRateFe"), 1, 1_000_000);
        FE_RATE_MU = BUILDER.comment("MU side of the FE exchange rate; see feRateFe.").defineInRange("feRateMu",
                CONFIG.intDefault("energy.feRateMu"), 1, 1_000_000);
        EXPORT_ENABLED = BUILDER
                .comment("Whether MaxTech energy may flow out to other mods' blocks (their FE machines and batteries).",
                        "Off, networks still take FE in from other mods, but give none out.")
                .define("exportEnabled", CONFIG.booleanDefault("energy.exportEnabled"));
        EXPORT_PER_EDGE = BUILDER
                .comment("Most MU per tick one other mod's block next to a MaxTech energy block may be sent; 0 for no",
                        "limit. Also limits how much one MaxTech block hands out through its FE capability per tick.")
                .defineInRange("exportPerEdge", CONFIG.longDefault("energy.exportPerEdge"), 0L, 1L << 40);
        EXPORT_PER_NETWORK = BUILDER
                .comment("Most MU per tick one energy network sends to other mods' blocks in total; 0 for no limit.")
                .defineInRange("exportPerNetwork", CONFIG.longDefault("energy.exportPerNetwork"), 0L, 1L << 40);
        EDGE_POLL_TICKS = BUILDER
                .comment("FE has no change signal: while no energy flows to or from another mod's block, its edge",
                        "looks at it again every this many ticks (and at once when the block is placed or removed).")
                .defineInRange("edgePollTicks", CONFIG.intDefault("energy.edgePollTicks"), 1, 1200);
        BUILDER.pop();

        BUILDER.push("transfer");
        TRANSFER_ITEMS_PER_OP = BUILDER.comment("Most items one operation of a transfer port moves through one face.")
                .defineInRange("itemsPerOp", CONFIG.intDefault("transfer.itemsPerOp"), 1, 4096);
        TRANSFER_FLUID_PER_OP = BUILDER
                .comment("Most fluid (mB) one operation of a transfer port moves through one face.")
                .defineInRange("fluidPerOp", CONFIG.intDefault("transfer.fluidPerOp"), 1, 1_000_000);
        TRANSFER_ENERGY_PER_OP = BUILDER
                .comment("Most energy (MU) one operation of a transfer port moves through one face.")
                .defineInRange("energyPerOp", CONFIG.longDefault("transfer.energyPerOp"), 1L, 1L << 40);
        TRANSFER_MIN_INTERVAL_TICKS = BUILDER
                .comment("Fewest ticks between two operations of one transfer port; ports sleep longer while idle.")
                .defineInRange("minIntervalTicks", CONFIG.intDefault("transfer.minIntervalTicks"), 1, 1200);
        TRANSFER_FILTER_SIZE = BUILDER
                .comment("Most entries (resources and tags) a player can put in one transfer port filter.")
                .defineInRange("filterSize", CONFIG.intDefault("transfer.filterSize"), 1, 54);
        BUILDER.pop();

    }

    private ServerConfig() {
    }

    /**
     * {@code value} as configured, or {@code fallback} while no server config is loaded: on a client outside a world,
     * or when the world closes between the check and the read. Any thread; a client in a world reads the values the
     * server sent it.
     */
    public static int loadedOr(IntSupplier value, int fallback) {
        ConfigFile current = file;
        if (current == null || !current.isLoaded()) {
            return fallback;
        }
        try {
            return value.getAsInt();
        } catch (IllegalStateException | NullPointerException e) {
            // Unloaded after isLoaded() answered; an IllegalStateException, or a NullPointerException from
            // ConfigValue.get() unboxing a just-cleared cache.
            return fallback;
        }
    }

    /** {@link #loadedOr(IntSupplier, int)} for a long. */
    public static long loadedOr(LongSupplier value, long fallback) {
        ConfigFile current = file;
        if (current == null || !current.isLoaded()) {
            return fallback;
        }
        try {
            return value.getAsLong();
        } catch (IllegalStateException | NullPointerException e) {
            // Unloaded after isLoaded() answered; an IllegalStateException, or a NullPointerException from
            // ConfigValue.get() unboxing a just-cleared cache.
            return fallback;
        }
    }

    /**
     * The configured FE exchange rate, or 1:1 while no server config is loaded. Allocates only when the configured
     * numbers changed since the last call. Any thread: the cache is volatile, so a race costs one extra allocation.
     */
    public static FeRate feRate() {
        int fe = loadedOr(FE_RATE_FE, 1);
        int mu = loadedOr(FE_RATE_MU, 1);
        FeRate current = feRate;
        if (current.fe() != fe || current.mu() != mu) {
            current = new FeRate(fe, mu);
            feRate = current;
        }
        return current;
    }

    /**
     * The FE rate and export settings for energy edges, or {@link EdgeSettings#DEFAULTS} while no server config is
     * loaded. Allocates only when a configured value changed since the last call. Server thread.
     */
    public static EdgeSettings edgeSettings() {
        FeRate rate = feRate();
        boolean enabled = loadedOr(EXPORT_ENABLED, true);
        long perEdge = loadedOr(EXPORT_PER_EDGE, 0L);
        long perNetwork = loadedOr(EXPORT_PER_NETWORK, 0L);
        int poll = loadedOr(EDGE_POLL_TICKS, EdgeSettings.DEFAULTS.pollTicks());
        EdgeSettings current = edgeSettings;
        if (!current.rate().equals(rate) || current.exportEnabled() != enabled || current.exportPerEdge() != perEdge
                || current.exportPerNetwork() != perNetwork || current.pollTicks() != poll) {
            current = new EdgeSettings(rate, enabled, perEdge, perNetwork, poll);
            edgeSettings = current;
        }
        return current;
    }

    /** The registered server config file; null before {@link #register()}. For tests that change a value in memory. */
    public static @Nullable ConfigFile configFile() {
        return file;
    }

    /** {@link #loadedOr(IntSupplier, int)} for a switch. */
    public static boolean loadedOr(BooleanSupplier value, boolean fallback) {
        ConfigFile current = file;
        if (current == null || !current.isLoaded()) {
            return fallback;
        }
        try {
            return value.getAsBoolean();
        } catch (IllegalStateException | NullPointerException e) {
            // Unloaded after isLoaded() answered; an IllegalStateException, or a NullPointerException from
            // ConfigValue.get() unboxing a just-cleared cache.
            return fallback;
        }
    }

    /**
     * Where the built-in presets and this config's values disagree; see {@link LibPresetConfig#presetProblems()}. The
     * {@code energy.*} and {@code transfer.*} keys must be in every preset.
     */
    public static List<String> presetProblems() {
        return CONFIG.presetProblems(path -> path.startsWith("energy.") || path.startsWith("transfer."));
    }

    /**
     * Lib's values for guide pages' {@code <Config key="maxtech_lib:..." />}; see
     * {@link LibPresetConfig#guideSource()}.
     */
    public static GuideConfigValues.Source guideSource() {
        return CONFIG.guideSource();
    }

    /** Registers the file and preset handling; called once from {@link MaxtechLib#init()}. */
    public static void register() {
        ConfigFile registered = CONFIG.register();
        file = registered;
    }
}
