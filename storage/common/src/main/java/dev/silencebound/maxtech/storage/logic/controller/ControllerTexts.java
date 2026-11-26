package dev.silencebound.maxtech.storage.logic.controller;

import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.logic.template.BuiltInTemplates;
import dev.silencebound.maxtech.storage.logic.template.LogicTemplate;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.network.chat.Component;

/** The Logic Controller's lines: Jade and the scanner, and its screen. */
public final class ControllerTexts {
    public static final String GRAPHS = key("graphs");
    public static final String CHECKS = key("checks");
    public static final String ACTION_POINTS = key("action_points");
    public static final String WAITING = key("waiting");
    public static final String NO_GRAPHS = key("no_graphs");
    public static final String ON = key("on");
    public static final String OFF = key("off");
    /** The title of a logic notice's toast (area E). */
    public static final String TOAST = key("toast");
    /** A logic notice in team chat: "[Logic] %s" (area E). */
    public static final String CHAT = key("chat");
    /** Sharing graphs as text (M7L2): the buttons, their tips and the line under the list. */
    public static final String EXPORT = key("share.export");
    public static final String EXPORT_TIP = key("share.export_tip");
    public static final String IMPORT = key("share.import");
    public static final String IMPORT_TIP = key("share.import_tip");
    public static final String SHARE_COPIED = key("share.copied");
    public static final String SHARE_COPIED_LONG = key("share.copied_long");
    public static final String SHARE_EXPORT_FAILED = key("share.export_failed");
    public static final String SHARE_NO_GRAPH = key("share.no_graph");
    public static final String SHARE_TOO_FAST = key("share.too_fast");
    public static final String SHARE_NO_ROW = key("share.no_row");
    public static final String SHARE_IMPORTED = key("share.imported");
    public static final String SHARE_CHECKING = key("share.checking");
    /** Copy and paste, logic cards and templates (M7L2): the buttons, their tips and the lines under the list. */
    public static final String COPY = key("share.copy");
    public static final String COPY_TIP = key("share.copy_tip");
    public static final String PASTE = key("share.paste");
    public static final String PASTE_TIP = key("share.paste_tip");
    public static final String TO_CARD = key("share.to_card");
    public static final String TO_CARD_TIP = key("share.to_card_tip");
    public static final String FROM_CARD = key("share.from_card");
    public static final String FROM_CARD_TIP = key("share.from_card_tip");
    public static final String TEMPLATES = key("share.templates");
    public static final String TEMPLATES_TIP = key("share.templates_tip");
    public static final String SHARE_COPIED_GAME = key("share.copied_game");
    public static final String SHARE_PASTE_EMPTY = key("share.paste_empty");
    public static final String SHARE_NO_BLANK_CARD = key("share.no_blank_card");
    public static final String SHARE_CARD_WRITTEN = key("share.card_written");
    public static final String SHARE_NO_CARD = key("share.no_card");
    public static final String SHARE_CARD_LOADED = key("share.card_loaded");
    public static final String SHARE_NO_TEMPLATE = key("share.no_template");
    /** A template parameter's value the server cannot read (M7L2 row 19): the parameter's name and the text. */
    public static final String SHARE_BAD_VALUE = key("share.bad_value");
    /** The template screen's heading over the parameter form: the template's name. */
    public static final String TEMPLATE_VALUES = key("template.values");
    public static final String TEMPLATE_VALUES_HINT = key("template.values_hint");
    public static final String SHARE_TEMPLATE_ADDED = key("share.template_added");
    /** The template list. */
    public static final String TEMPLATE_TITLE = key("template.title");
    public static final String TEMPLATE_SEARCH = key("template.search");
    public static final String TEMPLATE_CATEGORY = key("template.category");
    public static final String TEMPLATE_ALL = key("template.all");
    public static final String TEMPLATE_ADD = key("template.add");
    public static final String TEMPLATE_BACK = key("template.back");
    public static final String TEMPLATE_NODES = key("template.nodes");
    public static final String TEMPLATE_NONE = key("template.none");
    public static final String TEMPLATE_LOADING = key("template.loading");

