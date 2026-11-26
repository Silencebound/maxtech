package dev.silencebound.maxtech.storage.logic;

import java.util.Locale;

/**
 * A label that names another network (M7L2 row 13): {@code intake@North Base} is the devices labelled "intake" on the
 * network named "North Base", {@code @North Base} that network as a whole (its storage, its energy). The text after the
 * last {@code @} is the network's name; a label ending in {@code @} names a device on the rule's own network, so a
 * device label that holds an {@code @} can still be named. Labels stay text in saved graphs, so a shared rule carries
 * the network by name. Pure; any thread.
 */
public final class NetworkRef {
    /** What separates a label from the network's name. */
    public static final char MARK = '@';
    /** Most characters of a network's name, so {@code @} and the name fit a device label for the pickers. */
    public static final int MAX_NAME = 24;
    /** Most characters of a label setting: a device label, {@code @} and a network's name. */
    public static final int MAX_REF = SettingSpec.MAX_LABEL + 1 + MAX_NAME;

    private NetworkRef() {
    }

    /** The network {@code label} names, trimmed; empty when it names the rule's own network. */
    public static String network(String label) {
        int at = label.lastIndexOf(MARK);
        return at < 0 ? "" : label.substring(at + 1).strip();
    }

    /** The device label part of {@code label}, trimmed: all of it when it names no other network. */
    public static String device(String label) {
        int at = label.lastIndexOf(MARK);
        return at < 0 ? label.strip() : label.substring(0, at).strip();
    }

    /** Whether {@code label} names another network. */
    public static boolean remote(String label) {
        return !network(label).isEmpty();
    }

    /** A network's name as names are compared: trimmed and lower case, so "North base" is "North Base". */
    public static String key(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    /** The label for the devices labelled {@code device} on the network {@code network} (empty: the own network). */
    public static String of(String device, String network) {
        return network.isEmpty() ? device : device + MARK + network;
    }

    /**
     * Whether nodes of {@code op} may name another network: the reads of counts, sensor values, device state, labelled
     * device counts, full cells, energy and free space, and the actions switch, priority, power priority, redstone out,
     * request craft, move items, write display and set filter. Other nodes stay on their own network (signals and
     * per-controller state such as pulses, pools and stock keepers).
     */
    static boolean supports(Op op) {
        return switch (op) {
            case COUNT_CROSSES, ITEM_COUNT, SENSOR_CROSSES, SENSOR_VALUE, DEVICE_STATE, DEVICES_CHANGE, CELLS_FULL,
                    ENERGY, FREE_SPACE, DEVICE_SWITCH, SET_PRIORITY, SET_POWER_PRIORITY, REDSTONE_OUT, REQUEST_CRAFT,
                    MOVE_ITEMS, WRITE_DISPLAY, SET_FILTER, CONSTANT, PARAMETER ->
                true;
            default -> false;
        };
    }
}
