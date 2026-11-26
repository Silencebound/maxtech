package dev.silencebound.maxtech.storage.label;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.registry.ModContent;
import net.minecraft.network.chat.Component;

/** The label tab's lang entries and packets (M7L area C). Nothing registered beyond them. */
public final class LabelContent {
    private static final String PREFIX = "gui." + MaxtechStorage.MOD_ID + ".label.";
    /** "Label": the page's title. */
    public static final String TITLE_KEY = PREFIX + "title";
    /** The text field's hint while it is empty. */
    public static final String HINT_KEY = PREFIX + "hint";
    public static final String COLOUR_KEY = PREFIX + "colour";
    /** The "no colour" button. */
    public static final String NO_COLOUR_KEY = PREFIX + "no_colour";
    public static final String HELP_KEY = PREFIX + "help";
    public static final String BACK_KEY = PREFIX + "back";
    /** "Label: %s": the tab's tooltip. */
    public static final String VALUE_KEY = PREFIX + "value";
    /** "No label": the tab's tooltip without one. */
    public static final String NONE_KEY = PREFIX + "none";

    static {
        ModContent.CONTENT.translation(TITLE_KEY, "Label");
        ModContent.CONTENT.translation(HINT_KEY, "Name this device");
        ModContent.CONTENT.translation(COLOUR_KEY, "Colour");
        ModContent.CONTENT.translation(NO_COLOUR_KEY, "None");
        ModContent.CONTENT.translation(HELP_KEY,
                "Logic finds devices on this network by label. Upper and lower case count as the same.");
        ModContent.CONTENT.translation(BACK_KEY, "Back");
        ModContent.CONTENT.translation(VALUE_KEY, "Label: %s");
        ModContent.CONTENT.translation(NONE_KEY, "No label");
    }

    private LabelContent() {
    }

    /** "Label: smelters" in the label's colour, or "No label". */
    public static Component line(DeviceLabel label) {
        return label.isEmpty()
                ? Component.translatable(NONE_KEY)
                : Component.translatable(VALUE_KEY, label.component());
    }

    /** Loads the class during mod construction so the entries above are queued, and registers the packets. */
    public static void init() {
        LabelPayloads.register();
    }
}
