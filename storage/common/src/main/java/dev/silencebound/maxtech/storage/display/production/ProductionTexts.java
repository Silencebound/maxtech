package dev.silencebound.maxtech.storage.display.production;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.registry.ModContent;

/** The production stats' lang keys and their English (M7C item 18), shared by the display and the controller. */
public final class ProductionTexts {
    private static final String KEY = "gui." + MaxtechStorage.MOD_ID + ".production.";

    public static final String TITLE = KEY + "title";
    public static final String STATUS = KEY + "status";
    public static final String EMPTY = KEY + "empty";
    public static final String PER_MINUTE = KEY + "per_minute";
    public static final String IDLE = KEY + "idle";
    public static final String CPU = KEY + "cpu";
    public static final String STOCK_KEEPER = KEY + "stock_keeper";
    public static final String POOL = KEY + "pool";
    public static final String TOP_IN = KEY + "top_in";
    public static final String TOP_OUT = KEY + "top_out";
    public static final String HINT = KEY + "hint";
    /** The controller's Network tab section. */
    public static final String HEADER = KEY + "header";
    public static final String TOTALS = KEY + "totals";

    private ProductionTexts() {
    }

    /** The lang key of a group's row name; its argument is the label, or the position when there is none. */
    public static String groupKey(ProductionStats.Kind kind) {
        return switch (kind) {
            case CPU -> CPU;
            case STOCK_KEEPER -> STOCK_KEEPER;
            case POOL -> POOL;
        };
    }

    /** The lang key of a group row by the controller overview's line kind (CPU 0, stock keeper 1, pool 2). */
    public static String groupKey(int kind) {
        return kind == 0 ? CPU : kind == 1 ? STOCK_KEEPER : POOL;
    }

    /** Queues the English texts; during mod construction. */
    public static void init() {
        add(TITLE, "Production");
        add(STATUS, "In %s/min, out %s/min");
        add(EMPTY, "Nothing has moved yet");
        add(PER_MINUTE, "%s/min");
        add(IDLE, "idle");
        add(CPU, "CPU %s");
        add(STOCK_KEEPER, "Stock keeper %s");
        add(POOL, "Pool %s");
        add(TOP_IN, "Most coming in");
        add(TOP_OUT, "Most going out");
        add(HINT, "Items per minute of CPUs, stock keepers and labelled machine pools, and the items moving most.");
        add(HEADER, "Production (items per minute)");
        add(TOTALS, "Total: %s in, %s out");
    }

    private static void add(String key, String english) {
        ModContent.CONTENT.translation(key, english);
    }
}
