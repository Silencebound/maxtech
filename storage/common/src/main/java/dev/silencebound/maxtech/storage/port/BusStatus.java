package dev.silencebound.maxtech.storage.port;

import dev.silencebound.maxtech.storage.MaxtechStorage;

/**
 * What a port in storage mode found at its last look (B09 item 8): an inventory it mounts, none, one a land claim keeps
 * it from, or one it refuses because mounting it would loop back into its own network. Shown in Jade and the GUI.
 */
public enum BusStatus {
    /** It mounts the inventory: the network sees and moves through it as its access allows. */
    MOUNTED("mounted", "Mounted"),
    /** It faces nothing with an item or fluid handler, or the block there is not loaded. */
    NOTHING("nothing", "Faces no inventory"),
    /** A land claim keeps it from the inventory. */
    BLOCKED("blocked", "Blocked by a land claim"),
    /**
     * It faces a device of its own network, or of a network whose storage already reaches back into its own (an
     * interface, network chest or bridge of a network that mounts this one): mounting it would loop (AE2's recursion).
     */
    LOOP("loop", "Refused: would loop back into this network");

    private final String name;
    private final String english;

    BusStatus(String name, String english) {
        this.name = name;
        this.english = english;
    }

    /** The lang key of the status's words. */
    public String translationKey() {
        return "gui." + MaxtechStorage.MOD_ID + ".port.bus." + name;
    }

    /** The English words, for the lang file. */
    public String english() {
        return english;
    }
}
