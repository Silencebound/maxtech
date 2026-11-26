package dev.silencebound.maxtech.storage.config;

import dev.silencebound.maxtech.lib.platform.ConfigBuilder;
import dev.silencebound.maxtech.lib.platform.ConfigType;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.function.BooleanSupplier;

/**
 * Storage's common config: the {@code [modules]} switches that recipes, creative tabs and JEI read, which load before
 * the server config does (see {@link dev.silencebound.maxtech.lib.content.ContentToggle}). Balance goes in
 * {@link StorageServerConfig}; presets only cover server config, so the defaults here are plain {@code true}. Each
 * switch is read through its toggle in {@link StorageModules}.
 */
public final class StorageCommonConfig {
    private static final ConfigBuilder BUILDER = Platform.get().config().builder(MaxtechStorage.MOD_ID);
    private static final String RESTART = "Restart the game after changing it.";

    /** {@code modules.placerBreaker}: the placer and breaker ports. */
    public static final BooleanSupplier PLACER_BREAKER;
    /** {@code modules.collector}: the collector. */
    public static final BooleanSupplier COLLECTOR;
    /** {@code modules.p2pItem}: P2P item links. */
    public static final BooleanSupplier P2P_ITEM;
    /** {@code modules.p2pFluid}: P2P fluid links. */
    public static final BooleanSupplier P2P_FLUID;
    /** {@code modules.p2pEnergy}: P2P energy links. */
    public static final BooleanSupplier P2P_ENERGY;
    /** {@code modules.p2pRedstone}: P2P redstone links. */
    public static final BooleanSupplier P2P_REDSTONE;
    /** {@code modules.networkAnchor}: the controller's network anchor module. */
    public static final BooleanSupplier NETWORK_ANCHOR;
    /** {@code modules.wireless}: the wireless terminal and the wireless access mount. */
    public static final BooleanSupplier WIRELESS;
    /** {@code modules.dataGeodes}: deep data geodes in worldgen and budding data crystal. */
    public static final BooleanSupplier DATA_GEODES;
    /** {@code modules.voidGeodes}: void geodes in worldgen and budding void crystal. */
    public static final BooleanSupplier VOID_GEODES;

    static {
        BUILDER.push("modules");
        PLACER_BREAKER = BUILDER.comment("Whether placer and breaker ports (blocks and parts) work.",
                "When off, their recipes do not load, their items leave creative tabs and JEI, and placed ones",
                "do nothing. " + RESTART).gameRestart().define("placerBreaker", true);
        COLLECTOR = BUILDER
                .comment("Whether the collector (block and part) works; off works as for placerBreaker.", RESTART)
                .gameRestart().define("collector", true);
        P2P_ITEM = BUILDER.comment("Whether P2P item links work; off works as for placerBreaker.", RESTART)
                .gameRestart().define("p2pItem", true);
        P2P_FLUID = BUILDER.comment("Whether P2P fluid links work; off works as for placerBreaker.", RESTART)
                .gameRestart().define("p2pFluid", true);
        P2P_ENERGY = BUILDER.comment("Whether P2P energy links work; off works as for placerBreaker.", RESTART)
                .gameRestart().define("p2pEnergy", true);
        P2P_REDSTONE = BUILDER.comment("Whether P2P redstone links work; off works as for placerBreaker.", RESTART)
                .gameRestart().define("p2pRedstone", true);
        NETWORK_ANCHOR = BUILDER.comment("Whether network anchors keep chunks loaded.",
                "When off, its recipe does not load, its item leaves creative tabs and JEI, and placed anchors",
                "hold no chunks. " + RESTART).gameRestart().define("networkAnchor", true);
        WIRELESS = BUILDER
                .comment("Whether the wireless terminal and wireless access mounts work; off works",
                        "as for placerBreaker (a wireless terminal opens nothing).", RESTART)
                .gameRestart().define("wireless", true);
        DATA_GEODES = BUILDER.comment("Whether deep data geodes generate and budding data crystal grows.",
                "When off, new chunks get no geodes, budding data crystal leaves creative tabs and JEI, and placed",
                "budding blocks stop growing. " + RESTART).gameRestart().define("dataGeodes", true);
        VOID_GEODES = BUILDER.comment("Whether void geodes generate on the outer End islands and budding void crystal",
                "grows; off works as for dataGeodes. " + RESTART).gameRestart().define("voidGeodes", true);
        BUILDER.pop();
    }

    private StorageCommonConfig() {
    }

    /** Registers the file; called once from {@link MaxtechStorage#init()}. */
    public static void register() {
        Platform.get().config().register(MaxtechStorage.MOD_ID, ConfigType.COMMON, BUILDER);
    }
}
