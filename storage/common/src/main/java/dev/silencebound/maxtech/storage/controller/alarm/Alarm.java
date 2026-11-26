package dev.silencebound.maxtech.storage.controller.alarm;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * One alarm of a network as screens show it (M7C item 16). Immutable; {@link AlarmCenter} replaces it when it changes.
 *
 * @param key
 *            what the alarm is about, unique on its network: {@code low_power}, {@code stuck/<pos>/<reason>} ...
 * @param kind
 *            {@link AlarmKind#id()}
 * @param text
 *            what it says, with its numbers
 * @param raised
 *            the game time it was raised (again)
 * @param active
 *            whether it still holds; a cleared alarm stays listed for {@code alarms.clearedKeepTicks}
 * @param cleared
 *            the game time it cleared; 0 while active
 * @param ackBy
 *            who acknowledged it; empty while nobody has
 * @param ackAt
 *            when it was acknowledged; 0 while nobody has
 */
public record Alarm(String key, int kind, Component text, long raised, boolean active, long cleared, String ackBy,
        long ackAt) {
    /** Longest key; keys are made by the center from positions and names, so this is a guard, not a limit. */
    public static final int MAX_KEY = 256;
    /** Longest name of who acknowledged an alarm. */
    public static final int MAX_BY = 64;

    public static final StreamCodec<RegistryFriendlyByteBuf, Alarm> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_KEY), Alarm::key, ByteBufCodecs.VAR_INT, Alarm::kind,
            ComponentSerialization.TRUSTED_STREAM_CODEC, Alarm::text, ByteBufCodecs.VAR_LONG, Alarm::raised,
            ByteBufCodecs.BOOL, Alarm::active, ByteBufCodecs.VAR_LONG, Alarm::cleared, ByteBufCodecs.stringUtf8(MAX_BY),
            Alarm::ackBy, ByteBufCodecs.VAR_LONG, Alarm::ackAt, Alarm::new);

    /** An alarm as the controller saves it. */
    public static final Codec<Alarm> CODEC = RecordCodecBuilder
            .create(i -> i.group(Codec.string(1, MAX_KEY).fieldOf("key").forGetter(Alarm::key),
                    Codec.INT.fieldOf("kind").forGetter(Alarm::kind),
                    ComponentSerialization.CODEC.fieldOf("text").forGetter(Alarm::text),
                    Codec.LONG.fieldOf("raised").forGetter(Alarm::raised),
                    Codec.BOOL.fieldOf("active").forGetter(Alarm::active),
                    Codec.LONG.optionalFieldOf("cleared", 0L).forGetter(Alarm::cleared),
                    Codec.string(0, MAX_BY).optionalFieldOf("ack_by", "").forGetter(Alarm::ackBy),
                    Codec.LONG.optionalFieldOf("ack_at", 0L).forGetter(Alarm::ackAt)).apply(i, Alarm::new));

    public Alarm {
        ackBy = ackBy.length() > MAX_BY ? ackBy.substring(0, MAX_BY) : ackBy;
    }

    /** A new alarm for {@code key}, raised at {@code now}, not acknowledged. */
    public static Alarm raise(String key, AlarmKind kind, Component text, long now) {
        return new Alarm(key, kind.id(), text, now, true, 0L, "", 0L);
    }

    /** Whether it holds and nobody acknowledged it yet. */
    public boolean fresh() {
        return active && ackBy.isEmpty();
    }

    /** Whether someone acknowledged it. */
    public boolean acknowledged() {
        return !ackBy.isEmpty();
    }

    /** What it watches. */
    public AlarmKind alarmKind() {
        return AlarmKind.byId(kind);
    }

    /** The same alarm saying {@code next}. */
    public Alarm withText(Component next) {
        return new Alarm(key, kind, next, raised, active, cleared, ackBy, ackAt);
    }

    /** The alarm stopped holding at {@code now}; who acknowledged it stays shown. */
    public Alarm clear(long now) {
        return new Alarm(key, kind, text, raised, false, now, ackBy, ackAt);
    }

    /** The alarm acknowledged by {@code by} at {@code now}. */
    public Alarm acknowledge(String by, long now) {
        return new Alarm(key, kind, text, raised, active, cleared, by, now);
    }
}
