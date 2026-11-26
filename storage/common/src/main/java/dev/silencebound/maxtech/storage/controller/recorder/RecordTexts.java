package dev.silencebound.maxtech.storage.controller.recorder;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.controller.module.AlarmSettings;
import dev.silencebound.maxtech.storage.registry.ModContent;

/**
 * The lang keys and English texts of the controller's recorder views (M7R item 3). The ledger and alarm keys are the
 * ones the ledger and alarm module screens used, so their translations carry over.
 */
public final class RecordTexts {
    private static final String GUI = "gui." + MaxtechStorage.MOD_ID + ".";
    public static final String PAGE_TELEMETRY = GUI + "records.telemetry";
    public static final String PAGE_LEDGER = GUI + "records.ledger";
    public static final String PAGE_ALARMS = GUI + "records.alarms";
    public static final String TAB = GUI + "records.tab";
    public static final String WAITING = GUI + "records.waiting";
    public static final String NO_CONTROLLER = GUI + "records.no_controller";
    public static final String LOCKED_TELEMETRY = GUI + "records.locked.telemetry";
    public static final String LOCKED_LEDGER = GUI + "records.locked.ledger";
    public static final String LOCKED_ALARMS = GUI + "records.locked.alarms";
    public static final String OFF_TELEMETRY = GUI + "records.off.telemetry";
    public static final String OFF_LEDGER = GUI + "records.off.ledger";
    public static final String OFF_ALARMS = GUI + "records.off.alarms";

    public static final String LEDGER_HEADER = GUI + "ledger.header";
    public static final String LEDGER_EMPTY = GUI + "ledger.empty";
    public static final String LEDGER_IN = GUI + "ledger.in";
    public static final String LEDGER_OUT = GUI + "ledger.out";
    public static final String LEDGER_UNRECORDED = GUI + "ledger.unrecorded";
    public static final String LEDGER_EVERYONE = GUI + "ledger.everyone";
    public static final String LEDGER_AUTOMATION = GUI + "ledger.automation";
    public static final String LEDGER_INFO = "info." + MaxtechStorage.MOD_ID + ".ledger.rows";

    public static final String ALARM_HEADER = GUI + "alarm.header";
    public static final String ALARM_NONE = GUI + "alarm.none";
    public static final String ALARM_ADD = GUI + "alarm.add";
    public static final String ALARM_REMOVE = GUI + "alarm.remove";
    public static final String ALARM_NOT_ALLOWED = GUI + "alarm.not_allowed";
    public static final String ALARM_THRESHOLD = GUI + "alarm.threshold";
    public static final String ALARM_VALUE = GUI + "alarm.value";
    public static final String ALARM_ON = GUI + "alarm.on";
    public static final String ALARM_OFF = GUI + "alarm.off";
    public static final String ALARM_BELOW = GUI + "alarm.below";
    public static final String ALARM_ABOVE = GUI + "alarm.above";
    public static final String ALARM_INFO = "info." + MaxtechStorage.MOD_ID + ".controller.alarm_rules";
    public static final String RETIRED_INFO = "info." + MaxtechStorage.MOD_ID + ".expansion.built_in";

    private RecordTexts() {
    }

    /** Registers the texts; called once while the controller's content loads. */
    public static void register() {
        ModContent.CONTENT.translation(PAGE_TELEMETRY, "Graphs");
        ModContent.CONTENT.translation(PAGE_LEDGER, "Ledger");
        ModContent.CONTENT.translation(PAGE_ALARMS, "Redstone");
        ModContent.CONTENT.translation(TAB, "Network history");
        ModContent.CONTENT.translation(WAITING, "Waiting for the controller...");
        ModContent.CONTENT.translation(NO_CONTROLLER, "This network has no controller to keep its history");
        ModContent.CONTENT.translation(LOCKED_TELEMETRY, "The owner's team needs the research Network Telemetry");
        ModContent.CONTENT.translation(LOCKED_LEDGER, "The owner's team needs the research Network Ledger");
        ModContent.CONTENT.translation(LOCKED_ALARMS, "The owner's team needs the research Network Tuning");
        ModContent.CONTENT.translation(OFF_TELEMETRY, "Telemetry is turned off in this server's config");
        ModContent.CONTENT.translation(OFF_LEDGER, "The ledger is turned off in this server's config");
        ModContent.CONTENT.translation(OFF_ALARMS, "Redstone alarms are turned off in this server's config");

        ModContent.CONTENT.translation(LEDGER_HEADER, "Last %s days: %s kinds moved");
        ModContent.CONTENT.translation(LEDGER_EMPTY, "Nothing went in or out yet");
        ModContent.CONTENT.translation(LEDGER_IN, "+%s");
        ModContent.CONTENT.translation(LEDGER_OUT, "-%s");
        ModContent.CONTENT.translation(LEDGER_UNRECORDED, "%s changes found no room in the ledger");
        ModContent.CONTENT.translation(LEDGER_EVERYONE, "Everyone");
        ModContent.CONTENT.translation(LEDGER_AUTOMATION, "Automation");
        ModContent.CONTENT.translation(LEDGER_INFO, "Ledger: %s of %s rows over %s days");

        ModContent.CONTENT.translation(AlarmSettings.Type.LOW_POWER.translationKey(), "Low power");
        ModContent.CONTENT.translation(AlarmSettings.Type.STORAGE_FULL.translationKey(), "Storage nearly full");
        ModContent.CONTENT.translation(AlarmSettings.Type.LINK_SATURATED.translationKey(), "Root link nearly full");
        ModContent.CONTENT.translation(AlarmSettings.Type.DEVICE_LIMIT.translationKey(), "Device limit reached");
        ModContent.CONTENT.translation(AlarmSettings.Type.JOB_FINISHED.translationKey(), "Crafting job finished");
        ModContent.CONTENT.translation(ALARM_HEADER, "Rules: %s of %s. The controller gives redstone while one holds");
        ModContent.CONTENT.translation(ALARM_NONE, "No rules yet");
        ModContent.CONTENT.translation(ALARM_ADD, "Add rule");
        ModContent.CONTENT.translation(ALARM_REMOVE, "x");
        ModContent.CONTENT.translation(ALARM_NOT_ALLOWED, "Only players who may configure the network change rules");
        ModContent.CONTENT.translation(ALARM_THRESHOLD, "Threshold: %s");
        ModContent.CONTENT.translation(ALARM_VALUE, "Now: %s%%");
        ModContent.CONTENT.translation(ALARM_ON, "Signal on");
        ModContent.CONTENT.translation(ALARM_OFF, "Signal off");
        ModContent.CONTENT.translation(ALARM_BELOW, "at or below %s%%");
        ModContent.CONTENT.translation(ALARM_ABOVE, "at or above %s%%");
        ModContent.CONTENT.translation(ALARM_INFO, "Redstone alarm rules: %s, %s holding");
        ModContent.CONTENT.translation(RETIRED_INFO, "Built into the controller now; this block does nothing");
    }
}
