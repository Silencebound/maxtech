package dev.silencebound.maxtech.storage.research;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.registry.ModContent;

/** The research screen's lang keys, with their English lines. */
public final class ResearchTexts {
    public static final String POINTS = key("points");
    public static final String TOKENS = key("tokens");
    public static final String NEXT_TOKEN = key("next_token");
    public static final String OFF = key("off");
    public static final String LOCKED = key("locked");
    public static final String AVAILABLE = key("available");
    public static final String COMPLETE = key("complete");
    public static final String REQUIRES = key("requires");
    public static final String UNLOCKS = key("unlocks");
    public static final String ANY_OF = key("any_of");
    public static final String SKIPPED = key("skipped");
    public static final String PUZZLE = key("puzzle");
    public static final String PUZZLE_MISSING = key("puzzle_missing");
    public static final String SPEND = key("spend");
    public static final String USE_TOKEN = key("use_token");
    public static final String NEXT_TIER = key("next_tier");
    public static final String TIER_PROGRESS = key("tier_progress");
    public static final String TIER_MATERIAL = key("tier_material");
    public static final String TIER_NEXT_NODE = key("tier_next_node");
    public static final String ALL_DONE = key("all_done");
    public static final String WAITING = key("waiting");

    static {
        ModContent.CONTENT.translation(POINTS, "Points: %s");
        ModContent.CONTENT.translation(TOKENS, "Skip tokens: %s");
        ModContent.CONTENT.translation(NEXT_TOKEN, "Next token: %s / %s points");
        ModContent.CONTENT.translation(OFF, "Research is off: everything is unlocked");
        ModContent.CONTENT.translation(LOCKED, "Needs earlier research");
        ModContent.CONTENT.translation(AVAILABLE, "Available");
        ModContent.CONTENT.translation(COMPLETE, "Complete");
        ModContent.CONTENT.translation(REQUIRES, "Requires: %s");
        ModContent.CONTENT.translation(UNLOCKS, "Unlocks: %s");
        ModContent.CONTENT.translation(ANY_OF, "Complete any one:");
        ModContent.CONTENT.translation(SKIPPED, "%s (not in this pack)");
        ModContent.CONTENT.translation(PUZZLE, "Solve %s (%s)");
        ModContent.CONTENT.translation(PUZZLE_MISSING, "%s (puzzle not available)");
        ModContent.CONTENT.translation(SPEND, "Spend %s points");
        ModContent.CONTENT.translation(USE_TOKEN, "Use a skip token");
        ModContent.CONTENT.translation(NEXT_TIER, "Next: %s");
        ModContent.CONTENT.translation(TIER_PROGRESS, "%s of %s research done");
        ModContent.CONTENT.translation(TIER_MATERIAL, "Material: %s");
        ModContent.CONTENT.translation(TIER_NEXT_NODE, "Up next: %s");
        ModContent.CONTENT.translation(ALL_DONE, "All research is complete");
        ModContent.CONTENT.translation(WAITING, "Reading research...");
        for (TaskType type : TaskType.values()) {
            ModContent.CONTENT.translation(task(type), switch (type) {
                case STORE_KINDS -> "Store %s kinds in one network";
                case STORE_ITEMS -> "Store %s items in one network";
                case CELL_FILL -> "Fill a cell to %s%%";
                case MACHINE_RUNS -> "Finish %s machine runs";
                case REFINE_AMOUNT -> "Refine %s items";
                case PROCESSORS_MADE -> "Make %s processors";
                case DRIVES_RUNNING -> "Run %s drives in one network";
                case PORTS_THROUGHPUT -> "Move %s items through ports";
            });
        }
        ModContent.CONTENT.translation(key("filter"), "%s (%s)");
    }

    private ResearchTexts() {
    }

    /** The line of a task of {@code type}, taking the target. */
    public static String task(TaskType type) {
        return key("task." + type.getSerializedName());
    }

    /** A task line with its filter (an item, tag or machine) appended. */
    public static String filter() {
        return key("filter");
    }

    /** Loads the class so its lines are declared. */
    static void init() {
    }

    private static String key(String name) {
        return "gui." + MaxtechStorage.MOD_ID + ".research." + name;
    }
}
