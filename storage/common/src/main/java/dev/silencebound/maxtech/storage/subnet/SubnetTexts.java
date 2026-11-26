package dev.silencebound.maxtech.storage.subnet;

import dev.silencebound.maxtech.storage.MaxtechStorage;

/** The subnet controller's lang keys; the English lines are declared in {@link SubnetContent}. */
public final class SubnetTexts {
    public static final String INFO_MODE = info("mode");
    public static final String INFO_PARENT = info("parent");
    public static final String INFO_NO_PARENT = info("no_parent");
    public static final String INFO_SAME_NETWORK = info("same_network");
    public static final String TOOLTIP_MODE = "tooltip." + MaxtechStorage.MOD_ID + ".subnet.mode";
    public static final String GUI_MODE = gui("mode");
    public static final String GUI_FILTER = gui("filter");
    public static final String GUI_MODE_HELP_PREFIX = gui("help.");

    private SubnetTexts() {
    }

    private static String info(String path) {
        return "info." + MaxtechStorage.MOD_ID + ".subnet." + path;
    }

    private static String gui(String path) {
        return "gui." + MaxtechStorage.MOD_ID + ".subnet." + path;
    }

    /** The one-line explanation of {@code mode} under the mode button. */
    public static String help(SubnetMode mode) {
        return GUI_MODE_HELP_PREFIX + mode.name().toLowerCase(java.util.Locale.ROOT);
    }
}