    static {
        ModContent.CONTENT.translation(COPY, "Copy");
        ModContent.CONTENT.translation(COPY_TIP,
                "Copy the selected graph inside the game, to paste it on another controller");
        ModContent.CONTENT.translation(PASTE, "Paste");
        ModContent.CONTENT.translation(PASTE_TIP, "Add the graph you copied in the game; it starts switched off");
        ModContent.CONTENT.translation(TO_CARD, "To card");
        ModContent.CONTENT.translation(TO_CARD_TIP,
                "Write the selected graph onto a blank logic card from your inventory");
        ModContent.CONTENT.translation(FROM_CARD, "From card");
        ModContent.CONTENT.translation(FROM_CARD_TIP,
                "Add the graph on the logic card in your hand (or the first in your inventory); it starts switched off");
        ModContent.CONTENT.translation(TEMPLATES, "Templates");
        ModContent.CONTENT.translation(TEMPLATES_TIP, "Pick a ready-made graph to start from");
        ModContent.CONTENT.translation(SHARE_COPIED_GAME, "Copied \"%s\"; paste it on another controller");
        ModContent.CONTENT.translation(SHARE_PASTE_EMPTY, "Nothing copied yet: use Copy on a controller first");
        ModContent.CONTENT.translation(SHARE_NO_BLANK_CARD, "You need a blank logic card in your inventory");
        ModContent.CONTENT.translation(SHARE_CARD_WRITTEN, "Wrote \"%s\" onto a logic card");
        ModContent.CONTENT.translation(SHARE_NO_CARD, "You have no written logic card");
        ModContent.CONTENT.translation(SHARE_CARD_LOADED,
                "Added \"%s\" from the card; it is off until you switch it on");
        ModContent.CONTENT.translation(SHARE_NO_TEMPLATE, "That template is no longer loaded");
        ModContent.CONTENT.translation(SHARE_BAD_VALUE, "\"%2$s\" is not a value for %1$s");
        ModContent.CONTENT.translation(TEMPLATE_VALUES, "Values for %s");
        ModContent.CONTENT.translation(TEMPLATE_VALUES_HINT,
                "Numbers as digits, items by id (minecraft:stick) or #tag, labels as text; Add again to add the rule");
        ModContent.CONTENT.translation(SHARE_TEMPLATE_ADDED, "Added \"%s\"; it is off until you switch it on");
        ModContent.CONTENT.translation(TEMPLATE_TITLE, "Graph templates");
        ModContent.CONTENT.translation(TEMPLATE_SEARCH, "Search");
        ModContent.CONTENT.translation(TEMPLATE_CATEGORY, "Category: %s");
        ModContent.CONTENT.translation(TEMPLATE_ALL, "All");
        ModContent.CONTENT.translation(TEMPLATE_ADD, "Add");
        ModContent.CONTENT.translation(TEMPLATE_BACK, "Back");
        ModContent.CONTENT.translation(TEMPLATE_NODES, "%s nodes");
        ModContent.CONTENT.translation(TEMPLATE_NONE, "No templates match");
        ModContent.CONTENT.translation(TEMPLATE_LOADING, "Loading...");
        BuiltInTemplates.CATEGORIES.forEach(
                (category, english) -> ModContent.CONTENT.translation(LogicTemplate.categoryKey(category), english));
    }

