package dev.silencebound.maxtech.storage.logic;

/** The editor palette's groups. */
public enum NodeCategory {
    /** Starts a run when something happens on the network. */
    EVENTS("events", "Events"),
    /** Reads a value from the network or a device. */
    READ("read", "Read"),
    /** Compares and combines values. */
    CHECK("check", "Check"),
    /** Keeps a value between runs. */
    REMEMBER("remember", "Remember"),
    /** Does something on the network. */
    ACT("act", "Act"),
    /** Waits. */
    TIME("time", "Time");

    private final String id;
    private final String nameKey;

    NodeCategory(String id, String english) {
        this.id = id;
        this.nameKey = LogicLang.add("category." + id, english);
    }

    public String id() {
        return id;
    }

    public String nameKey() {
        return nameKey;
    }
}
