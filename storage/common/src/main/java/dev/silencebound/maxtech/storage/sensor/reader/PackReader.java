package dev.silencebound.maxtech.storage.sensor.reader;

import com.google.common.collect.ImmutableMap;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One reader file ({@code data/<namespace>/maxtech_storage/readers/<path>.json}): the blocks it applies to and the
 * values it reads from their block entities' saved data, by name. Load conditions such as {@code neoforge:mod_loaded}
 * go in the file's standard {@code neoforge:conditions} list. A sensor offers each value as {@code data.<name>}.
 * Immutable.
 *
 * <pre>
 * {
 *   "neoforge:conditions": [{"type": "neoforge:mod_loaded", "modid": "examplemod"}],
 *   "blocks": ["examplemod:tank", "#examplemod:machines"],
 *   "values": {
 *     "stored": {"path": "Tank.Amount", "unit": "mB"},
 *     "working": {"path": "Active", "type": "yes_no"},
 *     "progress": "Progress"
 *   }
 * }
 * </pre>
 *
 * @param blocks
 *            the blocks it applies to
 * @param values
 *            its values by name, in file order
 */
public record PackReader(BlockMatch blocks, Map<String, ReaderValue> values) {
    /** Longest value name. */
    public static final int MAX_NAME = 48;
    /** Most values one reader names. */
    public static final int MAX_VALUES = 64;

    private static final Codec<String> NAME = Codec.STRING.validate(PackReader::checkName);
    /**
     * The values by name. Each value is decoded on its own so that an error names the value it is in and no partial
     * reader loads.
     */
    private static final Codec<Map<String, ReaderValue>> VALUES = Codec.unboundedMap(NAME, Codec.PASSTHROUGH)
            .comapFlatMap(PackReader::decodeValues, PackReader::encodeValues);

    public static final Codec<PackReader> CODEC = RecordCodecBuilder
            .<PackReader>create(i -> i.group(BlockMatch.CODEC.fieldOf("blocks").forGetter(PackReader::blocks),
                    VALUES.fieldOf("values").forGetter(PackReader::values)).apply(i, PackReader::new))
            .validate(reader -> reader.values().isEmpty()
                    ? DataResult.error(() -> "a reader needs at least one value")
                    : reader.values().size() > MAX_VALUES
                            ? DataResult.error(() -> "more than " + MAX_VALUES + " values")
                            : DataResult.success(reader));

    public PackReader {
        values = ImmutableMap.copyOf(values);
    }

    private static DataResult<Map<String, ReaderValue>> decodeValues(Map<String, Dynamic<?>> raw) {
        ImmutableMap.Builder<String, ReaderValue> values = ImmutableMap.builder();
        for (Map.Entry<String, Dynamic<?>> entry : raw.entrySet()) {
            String name = entry.getKey();
            DataResult<ReaderValue> value = ReaderValue.CODEC.parse(entry.getValue());
            Optional<DataResult.Error<ReaderValue>> error = value.error();
            if (error.isPresent()) {
                String message = error.get().message();
                return DataResult.error(() -> "value '" + name + "': " + message);
            }
            values.put(name, value.getOrThrow());
        }
        return DataResult.success(values.build());
    }

    private static Map<String, Dynamic<?>> encodeValues(Map<String, ReaderValue> values) {
        Map<String, Dynamic<?>> raw = new LinkedHashMap<>();
        for (Map.Entry<String, ReaderValue> entry : values.entrySet()) {
            raw.put(entry.getKey(), new Dynamic<>(JsonOps.INSTANCE,
                    ReaderValue.CODEC.encodeStart(JsonOps.INSTANCE, entry.getValue()).getOrThrow()));
        }
        return raw;
    }

    /**
     * Value names are what players type into logic nodes: lower-case letters, digits, {@code _}, {@code -} and
     * {@code .}, at most {@link #MAX_NAME} characters.
     */
    static DataResult<String> checkName(String name) {
        if (name.isEmpty() || name.length() > MAX_NAME) {
            return DataResult.error(() -> "value name '" + name + "' must be 1 to " + MAX_NAME + " characters");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '_' && c != '-' && c != '.') {
                return DataResult.error(
                        () -> "value name '" + name + "' may only hold a-z, 0-9, '_', '-' and '.' (found '" + c + "')");
            }
        }
        return DataResult.success(name);
    }
}
