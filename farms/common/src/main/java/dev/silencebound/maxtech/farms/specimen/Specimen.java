package dev.silencebound.maxtech.farms.specimen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * The data of a specimen item ({@code maxtech_farms:specimen} component): what it was sampled from, its generation, its
 * values and its traits. Two specimens stack when all of these match (record equality). Immutable; any thread.
 *
 * @param kind
 *            what the specimen was sampled from
 * @param target
 *            the sampled thing's id; for {@link SpecimenKind#MOB} the entity type's id
 * @param generation
 *            breeding generation, {@link #MIN_VALUE} to {@link #MAX_GENERATION}
 * @param growth
 *            Growth, {@link #MIN_VALUE} to {@link #MAX_VALUE}
 * @param gain
 *            Gain, {@link #MIN_VALUE} to {@link #MAX_VALUE}
 * @param strength
 *            Strength, {@link #MIN_VALUE} to {@link #MAX_VALUE}
 * @param traits
 *            at most {@link #MAX_TRAITS} traits; kept as an unmodifiable copy
 */
public record Specimen(SpecimenKind kind, Identifier target, int generation, int growth, int gain, int strength,
        Set<SpecimenTrait> traits) {
    public static final int MIN_VALUE = 1;
    public static final int MAX_VALUE = 10;
    public static final int MAX_GENERATION = 10;
    public static final int MAX_TRAITS = 2;

    private static final Codec<Integer> VALUE = Codec.intRange(MIN_VALUE, MAX_VALUE);
    private static final Codec<Set<SpecimenTrait>> TRAITS = SpecimenTrait.CODEC.listOf()
            .comapFlatMap(Specimen::traitSet, Specimen::traitList);

    /** Saved form; generation, values and traits may be left out for a fresh sample's 1, 1, 1, 1 and none. */
    public static final Codec<Specimen> CODEC = RecordCodecBuilder.create(instance -> instance
            .group(SpecimenKind.CODEC.fieldOf("kind").forGetter(Specimen::kind),
                    Identifier.CODEC.fieldOf("target").forGetter(Specimen::target),
                    Codec.intRange(MIN_VALUE, MAX_GENERATION).optionalFieldOf("generation", MIN_VALUE)
                            .forGetter(Specimen::generation),
                    VALUE.optionalFieldOf("growth", MIN_VALUE).forGetter(Specimen::growth),
                    VALUE.optionalFieldOf("gain", MIN_VALUE).forGetter(Specimen::gain),
                    VALUE.optionalFieldOf("strength", MIN_VALUE).forGetter(Specimen::strength),
                    TRAITS.optionalFieldOf("traits", Set.of()).forGetter(Specimen::traits))
            .apply(instance, Specimen::new));

    /** Network form; traits travel as a mask of their bits. */
    public static final StreamCodec<ByteBuf, Specimen> STREAM_CODEC = StreamCodec.composite(SpecimenKind.STREAM_CODEC,
            Specimen::kind, Identifier.STREAM_CODEC, Specimen::target, ByteBufCodecs.VAR_INT, Specimen::generation,
            ByteBufCodecs.VAR_INT, Specimen::growth, ByteBufCodecs.VAR_INT, Specimen::gain, ByteBufCodecs.VAR_INT,
            Specimen::strength, ByteBufCodecs.VAR_INT.map(Specimen::fromMask, Specimen::toMask), Specimen::traits,
            Specimen::new);

    /** Checks the bounds; throws {@link IllegalArgumentException} for a value out of range or too many traits. */
    public Specimen {
        checkRange("generation", generation, MAX_GENERATION);
        checkRange("growth", growth, MAX_VALUE);
        checkRange("gain", gain, MAX_VALUE);
        checkRange("strength", strength, MAX_VALUE);
        if (traits.size() > MAX_TRAITS) {
            throw new IllegalArgumentException("A specimen has at most " + MAX_TRAITS + " traits, got " + traits);
        }
        traits = traits.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(traits));
    }

    /** A fresh sample of {@code target}: generation 1, every value 1, no traits. */
    public static Specimen fresh(SpecimenKind kind, Identifier target) {
        return new Specimen(kind, target, MIN_VALUE, MIN_VALUE, MIN_VALUE, MIN_VALUE, Set.of());
    }

    private static void checkRange(String name, int value, int max) {
        if (value < MIN_VALUE || value > max) {
            throw new IllegalArgumentException(name + " must be " + MIN_VALUE + " to " + max + ", got " + value);
        }
    }

    private static DataResult<Set<SpecimenTrait>> traitSet(List<SpecimenTrait> list) {
        if (list.isEmpty()) {
            return DataResult.success(Set.of());
        }
        EnumSet<SpecimenTrait> set = EnumSet.copyOf(list);
        return set.size() > MAX_TRAITS
                ? DataResult.error(() -> "A specimen has at most " + MAX_TRAITS + " traits: " + list)
                : DataResult.success(set);
    }

    private static List<SpecimenTrait> traitList(Set<SpecimenTrait> set) {
        // EnumSet order, so the saved list does not depend on how the set was built.
        return new ArrayList<>(set.isEmpty() ? EnumSet.noneOf(SpecimenTrait.class) : EnumSet.copyOf(set));
    }

    private static Set<SpecimenTrait> fromMask(int mask) {
        EnumSet<SpecimenTrait> set = EnumSet.noneOf(SpecimenTrait.class);
        for (SpecimenTrait trait : SpecimenTrait.values()) {
            if ((mask & trait.bit()) != 0) {
                set.add(trait);
            }
        }
        return set;
    }

    private static int toMask(Set<SpecimenTrait> set) {
        int mask = 0;
        for (SpecimenTrait trait : set) {
            mask |= trait.bit();
        }
        return mask;
    }
}
