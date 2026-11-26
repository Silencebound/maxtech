package dev.silencebound.maxtech.storage.sensor;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A sensor's settings, saved with it and kept in its item ({@code maxtech_storage:sensor}) when they differ from a new
 * sensor's. Immutable.
 *
 * @param poll
 *            how often it reads on its own
 */
public record SensorSettings(SensorPoll poll) {
    public static final SensorSettings DEFAULT = new SensorSettings(SensorPoll.OFF);
    public static final Codec<SensorSettings> CODEC = RecordCodecBuilder.create(instance -> instance
            .group(SensorPoll.CODEC.optionalFieldOf("poll", SensorPoll.OFF).forGetter(SensorSettings::poll))
            .apply(instance, SensorSettings::new));
    public static final StreamCodec<ByteBuf, SensorSettings> STREAM_CODEC = ByteBufCodecs.VAR_INT
            .map(id -> new SensorSettings(SensorPoll.byId(id)), settings -> settings.poll().id());

    public SensorSettings withPoll(SensorPoll poll) {
        return new SensorSettings(poll);
    }
}
