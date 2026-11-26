package dev.silencebound.maxtech.storage.logic.controller;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.logic.SettingSpec;
import java.util.List;

/**
 * One shared variable of a Logic Controller (M7L2 batch 4): a value its graphs share by name. Saved on the block entity
 * and in the dropped item ({@code maxtech_storage:logic_variables}). Immutable.
 *
 * @param name
 *            the name as graphs key it (trimmed, lower case, at most {@link SettingSpec#MAX_LABEL} characters)
 * @param value
 *            the value; 1 or 0 for a yes/no
 * @param yesNo
 *            whether the graph that set it last set a yes/no rather than a number
 */
public record SharedVariable(String name, long value, boolean yesNo) {
    /** Most variables the codec reads, whatever the config allows. */
    public static final int HARD_MAX = 256;

    public static final Codec<SharedVariable> CODEC = RecordCodecBuilder.create(instance -> instance
            .group(Codec.string(1, SettingSpec.MAX_LABEL).fieldOf("name").forGetter(SharedVariable::name),
                    Codec.LONG.fieldOf("value").forGetter(SharedVariable::value),
                    Codec.BOOL.optionalFieldOf("yes_no", false).forGetter(SharedVariable::yesNo))
            .apply(instance, SharedVariable::new));
    public static final Codec<List<SharedVariable>> LIST_CODEC = CODEC.listOf(0, HARD_MAX);
}
