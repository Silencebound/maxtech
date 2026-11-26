package dev.silencebound.maxtech.storage.config;

import dev.silencebound.maxtech.lib.platform.ConfigBuilder;
import dev.silencebound.maxtech.lib.platform.ConfigFile;
import dev.silencebound.maxtech.lib.platform.ConfigType;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.sync.ListSort;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.palette.StatusPalette.Palette;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Storage's client config: visuals and terminal defaults. Registered on both sides like every config spec; only a
 * client loads it, and every read falls back to the default while the file is not loaded (a dedicated server, a
 * GameTest). Client thread.
 */
public final class StorageClientConfig {
    /** The terminal's sort direction when it first opens. */
    public enum SortDirection {
        DESCENDING, ASCENDING
    }

    /** Whether logic notices show as toasts for this player (M7L). */
    public static boolean logicToasts() {
        return !FILE.isLoaded() || LOGIC_TOASTS.getAsBoolean();
    }

    /** Whether a logic remote in the inventory shows new notices as a toast (M7L2). */
    public static boolean remotePings() {
        return !FILE.isLoaded() || REMOTE_PINGS.getAsBoolean();
    }

    /** Whether crafting jobs this player started show a toast when they finish or stop (B09 item 21d). */
    public static boolean craftingToasts() {
        return !FILE.isLoaded() || CRAFTING_TOASTS.getAsBoolean();
    }

    /** How terminal cells label amounts. */
    public enum AmountStyle {
        /** At most four characters ({@code 1.2k}). */
        SHORT,
        /** The exact count in half-size text while it has at most six digits, then short. */
        FULL
    }

    private static final ConfigBuilder BUILDER = Platform.get().config().builder(MaxtechStorage.MOD_ID);

    private static final BooleanSupplier EFFECTS;
    private static final BooleanSupplier CABLE_TIER_GLOW;
    private static final Supplier<ListSort> SORT;
    private static final Supplier<SortDirection> DIRECTION;
    private static final BooleanSupplier JEI_SYNC;
    private static final Supplier<AmountStyle> AMOUNTS;
    private static final Supplier<Palette> PALETTE;
    private static final BooleanSupplier CRAFTING_DISPLAY;
    private static final IntSupplier CRAFTING_DISPLAY_DISTANCE;
    private static final BooleanSupplier LOGIC_TOASTS;
    private static final BooleanSupplier REMOTE_PINGS;
    private static final BooleanSupplier CRAFTING_TOASTS;

    static {
        BUILDER.push("visuals");
        EFFECTS = BUILDER
                .comment("Animated effects: the display panels' scanline, the controller core's glow pulse and",
                        "the machines' animated fronts. Off draws them still. Restart the game after changing it.")
                .gameRestart().define("effects", true);
        CABLE_TIER_GLOW = BUILDER.comment("Draw the lane of tier 2 to 4 storage cables lit (light 4, 8 and 15) so it",
                "reads brighter in the dark. Only the drawing changes; the cables give no block light.",
                "Reload the chunks (F3+A) after changing it.").define("cableTierGlow", true);
        PALETTE = BUILDER
                .comment("Colours of status signals (good, warning, bad): DEFAULT, RED_GREEN_SAFE (blue, yellow,",
                        "orange; for deuteranopia and protanopia) or BLUE_YELLOW_SAFE (for tritanopia). Every status",
                        "also carries a symbol (+ ! x), whatever the palette.")
                .defineEnum("palette", Palette.DEFAULT);
        CRAFTING_DISPLAY = BUILDER
                .comment("Show the item a crafter or crafting assembler is crafting, turning behind its glass.")
                .define("craftingDisplay", true);
        CRAFTING_DISPLAY_DISTANCE = BUILDER
                .comment("Blocks from the camera within which the item being crafted is drawn.")
                .defineInRange("craftingDisplayDistance", 32, 4, 128);
        BUILDER.pop();
        BUILDER.push("terminal");
        SORT = BUILDER.comment("What a terminal sorts by when it first opens in a session: COUNT, NAME or MOD.")
                .defineEnum("sort", ListSort.COUNT);
        DIRECTION = BUILDER.comment("The sort direction a terminal starts with: DESCENDING or ASCENDING.")
                .defineEnum("direction", SortDirection.DESCENDING);
        JEI_SYNC = BUILDER.comment("Keep the terminal's search and JEI's search the same while JEI is installed.")
                .define("jeiSearchSync", false);
        AMOUNTS = BUILDER.comment("How cells label amounts: SHORT (1.2k) or FULL (the exact count while it fits).")
                .defineEnum("amountFormat", AmountStyle.SHORT);
        BUILDER.pop();
        BUILDER.push("logic");
        LOGIC_TOASTS = BUILDER.comment("Show the toasts logic graphs send to your team. Off hides them for you only;",
                "the notices still reach the network's log and chat.").define("toasts", true);
        REMOTE_PINGS = BUILDER
                .comment("Show a new notice of a network as a toast while a logic remote bound to it is in your",
                        "inventory and in range. Off keeps the remote quiet; its screen still lists the notices.")
                .define("remotePings", true);
        BUILDER.pop();
        BUILDER.push("crafting");
        CRAFTING_TOASTS = BUILDER
                .comment("Show a toast when a crafting job you started finishes, stops or is",
                        "cancelled by someone else. Off hides them; the terminal's jobs tab still shows its badge.")
                .define("toasts", true);
        BUILDER.pop();
    }

    private static final ConfigFile FILE = Platform.get().config().register(MaxtechStorage.MOD_ID, ConfigType.CLIENT,
            BUILDER);

    private StorageClientConfig() {
    }

    /** Loads the class, which registers the file; called once from {@link MaxtechStorage#init()}. */
    public static void register() {
    }

    /** Whether animated effects run. */
    public static boolean effects() {
        return !FILE.isLoaded() || EFFECTS.getAsBoolean();
    }

    /** Whether the lane of higher-tier storage cables is drawn lit ({@code CableTierMarks#lineEmission}). */
    public static boolean cableTierGlow() {
        return !FILE.isLoaded() || CABLE_TIER_GLOW.getAsBoolean();
    }

    /** Whether crafters and crafting assemblers show the item they craft (B09 item 29). */
    public static boolean craftingDisplay() {
        return !FILE.isLoaded() || CRAFTING_DISPLAY.getAsBoolean();
    }

    /** Blocks within which the item being crafted is drawn. */
    public static int craftingDisplayDistance() {
        return FILE.isLoaded() ? CRAFTING_DISPLAY_DISTANCE.getAsInt() : 32;
    }

    /** The palette of status colours. */
    public static Palette palette() {
        return FILE.isLoaded() ? PALETTE.get() : Palette.DEFAULT;
    }

    /** The sort a terminal starts with. */
    public static ListSort sort() {
        return FILE.isLoaded() ? SORT.get() : ListSort.COUNT;
    }

    /** Whether a terminal starts sorted ascending (reversed). */
    public static boolean ascending() {
        return FILE.isLoaded() && DIRECTION.get() == SortDirection.ASCENDING;
    }

    /** Whether the terminal search follows and drives JEI's. */
    public static boolean jeiSearchSync() {
        return FILE.isLoaded() && JEI_SYNC.getAsBoolean();
    }

    /** How terminal cells label amounts. */
    public static AmountStyle amountStyle() {
        return FILE.isLoaded() ? AMOUNTS.get() : AmountStyle.SHORT;
    }
}