    /** The controller screen (M7L2 chunk 10): readouts, tabs, the rule list and detail, and the LOG tab. */
    public static final String LCD_RULES = key("lcd.rules");
    public static final String LCD_RULES_VALUE = key("lcd.rules_value");
    public static final String LCD_TEAM = key("lcd.team");
    public static final String LCD_TEAM_TIP = key("lcd.team_tip");
    public static final String LCD_OWN = key("lcd.own");
    public static final String LCD_OWN_TIP = key("lcd.own_tip");
    public static final String LCD_OWN_VALUE = key("lcd.own_value");
    public static final String LCD_ACTIONS = key("lcd.actions");
    public static final String LCD_ACTIONS_TIP = key("lcd.actions_tip");
    public static final String TAB_RULES = key("tab.rules");
    public static final String TAB_LOG = key("tab.log");
    public static final String SUBTITLE_LABEL = key("subtitle.label");
    public static final String SEARCH = key("search");
    public static final String ALL_RULES = key("all_rules");
    public static final String RULE_COUNT = key("rule_count");
    public static final String RULE_COUNT_FILTERED = key("rule_count_filtered");
    public static final String NEW_RULE = key("new_rule");
    public static final String NEW_RULE_TIP = key("new_rule_tip");
    public static final String EDIT_TIP = key("edit_tip");
    public static final String TOGGLE_TIP = key("toggle_tip");
    public static final String DETAIL_COST = key("detail.cost");
    public static final String DETAIL_COST_VALUE = key("detail.cost_value");
    /** A cost in checks and action points: "%s / run" around {@link #costParts}. */
    public static final String DETAIL_COST_BOTH = key("detail.cost_both");
    public static final String COST_SUM = key("cost.sum");
    public static final String COST_CHECKS = key("cost.checks");
    public static final String COST_POINTS = key("cost.points");
    public static final String DETAIL_LAST_RUN = key("detail.last_run");
    public static final String DETAIL_RESULT = key("detail.result");
    public static final String DETAIL_NEVER = key("detail.never");
    public static final String DETAIL_ACTIONS = key("detail.actions");
    public static final String DETAIL_NOTHING = key("detail.nothing");
    public static final String DETAIL_WHY = key("detail.why");
    public static final String DETAIL_ERRORS = key("detail.errors");
    public static final String DETAIL_WAITING = key("detail.waiting");
    public static final String DETAIL_WAITING_ACTIONS = key("detail.waiting_actions");
    public static final String DETAIL_IDLE = key("detail.idle");
    public static final String DETAIL_LOADING = key("detail.loading");
    /** The NODES view (M7L2 chunk 11): the rule's graph as a read-only diagram, with the run details under it. */
    public static final String NODES_EMPTY = key("nodes.empty");
    public static final String NODES_OPEN_TIP = key("nodes.open_tip");
    public static final String NODES_DRAG_TIP = key("nodes.drag_tip");
    public static final String NODES_MORE = key("nodes.more");
    /** The detail's CARD / NODES switch (M7L2 chunk 11) and the card's hover lines. */
    public static final String VIEW_CARD = key("view.card");
    public static final String VIEW_NODES = key("view.nodes");
    public static final String VIEW_HISTORY = key("view.history");
    /** A rule drawn in a guide page ({@code <LogicGraph>}, M7G 6a): its buttons, hint and copy toast. */
    public static final String GUIDE_COPY = key("guide.copy");
    public static final String GUIDE_AS_CARD = key("guide.as_card");
    public static final String GUIDE_AS_NODES = key("guide.as_nodes");
    public static final String GUIDE_PASTE_HINT = key("guide.paste_hint");
    public static final String GUIDE_COPIED = key("guide.copied");
    public static final String GUIDE_COPIED_HINT = key("guide.copied_hint");
    public static final String CARD_NODE = key("card.node");
    public static final String CARD_NODES = key("card.nodes");
    /** Editing on the card (M7L2 chunk 11): blanks, the "+" buttons, the type picker and the setting popups. */
    public static final String CARD_PICK_TRIGGER = key("card.pick_trigger");
    public static final String CARD_PICK_ACTION = key("card.pick_action");
    public static final String CARD_ADD_TRIGGER = key("card.add_trigger");
    public static final String CARD_ADD_CONDITION = key("card.add_condition");
    public static final String CARD_ADD_ACTION = key("card.add_action");
    public static final String CARD_ADD_TIP = key("card.add_tip");
    public static final String CARD_NEED_ACTION_TIP = key("card.need_action_tip");
    public static final String CARD_FULL_TIP = key("card.full_tip");
    public static final String CARD_TITLE_TRIGGER = key("card.title_trigger");
    public static final String CARD_TITLE_CONDITION = key("card.title_condition");
    public static final String CARD_TITLE_ACTION = key("card.title_action");
    public static final String CARD_OPEN_EDITOR = key("card.open_editor");
    public static final String CARD_OPEN_EDITOR_TIP = key("card.open_editor_tip");
    public static final String CARD_CUSTOM_TIP = key("card.custom_tip");
    public static final String CARD_REMOVE_TIP = key("card.remove_tip");
    public static final String CARD_CYCLE_TIP = key("card.cycle_tip");
    public static final String CARD_CHANGE_TIP = key("card.change_tip");
    public static final String CARD_COMMON = key("card.common");
    public static final String CARD_ALL = key("card.all");
    public static final String CARD_OK = key("card.ok");
    public static final String CARD_CANCEL = key("card.cancel");
    public static final String CARD_HELD = key("card.held");
    public static final String AGO = key("ago");
    public static final String KIND_ACTIONS = key("kind.actions");
    public static final String KIND_SKIPPED = key("kind.skipped");
    public static final String KIND_WARNINGS = key("kind.warnings");
    public static final String KIND_ERRORS = key("kind.errors");
    public static final String LOG_EMPTY = key("log.empty");
    public static final String LOG_NETWORK = key("log.network");
    public static final String LOG_NOW = key("log.now");
    public static final String LOG_WHY = key("log.why");
    public static final String LOG_NO_CHAIN = key("log.no_chain");
    public static final String LOG_PAUSED = key("log.paused");
    public static final String LOG_ACTIONS_MORE = key("log.actions_more");
    public static final String LOG_MORE = key("log.more");
    public static final String SHOW_RULE = key("log.show_rule");
    /** A rule's last-run actions in the log, in plain words; arguments as in {@link ControllerLog.Line}. */
    public static final String ACTION_SWITCH_ON = key("action.switch_on");
    public static final String ACTION_SWITCH_OFF = key("action.switch_off");
    public static final String ACTION_PRIORITY = key("action.priority");
    public static final String ACTION_REDSTONE = key("action.redstone");
    public static final String ACTION_REDSTONE_SELF = key("action.redstone_self");
    /** A network redstone channel write (M7L2); the first argument is the colour. */
    public static final String ACTION_CHANNEL = key("action.channel");
    public static final String ACTION_CRAFT = key("action.craft");
    public static final String ACTION_MOVE = key("action.move");
    public static final String ACTION_MOVE_IN = key("action.move_in");
    public static final String ACTION_MOVE_OUT = key("action.move_out");
    public static final String ACTION_NOTIFY = key("action.notify");
    public static final String ACTION_CANCEL = key("action.cancel");
    public static final String ACTION_CANCEL_OWN = key("action.cancel_own");
    public static final String ACTION_DISPLAY = key("action.display");
    public static final String ACTION_FILTER = key("action.filter");
    public static final String ACTION_VARIABLE = key("action.variable");
    public static final String ACTION_RUN_RULE = key("action.run_rule");
    public static final String ACTION_STOCK_ON = key("action.stock_on");
    public static final String ACTION_STOCK_OFF = key("action.stock_off");
    public static final String ACTION_STOCK_ALL_ON = key("action.stock_all_on");
    public static final String ACTION_STOCK_ALL_OFF = key("action.stock_all_off");
    public static final String ACTION_STOCK_KEEP = key("action.stock_keep");
    public static final String ACTION_STOCK_RESTART = key("action.stock_restart");
    public static final String ACTION_STOCK_BATCH = key("action.stock_batch");
    /** A machine pool send (M7L2): the pool's label, the amount, the member's number ({@code #2}) and the item. */
    public static final String ACTION_POOL = key("action.pool");
    public static final String ACTION_POWER_PRIORITY = key("action.power_priority");
    public static final String ACTION_TASK_DONE = key("action.task_done");
    public static final String ACTION_TASK_OPEN = key("action.task_open");
    // Debugging (M7L2 chunk 8): simulate mode, revert, pinned values.
    public static final String SIMULATE = key("simulate");
    public static final String SIMULATE_TIP = key("simulate_tip");
    public static final String SIMULATE_STOP = key("simulate_stop");
    public static final String CHIP_SIMULATE = key("chip.simulate");
    public static final String REVERT = key("revert");
    public static final String REVERT_TIP = key("revert_tip");
    public static final String REVERT_NONE_TIP = key("revert_none_tip");
    public static final String REVERT_CONFIRM = key("revert_confirm");
    public static final String REVERT_CONFIRM_TIP = key("revert_confirm_tip");
    public static final String REVERT_EDITING = key("revert_editing");
    // Rules with parameters (M7L2 row 19).
    public static final String COPY_WITH_VALUES = key("copy_with_values");
    public static final String COPY_WITH_VALUES_TIP = key("copy_with_values_tip");
    public static final String COPY_NO_PARAMS_TIP = key("copy_no_params_tip");
    public static final String DETACH = key("detach");
    public static final String DETACH_TIP = key("detach_tip");
    public static final String CHIP_COPY = key("chip.copy");
    public static final String DETAIL_PINNED = key("detail.pinned");
    public static final String PIN_UNKNOWN = key("pin.unknown");
    /** Each action line's "would" form, for actions a rule in simulate mode only recorded. */
    private static final Map<String, String> WOULD = new HashMap<>();

