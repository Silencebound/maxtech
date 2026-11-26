package dev.silencebound.maxtech.storage.logic;

/**
 * What a wire carries. A wire joins an output and an input of the same type. Events are one-shot signals ("this just
 * happened"); numbers and yes/no values are levels that hold until they change; items and labels are fixed when the
 * graph is saved and only come from Constant nodes, so the controller knows what to watch without running the graph.
 */
public enum PortType {
    EVENT("event", "trigger", "Connect a trigger here", "Only a trigger fits here"), NUMBER("number", "number",
            "Connect a number here", "Only a number fits here"), YES_NO("yes_no", "yes/no", "Connect a yes/no here",
                    "Only a yes/no fits here"), ITEM("item", "item", "Pick an item or connect one here",
                            "Only an item fits here"), LABEL("label", "label", "Pick a label or connect one here",
                                    "Only a label fits here");

    private final String id;
    private final String nameKey;
    private final String connectKey;
    private final String wrongTypeKey;

    PortType(String id, String english, String connect, String wrongType) {
        this.id = id;
        this.nameKey = LogicLang.add("type." + id, english);
        this.connectKey = LogicLang.add("error.connect." + id, connect);
        this.wrongTypeKey = LogicLang.add("error.wrong_type." + id, wrongType);
    }

    /** The lower-case name used in lang keys. */
    public String id() {
        return id;
    }

    /** The type's player-facing name ("trigger", "yes/no"). */
    public String nameKey() {
        return nameKey;
    }

    /** The error shown on a required input of this type that has no wire. */
    public String connectKey() {
        return connectKey;
    }

    /** The error shown on an input of this type that a wire of another type goes into. */
    public String wrongTypeKey() {
        return wrongTypeKey;
    }

    /** Whether the value is fixed when the graph is saved rather than computed while it runs. */
    public boolean fixed() {
        return this == ITEM || this == LABEL;
    }
}
