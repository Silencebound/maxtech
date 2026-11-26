package dev.silencebound.maxtech.storage.priority;

import dev.silencebound.maxtech.storage.registry.ModContent;

/** The priority page's lang entries. Nothing registered beyond them. */
public final class PriorityContent {
    static {
        ModContent.CONTENT.translation(PriorityControl.TITLE_KEY, "Priority");
        ModContent.CONTENT.translation(PriorityControl.VALUE_KEY, "Priority: %s");
        ModContent.CONTENT.translation(PriorityControl.HELP_KEY, "Higher priority fills first");
        ModContent.CONTENT.translation(PriorityControl.BACK_KEY, "Back");
    }

    private PriorityContent() {
    }

    /**
     * Loads the class during mod construction so the entries above are queued before datagen runs, and registers the
     * packet.
     */
    public static void init() {
        PriorityPayloads.register();
    }
}
