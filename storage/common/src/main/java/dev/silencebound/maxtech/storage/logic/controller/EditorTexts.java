package dev.silencebound.maxtech.storage.logic.controller;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.registry.ModContent;

/** The node editor's lines (M7L area F): its screen, the controller screen's edit buttons and save refusals. */
public final class EditorTexts {
    public static final String TITLE = key("title", "Logic Editor");
    public static final String EDIT = key("edit", "Edit");
    public static final String NEW_GRAPH = key("new_graph", "New graph");
    public static final String NAME_HINT = key("name_hint", "Graph name");
    public static final String SAVE = key("save", "Save");
    public static final String UNDO = key("undo", "Undo");
    public static final String REDO = key("redo", "Redo");
    public static final String DELETE = key("delete", "Delete");
    public static final String BACK = key("back", "Back");
    public static final String LOADING = key("loading", "Loading the graph...");
    public static final String READ_ONLY = key("read_only", "Being edited by %s — read only");
    public static final String READ_ONLY_PLAIN = key("read_only_plain", "Read only");
    public static final String UNSAVED = key("unsaved", "Not saved yet");
    public static final String SAVED = key("saved", "Saved and running");
    public static final String SAVED_PAUSED = key("saved_paused", "Saved but paused: fix the red notes to run it");
    public static final String LEAVE_UNSAVED = key("leave_unsaved",
            "Unsaved changes: press Esc again to leave without saving");
    public static final String PROBLEMS = key("problems", "%s problems to fix before it runs");
    public static final String PALETTE_PICK = key("palette_pick", "Pick a node for the wire");
    public static final String PALETTE_COMMON = key("palette_common", "Nodes: Common");
    public static final String PALETTE_ALL = key("palette_all", "Nodes: All");
    public static final String SHOW_ALL = key("show_all", "Click to show every node");
    public static final String SHOW_COMMON = key("show_common", "Click to show only the common nodes");
    public static final String TAKES_NO = key("takes_no", "takes no %s");
    public static final String GIVES_NO = key("gives_no", "gives no %s");
    public static final String SETTINGS = key("settings", "Settings");
    public static final String NO_SELECTION = key("no_selection", "Click a node to change its settings");
    public static final String NO_SETTINGS = key("no_settings", "This node has no settings");
    public static final String FROM_WIRE = key("from_wire", "Set by the wire");
    public static final String FULL = key("full", "A graph holds at most %s nodes");
    public static final String SAME_NODE = key("same_node", "A node can't feed itself");
    public static final String WHY_NONE = key("why_none", "Not run yet since the editor opened");
    public static final String WHY_OK = key("why_ok", "Last run went through: %s");
    public static final String WHY_LINE = key("why_line", "Why: %s");
    public static final String WHY_MORE = key("why_more", "and %s more");
    public static final String PORT = key("port", "%s (%s)");
    public static final String VALUE_YES = key("yes", "yes");
    public static final String VALUE_NO = key("no", "no");
    public static final String FIRED = key("fired", "fired in the last run");
    public static final String NOT_FIRED = key("not_fired", "did not fire in the last run");
    public static final String HELD_ITEM = key("held_item", "Use held item");
    public static final String SEARCH_HINT = key("search_hint", "Search items, or #tag");
    public static final String SEARCH_FLUID_HINT = key("search_fluid_hint", "Search fluids, or #tag");
    public static final String SEARCH_TAG_HINT = key("search_tag_hint", "Search item tags");
    /** The notify node's message box: it takes display markup. */
    public static final String MARKUP_HINT = key("markup_hint", "Message; markup such as <red> or [item:id] works");
    public static final String MARKUP_TIP = key("markup_tip",
            "The message takes display panel markup: <red>...</red> and the other colours, <b>, <i>, [item:id]"
                    + " icons. See the guide's Display panels page.");
    /** The write-to-display node's text box: markup, and the inputs as {a} and {b}. */
    public static final String DISPLAY_TEXT_HINT = key("display_text_hint", "Text; {a} and {b} show the inputs");
    public static final String DISPLAY_TEXT_TIP = key("display_text_tip",
            "{a} and {b} show the numbers wired into A and B. The text takes display panel markup: <red>...</red>,"
                    + " <b>, <i>, <big>, [item:id] icons and {count:item} placeholders. See the guide's Display"
                    + " panels page.");
    public static final String NO_ITEM = key("no_item", "No item yet");
    public static final String NO_FLUID = key("no_fluid", "No fluid yet");
    public static final String NO_TAG = key("no_tag", "No tag yet");
    public static final String LABEL_HINT = key("label_hint", "Type or pick a label");
    public static final String WHOLE_NETWORK = key("whole_network", "Empty: the whole network");
    public static final String HELP = key("help",
            "Drag from a port to wire; drop on empty space to pick a node. Del deletes, Ctrl+Z undoes.");

