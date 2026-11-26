package dev.silencebound.maxtech.storage.controller.recovery;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.registry.ModContent;

/** The recovery bin's lang keys and their English (M7C item 20). */
public final class RecoveryTexts {
    private static final String PREFIX = "gui." + MaxtechStorage.MOD_ID + ".recovery.";

    /** The controller screen's tab. */
    public static final String TAB = PREFIX + "tab";
    public static final String EMPTY = PREFIX + "empty";
    public static final String OFF = PREFIX + "off";
    public static final String LEFT = PREFIX + "left";
    public static final String RECOVER = PREFIX + "recover";
    public static final String NOT_ALLOWED = PREFIX + "not_allowed";

    private RecoveryTexts() {
    }

    /** Queues the English texts; called once while the mod loads. */
    public static void register() {
        ModContent.CONTENT.translation(TAB, "Recovery");
        ModContent.CONTENT.translation(EMPTY,
                "Nothing to recover. What void overflow destroys, or a destroyed cell loses, stays here for %s.");
        ModContent.CONTENT.translation(OFF, "The recovery bin is turned off in the server config.");
        ModContent.CONTENT.translation(LEFT, "%s left");
        ModContent.CONTENT.translation(RECOVER, "Recover");
        ModContent.CONTENT.translation(NOT_ALLOWED, "Only the network's owner or an admin can recover items.");
        RecoveryCommand.register();
    }
}