    static {
        ModContent.CONTENT.translation(LCD_RULES, "RULES");
        ModContent.CONTENT.translation(LCD_RULES_VALUE, "%s ON / %s");
        ModContent.CONTENT.translation(LCD_TEAM, "CHECKS /S");
        ModContent.CONTENT.translation(LCD_TEAM_TIP,
                "Checks your team used this second, of its budget per second: every node a rule runs costs checks");
        ModContent.CONTENT.translation(LCD_ACTIONS, "ACTIONS /S");
        ModContent.CONTENT.translation(LCD_ACTIONS_TIP,
                "Action points your team used this second, of its budget per second: every action a rule sends costs"
                        + " points, about one per microsecond of server time");
        ModContent.CONTENT.translation(LCD_OWN, "HERE /S");
        ModContent.CONTENT.translation(LCD_OWN_VALUE, "%s + %s");
        ModContent.CONTENT.translation(LCD_OWN_TIP, "Checks + action points this controller used in the last second");
        ModContent.CONTENT.translation(TAB_RULES, "RULES");
        ModContent.CONTENT.translation(TAB_LOG, "LOG");
        ModContent.CONTENT.translation(SUBTITLE_LABEL, "LABEL %s");
        ModContent.CONTENT.translation(SEARCH, "Search rules");
        ModContent.CONTENT.translation(ALL_RULES, "ALL RULES");
        ModContent.CONTENT.translation(RULE_COUNT, "%s RULES");
        ModContent.CONTENT.translation(RULE_COUNT + Plural.ONE, "%s RULE");
        ModContent.CONTENT.translation(RULE_COUNT_FILTERED, "%s OF %s");
        ModContent.CONTENT.translation(NEW_RULE, "+ NEW RULE");
        ModContent.CONTENT.translation(NEW_RULE_TIP,
                "In the card view, start a rule as a card with blanks to fill; otherwise open the node editor on it");
        ModContent.CONTENT.translation(EDIT_TIP, "Open this rule in the node editor");
        ModContent.CONTENT.translation(TOGGLE_TIP, "Switch this rule on or off");
        ModContent.CONTENT.translation(DETAIL_COST, "COST");
        ModContent.CONTENT.translation(DETAIL_COST_VALUE, "%s checks / run");
        ModContent.CONTENT.translation(DETAIL_COST_VALUE + Plural.ONE, "%s check / run");
        ModContent.CONTENT.translation(DETAIL_COST_BOTH, "%s / run");
        ModContent.CONTENT.translation(COST_SUM, "%s + %s");
        ModContent.CONTENT.translation(COST_CHECKS, "%s checks");
        ModContent.CONTENT.translation(COST_CHECKS + Plural.ONE, "%s check");
        ModContent.CONTENT.translation(COST_POINTS, "%s points");
        ModContent.CONTENT.translation(COST_POINTS + Plural.ONE, "%s point");
        ModContent.CONTENT.translation(DETAIL_LAST_RUN, "LAST RUN");
        ModContent.CONTENT.translation(DETAIL_RESULT, "RESULT");
        ModContent.CONTENT.translation(DETAIL_NEVER, "not since it was saved or loaded");
        ModContent.CONTENT.translation(DETAIL_ACTIONS, "asked for %s actions");
        ModContent.CONTENT.translation(DETAIL_ACTIONS + Plural.ONE, "asked for %s action");
        ModContent.CONTENT.translation(DETAIL_NOTHING, "nothing to do");
        ModContent.CONTENT.translation(DETAIL_WHY, "LAST RUN, STEP BY STEP");
        ModContent.CONTENT.translation(DETAIL_ERRORS, "WHY IT IS PAUSED");
        ModContent.CONTENT.translation(DETAIL_WAITING, "Waiting: your team's checks are used up for this second");
        ModContent.CONTENT.translation(DETAIL_WAITING_ACTIONS,
                "Waiting: your team's action points are used up for this second");
        ModContent.CONTENT.translation(DETAIL_IDLE, "Over this server's rule limit per controller: it does not run");
        ModContent.CONTENT.translation(DETAIL_LOADING, "Loading...");
        ModContent.CONTENT.translation(NODES_EMPTY, "This rule has no nodes yet");
        ModContent.CONTENT.translation(NODES_OPEN_TIP, "Click to open it in the node editor");
        ModContent.CONTENT.translation(NODES_DRAG_TIP, "Drag to move the view");
        ModContent.CONTENT.translation(NODES_MORE, "+%s more");
        ModContent.CONTENT.translation(VIEW_CARD, "CARD");
        ModContent.CONTENT.translation(VIEW_NODES, "NODES");
        ModContent.CONTENT.translation(VIEW_HISTORY, "HISTORY");
        ModContent.CONTENT.translation(GUIDE_COPY, "COPY RULE");
        ModContent.CONTENT.translation(GUIDE_AS_CARD, "AS CARD");
        ModContent.CONTENT.translation(GUIDE_AS_NODES, "AS NODES");
        ModContent.CONTENT.translation(GUIDE_PASTE_HINT, "Paste it into your Logic Controller");
        ModContent.CONTENT.translation(GUIDE_COPIED, "Rule copied");
        ModContent.CONTENT.translation(GUIDE_COPIED_HINT, "Paste it into a Logic Controller");

        ModContent.CONTENT.translation(CARD_NODE, "Node: %s");
        ModContent.CONTENT.translation(CARD_NODES, "Nodes: %s");
        ModContent.CONTENT.translation(CARD_PICK_TRIGGER, "pick a trigger…");
        ModContent.CONTENT.translation(CARD_PICK_ACTION, "pick an action…");
        ModContent.CONTENT.translation(CARD_ADD_TRIGGER, "+ TRIGGER");
        ModContent.CONTENT.translation(CARD_ADD_CONDITION, "+ CONDITION");
        ModContent.CONTENT.translation(CARD_ADD_ACTION, "+ ACTION");
        ModContent.CONTENT.translation(CARD_ADD_TIP, "Add a node to the rule; the card wires it the usual way");
        ModContent.CONTENT.translation(CARD_NEED_ACTION_TIP, "Add an action first; a condition decides when it runs");
        ModContent.CONTENT.translation(CARD_FULL_TIP, "A rule holds at most %s nodes");
        ModContent.CONTENT.translation(CARD_TITLE_TRIGGER, "Pick a trigger");
        ModContent.CONTENT.translation(CARD_TITLE_CONDITION, "Pick a condition");
        ModContent.CONTENT.translation(CARD_TITLE_ACTION, "Pick an action");
        ModContent.CONTENT.translation(CARD_OPEN_EDITOR, "open in editor");
        ModContent.CONTENT.translation(CARD_OPEN_EDITOR_TIP, "The card can't change this part; the node editor can");
        ModContent.CONTENT.translation(CARD_CUSTOM_TIP,
                "This rule is wired in a way the card can't add to; open it in the node editor");
        ModContent.CONTENT.translation(CARD_REMOVE_TIP, "Remove this line from the rule");
        ModContent.CONTENT.translation(CARD_CYCLE_TIP, "%s: click for the next choice");
        ModContent.CONTENT.translation(CARD_CHANGE_TIP, "%s: click to change");
        ModContent.CONTENT.translation(CARD_COMMON, "Common");
        ModContent.CONTENT.translation(CARD_ALL, "All");
        ModContent.CONTENT.translation(CARD_OK, "OK");
        ModContent.CONTENT.translation(CARD_CANCEL, "Cancel");
        ModContent.CONTENT.translation(CARD_HELD, "Use the held item");
        ModContent.CONTENT.translation(AGO, "%s s ago");
        ModContent.CONTENT.translation(KIND_ACTIONS, "ACTIONS");
        ModContent.CONTENT.translation(KIND_SKIPPED, "SKIPPED");
        ModContent.CONTENT.translation(KIND_WARNINGS, "WARNINGS");
        ModContent.CONTENT.translation(KIND_ERRORS, "ERRORS");
        ModContent.CONTENT.translation(LOG_EMPTY, "Nothing logged yet");
        ModContent.CONTENT.translation(LOG_NETWORK, "network");
        ModContent.CONTENT.translation(LOG_NOW, "now");
        ModContent.CONTENT.translation(LOG_WHY, "WHY · %s");
        ModContent.CONTENT.translation(LOG_NO_CHAIN, "Nothing more is known about this line");
        ModContent.CONTENT.translation(LOG_PAUSED, "Paused: %s");
        ModContent.CONTENT.translation(LOG_ACTIONS_MORE, "%s (and %s more)");
        ModContent.CONTENT.translation(LOG_MORE, "and %1$s more lines");
        ModContent.CONTENT.translation(SHOW_RULE, "SHOW RULE");
        ModContent.CONTENT.translation(ACTION_SWITCH_ON, "Turn %1$s on");
        ModContent.CONTENT.translation(ACTION_SWITCH_OFF, "Turn %1$s off");
        ModContent.CONTENT.translation(ACTION_PRIORITY, "Set the priority of %1$s to %2$s");
        ModContent.CONTENT.translation(ACTION_REDSTONE, "Set the redstone of %1$s to %2$s");
        ModContent.CONTENT.translation(ACTION_REDSTONE_SELF, "Set the controller's redstone to %2$s");
        ModContent.CONTENT.translation(ACTION_CHANNEL, "Set channel %1$s to %2$s");
        ModContent.CONTENT.translation(ACTION_CRAFT, "Request %2$s %4$s");
        ModContent.CONTENT.translation(ACTION_MOVE, "Move up to %2$s %4$s from %1$s to %3$s");
        ModContent.CONTENT.translation(ACTION_MOVE_IN, "Move up to %2$s %4$s from %1$s into storage");
        ModContent.CONTENT.translation(ACTION_MOVE_OUT, "Move up to %2$s %4$s from storage to %3$s");
        ModContent.CONTENT.translation(ACTION_NOTIFY, "Notify: %1$s");
        ModContent.CONTENT.translation(ACTION_CANCEL, "Cancel the crafts of %4$s");
        ModContent.CONTENT.translation(ACTION_CANCEL_OWN, "Cancel this rule's crafts of %4$s");
        ModContent.CONTENT.translation(ACTION_DISPLAY, "Write to %1$s: %3$s");
        ModContent.CONTENT.translation(ACTION_FILTER, "Set the filter of %1$s");
        ModContent.CONTENT.translation(ACTION_VARIABLE, "Set the shared value %1$s to %2$s");
        ModContent.CONTENT.translation(ACTION_RUN_RULE, "Run the rule %1$s");
        ModContent.CONTENT.translation(ACTION_STOCK_ON, "Switch on the stock rule for %4$s in %1$s");
        ModContent.CONTENT.translation(ACTION_STOCK_OFF, "Switch off the stock rule for %4$s in %1$s");
        ModContent.CONTENT.translation(ACTION_STOCK_ALL_ON, "Switch on every stock rule in %1$s");
        ModContent.CONTENT.translation(ACTION_STOCK_ALL_OFF, "Switch off every stock rule in %1$s");
        ModContent.CONTENT.translation(ACTION_STOCK_KEEP, "Keep %2$s %4$s in %1$s");
        ModContent.CONTENT.translation(ACTION_STOCK_RESTART, "Restart %4$s in %1$s below %2$s");
        ModContent.CONTENT.translation(ACTION_STOCK_BATCH, "Craft %4$s in %1$s in batches of %2$s");
        ModContent.CONTENT.translation(ACTION_POOL, "Sent %2$s %4$s to %1$s %3$s");
        ModContent.CONTENT.translation(ACTION_POWER_PRIORITY, "Set the power priority of %1$s to %2$s");
        ModContent.CONTENT.translation(ACTION_TASK_DONE, "Tick task %1$s");
        ModContent.CONTENT.translation(ACTION_TASK_OPEN, "Open task %1$s again");
        ModContent.CONTENT.translation(SIMULATE, "Simulate");
        ModContent.CONTENT.translation(SIMULATE_TIP,
                "Run this rule without touching anything: its actions are only logged as \"Would ...\"");
        ModContent.CONTENT.translation(SIMULATE_STOP, "Act for real");
        ModContent.CONTENT.translation(CHIP_SIMULATE, "SIMULATE");
        ModContent.CONTENT.translation(REVERT, "Revert");
        ModContent.CONTENT.translation(REVERT_TIP,
                "Put back the last version that worked before the last save; the rule is switched off");
        ModContent.CONTENT.translation(REVERT_NONE_TIP, "No earlier working version is kept for this rule");
        ModContent.CONTENT.translation(REVERT_CONFIRM, "Sure? Revert");
        ModContent.CONTENT.translation(REVERT_CONFIRM_TIP,
                "Click again to put back the last working version; anything after it is replaced");
        ModContent.CONTENT.translation(REVERT_EDITING,
                "%s is editing this rule; ask them to close the editor first, or their changes would be lost");
        ModContent.CONTENT.translation(COPY_WITH_VALUES, "Copy with values");
        ModContent.CONTENT.translation(COPY_WITH_VALUES_TIP,
                "Add a copy of this rule with its own parameter values, switched off; editing this rule updates every copy");
        ModContent.CONTENT.translation(COPY_NO_PARAMS_TIP,
                "Only a rule with Parameter nodes can be copied with its own values");
        ModContent.CONTENT.translation(DETACH, "Detach");
        ModContent.CONTENT.translation(DETACH_TIP,
                "Make this copy a rule of its own; changes to the rule it copies no longer reach it");
        ModContent.CONTENT.translation(CHIP_COPY, "COPY");
        ModContent.CONTENT.translation(DETAIL_PINNED, "PINNED VALUES");
        ModContent.CONTENT.translation(PIN_UNKNOWN, "not known yet");
        would(ACTION_SWITCH_ON, "Would turn %1$s on");
        would(ACTION_SWITCH_OFF, "Would turn %1$s off");
        would(ACTION_PRIORITY, "Would set the priority of %1$s to %2$s");
        would(ACTION_REDSTONE, "Would set the redstone of %1$s to %2$s");
        would(ACTION_REDSTONE_SELF, "Would set the controller's redstone to %2$s");
        would(ACTION_CHANNEL, "Would set channel %1$s to %2$s");
        would(ACTION_CRAFT, "Would request %2$s %4$s");
        would(ACTION_MOVE, "Would move up to %2$s %4$s from %1$s to %3$s");
        would(ACTION_MOVE_IN, "Would move up to %2$s %4$s from %1$s into storage");
        would(ACTION_MOVE_OUT, "Would move up to %2$s %4$s from storage to %3$s");
        would(ACTION_NOTIFY, "Would notify: %1$s");
        would(ACTION_CANCEL, "Would cancel the crafts of %4$s");
        would(ACTION_CANCEL_OWN, "Would cancel this rule's crafts of %4$s");
        would(ACTION_DISPLAY, "Would write to %1$s: %3$s");
        would(ACTION_FILTER, "Would set the filter of %1$s");
        would(ACTION_VARIABLE, "Would set the shared value %1$s to %2$s");
        would(ACTION_RUN_RULE, "Would run the rule %1$s");
        would(ACTION_STOCK_ON, "Would switch on the stock rule for %4$s in %1$s");
        would(ACTION_STOCK_OFF, "Would switch off the stock rule for %4$s in %1$s");
        would(ACTION_STOCK_ALL_ON, "Would switch on every stock rule in %1$s");
        would(ACTION_STOCK_ALL_OFF, "Would switch off every stock rule in %1$s");
        would(ACTION_STOCK_KEEP, "Would keep %2$s %4$s in %1$s");
        would(ACTION_STOCK_RESTART, "Would restart %4$s in %1$s below %2$s");
        would(ACTION_STOCK_BATCH, "Would craft %4$s in %1$s in batches of %2$s");
        would(ACTION_POOL, "Would send %2$s %4$s to %1$s %3$s");
        would(ACTION_POWER_PRIORITY, "Would set the power priority of %1$s to %2$s");
        would(ACTION_TASK_DONE, "Would tick task %1$s");
        would(ACTION_TASK_OPEN, "Would open task %1$s again");
    }

