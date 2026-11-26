package dev.silencebound.maxtech.lib.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.Locale;
import java.util.Optional;

/** Converts between preset JSON and typed config values, using a value's default to know its type. */
final class PresetValues {
    private PresetValues() {
    }

    /** Converts {@code json} to the type of {@code defaultValue}; empty if it does not fit. */
    static Optional<Object> fromJson(JsonElement json, Object defaultValue) {
        if (!json.isJsonPrimitive()) {
            return Optional.empty();
        }
        JsonPrimitive primitive = json.getAsJsonPrimitive();
        return switch (defaultValue) {
            case Boolean ignored -> primitive.isBoolean() ? Optional.of(primitive.getAsBoolean()) : Optional.empty();
            case Double ignored -> primitive.isNumber() ? Optional.of(primitive.getAsDouble()) : Optional.empty();
            case Integer ignored -> integral(primitive).filter(v -> v == v.intValue()).map(Long::intValue);
            case Long ignored -> integral(primitive).map(v -> (Object) v);
            case String ignored -> primitive.isString() ? Optional.of(primitive.getAsString()) : Optional.empty();
            case Enum<?> constant -> primitive.isString()
                    ? enumConstant(constant.getDeclaringClass(), primitive.getAsString())
                    : Optional.empty();
            default -> Optional.empty();
        };
    }

    /** The JSON form written to presets and the state file; enum constants are lower case, as in the PRD. */
    static JsonElement toJson(Object value) {
        return switch (value) {
            case Boolean b -> new JsonPrimitive(b);
            case Number n -> new JsonPrimitive(n);
            case Enum<?> e -> new JsonPrimitive(e.name().toLowerCase(Locale.ROOT));
            default -> new JsonPrimitive(value.toString());
        };
    }

    static <E extends Enum<E>> E enumValue(Class<E> type, String name) {
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(name)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("No " + type.getSimpleName() + " named " + name);
    }

    private static Optional<Object> enumConstant(Class<?> type, String name) {
        for (Object constant : type.getEnumConstants()) {
            if (((Enum<?>) constant).name().equalsIgnoreCase(name)) {
                return Optional.of(constant);
            }
        }
        return Optional.empty();
    }

    private static Optional<Long> integral(JsonPrimitive primitive) {
        if (!primitive.isNumber()) {
            return Optional.empty();
        }
        double value = primitive.getAsDouble();
        long rounded = (long) value;
        return rounded == value ? Optional.of(rounded) : Optional.empty();
    }
}
