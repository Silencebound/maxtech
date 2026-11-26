package dev.silencebound.maxtech.storage.controller.module;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/**
 * What one alarm module watches (PRD 7.2, Controller): a network condition and its threshold in percent. Kept in the
 * alarm's block entity and item. Immutable; any thread.
 *
 * @param type
 *            the condition
 * @param threshold
 *            0 to 100: the level the condition's value must reach (or, for low power, fall to)
 */
public record AlarmSettings(Type type, int threshold) {
    /** Low power at 10 %, the first type's default. */
    public static final AlarmSettings DEFAULT = new AlarmSettings(Type.LOW_POWER, Type.LOW_POWER.defaultThreshold());

    public static final Codec<AlarmSettings> CODEC = RecordCodecBuilder.create(i -> i
            .group(Type.CODEC.fieldOf("type").forGetter(AlarmSettings::type),
                    Codec.intRange(0, 100).fieldOf("threshold").forGetter(AlarmSettings::threshold))
            .apply(i, AlarmSettings::new));
    public static final StreamCodec<ByteBuf, AlarmSettings> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.map(Type::byId, Type::id), AlarmSettings::type, ByteBufCodecs.VAR_INT,
            AlarmSettings::threshold, AlarmSettings::new);

    public AlarmSettings {
        threshold = Math.clamp(threshold, 0, 100);
    }

    /** Whether the condition holds for {@code percent}: at or below the threshold for low power, else at or above. */
    public boolean met(long percent) {
        return type.below() ? percent <= threshold : percent >= threshold;
    }

    /** The next type with its default threshold. */
    public AlarmSettings nextType() {
        Type next = type.next();
        return new AlarmSettings(next, next.defaultThreshold());
    }

    /** These settings with the threshold moved by {@code step}, kept in 0 to 100. */
    public AlarmSettings step(int step) {
        return new AlarmSettings(type, threshold + step);
    }

    /** The network conditions an alarm can watch; each reads a percentage. */
    public enum Type implements StringRepresentable {
        /** The network's stored FE in percent of its capacity, at or below the threshold. */
        LOW_POWER("low_power", 10, true),
        /** The storage cells' bytes in use in percent of their capacity, at or above the threshold. */
        STORAGE_FULL("storage_full", 90, false),
        /** The busiest root link's load in percent, at or above the threshold. */
        LINK_SATURATED("link_saturated", 90, false),
        /** The devices in percent of the controller's device limit, at or above the threshold. */
        DEVICE_LIMIT("device_limit", 100, false),
        /**
         * A crafting job of the network, or run by one of its CPUs, ended done or partial (M7 C18): 100 for
         * {@link AlarmWorker#JOB_PULSE_TICKS} after it, else 0.
         */
        JOB_FINISHED("job_finished", 100, false);

        public static final Codec<Type> CODEC = StringRepresentable.fromEnum(Type::values);
        private static final Type[] VALUES = values();

        private final String name;
        private final int defaultThreshold;
        private final boolean below;

        Type(String name, int defaultThreshold, boolean below) {
            this.name = name;
            this.defaultThreshold = defaultThreshold;
            this.below = below;
        }

        public int defaultThreshold() {
            return defaultThreshold;
        }

        /** Whether the condition holds below the threshold rather than above. */
        public boolean below() {
            return below;
        }

        /** A small number for sending: 0 to 4. */
        public int id() {
            return switch (this) {
                case LOW_POWER -> 0;
                case STORAGE_FULL -> 1;
                case LINK_SATURATED -> 2;
                case DEVICE_LIMIT -> 3;
                case JOB_FINISHED -> 4;
            };
        }

        /** The type with {@code id}; {@link #LOW_POWER} for anything else. */
        public static Type byId(int id) {
            return id >= 0 && id < VALUES.length ? VALUES[id] : LOW_POWER;
        }

        /** The next type, wrapping round. */
        public Type next() {
            return byId((id() + 1) % VALUES.length);
        }

        /** The lang key of the type's name. */
        public String translationKey() {
            return "gui.maxtech_storage.alarm." + name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}