    /** Declares the "would" form of action line {@code action}. */
    private static void would(String action, String english) {
        String key = action + ".would";
        WOULD.put(action, key);
        ModContent.CONTENT.translation(key, english);
    }

    /** The "would" form of action line key {@code action} ("Would turn %1$s on"); the key itself when it has none. */
    public static String would(String action) {
        return WOULD.getOrDefault(action, action);
    }

    static {
        ModContent.CONTENT.translation(GRAPHS, "Graphs: %s running, %s paused, %s off");
        ModContent.CONTENT.translation(CHECKS, "Checks: %s per second");
        ModContent.CONTENT.translation(ACTION_POINTS, "Action points: %s per second");
        ModContent.CONTENT.translation(WAITING, "Waiting for the budget: %s");
        ModContent.CONTENT.translation(NO_GRAPHS, "No graphs yet");
        ModContent.CONTENT.translation(ON, "On");
        ModContent.CONTENT.translation(OFF, "Off");
        ModContent.CONTENT.translation(TOAST, "Logic notice");
        ModContent.CONTENT.translation(CHAT, "[Logic] %s");
        ModContent.CONTENT.translation(EXPORT, "Export");
        ModContent.CONTENT.translation(EXPORT_TIP, "Copy the selected graph to the clipboard as text to share it");
        ModContent.CONTENT.translation(IMPORT, "Import");
        ModContent.CONTENT.translation(IMPORT_TIP,
                "Add the graph whose text is on the clipboard; it starts switched off");
        ModContent.CONTENT.translation(SHARE_COPIED, "Copied \"%s\" to the clipboard");
        ModContent.CONTENT.translation(SHARE_COPIED_LONG,
                "Copied, but the text has %s characters and this server imports at most %s");
        ModContent.CONTENT.translation(SHARE_EXPORT_FAILED, "This graph could not be written as text");
        ModContent.CONTENT.translation(SHARE_NO_GRAPH, "That graph is no longer there");
        ModContent.CONTENT.translation(SHARE_TOO_FAST, "One moment; try again in a second");
        ModContent.CONTENT.translation(SHARE_NO_ROW, "All %s graph rows are in use");
        ModContent.CONTENT.translation(SHARE_IMPORTED, "Imported \"%s\"; it is off until you switch it on");
        ModContent.CONTENT.translation(SHARE_CHECKING, "Checking the graph...");
        for (LogicWorker.Status status : LogicWorker.Status.values()) {
            ModContent.CONTENT.translation(statusKey(status), switch (status) {
                case RUNNING -> "Running";
                case PAUSED -> "Paused: errors";
                case DISABLED -> "Off";
                case WAITING -> "Waiting";
                case IDLE -> "Over the limit";
            });
            ModContent.CONTENT.translation(chipKey(status), switch (status) {
                case RUNNING -> "RUNNING";
                case PAUSED -> "ERROR";
                case DISABLED -> "PAUSED";
                case WAITING -> "WAITING";
                case IDLE -> "OVER LIMIT";
            });
        }
    }

