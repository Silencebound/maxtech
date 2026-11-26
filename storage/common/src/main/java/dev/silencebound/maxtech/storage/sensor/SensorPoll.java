package dev.silencebound.maxtech.storage.sensor;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * How often a sensor reads its target on its own, for blocks that change without telling their neighbours. Off by
 * default: a sensor then reads only when its target tells its neighbours of a change. The shortest interval is one
 * second; each read costs checks of the owner's team budget ({@code TeamBudget}).
 */
public enum SensorPoll implements StringRepresentable {
    OFF("off", 0, 0), ONE_SECOND("1s", 1, 20), FIVE_SECONDS("5s", 2, 100), THIRTY_SECONDS("30s", 3, 600);

    public static final Codec<SensorPoll> CODEC = StringRepresentable.fromEnum(SensorPoll::values);
    private static final SensorPoll[] VALUES = values();

    private final String name;
    private final int id;
    private final int ticks;

    SensorPoll(String name, int id, int ticks) {
        this.name = name;
        this.id = id;
        this.ticks = ticks;
    }

    /** The id the screen's data slot and the item's network sync carry; fixed per interval. */
    public int id() {
        return id;
    }

    /** Ticks between reads; 0 when off. */
    public int ticks() {
        return ticks;
    }

    public SensorPoll next() {
        return byId((id + 1) % VALUES.length);
    }

    /** The interval with {@link #id()} {@code id}, or {@link #OFF} for an unknown one. */
    public static SensorPoll byId(int id) {
        for (SensorPoll poll : VALUES) {
            if (poll.id == id) {
                return poll;
            }
        }
        return OFF;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
