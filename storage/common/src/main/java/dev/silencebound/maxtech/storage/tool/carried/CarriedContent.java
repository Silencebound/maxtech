package dev.silencebound.maxtech.storage.tool.carried;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.registry.ModContent;

/** The carried-gadget key's texts and payloads. */
public final class CarriedContent {
    private static final String PREFIX = MaxtechStorage.MOD_ID + ".";
    /** The key mapping's category in the controls screen. */
    public static final String KEY_CATEGORY = "key.category." + MaxtechStorage.MOD_ID + ".gadgets";
    /** The one gadget key's name. */
    public static final String OPEN_KEY = "key." + PREFIX + "open_gadget";
    /** The message when the player carries no gadget at all. */
    public static final String NONE_KEY = "message." + PREFIX + "carried.none";
    /** The picker's title and the hint under its rows. */
    public static final String PICKER_TITLE = "gui." + PREFIX + "gadget_picker.title";
    public static final String PICKER_HINT = "gui." + PREFIX + "gadget_picker.hint";

    static {
        ModContent.CONTENT.translation(KEY_CATEGORY, "MaxTech Storage gadgets");
        ModContent.CONTENT.translation(OPEN_KEY, "Open a carried gadget");
        ModContent.CONTENT.translation(NONE_KEY, "You carry no gadget to open");
        ModContent.CONTENT.translation(PICKER_TITLE, "Carried gadgets");
        ModContent.CONTENT.translation(PICKER_HINT, "Click one or press its number");
        ModContent.CONTENT.translation(noneKey(CarriedGadget.WIRELESS_TERMINAL), "You carry no wireless terminal");
        ModContent.CONTENT.translation(noneKey(CarriedGadget.CRAFTING_TABLET), "You carry no crafting tablet");
        ModContent.CONTENT.translation(noneKey(CarriedGadget.LOGIC_REMOTE), "You carry no logic remote");
        ModContent.CONTENT.translation(noneKey(CarriedGadget.PORTABLE_CELL), "You carry no portable cell");
    }

    private CarriedContent() {
    }

    /** The message when the player carries no {@code gadget}. */
    public static String noneKey(CarriedGadget gadget) {
        return "message." + PREFIX + "carried.none." + gadget.id();
    }

    /** Loads the class during mod construction and registers the payload. */
    public static void init() {
        CarriedOpen.register();
    }
}
