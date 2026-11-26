package dev.silencebound.maxtech.storage.display.task;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.registry.ModContent;

/** The task board's lang keys and their English (M7C item 15). */
public final class TaskTexts {
    private static final String SCREEN = "gui." + MaxtechStorage.MOD_ID + ".task.";

    /** On the screen. */
    public static final String DEFAULT_TITLE = SCREEN + "default_title";
    public static final String DONE_COUNT = SCREEN + "done_count";
    public static final String EMPTY = SCREEN + "empty";
    public static final String NEEDS_CONTROLLER = SCREEN + "needs_controller";
    public static final String NEEDS_OWNER = SCREEN + "needs_owner";
    public static final String AUTO_PROGRESS = SCREEN + "auto_progress";
    public static final String DEVICES_PROGRESS = SCREEN + "devices_progress";
    public static final String RESEARCH_OPEN = SCREEN + "research_open";
    public static final String LOGIC_OPEN = SCREEN + "logic_open";
    public static final String DONE_AUTO = SCREEN + "done_auto";
    public static final String DONE_LOGIC = SCREEN + "done_logic";
    /** The editor. */
    public static final String EDITOR_TITLE = SCREEN + "editor_title";
    public static final String EDIT_TASKS = SCREEN + "edit_tasks";
    public static final String BOARD = SCREEN + "board";
    public static final String BOARD_HINT = SCREEN + "board_hint";
    public static final String SCOPE_TEAM = SCREEN + "scope_team";
    public static final String SCOPE_NETWORK = SCREEN + "scope_network";
    public static final String SCOPE_TIP = SCREEN + "scope_tip";
    public static final String NEW_TASK = SCREEN + "new_task";
    public static final String NAME = SCREEN + "name";
    public static final String DONE_WHEN = SCREEN + "done_when";
    public static final String TARGET_ITEM = SCREEN + "target_item";
    public static final String TARGET_LABEL = SCREEN + "target_label";
    public static final String TARGET_RESEARCH = SCREEN + "target_research";
    public static final String AMOUNT = SCREEN + "amount";
    public static final String SAVE = SCREEN + "save";
    public static final String REMOVE = SCREEN + "remove";
    public static final String ADD = SCREEN + "add";
    public static final String HINT = SCREEN + "hint";
    public static final String DONE_BY = SCREEN + "done_by";
    /** Replies to an edit. */
    public static final String SAVED = SCREEN + "saved";
    public static final String FULL = SCREEN + "full";
    public static final String TOO_MANY_BOARDS = SCREEN + "too_many_boards";
    public static final String EMPTY_NAME = SCREEN + "empty_name";
    public static final String NO_SUCH_TASK = SCREEN + "no_such_task";
    public static final String NO_ITEM = SCREEN + "no_item";
    public static final String NO_LABEL = SCREEN + "no_label";
    public static final String NO_RESEARCH = SCREEN + "no_research";
    public static final String OFFLINE = SCREEN + "offline";

    private TaskTexts() {
    }

    /** Queues the English texts; during mod construction. */
    public static void init() {
        add(DEFAULT_TITLE, "Task board");
        add(DONE_COUNT, "%s/%s done");
        add(EMPTY, "No tasks yet: sneak and right-click to edit");
        add(NEEDS_CONTROLLER, "A network board needs one network controller");
        add(NEEDS_OWNER, "A team board needs a network someone owns; use a network board");
        add(AUTO_PROGRESS, "auto %s/%s");
        add(DEVICES_PROGRESS, "%s/%s devices");
        add(RESEARCH_OPEN, "research");
        add(LOGIC_OPEN, "logic");
        add(DONE_AUTO, "auto");
        add(DONE_LOGIC, "logic %s");
        add(EDITOR_TITLE, "Task Board");
        add(EDIT_TASKS, "Edit tasks");
        add(BOARD, "Board");
        add(BOARD_HINT, "Board name");
        add(SCOPE_TEAM, "Team");
        add(SCOPE_NETWORK, "Network");
        add(SCOPE_TIP, "Team: every network of your team with this board name shares it. Network: only this"
                + " network's screens and logic do.");
        add(NEW_TASK, "+ New task");
        add(NAME, "Name");
        add(DONE_WHEN, "Done when");
        add(TARGET_ITEM, "Item id, e.g. minecraft:iron_ingot");
        add(TARGET_LABEL, "Device label");
        add(TARGET_RESEARCH, "Research node id");
        add(AMOUNT, "Amount");
        add(SAVE, "Save");
        add(REMOVE, "Remove");
        add(ADD, "Add");
        add(HINT, "Right-click a line on the screen to tick it; sneak and right-click for the settings.");
        add(DONE_BY, "Done by %s");
        add(SAVED, "Saved");
        add(FULL, "The board holds at most %s tasks");
        add(TOO_MANY_BOARDS, "The server has too many task boards");
        add(EMPTY_NAME, "Give the task a name");
        add(NO_SUCH_TASK, "That task is gone");
        add(NO_ITEM, "No such item");
        add(NO_LABEL, "Enter a device label");
        add(NO_RESEARCH, "No such research node");
        add(OFFLINE, "The screen is in no network");
        for (TaskGoal.Kind kind : TaskGoal.Kind.values()) {
            add(kind.translationKey(), switch (kind) {
                case HAND -> "By hand";
                case ITEM_COUNT -> "Items stored";
                case CRAFTED -> "Items crafted";
                case LABEL_COUNT -> "Labelled devices";
                case RESEARCH -> "Research step";
                case LOGIC -> "By logic";
            });
        }
    }

    private static void add(String key, String english) {
        ModContent.CONTENT.translation(key, english);
    }
}
