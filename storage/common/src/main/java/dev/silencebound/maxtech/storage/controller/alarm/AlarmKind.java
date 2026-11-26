package dev.silencebound.maxtech.storage.controller.alarm;

import dev.silencebound.maxtech.storage.MaxtechStorage;

/** What a network alarm watches (M7C item 16). Ids are sent; never change them. */
public enum AlarmKind {
    /** The network's stored FE at or below {@code alarms.lowPowerPercent}. */
    LOW_POWER(0, "low_power"),
    /** The cells' bytes in use at or above {@code alarms.storageFullPercent}. */
    STORAGE_FULL(1, "storage_full"),
    /** A provider holds up a crafting job, for one of item 9's reasons. */
    CRAFT_STUCK(2, "craft_stuck"),
    /** A Logic Controller's rule is paused by an error. */
    RULE_ERROR(3, "rule_error"),
    /** Drives of the network are in unloaded chunks, so their cells are missing. */
    OFFLINE(4, "offline");

    private static final AlarmKind[] VALUES = values();

    private final int id;
    private final String name;

    AlarmKind(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public int id() {
        return id;
    }

    /** The kind with {@code id}; an unknown one reads as {@link #LOW_POWER}. */
    public static AlarmKind byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : LOW_POWER;
    }

    public String serializedName() {
        return name;
    }

    /** The lang key of the kind's short name ("Low power"). */
    public String translationKey() {
        return "gui." + MaxtechStorage.MOD_ID + ".alarm.kind." + name;
    }
}
