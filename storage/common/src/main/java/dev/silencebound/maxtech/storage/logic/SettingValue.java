package dev.silencebound.maxtech.storage.logic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;

/**
 * A saved setting value. Saved as {@code {"kind": "number", "value": 12}} (kinds number, flag, text, item), so a graph
 * decodes without knowing its node types' settings; the compiler checks each value against its {@link SettingSpec}.
 *
 * @param <K>
 *            what an item setting holds (Lib's {@code StackKey} in game, anything with a codec in tests)
 */
public sealed interface SettingValue<K> {
    /** A number. */
    record Num<K>(long value) implements SettingValue<K> {
    }

    /** Yes or no. */
    record Flag<K>(boolean value) implements SettingValue<K> {
    }

    /** Text, a label or a choice id. */
    record Text<K>(String value) implements SettingValue<K> {
    }

    /** An item. */
    record Item<K>(K value) implements SettingValue<K> {
    }

    /** The saved kind name. */
    default String kindName() {
        return switch (this) {
            case Num<K> num -> "number";
            case Flag<K> flag -> "flag";
            case Text<K> text -> "text";
            case Item<K> item -> "item";
        };
    }

    /** The codec of setting values whose items use {@code itemCodec}. */
    static <K> Codec<SettingValue<K>> codec(Codec<K> itemCodec) {
        MapCodec<SettingValue<K>> number = Codec.LONG.fieldOf("value").xmap(Num::new, SettingValue::numberOf);
        MapCodec<SettingValue<K>> flag = Codec.BOOL.fieldOf("value").xmap(Flag::new, SettingValue::flagOf);
        MapCodec<SettingValue<K>> text = Codec.string(0, SettingSpec.MAX_TEXT).fieldOf("value").xmap(Text::new,
                SettingValue::textOf);
        MapCodec<SettingValue<K>> item = itemCodec.fieldOf("value").xmap(Item::new, SettingValue::itemOf);
        Codec<String> kinds = Codec.STRING.validate(name -> switch (name) {
            case "number", "flag", "text", "item" -> DataResult.success(name);
            default -> DataResult.error(() -> "Unknown setting kind " + name);
        });
        return kinds.dispatch("kind", SettingValue::kindName, name -> switch (name) {
            case "number" -> number;
            case "flag" -> flag;
            case "text" -> text;
            default -> item;
        });
    }

    // The dispatch codec picks the encoder by kindName(), so each getter only sees its own kind.
    private static <K> long numberOf(SettingValue<K> value) {
        return value instanceof Num<K>(long number) ? number : 0;
    }

    private static <K> boolean flagOf(SettingValue<K> value) {
        return value instanceof Flag<K>(boolean flag) && flag;
    }

    private static <K> String textOf(SettingValue<K> value) {
        return value instanceof Text<K>(String text) ? text : "";
    }

    private static <K> K itemOf(SettingValue<K> value) {
        if (value instanceof Item<K>(K item)) {
            return item;
        }
        throw new IllegalArgumentException("Not an item setting: " + value);
    }
}
