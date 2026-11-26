package dev.silencebound.maxtech.storage.display;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.history.ChartValue;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntLists;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What a {@link DisplaySourceKind#HISTORY} screen records and shows (M7L2 history charts): the value reference
 * ({@link ChartValue}), the ticks between two samples and the chart's window in hours. Out-of-range numbers snap to the
 * nearest offered one, so an edited save or a forged payload cannot make a screen record every tick; the server
 * config's {@code history.minIntervalTicks} is applied where the samples are taken.
 *
 * @param value
 *            the reference as typed, cut to {@link ChartValue#MAX_LENGTH}; blank records nothing
 */
public record ChartSettings(String value, int intervalTicks, int windowHours) {
    /** The sample intervals the settings GUI offers, in ticks: a second, ten seconds, a minute, five minutes. */
    public static final IntList INTERVALS = IntLists.unmodifiable(IntArrayList.of(20, 200, 1200, 6000));
    /** The chart windows the settings GUI offers, in hours. */
    public static final IntList WINDOWS = IntLists.unmodifiable(IntArrayList.of(1, 4, 24));
    public static final ChartSettings DEFAULT = new ChartSettings("", 1200, 4);

    public static final Codec<ChartSettings> CODEC = RecordCodecBuilder.create(instance -> instance
            .group(Codec.STRING.optionalFieldOf("value", "").forGetter(ChartSettings::value),
                    Codec.INT.optionalFieldOf("interval", DEFAULT.intervalTicks())
                            .forGetter(ChartSettings::intervalTicks),
                    Codec.INT.optionalFieldOf("hours", DEFAULT.windowHours()).forGetter(ChartSettings::windowHours))
            .apply(instance, ChartSettings::new));
    public static final StreamCodec<ByteBuf, ChartSettings> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(ChartValue.MAX_LENGTH), ChartSettings::value, ByteBufCodecs.VAR_INT,
            ChartSettings::intervalTicks, ByteBufCodecs.VAR_INT, ChartSettings::windowHours, ChartSettings::new);

    public ChartSettings {
        value = value.length() > ChartValue.MAX_LENGTH ? value.substring(0, ChartValue.MAX_LENGTH) : value;
        intervalTicks = nearest(INTERVALS, intervalTicks);
        windowHours = nearest(WINDOWS, windowHours);
    }

    private static int nearest(IntList offered, int wanted) {
        int best = offered.getInt(0);
        for (int i = 1; i < offered.size(); i++) {
            int option = offered.getInt(i);
            if (Math.abs((long) option - wanted) < Math.abs((long) best - wanted)) {
                best = option;
            }
        }
        return best;
    }

    public ChartSettings withValue(String value) {
        return new ChartSettings(value.strip(), intervalTicks, windowHours);
    }

    /** The next offered interval, wrapping. */
    public ChartSettings nextInterval() {
        return new ChartSettings(value, INTERVALS.getInt((INTERVALS.indexOf(intervalTicks) + 1) % INTERVALS.size()),
                windowHours);
    }

    /** The next offered window, wrapping. */
    public ChartSettings nextWindow() {
        return new ChartSettings(value, intervalTicks,
                WINDOWS.getInt((WINDOWS.indexOf(windowHours) + 1) % WINDOWS.size()));
    }

    /** The window in ticks. */
    public long windowTicks() {
        return windowHours * 72_000L;
    }
}
