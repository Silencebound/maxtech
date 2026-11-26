package dev.silencebound.maxtech.storage.display;

import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/** How a wall screen shows its value (PRD 7.2, Devices: holographic 2D line and bar graphs). */
public enum DisplayMode implements StringRepresentable {
    /** The current value as a number. */
    VALUE("value", 0),
    /** The value over the time window as a line. */
    LINE("line", 1),
    /** The value over the time window as bars. */
    BAR("bar", 2);

    private static final DisplayMode[] VALUES = values();
    public static final Codec<DisplayMode> CODEC = StringRepresentable.fromEnum(DisplayMode::values);
    public static final StreamCodec<ByteBuf, DisplayMode> STREAM_CODEC = ByteBufCodecs.idMapper(DisplayMode::byId,
            DisplayMode::id);

    private final String name;
    private final int id;

    DisplayMode(String name, int id) {
        this.name = name;
        this.id = id;
    }

    /** The network id; an unknown id reads as {@link #VALUE}. */
    public int id() {
        return id;
    }

    public static DisplayMode byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : VALUE;
    }

    /** The mode after this one, for the settings GUI's button. */
    public DisplayMode next() {
        return byId((id + 1) % VALUES.length);
    }

    /** Whether the mode draws a graph, which research gates ({@link DisplayContent#HOLO_GRAPHS}). */
    public boolean isGraph() {
        return this != VALUE;
    }

    public String translationKey() {
        return "gui." + MaxtechStorage.MOD_ID + ".display.mode." + name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
