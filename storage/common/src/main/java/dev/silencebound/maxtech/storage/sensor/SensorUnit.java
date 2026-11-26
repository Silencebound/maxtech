package dev.silencebound.maxtech.storage.sensor;

/** What a sensor value counts, for the way it is shown. Sent by {@link #id()}. */
public enum SensorUnit {
    /** A redstone strength, 0 to 15. */
    SIGNAL,
    /** A block state property: 1 or 0 for yes/no, the number for numbers, else the index of the value. */
    STATE,
    /** Items. */
    ITEMS,
    /** Fluid in millibuckets. */
    MILLIBUCKETS,
    /** Energy in a block's energy handler, in MU (another mod's FE converted at Lib's rate). */
    ENERGY,
    /** Slots of an inventory. */
    SLOTS,
    /** A pack reader's number; {@link SensorValue#text()} holds its unit, empty for none. */
    CUSTOM,
    /** A pack reader's yes (1) or no (0). */
    YES_NO,
    /** A pack reader's text, in {@link SensorValue#text()}; the number is 0. */
    TEXT,
    /** MaxTech energy (MU). */
    MU,
    /** MaxTech energy per tick. */
    MU_PER_TICK,
    /** A percentage, 0 to 100. */
    PERCENT,
    /** Game ticks; -1 for never. */
    TICKS,
    /** Nanoseconds. */
    NANOSECONDS;

    private static final SensorUnit[] VALUES = values();

    /** The id a {@link SensorValue} is sent with; fixed per unit. */
    public int id() {
        return switch (this) {
            case SIGNAL -> 0;
            case STATE -> 1;
            case ITEMS -> 2;
            case MILLIBUCKETS -> 3;
            case ENERGY -> 4;
            case SLOTS -> 5;
            case CUSTOM -> 6;
            case YES_NO -> 7;
            case TEXT -> 8;
            case MU -> 9;
            case MU_PER_TICK -> 10;
            case PERCENT -> 11;
            case TICKS -> 12;
            case NANOSECONDS -> 13;
        };
    }

    /** The unit with {@link #id()} {@code id}, or {@link #SIGNAL} for an unknown one. */
    public static SensorUnit byId(int id) {
        for (SensorUnit unit : VALUES) {
            if (unit.id() == id) {
                return unit;
            }
        }
        return SIGNAL;
    }
}
