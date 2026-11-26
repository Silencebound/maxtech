package dev.silencebound.maxtech.lib.api.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * An item input of a machine recipe: {@code count} items matching {@code ingredient}, taken from any input slots.
 * Immutable and safe from any thread.
 *
 * @param ingredient
 *            which items count; recipes use {@code c:} tags, never our own items (PRD 5.5)
 * @param count
 *            how many must be present per run, at least 1
 * @param consumed
 *            whether a run uses them up; a catalyst (a laser engraver's lens, for instance) is not consumed, must be
 *            present {@code count} times however many copies run in parallel, and stays in its slot
 */
public record ItemInput(Ingredient ingredient, int count, boolean consumed) {
    /**
     * JSON form: {@code {"ingredient": <ingredient>, "count": n, "consumed": false}}, count defaulting to 1 and
     * consumed to true.
     */
    public static final Codec<ItemInput> CODEC = RecordCodecBuilder.create(i -> i
            .group(Ingredient.CODEC.fieldOf("ingredient").forGetter(ItemInput::ingredient),
                    ExtraCodecs.POSITIVE_INT.optionalFieldOf("count", 1).forGetter(ItemInput::count),
                    Codec.BOOL.optionalFieldOf("consumed", true).forGetter(ItemInput::consumed))
            .apply(i, ItemInput::new));
    /** Network form, for syncing recipes to clients. */
    public static final StreamCodec<RegistryFriendlyByteBuf, ItemInput> STREAM_CODEC = StreamCodec.composite(
            Ingredient.CONTENTS_STREAM_CODEC, ItemInput::ingredient, ByteBufCodecs.VAR_INT, ItemInput::count,
            ByteBufCodecs.BOOL, ItemInput::consumed, ItemInput::new);

    /** Throws {@link IllegalArgumentException} if {@code count} is below 1. */
    public ItemInput {
        if (count < 1) {
            throw new IllegalArgumentException("Item input count must be at least 1, was " + count);
        }
    }

    /** A consumed input; throws {@link IllegalArgumentException} if {@code count} is below 1. */
    public ItemInput(Ingredient ingredient, int count) {
        this(ingredient, count, true);
    }

    /** Whether {@code stack} is one of the accepted items; ignores its count. */
    public boolean test(ItemStack stack) {
        return ingredient.test(stack);
    }
}