    // Debugging (M7L2 chunk 8): pinned values, simulate mode and revert in the editor.
    public static final String PIN = key("pin", "Pin value to the controller");
    public static final String UNPIN = key("unpin", "Unpin value");
    public static final String PIN_TIP = key("pin_tip",
            "Pinned values show in this rule's detail on the controller screen; save to keep the change");
    public static final String PINS_FULL = key("pins_full", "A rule pins at most %s values");
    public static final String PINNED_MARK = key("pinned_mark", "Pinned to the controller screen");
    public static final String SIMULATING = key("simulating", "Simulating: actions are only logged, nothing changes");
    public static final String REVERT_ASK = key("revert_ask",
            "Click Revert again to put back the last working version; unsaved changes are lost");

    // Multiplayer (M7L2 chunk 9): a second editor's read-only view and the change history.
    public static final String EDITOR_LEFT = key("editor_left", "%s closed the editor — you can edit it now");
    public static final String EDITOR_LEFT_PLAIN = key("editor_left_plain",
            "Nobody is editing this graph now — you can edit it");
    public static final String EDIT_NOW = key("edit_now", "Edit now");
    public static final String EDIT_NOW_TIP = key("edit_now_tip",
            "Open this graph again as its editor; someone who got there first keeps it");
    public static final String HISTORY = key("history", "History");
    public static final String HISTORY_TIP = key("history_tip",
            "Who changed this rule, when, and what changed; the newest first");
    public static final String HISTORY_CLOSE = key("history_close", "Settings");
    /** The top bar's menu for the buttons that do not fit on a narrow bar. */
    public static final String MORE = key("more", "More ▾");
    public static final String MORE_TIP = key("more_tip", "History, Simulate and Revert");
    public static final String HISTORY_NONE = key("history_none", "No changes recorded yet");
    /** A history line's heading: the in-game day, the server's date and time, the player. */
    public static final String HISTORY_HEADER = key("history_header", "Day %s, %s · %s");
    /** A history line's heading without a clock time (an entry from a command or an old save). */
    public static final String HISTORY_HEADER_DAY = key("history_header_day", "Day %s · %s");
    public static final String HISTORY_SOMEONE = key("history_someone", "someone");
    public static final String HISTORY_JOIN = key("history_join", ", ");

    // Save refusals: a save that changed nothing.
    public static final String REFUSED_NOT_EDITOR = key("refused.not_editor", "Someone else is editing this graph");
    public static final String REFUSED_TOO_FAST = key("refused.too_fast", "Saved a moment ago; try again");
    public static final String REFUSED_UNREADABLE = key("refused.unreadable",
            "The graph could not be read; nothing was saved");
    public static final String REFUSED_NO_SLOT = key("refused.no_slot", "This graph slot no longer exists");
    /** A card edit while another player has the rule open in the editor; the argument is their name. */
    public static final String REFUSED_LOCKED = key("refused.locked", "%s is editing this rule; try again later");
    /** An editor save of a copy whose template changed after the editor opened it (M7L2 row 19). */
    public static final String REFUSED_COPY_CHANGED = key("refused.copy_changed",
            "The rule this copies was changed while you had this copy open, so nothing was saved; close the editor and open this copy again");
    /** An editor save that changes the graph of a copy with values (M7L2 row 19). */
    public static final String REFUSED_COPY_GRAPH = key("refused.copy_graph",
            "This rule is a copy with its own values: only its name and pins save here; edit the rule it copies, or detach it first");
    /** A change to a copy with values other than its values or name (M7L2 row 19); the argument is its template. */
    public static final String REFUSED_COPY = key("refused.copy",
            "This rule is a copy of %s with its own values: change only its values here, edit %s, or detach it first");

    private EditorTexts() {
    }

    /** Loads the class during mod construction so its lang lines are declared. */
    static void init() {
    }

    private static String key(String path, String english) {
        String key = "gui." + MaxtechStorage.MOD_ID + ".logic_editor." + path;
        ModContent.CONTENT.translation(key, english);
        return key;
    }
}
