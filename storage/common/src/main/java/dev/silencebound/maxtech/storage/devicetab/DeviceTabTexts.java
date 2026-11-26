package dev.silencebound.maxtech.storage.devicetab;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.registry.ModContent;

/** The device side tabs' texts (B09 item 21b): tab titles and the flyouts' captions and notes. */
public final class DeviceTabTexts {
    private static final String PREFIX = "gui." + MaxtechStorage.MOD_ID + ".device_tab.";
    /** Caption above a device's mode button. */
    public static final String MODE_CAPTION_KEY = PREFIX + "mode_caption";
    /** Caption above the power priority button (load shedding). */
    public static final String POWER_CAPTION_KEY = PREFIX + "power_caption";
    /** Caption above the storage access button. */
    public static final String ACCESS_CAPTION_KEY = PREFIX + "access_caption";
    /** The upgrades flyout when the window has no room for its slots beside the panel. */
    public static final String NO_ROOM_KEY = PREFIX + "no_room";
    /** Speed cards in a port in storage mode. */
    public static final String CARDS_IDLE_KEY = PREFIX + "cards_idle";
    /** The badge reason while the device's network has no power (B09 item 21d). */
    public static final String NO_POWER_KEY = PREFIX + "no_power";

    static {
        for (DeviceTab tab : DeviceTab.values()) {
            ModContent.CONTENT.translation(tab.titleKey(), switch (tab) {
                case MODE -> "Mode & redstone";
                case PRIORITY -> "Priority";
                case UPGRADES -> "Upgrades";
                case LABEL -> "Label";
                case ACCESS -> "Access";
            });
        }
        ModContent.CONTENT.translation(MODE_CAPTION_KEY, "Mode");
        ModContent.CONTENT.translation(POWER_CAPTION_KEY, "Power priority");
        ModContent.CONTENT.translation(ACCESS_CAPTION_KEY, "Storage access");
        ModContent.CONTENT.translation(NO_ROOM_KEY, "No room beside the panel; make the window wider");
        ModContent.CONTENT.translation(CARDS_IDLE_KEY, "Speed cards do nothing in storage mode");
        ModContent.CONTENT.translation(NO_POWER_KEY, "The network has no power");
    }

    private DeviceTabTexts() {
    }

    /** Loads the class during mod construction so the texts above are declared. */
    public static void init() {
    }
}
