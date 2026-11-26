package dev.silencebound.maxtech.storage.logic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * A node's settings by name. Immutable. Values whose name or kind the node's type does not know are kept, so a graph
 * survives a round trip unchanged, but the compiler ignores them and uses the spec's default.
 *
 * @param <K>
 *            what item settings hold
 * @param values
 *            the set values by setting name
 */
public record NodeSettings<K>(Map<String, SettingValue<K>> values) {
    /**
     * Most settings one node saves. Was 16 before the step sequence (a step count plus a name and a time limit for each
     * of its 16 steps); raising it only accepts more, so older saves read as before.
     */
    public static final int MAX_SETTINGS = 40;

    public NodeSettings {
        values = Map.copyOf(values);
    }

    public static <K> NodeSettings<K> empty() {
        return new NodeSettings<>(Map.of());
    }

    static <K> Codec<NodeSettings<K>> codec(Codec<K> itemCodec) {
        return Codec.unboundedMap(Codec.string(1, 32), SettingValue.codec(itemCodec))
                .validate(map -> map.size() <= MAX_SETTINGS
                        ? DataResult.success(map)
                        : DataResult.error(() -> "Too many node settings"))
                .xmap(NodeSettings::new, NodeSettings::values);
    }

    /** A copy with {@code name} set to {@code value}. */
    public NodeSettings<K> with(String name, SettingValue<K> value) {
        Map<String, SettingValue<K>> copy = new HashMap<>(values);
        copy.put(name, value);
        return new NodeSettings<>(copy);
    }

    public NodeSettings<K> withNumber(String name, long value) {
        return with(name, new SettingValue.Num<>(value));
    }

    public NodeSettings<K> withFlag(String name, boolean value) {
        return with(name, new SettingValue.Flag<>(value));
    }

    /** A copy with a text, label or choice id set. */
    public NodeSettings<K> withText(String name, String value) {
        return with(name, new SettingValue.Text<>(value));
    }

    public NodeSettings<K> withItem(String name, K value) {
        return with(name, new SettingValue.Item<>(value));
    }

    /** The number {@code name}, if it is set as a number. */
    public OptionalLong number(String name) {
        return values.get(name) instanceof SettingValue.Num<K>(long value)
                ? OptionalLong.of(value)
                : OptionalLong.empty();
    }

    /** The flag {@code name}, if it is set as a flag. */
    public Optional<Boolean> flag(String name) {
        return values.get(name) instanceof SettingValue.Flag<K>(boolean value) ? Optional.of(value) : Optional.empty();
    }

    /** The text, label or choice id {@code name}, if it is set as text. */
    public Optional<String> text(String name) {
        return values.get(name) instanceof SettingValue.Text<K>(String value) ? Optional.of(value) : Optional.empty();
    }

    /** The item {@code name}, if it is set as an item. */
    public Optional<K> item(String name) {
        return values.get(name) instanceof SettingValue.Item<K>(K value) ? Optional.of(value) : Optional.empty();
    }
}
