package dev.silencebound.maxtech.storage.sensor;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * One value a sensor can read, as its screen and the logic editor list it. Immutable.
 *
 * @param id
 *            the stable id a logic node names it by ({@link SensorRef#id()}): {@code redstone}, {@code comparator},
 *            {@code state.<property>}, {@code energy.stored}, {@code items.total}, {@code item.<item id>},
 *            {@code data.<name>} and so on
 * @param name
 *            what a player reads, in plain words
 * @param value
 *            the value at the sensor's last read
 * @param unit
 *            what the value counts
 * @param text
 *            the value in words for {@link SensorUnit#STATE} ({@code north}, {@code true}) and {@link SensorUnit#TEXT};
 *            the unit for {@link SensorUnit#CUSTOM} ({@code LP}); empty otherwise
 * @param group
 *            the id of the pack reader the value comes from; empty for the values every block gets
 * @param found
 *            false when a pack reader's path was not in the target's saved data at the last read; the value is then 0
 */
public record SensorValue(String id, Component name, long value, SensorUnit unit, String text, String group,
        boolean found) {
    /** Longest id or text sent to a client. */
    public static final int MAX_TEXT = 256;
    private static final StreamCodec<ByteBuf, SensorUnit> UNIT_CODEC = ByteBufCodecs.VAR_INT.map(SensorUnit::byId,
            SensorUnit::id);
    public static final StreamCodec<RegistryFriendlyByteBuf, SensorValue> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_TEXT), SensorValue::id, ComponentSerialization.STREAM_CODEC, SensorValue::name,
            ByteBufCodecs.VAR_LONG, SensorValue::value, UNIT_CODEC, SensorValue::unit,
            ByteBufCodecs.stringUtf8(MAX_TEXT), SensorValue::text, ByteBufCodecs.stringUtf8(MAX_TEXT),
            SensorValue::group, ByteBufCodecs.BOOL, SensorValue::found, SensorValue::new);

    /** A value every block of its kind gets (not from a pack reader); always found. */
    public static SensorValue of(String id, Component name, long value, SensorUnit unit, String text) {
        return new SensorValue(id, name, value, unit, text, "", true);
    }
}
