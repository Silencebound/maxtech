package dev.silencebound.maxtech.storage.controller.alarm;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.registry.ModContent;

/** The alarm panel's lang keys and their English (M7C item 16). */
public final class AlarmTexts {
    private static final String PREFIX = "gui." + MaxtechStorage.MOD_ID + ".alarm_panel.";

    /** What an alarm says. */
    public static final String LOW_POWER = PREFIX + "low_power";
    public static final String STORAGE_FULL = PREFIX + "storage_full";
    public static final String CRAFT_STUCK = PREFIX + "craft_stuck";
    public static final String RULE_ERROR = PREFIX + "rule_error";
    public static final String RULE_ERROR_POS = PREFIX + "rule_error_pos";
    public static final String OFFLINE = PREFIX + "offline";
    /** The network's log line and the chat line of a raised alarm. */
    public static final String NOTICE = PREFIX + "notice";
    public static final String CHAT = PREFIX + "chat";
    /** Screens. */
    public static final String TITLE = PREFIX + "title";
    public static final String STATUS = PREFIX + "status";
    public static final String ALL_CLEAR = PREFIX + "all_clear";
    public static final String NO_CONTROLLER = PREFIX + "no_controller";
    public static final String NEW = PREFIX + "new";
    public static final String ACK_BY = PREFIX + "ack_by";
    public static final String CLEARED = PREFIX + "cleared";
    public static final String HINT = PREFIX + "hint";
    public static final String TAB = PREFIX + "tab";
    public static final String ACK = PREFIX + "ack";

    private AlarmTexts() {
    }

    static void register() {
        ModContent.CONTENT.translation(AlarmKind.LOW_POWER.translationKey(), "Low power");
        ModContent.CONTENT.translation(AlarmKind.STORAGE_FULL.translationKey(), "Storage nearly full");
        ModContent.CONTENT.translation(AlarmKind.CRAFT_STUCK.translationKey(), "Craft stuck");
        ModContent.CONTENT.translation(AlarmKind.RULE_ERROR.translationKey(), "Rule error");
        ModContent.CONTENT.translation(AlarmKind.OFFLINE.translationKey(), "Devices offline");
        ModContent.CONTENT.translation(LOW_POWER, "Stored power at or below %s%%");
        ModContent.CONTENT.translation(STORAGE_FULL, "Storage %s%% full or more");
        ModContent.CONTENT.translation(CRAFT_STUCK, "%s stuck at %s: %s");
        ModContent.CONTENT.translation(RULE_ERROR, "%s: %s rules paused by errors");
        ModContent.CONTENT.translation(RULE_ERROR_POS, "Logic Controller at %s");
        ModContent.CONTENT.translation(OFFLINE, "%s storage cells offline: their drives are in unloaded chunks");
        ModContent.CONTENT.translation(NOTICE, "Alarm: %s");
        ModContent.CONTENT.translation(CHAT, "[%s] Alarm: %s");
        ModContent.CONTENT.translation(TITLE, "Alarms");
        ModContent.CONTENT.translation(STATUS, "%s new, %s active");
        ModContent.CONTENT.translation(ALL_CLEAR, "All clear");
        ModContent.CONTENT.translation(NO_CONTROLLER, "Needs a network with one controller");
        ModContent.CONTENT.translation(NEW, "New");
        ModContent.CONTENT.translation(ACK_BY, "Seen by %s");
        ModContent.CONTENT.translation(CLEARED, "Cleared");
        ModContent.CONTENT.translation(HINT, "Right-click an alarm to acknowledge it");
        ModContent.CONTENT.translation(TAB, "Alarms");
        ModContent.CONTENT.translation(ACK, "Ack");
    }
}
