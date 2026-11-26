package dev.silencebound.maxtech.storage.crafting.generator;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A generator provider's settings (M7C item 11), saved with the block and kept in its item when broken.
 *
 * @param output
 *            what it makes, empty while not set
 * @param strength
 *            the redstone signal it gives while its machine should run, 1 to 15
 * @param allSides
 *            whether the signal goes out on every side, not only toward the machine it faces
 * @param priority
 *            where the planner tries it among the patterns making the same output, {@link PatternItem#MIN_PRIORITY} to
 *            {@link PatternItem#MAX_PRIORITY}, default 0 (owner decision 2026-10-10)
 */
public record GeneratorSettings(Optional<StackKey> output, int strength, boolean allSides, int priority) {
    public static final int MAX_STRENGTH = 15;
    public static final GeneratorSettings DEFAULT = new GeneratorSettings(Optional.empty(), MAX_STRENGTH, false, 0);

    public static final Codec<GeneratorSettings> CODEC = RecordCodecBuilder
            .create(instance -> instance
                    .group(StackKey.CODEC.optionalFieldOf("output").forGetter(GeneratorSettings::output),
                            Codec.intRange(1, MAX_STRENGTH).optionalFieldOf("strength", MAX_STRENGTH)
                                    .forGetter(GeneratorSettings::strength),
                            Codec.BOOL.optionalFieldOf("all_sides", false).forGetter(GeneratorSettings::allSides),
                            Codec.intRange(PatternItem.MIN_PRIORITY, PatternItem.MAX_PRIORITY)
                                    .optionalFieldOf("priority", 0).forGetter(GeneratorSettings::priority))
                    .apply(instance, GeneratorSettings::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, GeneratorSettings> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.optional(StackKey.STREAM_CODEC), GeneratorSettings::output, ByteBufCodecs.VAR_INT,
            GeneratorSettings::strength, ByteBufCodecs.BOOL, GeneratorSettings::allSides, ByteBufCodecs.VAR_INT,
            GeneratorSettings::priority, GeneratorSettings::new);

    public GeneratorSettings {
        strength = Math.clamp(strength, 1, MAX_STRENGTH);
        priority = Math.clamp(priority, PatternItem.MIN_PRIORITY, PatternItem.MAX_PRIORITY);
    }

    public GeneratorSettings withOutput(Optional<StackKey> next) {
        return new GeneratorSettings(next, strength, allSides, priority);
    }

    public GeneratorSettings withStrength(int next) {
        return new GeneratorSettings(output, next, allSides, priority);
    }

    public GeneratorSettings withAllSides(boolean next) {
        return new GeneratorSettings(output, strength, next, priority);
    }

    public GeneratorSettings withPriority(int next) {
        return new GeneratorSettings(output, strength, allSides, next);
    }
}
