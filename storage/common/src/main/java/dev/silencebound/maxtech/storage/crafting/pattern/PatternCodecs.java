package dev.silencebound.maxtech.storage.crafting.pattern;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.save.KeptValue;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;

/**
 * Codecs for the api pattern types, which carry none of their own. Patterns are player data (items, terminal payloads),
 * so the codecs cap list sizes and amounts and turn the records' constructor checks into decode errors instead of
 * exceptions. The stream codec goes through the codec, which is fine for a component synced once per stack.
 */
public final class PatternCodecs {
    /** Most inputs a processing pattern takes; nine for crafting (the api's own check). */
    public static final int MAX_INPUTS = 16;
    /** Most outputs a pattern has: the main output and byproducts. */
    public static final int MAX_OUTPUTS = 6;
    /** Largest amount per entry: a million items, or a thousand buckets of fluid. */
    public static final long MAX_AMOUNT = 1_000_000;

    private static final Codec<Long> AMOUNT = Codec.LONG.validate(amount -> amount >= 1 && amount <= MAX_AMOUNT
            ? DataResult.success(amount)
            : DataResult.error(() -> "amount out of range: " + amount));

    private static final MapCodec<PatternIngredient.Exact> EXACT = StackKey.CODEC.fieldOf("key")
            .xmap(PatternIngredient.Exact::new, PatternIngredient.Exact::key);
    private static final MapCodec<PatternIngredient.ItemTag> ITEM_TAG = TagKey.codec(Registries.ITEM).fieldOf("tag")
            .xmap(PatternIngredient.ItemTag::new, PatternIngredient.ItemTag::tag);
    private static final MapCodec<PatternIngredient.FluidTag> FLUID_TAG = TagKey.codec(Registries.FLUID).fieldOf("tag")
            .xmap(PatternIngredient.FluidTag::new, PatternIngredient.FluidTag::tag);

    /** An ingredient as {@code {"type": "exact"|"item_tag"|"fluid_tag", ...}}. */
    public static final Codec<PatternIngredient> INGREDIENT = Codec.STRING.dispatch("type",
            PatternCodecs::ingredientType, PatternCodecs::ingredientCodec);

    public static final Codec<PatternInput> INPUT = RecordCodecBuilder.create(instance -> instance
            .group(INGREDIENT.fieldOf("ingredient").forGetter(PatternInput::ingredient),
                    AMOUNT.fieldOf("amount").forGetter(PatternInput::amount),
                    Direction.CODEC.optionalFieldOf("face").forGetter(PatternInput::face),
                    Codec.BOOL.optionalFieldOf("returned", false).forGetter(PatternInput::returned))
            .apply(instance, PatternInput::new));

    private static final Codec<Float> CHANCE = Codec.FLOAT.validate(chance -> chance > 0f && chance <= 1f
            ? DataResult.success(chance)
            : DataResult.error(() -> "chance out of range: " + chance));

    public static final Codec<PatternOutput> OUTPUT = RecordCodecBuilder.create(instance -> instance
            .group(StackKey.CODEC.fieldOf("key").forGetter(PatternOutput::key),
                    AMOUNT.fieldOf("amount").forGetter(PatternOutput::amount),
                    CHANCE.optionalFieldOf("chance", 1f).forGetter(PatternOutput::chance))
            .apply(instance, PatternOutput::new));

    private static final Codec<CraftingPattern.Kind> KIND = Codec.STRING.comapFlatMap(PatternCodecs::kind,
            kind -> kind.name().toLowerCase(Locale.ROOT));

    /** A whole pattern; a pattern the api refuses (no inputs, ten crafting inputs) is a decode error. */
    public static final Codec<CraftingPattern> PATTERN = RecordCodecBuilder
            .<Raw>create(instance -> instance
                    .group(KIND.fieldOf("kind").forGetter(Raw::kind),
                            INPUT.listOf(1, MAX_INPUTS).fieldOf("inputs").forGetter(Raw::inputs),
                            OUTPUT.listOf(1, MAX_OUTPUTS).fieldOf("outputs").forGetter(Raw::outputs),
                            Identifier.CODEC.optionalFieldOf("recipe_type").forGetter(Raw::recipeType),
                            Identifier.CODEC.optionalFieldOf("recipe").forGetter(Raw::recipe))
                    .apply(instance, Raw::new))
            .flatXmap(PatternCodecs::build, pattern -> DataResult.success(Raw.of(pattern)));

    public static final StreamCodec<RegistryFriendlyByteBuf, CraftingPattern> STREAM_CODEC = ByteBufCodecs
            .fromCodecWithRegistries(PATTERN);

    /**
     * The encoded pattern item's component: a pattern that no longer loads (an ingredient or output of a removed mod)
     * is kept as saved and written back unchanged, instead of failing and losing the whole stack. It crafts nothing.
     */
    public static final Codec<KeptValue<CraftingPattern>> KEPT_PATTERN = KeptValue.codec(PATTERN);
    public static final StreamCodec<RegistryFriendlyByteBuf, KeptValue<CraftingPattern>> KEPT_STREAM_CODEC = KeptValue
            .streamCodec(STREAM_CODEC);

    private PatternCodecs() {
    }

    private static String ingredientType(PatternIngredient ingredient) {
        return switch (ingredient) {
            case PatternIngredient.Exact exact -> "exact";
            case PatternIngredient.ItemTag tag -> "item_tag";
            case PatternIngredient.FluidTag tag -> "fluid_tag";
        };
    }

    private static MapCodec<? extends PatternIngredient> ingredientCodec(String type) {
        return switch (type) {
            case "item_tag" -> ITEM_TAG;
            case "fluid_tag" -> FLUID_TAG;
            default -> EXACT;
        };
    }

    private static DataResult<CraftingPattern.Kind> kind(String name) {
        for (CraftingPattern.Kind kind : CraftingPattern.Kind.values()) {
            if (kind.name().toLowerCase(Locale.ROOT).equals(name)) {
                return DataResult.success(kind);
            }
        }
        return DataResult.error(() -> "unknown pattern kind: " + name);
    }

    private static DataResult<CraftingPattern> build(Raw raw) {
        Supplier<CraftingPattern> make = () -> new CraftingPattern(raw.kind, raw.inputs, raw.outputs, raw.recipeType,
                raw.recipe);
        try {
            return DataResult.success(make.get());
        } catch (IllegalArgumentException e) {
            return DataResult.error(e::getMessage);
        }
    }

    /** The fields before the api's checks. */
    private record Raw(CraftingPattern.Kind kind, List<PatternInput> inputs, List<PatternOutput> outputs,
            Optional<Identifier> recipeType, Optional<Identifier> recipe) {
        static Raw of(CraftingPattern pattern) {
            return new Raw(pattern.kind(), pattern.inputs(), pattern.outputs(), pattern.recipeType(), pattern.recipe());
        }
    }
}
