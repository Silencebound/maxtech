package dev.silencebound.maxtech.storage.logic.admin;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.registry.ModContent;

/** Lang keys of the admin overview screen, declared with their English text. */
public final class AdminTexts {
    public static final String TITLE = gui("title");
    public static final String SUBTITLE = gui("subtitle");
    public static final String SHOWN = gui("shown");
    public static final String COLUMN_RULE = gui("column.rule");
    public static final String COLUMN_OWNER = gui("column.owner");
    public static final String COLUMN_WHERE = gui("column.where");
    public static final String COLUMN_CHECKS = gui("column.checks");
    public static final String COLUMN_ACTIONS = gui("column.actions");
    public static final String COLUMN_RAN = gui("column.ran");
    public static final String COLUMN_ERRORS = gui("column.errors");
    public static final String SORT_TIP = gui("sort_tip");
    public static final String ALL_DIMENSIONS = gui("all_dimensions");
    public static final String DIMENSION_TIP = gui("dimension_tip");
    public static final String OWNER_FILTER = gui("owner_filter");
    public static final String REFRESH = gui("refresh");
    public static final String TELEPORT = gui("teleport");
    public static final String INFO = gui("info");
    public static final String PAUSE = gui("pause");
    public static final String RESUME = gui("resume");
    public static final String EMPTY = gui("empty");
    public static final String NONE_MATCH = gui("none_match");
    public static final String NEVER = gui("never");
    public static final String AGO = gui("ago");

    static {
        ModContent.CONTENT.translation(TITLE, "LOGIC RULES");
        ModContent.CONTENT.translation(SUBTITLE, "%s RULES · %s LOADED CONTROLLERS · UNLOADED ONES NOT LISTED");
        ModContent.CONTENT.translation(SHOWN, "The costliest %s of %s rules are shown");
        ModContent.CONTENT.translation(COLUMN_RULE, "RULE");
        ModContent.CONTENT.translation(COLUMN_OWNER, "OWNER");
        ModContent.CONTENT.translation(COLUMN_WHERE, "WHERE");
        ModContent.CONTENT.translation(COLUMN_CHECKS, "CHECKS/S");
        ModContent.CONTENT.translation(COLUMN_ACTIONS, "ACTS/S");
        ModContent.CONTENT.translation(COLUMN_RAN, "LAST RUN");
        ModContent.CONTENT.translation(COLUMN_ERRORS, "ERR");
        ModContent.CONTENT.translation(SORT_TIP, "Click to sort by this column");
        ModContent.CONTENT.translation(ALL_DIMENSIONS, "All dimensions");
        ModContent.CONTENT.translation(DIMENSION_TIP, "Shows the rules of one dimension; click for the next");
        ModContent.CONTENT.translation(OWNER_FILTER, "Owner");
        ModContent.CONTENT.translation(REFRESH, "Refresh");
        ModContent.CONTENT.translation(TELEPORT, "Teleport");
        ModContent.CONTENT.translation(INFO, "Info");
        ModContent.CONTENT.translation(PAUSE, "Pause");
        ModContent.CONTENT.translation(RESUME, "Resume");
        ModContent.CONTENT.translation(EMPTY, "No logic rules are loaded on this server");
        ModContent.CONTENT.translation(NONE_MATCH, "No rule matches the filter");
        ModContent.CONTENT.translation(NEVER, "-");
        ModContent.CONTENT.translation(AGO, "%s s");
    }

    private AdminTexts() {
    }

    private static String gui(String path) {
        return "gui." + MaxtechStorage.MOD_ID + ".logic_admin." + path;
    }

    /** Loads the class during mod construction so its lang lines are declared. */
    static void init() {
    }
}
