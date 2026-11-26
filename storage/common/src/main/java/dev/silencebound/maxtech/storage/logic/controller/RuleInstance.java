package dev.silencebound.maxtech.storage.logic.controller;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.logic.Parameters;
import dev.silencebound.maxtech.storage.logic.SettingValue;
import java.util.HashMap;
import java.util.Map;

/**
 * A rule that is a copy of another rule of the same controller with its own parameter values (M7L2 row 19): its graph
 * is the template rule's graph with the values in ({@link Parameters#apply}), made again whenever the template changes,
 * so editing the template updates every copy. Only the link and the values are saved. Immutable.
 *
 * @param template
 *            the row (0 based) of the template rule on the same controller; never itself a copy
 * @param templateId
 *            the template rule's {@link GraphSlot#id}: the copy follows the rule with this id wherever it is, and a
 *            different rule on the template's row is not its template
 * @param values
 *            the copy's value per parameter key ({@link Parameters#key}); a parameter without one takes the template's
 *            default
 */
public record RuleInstance(int template, long templateId, Map<String, SettingValue<LogicKey>> values) {
    public static final Codec<RuleInstance> CODEC = RecordCodecBuilder.create(instance -> instance
            .group(Codec.intRange(0, GraphSlot.HARD_MAX - 1).fieldOf("template").forGetter(RuleInstance::template),
                    Codec.LONG.optionalFieldOf("template_id", 0L).forGetter(RuleInstance::templateId),
                    Codec.unboundedMap(Codec.string(1, 32), SettingValue.codec(LogicKey.CODEC))
                            .validate(map -> map.size() <= Parameters.MAX
                                    ? DataResult.success(map)
                                    : DataResult.error(() -> "Too many parameter values"))
                            .optionalFieldOf("values", Map.of()).forGetter(RuleInstance::values))
            .apply(instance, RuleInstance::new));

    public RuleInstance {
        values = Map.copyOf(values);
    }

    /** A copy with parameter {@code key}'s value set to {@code value}. */
    public RuleInstance withValue(String key, SettingValue<LogicKey> value) {
        Map<String, SettingValue<LogicKey>> copy = new HashMap<>(values);
        copy.put(key, value);
        return new RuleInstance(template, templateId, copy);
    }

    /** A copy linked to row {@code row} instead. */
    public RuleInstance withTemplate(int row) {
        return new RuleInstance(row, templateId, values);
    }
}