    /** The lang key of a rule status's chip on the screen: a switched-off rule reads PAUSED, one with errors ERROR. */
    public static String chipKey(LogicWorker.Status status) {
        return key("chip." + status.name().toLowerCase(java.util.Locale.ROOT));
    }

    private ControllerTexts() {
    }

    /** Loads the class during mod construction so its lang lines are declared. */
    static void init() {
    }

    private static String key(String path) {
        return "gui." + MaxtechStorage.MOD_ID + ".logic_controller." + path;
    }

    /** The lang key of a graph status's name. */
    public static String statusKey(LogicWorker.Status status) {
        return key("status." + status.name().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * A run's cost as the rule detail and the editor show it: "6 checks / run", or "6 checks + 12 points / run" when
     * its actions cost action points.
     */
    public static Component costText(int checks, int points) {
        if (points <= 0) {
            return Component.translatable(Plural.key(DETAIL_COST_VALUE, checks), InfoFormat.number(checks));
        }
        return Component.translatable(DETAIL_COST_BOTH, costParts(checks, points));
    }

    /** A cost without "/ run": "6 checks", or "6 checks + 12 points" when the actions cost action points. */
    public static Component costParts(int checks, int points) {
        Component inChecks = Component.translatable(Plural.key(COST_CHECKS, checks), InfoFormat.number(checks));
        if (points <= 0) {
            return inChecks;
        }
        return Component.translatable(COST_SUM, inChecks,
                Component.translatable(Plural.key(COST_POINTS, points), InfoFormat.number(points)));
    }

    /** Jade, the scanner and the analyzer at a glance: how many graphs run, are paused and are off. */
    static void glance(LogicWorker worker, BlockInfoSink sink) {
        int running = 0;
        int paused = 0;
        int off = 0;
        for (int i = 0; i < worker.size(); i++) {
            switch (worker.status(i)) {
                case RUNNING, WAITING -> running++;
                case PAUSED -> paused++;
                case DISABLED, IDLE -> off++;
            }
        }
        sink.line(Component.translatable(GRAPHS, running, paused, off));
    }

    /** The details after the glance: checks and action points per second, waiting actions. */
    static void info(LogicWorker worker, long gameTime, BlockInfoSink sink) {
        sink.line(Component.translatable(CHECKS, InfoFormat.number(worker.checksPerSecond(gameTime))));
        sink.line(Component.translatable(ACTION_POINTS, InfoFormat.number(worker.actionPointsPerSecond(gameTime))));
        if (worker.waiting() > 0) {
            sink.line(Component.translatable(WAITING, worker.waiting()));
        }
    }
}
