package dev.silencebound.maxtech.storage.sensor.reader;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * One value a pack reader names: where it is in the block entity's saved data and how to show it. In a reader file it
 * is either an object ({@code {"path": "Energy.stored", "unit": "FE", "type": "number", "scale": 1}}, only {@code path}
 * required) or just the path as a string. Immutable.
 *
 * @param path
 *            where the value is
 * @param unit
 *            what is shown after a number, such as {@code FE} or {@code LP}; empty for none
 * @param type
 *            number, yes/no or text
 * @param scale
 *            what a number is multiplied by before it is rounded to a whole number; 1 by default
 */
public record ReaderValue(ReaderPath path, String unit, ReaderType type, double scale) {
    /** Longest unit text. */
    public static final int MAX_UNIT = 16;
    /** Longest text a text value shows. */
    public static final int MAX_TEXT = 64;

    private static final Codec<ReaderValue> FULL = RecordCodecBuilder
            .<ReaderValue>create(i -> i
                    .group(ReaderPath.CODEC.fieldOf("path").forGetter(ReaderValue::path),
                            Codec.string(0, MAX_UNIT).optionalFieldOf("unit", "").forGetter(ReaderValue::unit),
                            ReaderType.CODEC.optionalFieldOf("type", ReaderType.NUMBER).forGetter(ReaderValue::type),
                            Codec.DOUBLE.optionalFieldOf("scale", 1.0).forGetter(ReaderValue::scale))
                    .apply(i, ReaderValue::new))
            .validate(value -> Double.isFinite(value.scale())
                    ? DataResult.success(value)
                    : DataResult.error(() -> "scale must be a finite number"));

    /**
     * The object form, or a plain path string read as a number without a unit. Chosen by the input's shape, so an error
     * names the problem of the form that was written, not of both. Always writes the object form.
     */
    public static final Codec<ReaderValue> CODEC = new Codec<>() {
        @Override
        public <T> DataResult<Pair<ReaderValue, T>> decode(DynamicOps<T> ops, T input) {
            if (ops.getStringValue(input).isSuccess()) {
                return ReaderPath.CODEC.decode(ops, input).map(pair -> pair.mapFirst(ReaderValue::number));
            }
            return FULL.decode(ops, input);
        }

        @Override
        public <T> DataResult<T> encode(ReaderValue value, DynamicOps<T> ops, T prefix) {
            return FULL.encode(value, ops, prefix);
        }
    };

    /** A number at {@code path} without unit or scale. */
    public static ReaderValue number(ReaderPath path) {
        return new ReaderValue(path, "", ReaderType.NUMBER, 1.0);
    }

    /**
     * The value found at {@code tag} as a number: for {@link ReaderType#NUMBER} the number times the scale, rounded (a
     * list or array gives its size times the scale); for {@link ReaderType#YES_NO} 1 or 0; for {@link ReaderType#TEXT}
     * 0. {@link Long#MIN_VALUE} when the tag holds no such value (text where a number is wanted). Allocates nothing.
     */
    public long number(Tag tag) {
        return switch (type) {
            case NUMBER -> {
                if (tag instanceof NumericTag numeric) {
                    yield scaled(numeric.doubleValue());
                }
                if (tag instanceof CollectionTag list) {
                    yield scaled(list.size());
                }
                yield Long.MIN_VALUE;
            }
            case YES_NO -> {
                if (tag instanceof NumericTag numeric) {
                    yield numeric.doubleValue() != 0 ? 1 : 0;
                }
                if (tag instanceof StringTag(String text)) {
                    yield text.equals("true") ? 1 : text.equals("false") ? 0 : Long.MIN_VALUE;
                }
                yield Long.MIN_VALUE;
            }
            case TEXT -> 0;
        };
    }

    /** The value found at {@code tag} as text, for {@link ReaderType#TEXT}: a string or a number; empty otherwise. */
    public static String text(Tag tag) {
        String text;
        if (tag instanceof StringTag(String value)) {
            text = value;
        } else if (tag instanceof NumericTag numeric) {
            text = numeric.box().toString();
        } else {
            return "";
        }
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
    }

    private long scaled(double value) {
        double scaled = scale == 1.0 ? value : value * scale;
        return Math.round(scaled);
    }
}
