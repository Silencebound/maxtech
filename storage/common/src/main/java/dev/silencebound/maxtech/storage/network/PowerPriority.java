package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.registry.ModContent;
import net.minecraft.network.chat.Component;

/**
 * A device's power priority for load shedding (M7L2 row 21): 1 essential to 5 optional. When the network's stored FE
 * falls, the least important devices pause first ({@link LoadShedding}); essential devices never pause. Only
 * {@link NetworkDevice#sheddable()} devices have a priority a player or a logic graph can change; every other device is
 * essential. Stays within the FE design: it only decides which devices pause, it adds no energy unit or grid.
 */
public final class PowerPriority {
    /** Never paused: the controller, drives, terminals, security and every device that is not sheddable. */
    public static final int ESSENTIAL = 1;
    public static final int HIGH = 2;
    /** The default of devices that do work: ports, interfaces, pattern providers, sensors. */
    public static final int NORMAL = 3;
    public static final int LOW = 4;
    /** The default of display panels: the first to pause. */
    public static final int OPTIONAL = 5;
    public static final int MIN = ESSENTIAL;
    public static final int MAX = OPTIONAL;

    /** "Paused: low power (priority %s)": a device load shedding paused; Jade, the status tab and screens. */
    public static final String PAUSED_KEY = key("paused");
    /** "Power priority: %s (%s)": the status tab's tooltip and Jade. */
    public static final String PRIORITY_KEY = key("priority");
    /** "Click to change, right-click to go back": the status tab's hint. */
    public static final String CHANGE_KEY = key("change");
    private static final String[] NAME_KEYS = {key("name.essential"), key("name.high"), key("name.normal"),
            key("name.low"), key("name.optional")};

    static {
        ModContent.CONTENT.translation(PAUSED_KEY, "Paused: low power (priority %s)");
        ModContent.CONTENT.translation(PRIORITY_KEY, "Power priority: %s (%s)");
        ModContent.CONTENT.translation(CHANGE_KEY, "Click: less important, right-click: more important");
        ModContent.CONTENT.translation(NAME_KEYS[0], "essential");
        ModContent.CONTENT.translation(NAME_KEYS[1], "high");
        ModContent.CONTENT.translation(NAME_KEYS[2], "normal");
        ModContent.CONTENT.translation(NAME_KEYS[3], "low");
        ModContent.CONTENT.translation(NAME_KEYS[4], "optional");
    }

    private PowerPriority() {
    }

    private static String key(String path) {
        return "info." + MaxtechStorage.MOD_ID + ".power_priority." + path;
    }

    /** {@code priority} kept within {@link #MIN} and {@link #MAX}. */
    public static int clamp(long priority) {
        return (int) Math.clamp(priority, MIN, MAX);
    }

    /** The name of {@code priority} ("essential" to "optional"). */
    public static Component name(int priority) {
        return Component.translatable(NAME_KEYS[clamp(priority) - 1]);
    }

    /** "Power priority: 3 (normal)". */
    public static Component line(int priority) {
        int p = clamp(priority);
        return Component.translatable(PRIORITY_KEY, p, name(p));
    }

    /** Loads the class during mod construction so its lang lines are declared. */
    public static void init() {
    }
}
