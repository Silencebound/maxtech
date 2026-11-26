package dev.silencebound.maxtech.lib.config;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.platform.ConfigBuilder;
import dev.silencebound.maxtech.lib.platform.ConfigFile;
import dev.silencebound.maxtech.lib.platform.ConfigType;
import dev.silencebound.maxtech.lib.platform.Platform;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Lib's client config: visuals only (CLAUDE.md, Config). Registered on both sides like every config spec; only a client
 * loads it. Reads fall back to the default while the file is not loaded.
 */
public final class ClientConfig {
    private static final ConfigBuilder BUILDER = Platform.get().config().builder(MaxtechLib.MOD_ID);

    private static final BooleanSupplier SHOW_CONDUITS_THROUGH_FACADE;
    private static final int HIGHLIGHT_RENDER_DISTANCE_DEFAULT = 96;
    private static final IntSupplier HIGHLIGHT_RENDER_DISTANCE;
    private static final Supplier<String> TEXT_SCALE;
    private static final BooleanSupplier CONDUIT_COLOR_LABELS;
    private static final Supplier<String> OVERLAY_DETAIL;
    private static final BooleanSupplier OVERLAY_SNEAK_DETAILS;

    static {
        BUILDER.push("facades");
        BUILDER.comment("Show the cables inside a facaded cable, instead of the facade, while holding a wrench or a",
                "cable.");
        SHOW_CONDUITS_THROUGH_FACADE = BUILDER.define("showConduitsThroughFacade", false);
        BUILDER.pop();

        BUILDER.push("highlights");
        HIGHLIGHT_RENDER_DISTANCE = BUILDER
                .comment("Outlines and overlay lines (locate, network analyzer) farther away than this many blocks",
                        "are not drawn.")
                .defineInRange("renderDistance", HIGHLIGHT_RENDER_DISTANCE_DEFAULT, 8, 512);
        BUILDER.pop();

        BUILDER.push("text");
        TEXT_SCALE = BUILDER
                .comment("Text size on MaxTech screens, independent of the GUI scale: normal, large (1.25x)",
                        "or larger (1.5x).")
                .define("scale", "normal", ClientConfig::validTextScale);
        BUILDER.pop();

        BUILDER.push("accessibility");
        CONDUIT_COLOR_LABELS = BUILDER.comment(
                "Mark what cable colours say in shape too: the dye's letter on a dyed cable, an arrow or",
                "diamond for a connector's mode, and both in words in the Jade overlay. Reload the chunks (F3+A) after",
                "changing it.").define("conduitColorLabels", false);
        BUILDER.pop();

        BUILDER.push("overlay");
        OVERLAY_DETAIL = BUILDER
                .comment("How much the Jade overlay shows for MaxTech blocks: compact (status and the most important",
                        "number) or full (every detail, always).")
                .define("detailLevel", "compact", ClientConfig::validOverlayDetail);
        OVERLAY_SNEAK_DETAILS = BUILDER
                .comment("Show every detail while Jade's \"show details\" key (sneak by default) is held. Only matters",
                        "with the compact detail level.")
                .define("sneakForDetails", true);
        BUILDER.pop();
    }

    private static final ConfigFile FILE = Platform.get().config().register(MaxtechLib.MOD_ID, ConfigType.CLIENT,
            BUILDER);

    private ClientConfig() {
    }

    /** Whether cables show through facades while the player holds a wrench or a cable. Client thread. */
    public static boolean showConduitsThroughFacade() {
        return FILE.isLoaded() && SHOW_CONDUITS_THROUGH_FACADE.getAsBoolean();
    }

    /** Distance in blocks beyond which highlight shapes are not drawn. Client thread. */
    public static int highlightRenderDistance() {
        return FILE.isLoaded() ? HIGHLIGHT_RENDER_DISTANCE.getAsInt() : HIGHLIGHT_RENDER_DISTANCE_DEFAULT;
    }

    /**
     * Whether cables show their colours as letters and shapes too (PRD 8.2). Client thread and chunk meshing threads;
     * the models read it while baking a mesh.
     */
    public static boolean conduitColorLabels() {
        return FILE.isLoaded() && CONDUIT_COLOR_LABELS.getAsBoolean();
    }

    /** Text size on MaxTech screens: normal, large or larger. Client thread. */
    public static String textScale() {
        return FILE.isLoaded() ? TEXT_SCALE.get() : "normal";
    }

    /**
     * Whether the Jade overlay shows every detail of MaxTech blocks always ({@code detailLevel = full}). Client thread.
     */
    public static boolean overlayFullDetail() {
        return FILE.isLoaded() && "full".equals(OVERLAY_DETAIL.get());
    }

    /**
     * Whether the Jade overlay shows every detail while its "show details" key is held; on by default. Client thread.
     */
    public static boolean overlaySneakForDetails() {
        return !FILE.isLoaded() || OVERLAY_SNEAK_DETAILS.getAsBoolean();
    }

    private static boolean validOverlayDetail(String value) {
        return "compact".equals(value) || "full".equals(value);
    }

    private static boolean validTextScale(String value) {
        return "normal".equals(value) || "large".equals(value) || "larger".equals(value);
    }

    /** Loads the class during mod construction, which registers the spec. */
    public static void register() {
    }
}
